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

/**
 * V7.2 reconciles unresolved Google Wallet rows from the store on every page.
 * It also avoids polluting the store with old history while searching for a
 * small number of unresolved recent transactions.
 */
object GoogleWalletSyncControllerV72 {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val GMS = "com.google.android.gms"
    private const val SELF = "com.bou.payhelper"
    private const val HOME = 0
    private const val OPEN_MORE = 1
    private const val HISTORY = 2
    private const val DETAIL = 3
    private const val RECENT_DAYS = 90
    private const val MAX_PAGES = 40

    private data class Card(val name: String, val last4: String, val type: String)
    private data class Meta(
        val exact: String, val id: String, val type: String,
        val virtualLast4: String, val virtualType: String,
        val card: Card?, val source: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var running = false
    private var service: PayAccessibilityService? = null
    private var state = HOME
    private var startedAt = 0L
    private var page = 0
    private var lastFingerprint = ""
    private var noMove = 0
    private var knownPages = 0
    private var pending: GoogleWalletTransaction? = null
    private var detailAttempt = 0
    private var openAttempts = 0
    private var cards: List<Card> = emptyList()
    private val visited = HashSet<String>()
    private val seen = HashSet<String>()
    private var newCount = 0
    private var detailCount = 0
    private var navigationFailures = 0
    private var lastRateLabel = ""
    private var lastRateAt = 0L

    @JvmStatic fun isRunning(): Boolean = running

    @JvmStatic fun start(s: PayAccessibilityService) {
        if (running) return
        service = s
        running = true
        scheduled = false
        state = HOME
        startedAt = System.currentTimeMillis()
        page = 0
        lastFingerprint = ""
        noMove = 0
        knownPages = 0
        pending = null
        detailAttempt = 0
        openAttempts = 0
        visited.clear()
        seen.clear()
        newCount = 0
        detailCount = 0
        navigationFailures = 0
        lastRateLabel = ""
        lastRateAt = 0L

        val dedup = GoogleWalletTransactionStore.compactLegacyGlobalHistory(s)
        val pruned = GoogleWalletTransactionStore.pruneUnresolvedOlderThan(s, RECENT_DAYS)
        s.stopGoogleWalletDiagnostic(false)
        GoogleWalletDiagnosticStore.clear(s)
        record(s, "v72-start", "existing=${GoogleWalletTransactionStore.load(s).size} dedup=$dedup prunedOldUnresolved=$pruned unresolved=${unresolvedCount(s)} keys=${unresolvedKeys(s)}")
        log(s, "=== Google Wallet V7.2 start unresolved=${unresolvedCount(s)} pruned=$pruned ===")

        val launch = s.packageManager.getLaunchIntentForPackage(WALLET)
        if (launch == null) { record(s, "v72-launch-missing", ""); finish(true); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { s.startActivity(launch) } catch (t: Throwable) { record(s, "v72-launch-error", t.message.orEmpty()) }
        schedule(1200)
    }

    @JvmStatic fun stop(returnToApp: Boolean = true) = finish(returnToApp)
    @JvmStatic fun poke() { if (running) schedule(80) }

    private val runnable = Runnable { scheduled = false; safeTick() }
    private fun schedule(delay: Long = 450) {
        if (!running || scheduled) return
        scheduled = true
        handler.postDelayed(runnable, delay)
    }

    private fun safeTick() {
        val s = service ?: return
        try { tick(s) } catch (t: Throwable) {
            record(s, "v72-error-state$state", "${t.javaClass.simpleName}:${t.message}")
            schedule(700)
        }
    }

    private fun tick(s: PayAccessibilityService) {
        if (!running) return
        if (System.currentTimeMillis() - startedAt > 360_000L) {
            record(s, "v72-timeout", "unresolved=${unresolvedCount(s)} keys=${unresolvedKeys(s)}")
            finish(true); return
        }
        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null }
        if (root == null) { rateRecord(s, "v72-wait-root", ""); schedule(); return }
        val pkg = root.packageName?.toString().orEmpty()
        val accepted = if (state == DETAIL) pkg == WALLET || pkg == GMS else pkg == WALLET
        if (!accepted) { rateRecord(s, "v72-wait-package", "state=$state pkg=$pkg"); schedule(); return }
        when (state) {
            HOME -> home(s, root)
            OPEN_MORE -> openMore(s, root)
            HISTORY -> history(s, root)
            DETAIL -> detail(s, root)
        }
    }

    private fun home(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        cards = findCards(root)
        if (cards.isEmpty()) { rateCapture(s, root, "v72-home-no-cards"); schedule(600); return }
        record(s, "v72-home", "cards=${cards.joinToString(",") { it.last4 }} unresolved=${unresolvedCount(s)}")
        state = OPEN_MORE
        openAttempts = 0
        schedule(900)
        tapText(s, root, "顯示更多", "home-more")
    }

    private fun openMore(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = texts(root)
        val more = findExact(root, "查看更多交易")
        val preview = parseRows(root)
        if (more != null || (values.any { it == "交易" } && preview.isNotEmpty())) {
            mergeRelevantRows(s, preview)
            if (more == null) { schedule(); return }
            state = HISTORY
            page = 0; lastFingerprint = ""; noMove = 0; knownPages = 0
            schedule(900)
            tapNode(s, more, "more-transactions")
            return
        }
        if (values.any { it == "付款卡" }) {
            openAttempts++
            if (openAttempts > 5) { record(s, "v72-open-more-failed", ""); finish(true); return }
            schedule(900)
            tapText(s, root, "顯示更多", "home-more-retry$openAttempts")
            return
        }
        rateCapture(s, root, "v72-open-more-loading")
        schedule()
    }

    private fun history(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = texts(root)
        if (values.any { it == "查看更多交易" } && values.none { it == "交易記錄" }) {
            findExact(root, "查看更多交易")?.let { schedule(850); tapNode(s, it, "more-transactions-retry"); return }
        }
        val rows = parseRows(root)
        if (rows.isEmpty()) {
            rateCapture(s, root, "v72-history-empty")
            // old Wallet rows can contain explicit years; if there are visible amounts but no parsed rows,
            // keep moving a few times instead of stalling forever.
            if (values.any { it.startsWith("$") }) {
                page++
                if (page > MAX_PAGES) { finish(true); return }
                schedule(900); swipeUp(s, "history-empty-page$page"); return
            }
            schedule(); return
        }

        val unresolvedNow = unresolvedMap(s)
        val relevant = rows.filter { isRecent(it.date) || unresolvedNow.containsKey(it.fallbackKey) }
        mergeRelevantRows(s, relevant)

        val stored = GoogleWalletTransactionStore.load(s).associateBy { it.fallbackKey }
        val candidate = rows.firstOrNull { tx ->
            val item = stored[tx.fallbackKey]
            tx.fallbackKey !in visited && item != null && !item.detailChecked && isRecent(item.date)
        }
        if (candidate != null) {
            val node = findRow(root, candidate)
            capture(s, root, "v72-before-detail-${safe(candidate.shop)}")
            if (node != null) {
                pending = candidate
                detailAttempt = 0
                state = DETAIL
                schedule(850)
                tapNodeAt(s, node, 0.50f, "detail-${safe(candidate.shop)}")
                return
            }
            visited.add(candidate.fallbackKey)
        }

        val fp = rows.joinToString("|") { it.fallbackKey }
        rows.forEach { if (seen.add(it.fallbackKey) && stored[it.fallbackKey] == null && isRecent(it.date)) newCount++ }
        val allKnown = relevant.isNotEmpty() && relevant.all { stored.containsKey(it.fallbackKey) }
        knownPages = if (allKnown) knownPages + 1 else 0
        noMove = if (fp == lastFingerprint) noMove + 1 else 0
        lastFingerprint = fp
        val unresolved = unresolvedCount(s)
        record(s, "v72-history-page$page", "rows=${rows.size} relevant=${relevant.size} unresolved=$unresolved knownPages=$knownPages noMove=$noMove")

        if (unresolved == 0 && knownPages >= 2) { finish(true); return }
        if (noMove >= 3 || page >= MAX_PAGES) {
            record(s, "v72-stop-history", "unresolved=$unresolved keys=${unresolvedKeys(s)}")
            finish(true); return
        }
        page++
        schedule(900)
        swipeUp(s, "history-page$page")
    }

    private fun detail(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val tx = pending ?: run { state = HISTORY; schedule(); return }
        val pkg = root.packageName?.toString().orEmpty()
        val values = texts(root)

        if (pkg == GMS) {
            val meta = extractMeta(root, tx, values)
            if (meta.exact.isNotBlank() || meta.id.isNotBlank()) {
                val card = meta.card
                val ok = GoogleWalletTransactionStore.updateDetail(
                    s, tx.fallbackKey, meta.exact, meta.id, meta.type, meta.virtualLast4, meta.virtualType,
                    card?.name.orEmpty(), card?.let { inferBank(it.name) }.orEmpty(), card?.last4.orEmpty(), card?.type.orEmpty(), meta.source
                )
                if (ok) detailCount++
                val remain = unresolvedCount(s)
                record(s, "v72-detail-ok", "${tx.fallbackKey} -> ${meta.exact} id=${meta.id} wallet=${card?.last4.orEmpty()} unresolved=$remain")
                visited.add(tx.fallbackKey)
                pending = null; detailAttempt = 0; state = HISTORY
                schedule(900); back(s, "detail-success"); return
            }
        }

        detailAttempt++
        if (pkg == WALLET && values.any { it == tx.shop }) {
            // Navigation failed: retry progressively across the clickable row instead of repeating center taps.
            if (detailAttempt in listOf(3, 6, 9, 12)) {
                findRow(root, tx)?.let { row ->
                    val frac = when (detailAttempt) { 3 -> .72f; 6 -> .30f; 9 -> .85f; else -> .18f }
                    record(s, "v72-detail-navigation-retry", "${tx.fallbackKey} try=$detailAttempt x=$frac")
                    schedule(800); tapNodeAt(s, row, frac, "detail-retry-${safe(tx.shop)}-$detailAttempt"); return
                }
            }
        }
        if (detailAttempt < 15) { schedule(300); return }

        navigationFailures++
        record(s, "v72-detail-navigation-failed", "${tx.fallbackKey} attempts=$detailAttempt unresolved=${unresolvedCount(s)}")
        visited.add(tx.fallbackKey)
        pending = null; detailAttempt = 0; state = HISTORY
        schedule(800)
        if (pkg != WALLET) back(s, "detail-failed")
    }

    private fun mergeRelevantRows(s: PayAccessibilityService, rows: List<GoogleWalletTransaction>) {
        if (rows.isNotEmpty()) GoogleWalletTransactionStore.merge(s, rows)
    }

    private fun unresolvedMap(s: PayAccessibilityService): Map<String, GoogleWalletTransaction> =
        GoogleWalletTransactionStore.unresolved(s, RECENT_DAYS).associateBy { it.fallbackKey }
    private fun unresolvedCount(s: PayAccessibilityService): Int = GoogleWalletTransactionStore.unresolved(s, RECENT_DAYS).size
    private fun unresolvedKeys(s: PayAccessibilityService): String = GoogleWalletTransactionStore.unresolvedSummary(s, RECENT_DAYS, 12)

    private fun parseRows(root: AccessibilityNodeInfo): List<GoogleWalletTransaction> {
        val out = LinkedHashMap<String, GoogleWalletTransaction>()
        val dateRegex = Regex("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$")
        val amountRegex = Regex("^\\$[0-9,]+(?:\\.[0-9]{2})?$")
        val controls = setOf("交易", "交易記錄", "查看更多交易", "全部", "Google Pay", "Google Pay", "搜尋", "返回", "在錢包中搜尋", "憑證", "會員方案")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35) return
            try {
                if (n.isClickable) {
                    val v = texts(n)
                    val date = v.firstOrNull { dateRegex.matches(it) }
                    val amount = v.firstOrNull { amountRegex.matches(it) }
                    if (date != null && amount != null) {
                        val shop = v.firstOrNull { x -> x.isNotBlank() && !dateRegex.matches(x) && !amountRegex.matches(x) && x !in controls && !x.startsWith("末位數號碼為") && !x.contains("••") }.orEmpty()
                        if (shop.isNotBlank()) {
                            val tx = GoogleWalletTransaction(shop, amount.replace("$", "").replace(",", ""), normalizeDate(date), System.currentTimeMillis())
                            out[tx.fallbackKey] = tx
                        }
                    }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0)
        return out.values.toList()
    }

    private fun normalizeDate(text: String): String {
        val m = Regex("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$").find(text) ?: return text
        val explicitYear = m.groupValues[1].toIntOrNull()
        val month = m.groupValues[2].toIntOrNull() ?: return text
        val day = m.groupValues[3].toIntOrNull() ?: return text
        val now = Calendar.getInstance()
        var year = explicitYear ?: now.get(Calendar.YEAR)
        if (explicitYear == null && month > now.get(Calendar.MONTH) + 2) year--
        return String.format(Locale.US, "%04d/%02d/%02d 12:00:00", year, month, day)
    }

    private fun findRow(root: AccessibilityNodeInfo, tx: GoogleWalletTransaction): AccessibilityNodeInfo? {
        val p = tx.date.substringBefore(' ').split('/')
        if (p.size < 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        val labels = setOf("${m}月${d}日", "${y}年${m}月${d}日")
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35 || found != null) return
            try {
                if (n.isClickable) {
                    val v = texts(n)
                    val amountOk = v.any { clean(it).isNotBlank() && clean(it) == clean(tx.amount) }
                    if (v.any { it == tx.shop } && v.any { it in labels } && amountOk) { found = n; return }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0)
        return found
    }

    private fun extractMeta(root: AccessibilityNodeInfo, tx: GoogleWalletTransaction, values: List<String>): Meta {
        val exact = extractTime(tx.date, values).orEmpty()
        val id = extractId(root, values)
        val type = values.firstOrNull { it == "使用手機購買" || it == "線上購物" || it.contains("感應付款") }.orEmpty()
        var virtualType = ""; var virtualLast4 = ""
        val vr = Regex("(?i)(Mastercard|Visa|JCB)\\s*••\\s*([0-9 ]{4,})")
        values.forEach { v -> vr.find(v)?.let { m -> virtualType = normalizeCard(m.groupValues[1]); virtualLast4 = m.groupValues[2].filter { it.isDigit() }.takeLast(4) } }
        val compact = values.joinToString(" ").replace(" ", "")
        cards.firstOrNull { compact.contains("••${it.last4}") }?.let { return Meta(exact, id, type, virtualLast4, virtualType, it, "explicit-wallet-last4") }
        if (virtualType.isNotBlank()) {
            val same = cards.filter { normalizeCard(it.type) == virtualType }
            if (same.size == 1) return Meta(exact, id, type, virtualLast4, virtualType, same.first(), "unique-card-type")
        }
        return Meta(exact, id, type, virtualLast4, virtualType, null, "")
    }

    private fun extractId(root: AccessibilityNodeInfo, values: List<String>): String {
        try {
            root.findAccessibilityNodeInfosByViewId("$GMS:id/UserVisibleTransactionId")?.firstOrNull()?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Throwable) { }
        val i = values.indexOfFirst { it == "交易 ID" || it.equals("Transaction ID", true) }
        return if (i >= 0 && i + 1 < values.size) values[i + 1].trim() else ""
    }

    private fun extractTime(originalDate: String, values: List<String>): String? {
        val r = Regex("(?i)(上午|下午|AM|PM)?\\s*(\\d{1,2})[:：](\\d{2})(?:[:：](\\d{2}))?")
        values.forEach { v ->
            val m = r.find(v) ?: return@forEach
            val period = m.groupValues[1].uppercase(Locale.US)
            var h = m.groupValues[2].toIntOrNull() ?: return@forEach
            val min = m.groupValues[3].toIntOrNull() ?: return@forEach
            val sec = m.groupValues[4].toIntOrNull() ?: 0
            if ((period == "下午" || period == "PM") && h in 1..11) h += 12
            if ((period == "上午" || period == "AM") && h == 12) h = 0
            val day = originalDate.substringBefore(' ')
            if (Regex("^\\d{4}/\\d{2}/\\d{2}$").matches(day)) return String.format(Locale.US, "%s %02d:%02d:%02d", day, h, min, sec)
        }
        return null
    }

    private fun isRecent(date: String): Boolean = try {
        val f = SimpleDateFormat("yyyy/MM/dd", Locale.US); f.isLenient = false
        val d = f.parse(date.substringBefore(' ')) ?: return false
        val age = System.currentTimeMillis() - d.time
        age >= -86_400_000L && age <= RECENT_DAYS.toLong() * 86_400_000L
    } catch (_: Throwable) { false }

    private fun findCards(root: AccessibilityNodeInfo): List<Card> {
        val nodes = try { root.findAccessibilityNodeInfosByViewId("$WALLET:id/Card") ?: emptyList() } catch (_: Throwable) { emptyList() }
        return nodes.mapNotNull { n ->
            val desc = texts(n).firstOrNull { it.startsWith("末位數號碼為") } ?: return@mapNotNull null
            val last4 = Regex("末位數號碼為\\s*([0-9 ]+)").find(desc)?.groupValues?.getOrNull(1)?.filter { it.isDigit() }?.takeLast(4).orEmpty()
            if (last4.length != 4) return@mapNotNull null
            var name = desc.substringAfter("的 ", desc).removeSuffix("卡片。").removeSuffix("卡片").trim()
            val type = normalizeCard(name)
            if (type in setOf("Mastercard", "Visa", "JCB")) name = name.replace(type, "", true).trim()
            Card(name, last4, if (type in setOf("Mastercard", "Visa", "JCB")) type else "")
        }.distinctBy { it.last4 }
    }

    private fun normalizeCard(v: String): String = when { v.contains("Mastercard", true) -> "Mastercard"; v.contains("Visa", true) -> "Visa"; v.contains("JCB", true) -> "JCB"; else -> v.trim() }
    private fun inferBank(n: String): String = when { n.contains("彰化銀行") || n.contains("彰銀") -> "彰銀"; n.contains("台新") -> "台新"; n.contains("國泰") -> "國泰"; n.contains("玉山") -> "玉山"; n.contains("中信") || n.contains("中國信託") -> "中信"; n.contains("富邦") -> "富邦"; n.contains("永豐") -> "永豐"; else -> "" }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 28 || out.size > 320) return
            try {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let { out.add(it) }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0); return out
    }

    private fun findExact(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        try {
            if (root.text?.toString()?.trim() == target || root.contentDescription?.toString()?.trim() == target) return root
            for (i in 0 until root.childCount) root.getChild(i)?.let { findExact(it, target) }?.let { return it }
        } catch (_: Throwable) { }
        return null
    }

    private fun tapText(s: PayAccessibilityService, root: AccessibilityNodeInfo, text: String, label: String) { findExact(root, text)?.let { tapNode(s, it, label) } ?: record(s, "tap-$label-missing", text) }
    private fun tapNode(s: PayAccessibilityService, n: AccessibilityNodeInfo, label: String) = tapNodeAt(s, n, .5f, label)
    private fun tapNodeAt(s: PayAccessibilityService, n: AccessibilityNodeInfo, xf: Float, label: String) {
        val r = Rect(); try { n.getBoundsInScreen(r) } catch (_: Throwable) { return }
        if (r.isEmpty) return
        tapPoint(s, r.left + r.width() * xf.coerceIn(.12f, .88f), r.exactCenterY(), label)
    }
    private fun tapPoint(s: PayAccessibilityService, x: Float, y: Float, label: String) {
        record(s, "tap-$label-dispatch", "x=${x.toInt()} y=${y.toInt()}")
        try {
            val p = Path(); p.moveTo(x, y)
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 90)).build()
            val accepted = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) { record(s, "tap-$label-completed", "ok"); poke() }
                override fun onCancelled(gd: GestureDescription?) { record(s, "tap-$label-cancelled", ""); poke() }
            }, null)
            record(s, "tap-$label-accepted", "accepted=$accepted")
        } catch (t: Throwable) { record(s, "tap-$label-error", t.message.orEmpty()); poke() }
    }
    private fun swipeUp(s: PayAccessibilityService, label: String) {
        val dm = s.resources.displayMetrics; val x = dm.widthPixels / 2f; val y1 = dm.heightPixels * .78f; val y2 = dm.heightPixels * .30f
        val p = Path(); p.moveTo(x, y1); p.lineTo(x, y2)
        try {
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 420)).build()
            s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() { override fun onCompleted(gd: GestureDescription?) { poke() }; override fun onCancelled(gd: GestureDescription?) { poke() } }, null)
            record(s, "swipe-$label", "from=${y1.toInt()} to=${y2.toInt()}")
        } catch (t: Throwable) { record(s, "swipe-$label-error", t.message.orEmpty()) }
    }
    private fun back(s: PayAccessibilityService, label: String) { record(s, "back-$label", "dispatch"); try { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Throwable) { } }

    private fun clean(v: String): String = v.replace(Regex("[^0-9.]"), "").trimStart('0')
    private fun safe(v: String): String = v.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fff_-]"), "_").take(24)

    private fun rateCapture(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        val now = System.currentTimeMillis(); if (label == lastRateLabel && now - lastRateAt < 1500) return
        lastRateLabel = label; lastRateAt = now; capture(s, root, label)
    }
    private fun rateRecord(s: PayAccessibilityService, label: String, msg: String) {
        val now = System.currentTimeMillis(); if (label == lastRateLabel && now - lastRateAt < 1500) return
        lastRateLabel = label; lastRateAt = now; record(s, label, msg)
    }
    private fun capture(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        try { GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(System.currentTimeMillis(), root.packageName?.toString().orEmpty(), -720, "formal-sync-v72/$label", label, texts(root).joinToString("\n"), "")) } catch (_: Throwable) { }
    }
    private fun record(s: PayAccessibilityService, label: String, msg: String) {
        try { GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(System.currentTimeMillis(), WALLET, -721, "formal-sync-v72/$label", label, msg, "")) } catch (_: Throwable) { }
    }

    private fun finish(returnToApp: Boolean) {
        val s = service
        running = false; scheduled = false; handler.removeCallbacks(runnable)
        if (s != null) {
            val unresolved = unresolvedCount(s)
            record(s, "v72-finish", "new=$newCount detail=$detailCount navigationFailures=$navigationFailures total=${GoogleWalletTransactionStore.load(s).size} unresolvedRemaining=$unresolved keys=${unresolvedKeys(s)}")
            log(s, "=== Google Wallet V7.2 finish unresolved=$unresolved navigationFailures=$navigationFailures ===")
            if (returnToApp) try { s.packageManager.getLaunchIntentForPackage(SELF)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP); s.startActivity(it) } } catch (_: Throwable) { }
        }
        pending = null; service = null
    }

    private fun log(s: PayAccessibilityService, message: String) {
        val p = s.getSharedPreferences("v241", 0); val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()); val old = p.getString("log", "") ?: ""
        p.edit().putString("log", ("[$time] $message\n" + old).take(40000)).apply()
    }
}