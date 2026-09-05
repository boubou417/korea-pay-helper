package tw.apostar.notificationpaytest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object GoogleWalletSyncControllerV74 {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val GMS = "com.google.android.gms"
    private const val SELF = "com.bou.payhelper"
    private const val RECENT_DAYS = 90
    private const val MAX_PAGES = 40
    private const val MAX_DETAIL_RETRIES_PER_ROW = 3
    private const val MAX_STUCK_SCROLL_RECOVERIES = 8
    private const val HOME = 0
    private const val OPEN_MORE = 1
    private const val HISTORY = 2
    private const val DETAIL = 3
    private const val CARD_OPEN = 4
    private const val CARD_HISTORY = 5
    private const val GLOBAL_OPEN_MORE = 6

    private data class Card(val name: String, val last4: String, val type: String)
    private data class Meta(val time: String, val id: String, val txType: String, val vLast4: String, val vType: String, val card: Card?, val source: String)

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var scheduled = false
    private var service: PayAccessibilityService? = null
    private var state = HOME
    private var startedAt = 0L
    private var page = 0
    private var lastFp = ""
    private var noMove = 0
    private var knownPages = 0
    private var pending: GoogleWalletTransaction? = null
    private var detailTry = 0
    private var openTry = 0
    private var cards: List<Card> = emptyList()
    private val visited = HashSet<String>()
    private val detailFailures = HashMap<String, Int>()
    private var detailCount = 0
    private var navFailCount = 0
    private var stuckScrollRecoveries = 0
    private var cardIndex = 0
    private var currentCard: Card? = null
    private var cardPage = 0
    private var cardLastFp = ""
    private var cardNoMove = 0
    private var cardOpenTry = 0

    @JvmStatic fun isRunning(): Boolean = running

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        if (running) return
        service = s
        running = true
        scheduled = false
        state = HOME
        startedAt = System.currentTimeMillis()
        page = 0
        lastFp = ""
        noMove = 0
        knownPages = 0
        pending = null
        detailTry = 0
        openTry = 0
        cards = emptyList()
        visited.clear()
        detailFailures.clear()
        detailCount = 0
        navFailCount = 0
        stuckScrollRecoveries = 0
        cardIndex = 0
        currentCard = null
        cardPage = 0
        cardLastFp = ""
        cardNoMove = 0
        cardOpenTry = 0

        val dedup = GoogleWalletTransactionStore.compactLegacyGlobalHistory(s)
        val pruned = GoogleWalletTransactionStore.pruneUnresolvedOlderThan(s, RECENT_DAYS)
        s.stopGoogleWalletDiagnostic(false)
        GoogleWalletDiagnosticStore.clear(s)
        record(s, "v84-start", "existing=${GoogleWalletTransactionStore.load(s).size} dedup=$dedup pruned=$pruned cardUnresolved=${unresolvedCount(s)} keys=${unresolvedKeys(s)}")

        val launch = s.packageManager.getLaunchIntentForPackage(WALLET)
        if (launch == null) { record(s, "v84-launch-missing", ""); finish(true); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { s.startActivity(launch) } catch (t: Throwable) { record(s, "v84-launch-error", t.message.orEmpty()) }
        schedule(1200)
    }

    @JvmStatic fun stop(returnToApp: Boolean = true) = finish(returnToApp)
    @JvmStatic fun poke() { if (running) schedule(80) }

    private val runner = Runnable { scheduled = false; safeTick() }
    private fun schedule(delay: Long = 450) {
        if (!running || scheduled) return
        scheduled = true
        handler.postDelayed(runner, delay)
    }

    private fun safeTick() {
        val s = service ?: return
        try { tick(s) } catch (t: Throwable) {
            record(s, "v84-error-state$state", "${t.javaClass.simpleName}:${t.message}")
            schedule(700)
        }
    }

    private fun tick(s: PayAccessibilityService) {
        if (!running) return
        if (System.currentTimeMillis() - startedAt > 360_000L) { record(s, "v84-timeout", "keys=${unresolvedKeys(s)}"); finish(true); return }
        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null }
        if (root == null) { schedule(); return }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == SELF) { record(s, "v84-returned-to-app", "state=$state"); finish(false); return }
        val accepted = if (state == DETAIL) pkg == WALLET || pkg == GMS else pkg == WALLET
        if (!accepted) { schedule(); return }
        when (state) {
            HOME -> onHome(s, root)
            OPEN_MORE -> onOpenMore(s, root)
            HISTORY -> onHistory(s, root)
            DETAIL -> onDetail(s, root)
            CARD_OPEN -> onCardOpen(s, root)
            CARD_HISTORY -> onCardHistory(s, root)
            GLOBAL_OPEN_MORE -> onGlobalOpenMore(s, root)
        }
    }

    private fun onHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        cards = findCards(root)
        if (cards.isEmpty()) { schedule(600); return }
        record(s, "v84-home", "cards=${cards.joinToString(",") { it.last4 }} cardIndex=$cardIndex unresolved=${unresolvedCount(s)}")

        if (cardIndex < cards.size) {
            currentCard = cards[cardIndex]
            cardPage = 0
            cardLastFp = ""
            cardNoMove = 0
            cardOpenTry = 0
            val node = findCardNode(root, currentCard!!)
            if (node != null) {
                state = CARD_OPEN
                schedule(1100)
                // ACTION_CLICK returning true only means the Accessibility action was
                // accepted. It does NOT mean Google Wallet actually navigated. The
                // v84 diagnostic showed exactly this: accepted=true, then immediate
                // return to the same Wallet home. Force a real screen-coordinate tap.
                record(s, "v84-card-gesture-start", "last4=${currentCard!!.last4}")
                tapAt(s, node, .50f, "card-${currentCard!!.last4}-gesture")
                return
            }
            record(s, "v84-card-node-missing", "index=$cardIndex last4=${currentCard!!.last4}")
            cardIndex++
            schedule(500)
            return
        }

        state = GLOBAL_OPEN_MORE
        schedule(700)
        tapText(s, root, "顯示更多", "global-more")
    }

    private fun onCardOpen(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val card = currentCard ?: run { state = HOME; schedule(); return }
        val vals = texts(root)
        val selected = vals.firstOrNull { extractLast4FromText(it) == card.last4 || compactLast4(it) == card.last4 }
        val rows = parseRows(root)
        val more = findExact(root, "查看更多交易")
        val historyText = findExact(root, "交易記錄") ?: findExact(root, "交易")

        if (selected != null) {
            record(s, "v84-card-selected", "index=$cardIndex bank=${inferBank(card.name)} last4=${card.last4} name=${card.name} text=$selected")
        }

        if (more != null) {
            state = CARD_HISTORY
            cardPage = 0
            cardLastFp = ""
            cardNoMove = 0
            schedule(850)
            tapNode(s, more, "card-more-${card.last4}", false)
            return
        }

        if (historyText != null && selected != null) {
            state = CARD_HISTORY
            cardPage = 0
            cardLastFp = ""
            cardNoMove = 0
            schedule(850)
            tapNode(s, historyText, "card-history-${card.last4}", false)
            return
        }

        if (rows.isNotEmpty() && selected != null) {
            state = CARD_HISTORY
            schedule(500)
            return
        }

        // Google Wallet may keep the same accessibility tree for several hundred ms
        // after the physical tap. Retry the coordinate tap rather than spinning forever.
        cardOpenTry++
        record(s, "v84-card-open-wait", "index=$cardIndex last4=${card.last4} try=$cardOpenTry values=${vals.take(18).joinToString(" | ")}")
        if (cardOpenTry <= 5) {
            val node = findCardNode(root, card) ?: findNodeContaining(root, card.last4)
            if (node != null) {
                schedule(900)
                tapAt(s, node, when (cardOpenTry % 3) { 1 -> .50f; 2 -> .35f; else -> .65f }, "card-${card.last4}-retry-$cardOpenTry")
                return
            }
            schedule(700)
            return
        }

        // Do not let a failed card navigation block the entire Google Pay sync.
        record(s, "v84-card-open-failed", "index=$cardIndex last4=${card.last4} retry=$cardOpenTry")
        finishCurrentCard(s)
    }

    private fun onCardHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val card = currentCard ?: run { state = HOME; schedule(); return }
        val rows = parseRows(root)
        if (rows.isEmpty()) {
            cardNoMove++
            if (cardNoMove >= 3) {
                record(s, "v84-card-history-empty", "index=$cardIndex last4=${card.last4} page=$cardPage")
                finishCurrentCard(s)
                return
            }
            schedule(500)
            return
        }

        val bank = inferBank(card.name)
        var saved = 0
        rows.filter { isRecent(it.date) }.forEach { tx ->
            GoogleWalletTransactionStore.merge(s, listOf(tx))
            if (GoogleWalletTransactionStore.updateDetail(
                    s, tx.fallbackKey, "", "", "", "", "",
                    card.name, bank, card.last4, card.type,
                    "wallet-card-history-v84"
                )) saved++
        }

        val fp = rows.joinToString("|") { it.fallbackKey }
        if (fp == cardLastFp) cardNoMove++ else cardNoMove = 0
        cardLastFp = fp
        record(s, "v84-card-history-page", "index=$cardIndex last4=${card.last4} page=$cardPage rows=${rows.size} saved=$saved noMove=$cardNoMove")

        if (cardPage >= MAX_PAGES || cardNoMove >= 3) {
            finishCurrentCard(s)
            return
        }
        cardPage++
        schedule(850)
        swipeUp(s, "card-history-$cardPage-${card.last4}")
    }

    private fun finishCurrentCard(s: PayAccessibilityService) {
        val card = currentCard
        record(s, "v84-card-history-finish", "index=$cardIndex last4=${card?.last4.orEmpty()} page=$cardPage")
        cardIndex++
        currentCard = null
        cardPage = 0
        cardLastFp = ""
        cardNoMove = 0
        cardOpenTry = 0
        state = HOME
        schedule(1100)
        back(s, "card-history-finish")
    }

    private fun onGlobalOpenMore(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val vals = texts(root)
        val more = findExact(root, "查看更多交易")
        if (more != null) {
            mergeRecent(s, parseRows(root))
            state = HISTORY
            page = 0
            lastFp = ""
            noMove = 0
            knownPages = 0
            stuckScrollRecoveries = 0
            schedule(900)
            tapNode(s, more, "global-more-transactions", false)
            return
        }
        if (vals.any { it == "付款卡" }) {
            openTry++
            if (openTry > 5) { finish(true); return }
            schedule(900)
            tapText(s, root, "顯示更多", "global-more-retry$openTry")
            return
        }
        schedule()
    }

    private fun onOpenMore(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val vals = texts(root)
        val more = findExact(root, "查看更多交易")
        if (more != null) {
            mergeRecent(s, parseRows(root))
            state = HISTORY
            page = 0
            lastFp = ""
            noMove = 0
            knownPages = 0
            stuckScrollRecoveries = 0
            schedule(900)
            tapNode(s, more, "more-transactions", false)
            return
        }
        if (vals.any { it == "付款卡" }) {
            openTry++
            if (openTry > 5) { finish(true); return }
            schedule(900)
            tapText(s, root, "顯示更多", "home-more-retry$openTry")
            return
        }
        schedule()
    }

    private fun onHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val vals = texts(root)
        if (vals.any { it == "查看更多交易" } && vals.none { it == "交易記錄" }) {
            findExact(root, "查看更多交易")?.let { schedule(850); tapNode(s, it, "more-transactions-retry", false); return }
        }
        val rows = parseRows(root)
        if (rows.isEmpty()) {
            if (vals.any { it.startsWith("$") }) { page++; schedule(900); swipeUp(s, "empty-page$page"); return }
            schedule(); return
        }
        mergeRecent(s, rows)
        val stored = GoogleWalletTransactionStore.load(s).associateBy { it.fallbackKey }
        val candidate = rows.firstOrNull { tx ->
            val x = stored[tx.fallbackKey]
            val failures = detailFailures[tx.fallbackKey] ?: 0
            tx.fallbackKey !in visited && failures < MAX_DETAIL_RETRIES_PER_ROW && x != null && needsCardDetail(x) && isRecent(x.date)
        }
        if (candidate != null) {
            val node = findRow(root, candidate)
            if (node != null) {
                val x = stored[candidate.fallbackKey]
                record(s, "v78-force-detail", "${candidate.fallbackKey} detailChecked=${x?.detailChecked} bank=${x?.bank.orEmpty()} last4=${x?.cardLast4.orEmpty()} retry=${detailFailures[candidate.fallbackKey] ?: 0}")
                pending = candidate
                detailTry = 0
                state = DETAIL
                capture(s, root, "v78-before-detail-${safe(candidate.shop)}")
                schedule(850)
                tapNode(s, node, "detail-${safe(candidate.shop)}", true)
                return
            }
            val failures = (detailFailures[candidate.fallbackKey] ?: 0) + 1
            detailFailures[candidate.fallbackKey] = failures
            record(s, "v78-row-node-missing", "${candidate.fallbackKey} failures=$failures")
            if (failures >= MAX_DETAIL_RETRIES_PER_ROW) visited.add(candidate.fallbackKey)
        }
        val fp = rows.joinToString("|") { it.fallbackKey }
        val relevant = rows.filter { isRecent(it.date) }
        val allKnown = relevant.isNotEmpty() && relevant.all { stored.containsKey(it.fallbackKey) }
        knownPages = if (allKnown) knownPages + 1 else 0
        noMove = if (fp == lastFp) noMove + 1 else 0
        lastFp = fp
        val unresolved = unresolvedCount(s)
        record(s, "v78-history-page$page", "rows=${rows.size} cardUnresolved=$unresolved knownPages=$knownPages noMove=$noMove stuckRecovery=$stuckScrollRecoveries")
        if (unresolved == 0 && (knownPages >= 2 || noMove >= 3 || page >= MAX_PAGES)) { finish(true); return }
        if (unresolved > 0 && (noMove >= 3 || page >= MAX_PAGES)) {
            if (stuckScrollRecoveries < MAX_STUCK_SCROLL_RECOVERIES) {
                stuckScrollRecoveries++; noMove = 0; page++
                record(s, "v78-stuck-scroll-recovery", "attempt=$stuckScrollRecoveries unresolved=$unresolved keys=${unresolvedKeys(s)}")
                schedule(1200); swipeUpStrong(s, "recovery-$stuckScrollRecoveries"); return
            }
            record(s, "v78-stop-after-recovery", "unresolved=$unresolved keys=${unresolvedKeys(s)}"); finish(true); return
        }
        page++; schedule(900); swipeUp(s, "page$page")
    }

    private fun onDetail(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val tx = pending ?: run { state = HISTORY; schedule(); return }
        val pkg = root.packageName?.toString().orEmpty()
        val vals = texts(root)
        if (pkg == GMS || (pkg == WALLET && vals.any { it == "交易 ID" || it.equals("Transaction ID", true) })) {
            val m = parseMeta(root, tx, vals)
            if (m.time.isNotBlank() || m.id.isNotBlank()) {
                val c = m.card
                val bank = c?.let { inferBank(it.name) }.orEmpty()
                if (GoogleWalletTransactionStore.updateDetail(s, tx.fallbackKey, m.time, m.id, m.txType, m.vLast4, m.vType, c?.name.orEmpty(), bank, c?.last4.orEmpty(), c?.type.orEmpty(), m.source)) detailCount++
                val cardComplete = c != null && c.last4.length == 4 && bank.isNotBlank()
                if (cardComplete) { visited.add(tx.fallbackKey); detailFailures.remove(tx.fallbackKey); record(s, "v78-detail-complete", "${tx.fallbackKey} card=${c.name} last4=${c.last4} bank=$bank source=${m.source}") }
                else {
                    val failures = (detailFailures[tx.fallbackKey] ?: 0) + 1
                    detailFailures[tx.fallbackKey] = failures
                    if (failures >= MAX_DETAIL_RETRIES_PER_ROW) visited.add(tx.fallbackKey)
                    record(s, "v78-detail-incomplete", "${tx.fallbackKey} card=${c?.name.orEmpty()} last4=${c?.last4.orEmpty()} bank=$bank retry=$failures/$MAX_DETAIL_RETRIES_PER_ROW vals=${vals.take(40).joinToString(" | ")}")
                }
                pending = null; detailTry = 0; state = HISTORY; schedule(900); back(s, "detail-return"); return
            }
        }
        detailTry++
        if (pkg == WALLET && vals.any { it == tx.shop } && detailTry in listOf(3, 6, 9, 12)) {
            findRow(root, tx)?.let { row ->
                val xf = when (detailTry) { 3 -> .72f; 6 -> .30f; 9 -> .86f; else -> .16f }
                record(s, "v78-detail-navigation-retry", "${tx.fallbackKey} try=$detailTry x=$xf")
                schedule(800); tapAt(s, row, xf, "detail-retry-${safe(tx.shop)}-$detailTry"); return
            }
        }
        if (detailTry < 15) { schedule(300); return }
        navFailCount++
        val failures = (detailFailures[tx.fallbackKey] ?: 0) + 1
        detailFailures[tx.fallbackKey] = failures
        if (failures >= MAX_DETAIL_RETRIES_PER_ROW) visited.add(tx.fallbackKey)
        record(s, "v78-detail-navigation-failed", "${tx.fallbackKey} attempts=$detailTry retry=$failures/$MAX_DETAIL_RETRIES_PER_ROW")
        pending = null; detailTry = 0; state = HISTORY; schedule(800)
        if (pkg != WALLET) back(s, "detail-failed")
    }

    private fun mergeRecent(s: PayAccessibilityService, rows: List<GoogleWalletTransaction>) {
        val unresolved = cardUnresolved(s).map { it.fallbackKey }.toHashSet()
        val before = cardUnresolved(s).associateBy { "${it.date.substringBefore(' ')}|${it.amount}" }
        val keep = rows.filter { isRecent(it.date) || it.fallbackKey in unresolved }
        if (keep.isNotEmpty()) {
            GoogleWalletTransactionStore.merge(s, keep)
            keep.forEach { tx ->
                val old = before["${tx.date.substringBefore(' ')}|${tx.amount}"]
                if (old != null && old.shop != tx.shop && old.shop.trim().length <= 2) record(s, "v78-merchant-repair", "${old.fallbackKey} -> ${tx.fallbackKey}")
            }
        }
    }

    private fun needsCardDetail(x: GoogleWalletTransaction): Boolean = !x.detailChecked || x.bank.isBlank() || x.cardLast4.length != 4 || x.cardName.isBlank()
    private fun cardUnresolved(s: PayAccessibilityService): List<GoogleWalletTransaction> = GoogleWalletTransactionStore.load(s).filter { isRecent(it.date) && needsCardDetail(it) }
    private fun unresolvedCount(s: PayAccessibilityService) = cardUnresolved(s).size
    private fun unresolvedKeys(s: PayAccessibilityService) = cardUnresolved(s).take(12).joinToString(" || ") { it.fallbackKey }

    private fun parseRows(root: AccessibilityNodeInfo): List<GoogleWalletTransaction> {
        val out = LinkedHashMap<String, GoogleWalletTransaction>()
        val dr = Regex("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$")
        val ar = Regex("^\\$[0-9,]+(?:\\.[0-9]{2})?$")
        val controls = setOf("交易", "交易記錄", "查看更多交易", "全部", "Google Pay", "Google Pay", "搜尋", "返回", "在錢包中搜尋", "憑證", "會員方案", "從主畫面移除")
        fun merchantCandidate(x: String): Boolean { val s=x.trim(); return s.isNotBlank() && !dr.matches(s) && !ar.matches(s) && s !in controls && !s.contains("••") && !s.startsWith("末位數號碼為") }
        fun merchantScore(x: String): Int { val s=x.trim(); var score=s.length.coerceAtMost(80); if(s.length<=2)score-=100; if(s.any{it.isWhitespace()})score+=8; if(s.any{it.isLetter()})score+=5; return score }
        fun walk(n: AccessibilityNodeInfo?, d: Int) { if(n==null||d>35)return; try { if(n.isClickable){ val v=texts(n); val date=v.firstOrNull{dr.matches(it)}; val amount=v.firstOrNull{ar.matches(it)}; if(date!=null&&amount!=null){ val shop=v.filter(::merchantCandidate).distinct().maxByOrNull(::merchantScore).orEmpty(); if(shop.isNotBlank()){ val tx=GoogleWalletTransaction(shop,amount.replace("$","").replace(",",""),normalizeDate(date),System.currentTimeMillis()); out[tx.fallbackKey]=tx } } }; for(i in 0 until n.childCount)walk(n.getChild(i),d+1) } catch(_:Throwable){} }
        walk(root,0); return out.values.toList()
    }

    private fun normalizeDate(text: String): String { val m=Regex("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$").find(text)?:return text; val ey=m.groupValues[1].toIntOrNull(); val month=m.groupValues[2].toIntOrNull()?:return text; val day=m.groupValues[3].toIntOrNull()?:return text; val now=Calendar.getInstance(); var year=ey?:now.get(Calendar.YEAR); if(ey==null&&month>now.get(Calendar.MONTH)+2)year--; return String.format(Locale.US,"%04d/%02d/%02d 12:00:00",year,month,day) }
    private fun isRecent(date: String): Boolean { return try { val f=SimpleDateFormat("yyyy/MM/dd",Locale.US); f.isLenient=false; val d=f.parse(date.substringBefore(' ')); if(d==null)false else { val age=System.currentTimeMillis()-d.time; age>=-86_400_000L&&age<=RECENT_DAYS.toLong()*86_400_000L } } catch(_:Throwable){false} }

    private fun findRow(root: AccessibilityNodeInfo, tx: GoogleWalletTransaction): AccessibilityNodeInfo? { val p=tx.date.substringBefore(' ').split('/'); if(p.size<3)return null; val y=p[0].toIntOrNull()?:return null; val m=p[1].toIntOrNull()?:return null; val d=p[2].toIntOrNull()?:return null; val labels=setOf("${m}月${d}日","${y}年${m}月${d}日"); var found:AccessibilityNodeInfo?=null; fun walk(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>35||found!=null)return; try{if(n.isClickable){val v=texts(n);val amountOk=v.any{clean(it).isNotBlank()&&clean(it)==clean(tx.amount)};if(v.any{it==tx.shop}&&v.any{it in labels}&&amountOk){found=n;return}};for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)}catch(_:Throwable){}};walk(root,0);return found }

    private fun parseMeta(root: AccessibilityNodeInfo, tx: GoogleWalletTransaction, vals: List<String>): Meta { val time=extractTime(tx.date,vals).orEmpty(); val id=extractId(root,vals); val txType=vals.firstOrNull{it=="使用手機購買"||it=="線上購物"||it.contains("感應付款")}.orEmpty(); var vt="";var vl="";val vr=Regex("(?i)(Mastercard|Visa|JCB|萬事達)[\\s\\u00a0\\u200b\\u200e\\u200f]*[•·●∙⋅‧・▪◦]{1,}[\\s\\u00a0\\u200b\\u200e\\u200f]*([0-9][0-9\\s\\u00a0\\u200b\\u200e\\u200f]{3,})");vals.forEach{v->vr.find(v)?.let{mm->vt=normalizeCard(mm.groupValues[1]);vl=mm.groupValues[2].filter{it.isDigit()}.takeLast(4)}};extractExplicitDetailCard(vals)?.let{return Meta(time,id,txType,vl,vt,it,"detail-bank-last4-v78")};val compact=vals.joinToString(" ").replace(Regex("[\\s\\u00a0\\u200b\\u200e\\u200f]"),"");cards.firstOrNull{compact.contains(it.last4)}?.let{return Meta(time,id,txType,vl,vt,it,"explicit-wallet-last4")};return Meta(time,id,txType,vl,vt,null,"") }

    private fun extractExplicitDetailCard(vals: List<String>): Card? {
        val normalized=vals.map{it.replace('\u00a0',' ').replace("\u200b","").replace("\u200e","").replace("\u200f","").trim()}
        fun build(nameRaw:String,lastRaw:String):Card?{val last4=lastRaw.filter{it.isDigit()}.takeLast(4);if(last4.length!=4)return null;val name=nameRaw.trim().trim(' ',':','：','-','–','—',',','，');if(name.isBlank()||name.equals("Mastercard",true)||name.equals("Visa",true)||name.equals("JCB",true)||name=="萬事達")return null;val bank=inferBank(name);val looksPhysical=bank.isNotBlank()||name.contains("銀行")||name.contains("信用卡")||name.contains("鈦金")||name.contains("商旅卡");if(!looksPhysical)return null;val type=normalizeCard(name);return Card(name,last4,if(type in setOf("Mastercard","Visa","JCB"))type else "")}
        val sameLine=Regex("(.+?)[\\s\\u00a0\\u200b\\u200e\\u200f]*(?:[•·●∙⋅‧・▪◦]{1,}|(?:末四碼|末4碼|尾號|後四碼|末位數(?:號碼)?(?:為)?))[\\s:：\\u00a0\\u200b\\u200e\\u200f]*([0-9](?:[\\s\\u00a0\\u200b\\u200e\\u200f]*[0-9]){3})",RegexOption.IGNORE_CASE)
        for(raw in normalized){val m=sameLine.find(raw)?:continue;build(m.groupValues[1],m.groupValues[2])?.let{return it}}
        val cardNameCandidates=normalized.filter{inferBank(it).isNotBlank()&&(it.contains("卡")||it.contains("銀行")||it.contains("鈦金")||it.contains("商旅"))}.map{it.substringBefore("••").substringBefore("··").substringBefore("●●").trim(' ',':','：','-','–','—',',','，')}.filter{it.isNotBlank()}.distinct()
        val last4Candidates=LinkedHashSet<String>();val explicitLast4=Regex("(?:[•·●∙⋅‧・▪◦]{1,}|(?:末四碼|末4碼|尾號|後四碼|末位數(?:號碼)?(?:為)?))[\\s:：\\u00a0\\u200b\\u200e\\u200f]*([0-9](?:[\\s\\u00a0\\u200b\\u200e\\u200f]*[0-9]){3})",RegexOption.IGNORE_CASE);normalized.forEach{raw->explicitLast4.findAll(raw).forEach{m->val x=m.groupValues[1].filter{it.isDigit()}.takeLast(4);if(x.length==4)last4Candidates.add(x)}};if(cardNameCandidates.size==1&&last4Candidates.size==1)build(cardNameCandidates.first(),last4Candidates.first())?.let{return it};if(cardNameCandidates.size==1){val standalone4=normalized.mapNotNull{raw->val digits=raw.filter{it.isDigit()};if(digits.length==4&&raw.none{it.isLetter()}&&!raw.contains(':')&&!raw.contains('/')&&!raw.contains('-'))digits else null}.distinct();if(standalone4.size==1)build(cardNameCandidates.first(),standalone4.first())?.let{return it}};return null
    }

    private fun extractId(root: AccessibilityNodeInfo, vals: List<String>): String { try{root.findAccessibilityNodeInfosByViewId("$GMS:id/UserVisibleTransactionId")?.firstOrNull()?.text?.toString()?.trim()?.takeIf{it.isNotBlank()}?.let{return it}}catch(_:Throwable){};val i=vals.indexOfFirst{it=="交易 ID"||it.equals("Transaction ID",true)};return if(i>=0&&i+1<vals.size)vals[i+1].trim() else "" }
    private fun extractTime(date:String,vals:List<String>):String?{val r=Regex("(?i)(上午|下午|AM|PM)?\\s*(\\d{1,2})[:：](\\d{2})(?:[:：](\\d{2}))?");for(v in vals){val mm=r.find(v)?:continue;val period=mm.groupValues[1].uppercase(Locale.US);var h=mm.groupValues[2].toIntOrNull()?:continue;val min=mm.groupValues[3].toIntOrNull()?:continue;val sec=mm.groupValues[4].toIntOrNull()?:0;if((period=="下午"||period=="PM")&&h in 1..11)h+=12;if((period=="上午"||period=="AM")&&h==12)h=0;return String.format(Locale.US,"%s %02d:%02d:%02d",date.substringBefore(' '),h,min,sec)};return null}

    private fun findCardNode(root:AccessibilityNodeInfo,card:Card):AccessibilityNodeInfo?{
        val nodes=try{root.findAccessibilityNodeInfosByViewId("$WALLET:id/Card")?:emptyList()}catch(_:Throwable){emptyList()}
        nodes.firstOrNull{n->texts(n).any{extractLast4FromText(it)==card.last4||compactLast4(it)==card.last4}}?.let{return it}
        return findNodeContaining(root, card.last4)
    }

    private fun findNodeContaining(root:AccessibilityNodeInfo, last4:String):AccessibilityNodeInfo?{
        var found:AccessibilityNodeInfo?=null
        fun walk(n:AccessibilityNodeInfo?,d:Int){
            if(n==null||d>30||found!=null)return
            try{
                val joined=texts(n).joinToString(" ")
                if(joined.contains(last4) && (n.isClickable || n.className?.toString()?.contains("Card",true)==true)){found=n;return}
                for(i in 0 until n.childCount)walk(n.getChild(i),d+1)
            }catch(_:Throwable){}
        }
        walk(root,0)
        return found
    }

    private fun extractLast4FromText(raw:String):String{val compact=raw.replace(Regex("[\\s\\u00a0\\u200b\\u200e\\u200f]"),"");return Regex("末位數號碼為([0-9]{4})").find(compact)?.groupValues?.getOrNull(1).orEmpty()}
    private fun compactLast4(raw:String):String{val compact=raw.replace(Regex("[\\s\\u00a0\\u200b\\u200e\\u200f]"),"");return Regex("(?:••|··|●●|•{2})([0-9]{4})").find(compact)?.groupValues?.getOrNull(1).orEmpty()}

    private fun findCards(root:AccessibilityNodeInfo):List<Card>{val nodes=try{root.findAccessibilityNodeInfosByViewId("$WALLET:id/Card")?:emptyList()}catch(_:Throwable){emptyList()};return nodes.mapNotNull{n->val desc=texts(n).firstOrNull{it.startsWith("末位數號碼為")}?:return@mapNotNull null;val last4=Regex("末位數號碼為\\s*([0-9 ]+)").find(desc)?.groupValues?.getOrNull(1)?.filter{it.isDigit()}?.takeLast(4).orEmpty();if(last4.length!=4)return@mapNotNull null;var name=desc.substringAfter("的 ",desc).removeSuffix("卡片。").removeSuffix("卡片").trim();val type=normalizeCard(name);if(type in setOf("Mastercard","Visa","JCB"))name=name.replace("Mastercard","",true).replace("Visa","",true).replace("JCB","",true).replace("萬事達","",true).trim();Card(name,last4,if(type in setOf("Mastercard","Visa","JCB"))type else "")}.distinctBy{it.last4}}
    private fun normalizeCard(v:String):String=when{v.contains("Mastercard",true)||v.contains("萬事達")->"Mastercard";v.contains("Visa",true)->"Visa";v.contains("JCB",true)->"JCB";else->v.trim()}
    private fun inferBank(n:String):String=when{n.contains("彰化銀行")||n.contains("彰銀")->"彰銀";n.contains("台新")->"台新";n.contains("國泰")->"國泰";n.contains("玉山")->"玉山";n.contains("中信")||n.contains("中國信託")->"中信";n.contains("富邦")->"富邦";n.contains("永豐")->"永豐";n.contains("星展")||n.contains("DBS",true)->"星展";n.contains("聯邦")->"聯邦";n.contains("兆豐")->"兆豐";n.contains("第一銀行")||n.contains("一銀")->"一銀";n.contains("華南")->"華南";else->""}
    private fun texts(root:AccessibilityNodeInfo):List<String>{val out=ArrayList<String>();fun walk(n:AccessibilityNodeInfo?,d:Int){if(n==null||d>28||out.size>320)return;try{n.text?.toString()?.trim()?.takeIf{it.isNotEmpty()}?.let(out::add);n.contentDescription?.toString()?.trim()?.takeIf{it.isNotEmpty()&&it!="Image"}?.let(out::add);for(i in 0 until n.childCount)walk(n.getChild(i),d+1)}catch(_:Throwable){}};walk(root,0);return out}
    private fun findExact(root:AccessibilityNodeInfo,target:String):AccessibilityNodeInfo?{try{if(root.text?.toString()?.trim()==target||root.contentDescription?.toString()?.trim()==target)return root;for(i in 0 until root.childCount)root.getChild(i)?.let{findExact(it,target)}?.let{return it}}catch(_:Throwable){};return null}
    private fun tapText(s:PayAccessibilityService,root:AccessibilityNodeInfo,text:String,label:String){findExact(root,text)?.let{tapNode(s,it,label,false)}}
    private fun tapNode(s:PayAccessibilityService,node:AccessibilityNodeInfo,label:String,preferAction:Boolean){if(preferAction){try{val ok=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);record(s,"tap-$label-action","accepted=$ok");if(ok){poke();return}}catch(_:Throwable){}};tapAt(s,node,.5f,label)}
    private fun tapAt(s:PayAccessibilityService,node:AccessibilityNodeInfo,xf:Float,label:String){val r=Rect();try{node.getBoundsInScreen(r)}catch(_:Throwable){return};if(r.isEmpty)return;tapPoint(s,r.left+r.width()*xf.coerceIn(.12f,.88f),r.exactCenterY(),label)}
    private fun tapPoint(s:PayAccessibilityService,x:Float,y:Float,label:String){try{val p=Path();p.moveTo(x,y);val g=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,90)).build();val ok=s.dispatchGesture(g,object:AccessibilityService.GestureResultCallback(){override fun onCompleted(gd:GestureDescription?){record(s,"tap-$label-completed","x=${x.toInt()} y=${y.toInt()}");poke()};override fun onCancelled(gd:GestureDescription?){poke()}},null);record(s,"tap-$label-gesture","accepted=$ok x=${x.toInt()} y=${y.toInt()}")}catch(t:Throwable){record(s,"tap-$label-error",t.message.orEmpty());poke()}}
    private fun swipeUp(s:PayAccessibilityService,label:String){val dm=s.resources.displayMetrics;val x=dm.widthPixels/2f;val y1=dm.heightPixels*.78f;val y2=dm.heightPixels*.30f;val p=Path();p.moveTo(x,y1);p.lineTo(x,y2);try{val g=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,420)).build();s.dispatchGesture(g,object:AccessibilityService.GestureResultCallback(){override fun onCompleted(gd:GestureDescription?){poke()};override fun onCancelled(gd:GestureDescription?){poke()}},null);record(s,"swipe-$label","")}catch(_:Throwable){}}
    private fun swipeUpStrong(s:PayAccessibilityService,label:String){val dm=s.resources.displayMetrics;val x=dm.widthPixels/2f;val y1=dm.heightPixels*.88f;val y2=dm.heightPixels*.16f;val p=Path();p.moveTo(x,y1);p.lineTo(x,y2);try{val g=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,560)).build();s.dispatchGesture(g,object:AccessibilityService.GestureResultCallback(){override fun onCompleted(gd:GestureDescription?){poke()};override fun onCancelled(gd:GestureDescription?){poke()}},null);record(s,"swipe-strong-$label","")}catch(_:Throwable){}}
    private fun back(s:PayAccessibilityService,label:String){record(s,"back-$label","");try{s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)}catch(_:Throwable){}}
    private fun clean(v:String)=v.replace(Regex("[^0-9.]"),"").trimStart('0')
    private fun safe(v:String)=v.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fff_-]"),"_").take(24)
    private fun capture(s:PayAccessibilityService,root:AccessibilityNodeInfo,label:String){try{GoogleWalletDiagnosticStore.add(s,GoogleWalletDiagnosticCapture(System.currentTimeMillis(),root.packageName?.toString().orEmpty(),-780,"formal-sync-v78/$label",label,texts(root).joinToString("\n"),""))}catch(_:Throwable){}}
    private fun record(s:PayAccessibilityService,label:String,msg:String){try{GoogleWalletDiagnosticStore.add(s,GoogleWalletDiagnosticCapture(System.currentTimeMillis(),WALLET,-781,"formal-sync-v84/$label",label,msg,""))}catch(_:Throwable){}}
    private fun finish(returnToApp:Boolean){val s=service;running=false;scheduled=false;handler.removeCallbacks(runner);if(s!=null){record(s,"v84-finish","detail=$detailCount navigationFailures=$navFailCount total=${GoogleWalletTransactionStore.load(s).size} cardUnresolved=${unresolvedCount(s)} keys=${unresolvedKeys(s)}");val p=s.getSharedPreferences("v241",0);val tm=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date());val old=p.getString("log","")?:"";p.edit().putString("log",("[$tm] Google Wallet V8.4 finish cardUnresolved=${unresolvedCount(s)}\n"+old).take(40000)).apply();if(returnToApp){try{s.packageManager.getLaunchIntentForPackage(SELF)?.let{it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP);s.startActivity(it)}}catch(_:Throwable){}}};pending=null;service=null}
}
