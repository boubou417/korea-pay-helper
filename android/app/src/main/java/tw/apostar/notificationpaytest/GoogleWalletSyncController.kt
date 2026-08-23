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

object GoogleWalletSyncController {
    private const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    private const val SELF = "com.bou.payhelper"
    private const val MAX_PAGES_PER_CARD = 20
    private const val DETAIL_MAX_AGE_DAYS = 45
    private const val MAX_CARD_OPEN_ATTEMPTS = 4

    private const val STAGE_HOME = 0
    private const val STAGE_CARD_OPENING = 1
    private const val STAGE_HISTORY = 2
    private const val STAGE_DETAIL = 3

    private data class CardInfo(
        val name: String,
        val last4: String,
        val type: String,
        val node: AccessibilityNodeInfo
    )

    private val h = Handler(Looper.getMainLooper())
    private var service: PayAccessibilityService? = null
    private var running = false
    private var startedAt = 0L
    private var stage = STAGE_HOME

    private var cardIndex = 0
    private var currentCardName = ""
    private var currentCardLast4 = ""
    private var currentCardType = ""
    private var cardOpenAttempts = 0
    private var cardOpenStartedAt = 0L

    private var page = 0
    private var lastFingerprint = ""
    private var noMoveCount = 0
    private var consecutiveKnownPages = 0

    private val knownAtStart = HashSet<String>()
    private val seenThisRun = HashSet<String>()
    private val detailVisitedThisRun = HashSet<String>()
    private var pendingDetail: GoogleWalletTransaction? = null
    private var detailAttempts = 0
    private var detailOpenedAt = 0L

    private var newThisRun = 0
    private var detailsThisRun = 0

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        if (running) return

        service = s
        running = true
        startedAt = System.currentTimeMillis()
        stage = STAGE_HOME
        cardIndex = 0
        currentCardName = ""
        currentCardLast4 = ""
        currentCardType = ""
        cardOpenAttempts = 0
        cardOpenStartedAt = 0L
        resetCardPageState()
        newThisRun = 0
        detailsThisRun = 0
        pendingDetail = null
        detailAttempts = 0
        detailOpenedAt = 0L
        seenThisRun.clear()
        detailVisitedThisRun.clear()
        knownAtStart.clear()
        knownAtStart.addAll(GoogleWalletTransactionStore.load(s).map { it.key })

        s.stopGoogleWalletDiagnostic(false)
        GoogleWalletDiagnosticStore.clear(s)
        log(s, "=== Google Wallet 快速同步開始；existing=${knownAtStart.size} ===")

        val launch = s.packageManager.getLaunchIntentForPackage(GOOGLE_WALLET)
        if (launch == null) {
            log(s, "⚠ 找不到 Google Wallet App")
            finish(true)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        s.startActivity(launch)
        schedule(1300)
    }

    @JvmStatic
    fun stop(returnToApp: Boolean = true) {
        finish(returnToApp)
    }

    private fun schedule(delay: Long = 650L) {
        h.removeCallbacks(tickRunnable)
        h.postDelayed(tickRunnable, delay)
    }

    private val tickRunnable = Runnable { tick() }

    private fun tick() {
        val s = service ?: return
        if (!running) return

        if (System.currentTimeMillis() - startedAt > 300_000L) {
            log(s, "⚠ Google Wallet 同步超過 5 分鐘，自動結束")
            finish(true)
            return
        }

        val root = s.rootInActiveWindow
        if (root == null || root.packageName?.toString() != GOOGLE_WALLET) {
            schedule(500)
            return
        }

        when (stage) {
            STAGE_HOME -> processWalletHome(s, root)
            STAGE_CARD_OPENING -> processCardOpening(s, root)
            STAGE_HISTORY -> processHistory(s, root)
            STAGE_DETAIL -> processTransactionDetail(s, root)
            else -> finish(true)
        }
    }

    private fun processWalletHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val cards = findCards(root)
        if (cards.isEmpty()) {
            captureFormal(s, root, "wallet-home-no-cards")
            schedule(600)
            return
        }

        if (cardIndex >= cards.size) {
            log(s, "✓ Google Wallet 全部卡片同步完成；new=$newThisRun detail=$detailsThisRun total=${GoogleWalletTransactionStore.load(s).size}")
            finish(true)
            return
        }

        val card = cards[cardIndex]
        currentCardName = card.name
        currentCardLast4 = card.last4
        currentCardType = card.type
        cardOpenAttempts = 0
        cardOpenStartedAt = System.currentTimeMillis()
        resetCardPageState()
        pendingDetail = null
        detailAttempts = 0
        detailOpenedAt = 0L

        captureFormal(s, root, "wallet-home-card-${card.last4}")
        log(s, "Google Wallet card ${cardIndex + 1}/${cards.size}: ${card.name} *${card.last4}")

        val accepted = clickAction(s, card.node, "card-${card.last4}-initial")
        log(s, "Google Wallet card *${card.last4} initial ACTION_CLICK accepted=$accepted")
        stage = STAGE_CARD_OPENING
        schedule(850)
    }

    private fun processCardOpening(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)
        val more = findExactText(root, "查看更多交易")

        // Reached the selected card activity. It exposes recent transactions plus
        // the "查看更多交易" button.
        if (more != null) {
            mergeRows(s, parseRows(root))
            captureFormal(s, root, "card-${currentCardLast4}-before-more")
            val accepted = clickRobust(s, more, "more-${currentCardLast4}")
            log(s, "Google Wallet *$currentCardLast4 查看更多交易 click accepted=$accepted")
            if (accepted) {
                stage = STAGE_HISTORY
                resetCardPageState()
                schedule(900)
                return
            }
            schedule(500)
            return
        }

        val stillHome = isWalletHome(root, values)
        if (stillHome) {
            val elapsed = System.currentTimeMillis() - cardOpenStartedAt
            if (elapsed < 700L) {
                schedule(350)
                return
            }

            cardOpenAttempts += 1
            captureFormal(s, root, "card-${currentCardLast4}-still-home-attempt$cardOpenAttempts")

            if (cardOpenAttempts > MAX_CARD_OPEN_ATTEMPTS) {
                log(s, "⚠ Google Wallet *$currentCardLast4 多次點卡仍停在首頁，跳過此卡")
                skipCurrentCardFromHome()
                return
            }

            val card = findCards(root).firstOrNull { it.last4 == currentCardLast4 }
            if (card == null) {
                log(s, "⚠ Google Wallet 首頁找不到目前卡 *$currentCardLast4，稍後重試")
                schedule(500)
                return
            }

            // Some Wallet/Compose versions report ACTION_CLICK=true for the selected
            // card while doing nothing. Alternate ACTION_CLICK and a direct gesture.
            val accepted = if (cardOpenAttempts == 1) {
                clickAction(s, card.node, "card-${currentCardLast4}-retry-action")
            } else {
                clickGesture(s, card.node, "card-${currentCardLast4}-retry-gesture$cardOpenAttempts")
            }
            log(s, "Google Wallet *$currentCardLast4 retry=$cardOpenAttempts accepted=$accepted")
            cardOpenStartedAt = System.currentTimeMillis()
            schedule(900)
            return
        }

        // We left the home page but the card content may still be binding.
        if (cardOpenAttempts == 0) {
            captureFormal(s, root, "card-${currentCardLast4}-opening-transient")
        }
        if (System.currentTimeMillis() - cardOpenStartedAt > 7000L) {
            cardOpenAttempts += 1
            captureFormal(s, root, "card-${currentCardLast4}-opening-timeout$cardOpenAttempts")
            if (cardOpenAttempts > MAX_CARD_OPEN_ATTEMPTS) {
                log(s, "⚠ Google Wallet *$currentCardLast4 卡片頁載入逾時，返回首頁並跳過")
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                h.postDelayed({ skipCurrentCardFromHome() }, 700)
                return
            }
            cardOpenStartedAt = System.currentTimeMillis()
        }
        schedule(500)
    }

    private fun processHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)

        // "查看更多交易" click may also be accepted without navigation. If that
        // happens, move back to card-opening logic and retry the button.
        if (values.any { it == "查看更多交易" } && values.none { it == "交易記錄" }) {
            captureFormal(s, root, "history-${currentCardLast4}-still-card-page")
            stage = STAGE_CARD_OPENING
            cardOpenStartedAt = System.currentTimeMillis()
            schedule(450)
            return
        }

        val rows = parseRows(root)
        if (rows.isEmpty()) {
            captureFormal(s, root, "history-${currentCardLast4}-rows-empty")
            schedule(550)
            return
        }

        val fingerprint = rows.joinToString("|") { it.key }
        val newUnique = rows.count { seenThisRun.add(it.key) && !knownAtStart.contains(it.key) }
        mergeRows(s, rows)
        newThisRun += newUnique

        val stored = GoogleWalletTransactionStore.load(s).associateBy { it.key }
        val detailCandidate = rows.firstOrNull { tx ->
            tx.key !in detailVisitedThisRun &&
                stored[tx.key]?.detailChecked != true &&
                isRecentEnoughForDetail(tx.date)
        }

        if (detailCandidate != null) {
            val rowNode = findRowNode(root, detailCandidate)
            captureFormal(s, root, "history-${currentCardLast4}-before-detail-${safeLabel(detailCandidate.shop)}")
            if (rowNode != null && clickRobust(s, rowNode, "detail-${safeLabel(detailCandidate.shop)}")) {
                pendingDetail = detailCandidate
                detailAttempts = 0
                detailOpenedAt = System.currentTimeMillis()
                stage = STAGE_DETAIL
                lastFingerprint = ""
                noMoveCount = 0
                log(s, "Google Wallet *$currentCardLast4 開啟明細：${detailCandidate.shop} ${detailCandidate.date.substringBefore(' ')} amount=${detailCandidate.amount}")
                schedule(700)
                return
            }
            detailVisitedThisRun.add(detailCandidate.key)
            log(s, "⚠ Google Wallet 找到待補時間交易，但 row 無法點擊：${detailCandidate.shop}")
        }

        val allKnown = rows.all { knownAtStart.contains(it.key) }
        consecutiveKnownPages = if (allKnown) consecutiveKnownPages + 1 else 0
        if (fingerprint == lastFingerprint) noMoveCount += 1 else noMoveCount = 0
        lastFingerprint = fingerprint

        log(s, "Google Wallet *$currentCardLast4 page=$page rows=${rows.size} new=$newUnique knownPages=$consecutiveKnownPages noMove=$noMoveCount")

        if (consecutiveKnownPages >= 2 || noMoveCount >= 2 || page >= MAX_PAGES_PER_CARD) {
            finishCurrentCard(s)
            return
        }

        val scrollable = findScrollable(root)
        val moved = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        if (!moved) {
            finishCurrentCard(s)
            return
        }
        page += 1
        schedule(750)
    }

    private fun processTransactionDetail(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val tx = pendingDetail
        if (tx == null) {
            stage = STAGE_HISTORY
            schedule(400)
            return
        }

        val values = descendantTexts(root)
        if (detailAttempts == 0 || detailAttempts == 4 || detailAttempts == 9) {
            captureFormal(s, root, "detail-${currentCardLast4}-${safeLabel(tx.shop)}-try$detailAttempts")
        }

        val exactDateTime = extractExactDateTime(tx.date, values)
        if (exactDateTime != null) {
            if (GoogleWalletTransactionStore.updateDetailTime(s, tx.key, exactDateTime)) {
                detailsThisRun += 1
                log(s, "✓ Google Wallet 明細時間：${tx.shop} -> $exactDateTime")
            } else {
                log(s, "⚠ Google Wallet 明細時間已讀到，但找不到 store key=${tx.key}")
            }
            detailVisitedThisRun.add(tx.key)
            pendingDetail = null
            detailAttempts = 0
            detailOpenedAt = 0L
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            stage = STAGE_HISTORY
            schedule(800)
            return
        }

        val stillHistory = values.any { it == "交易記錄" } && values.any { it == tx.shop }
        detailAttempts += 1

        if (stillHistory && detailAttempts == 5 && System.currentTimeMillis() - detailOpenedAt > 1200L) {
            val rowNode = findRowNode(root, tx)
            if (rowNode != null) {
                log(s, "Google Wallet 明細仍停在列表，使用 gesture fallback：${tx.shop}")
                clickGesture(s, rowNode, "detail-retry-${safeLabel(tx.shop)}")
            }
        }

        if (detailAttempts < 14) {
            schedule(300)
            return
        }

        detailVisitedThisRun.add(tx.key)
        captureFormal(s, root, "detail-${currentCardLast4}-${safeLabel(tx.shop)}-no-time")
        log(s, "⚠ Google Wallet 明細未讀到時間，保留待補：${tx.shop} ${tx.date.substringBefore(' ')}")
        pendingDetail = null
        detailAttempts = 0
        detailOpenedAt = 0L

        // Only BACK when we actually left the history list. If the click never
        // navigated, BACK here would incorrectly exit the history page.
        if (!stillHistory) {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        stage = STAGE_HISTORY
        schedule(800)
    }

    private fun finishCurrentCard(s: PayAccessibilityService) {
        log(s, "✓ Google Wallet card *$currentCardLast4 掃描完成")
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        h.postDelayed({
            if (!running) return@postDelayed
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            h.postDelayed({
                if (!running) return@postDelayed
                cardIndex += 1
                stage = STAGE_HOME
                currentCardName = ""
                currentCardLast4 = ""
                currentCardType = ""
                schedule(900)
            }, 650)
        }, 650)
    }

    private fun skipCurrentCardFromHome() {
        cardIndex += 1
        stage = STAGE_HOME
        currentCardName = ""
        currentCardLast4 = ""
        currentCardType = ""
        cardOpenAttempts = 0
        cardOpenStartedAt = 0L
        resetCardPageState()
        schedule(600)
    }

    private fun resetCardPageState() {
        page = 0
        lastFingerprint = ""
        noMoveCount = 0
        consecutiveKnownPages = 0
    }

    private fun isWalletHome(root: AccessibilityNodeInfo, values: List<String> = descendantTexts(root)): Boolean {
        if (values.any { it == "查看更多交易" } || values.any { it == "交易記錄" }) return false
        return values.any { it == "付款卡" } && findCards(root).isNotEmpty()
    }

    private fun mergeRows(s: PayAccessibilityService, rows: List<GoogleWalletTransaction>) {
        if (rows.isNotEmpty()) GoogleWalletTransactionStore.merge(s, rows)
    }

    private fun parseRows(root: AccessibilityNodeInfo): List<GoogleWalletTransaction> {
        val out = LinkedHashMap<String, GoogleWalletTransaction>()
        val dateRegex = Regex("^\\d{1,2}月\\d{1,2}日$")
        val amountRegex = Regex("^\\$[0-9,]+(?:\\.[0-9]{2})?$")
        val controls = setOf(
            "交易", "交易記錄", "查看更多交易", "全部", "Google Pay", "Google Pay",
            "搜尋", "返回", "在錢包中搜尋", "憑證", "會員方案", "從主畫面移除"
        )

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35) return
            if (n.isClickable) {
                val values = descendantTexts(n)
                val dateText = values.firstOrNull { dateRegex.matches(it) }
                val amountText = values.firstOrNull { amountRegex.matches(it) }
                if (dateText != null && amountText != null) {
                    val shop = values.firstOrNull { value ->
                        value.isNotBlank() &&
                            !dateRegex.matches(value) &&
                            !amountRegex.matches(value) &&
                            value !in controls &&
                            !value.startsWith("末位數號碼為") &&
                            !value.contains("••")
                    }.orEmpty()
                    if (shop.isNotBlank()) {
                        val tx = GoogleWalletTransaction(
                            shop = shop,
                            amount = amountText.replace("$", "").replace(",", ""),
                            date = normalizeWalletDate(dateText),
                            capturedAt = System.currentTimeMillis(),
                            cardName = currentCardName,
                            bank = inferBank(currentCardName),
                            cardLast4 = currentCardLast4,
                            cardType = currentCardType,
                            detailChecked = false
                        )
                        out[tx.key] = tx
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.values.toList()
    }

    private fun findRowNode(root: AccessibilityNodeInfo, tx: GoogleWalletTransaction): AccessibilityNodeInfo? {
        val parts = tx.date.substringBefore(' ').split('/')
        val dateLabel = if (parts.size >= 3) {
            "${parts[1].toIntOrNull() ?: 0}月${parts[2].toIntOrNull() ?: 0}日"
        } else ""
        var found: AccessibilityNodeInfo? = null

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35 || found != null) return
            if (n.isClickable) {
                val values = descendantTexts(n)
                val shopOk = values.any { it == tx.shop }
                val dateOk = dateLabel.isNotBlank() && values.any { it == dateLabel }
                val amountOk = values.any {
                    val a = cleanNumeric(it)
                    a.isNotBlank() && a == cleanNumeric(tx.amount)
                }
                if (shopOk && dateOk && amountOk) {
                    found = n
                    return
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    private fun findCards(root: AccessibilityNodeInfo): List<CardInfo> {
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId("$GOOGLE_WALLET:id/Card") ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        return nodes.mapNotNull { node ->
            val texts = descendantTexts(node)
            val desc = texts.firstOrNull { it.startsWith("末位數號碼為") } ?: return@mapNotNull null
            val digits = Regex("末位數號碼為\\s*([0-9 ]+)")
                .find(desc)?.groupValues?.getOrNull(1)?.filter { it.isDigit() }.orEmpty()
            val last4 = digits.takeLast(4)
            if (last4.length != 4) return@mapNotNull null

            var name = desc.substringAfter("的 ", desc)
                .removeSuffix("卡片。")
                .removeSuffix("卡片")
                .trim()
            val type = when {
                name.contains("Mastercard", true) -> "Mastercard"
                name.contains("Visa", true) -> "Visa"
                name.contains("JCB", true) -> "JCB"
                else -> ""
            }
            if (type.isNotBlank()) name = name.replace(type, "", ignoreCase = true).trim()
            CardInfo(name, last4, type, node)
        }.distinctBy { it.last4 }
    }

    private fun descendantTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24 || out.size > 180) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "Image" }
                ?.let { out.add(it) }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun findExactText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val text = root.text?.toString()?.trim().orEmpty()
        val desc = root.contentDescription?.toString()?.trim().orEmpty()
        if (text == target || desc == target) return root
        for (i in 0 until root.childCount) {
            val found = root.getChild(i)?.let { findExactText(it, target) }
            if (found != null) return found
        }
        return null
    }

    private fun clickAction(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String): Boolean {
        var p: AccessibilityNodeInfo? = node
        repeat(7) { level ->
            val current = p ?: return@repeat
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                log(s, "Google Wallet click[$label] ACTION_CLICK level=$level accepted=$ok")
                if (ok) return true
            }
            p = current.parent
        }
        return false
    }

    private fun clickRobust(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String): Boolean {
        if (clickAction(s, node, label)) return true
        return clickGesture(s, node, "$label-fallback")
    }

    private fun clickGesture(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) {
            log(s, "⚠ Google Wallet gesture[$label] bounds empty")
            return false
        }

        val p = Path()
        p.moveTo(r.exactCenterX(), r.exactCenterY())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 90))
            .build()
        val accepted = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                log(s, "✓ Google Wallet gesture[$label] completed ${r.centerX()},${r.centerY()}")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                log(s, "⚠ Google Wallet gesture[$label] cancelled ${r.centerX()},${r.centerY()}")
            }
        }, null)
        log(s, "Google Wallet gesture[$label] accepted=$accepted bounds=$r")
        return accepted
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val found = root.getChild(i)?.let { findScrollable(it) }
            if (found != null) return found
        }
        return null
    }

    private fun normalizeWalletDate(text: String): String {
        val m = Regex("^(\\d{1,2})月(\\d{1,2})日$").find(text) ?: return text
        val month = m.groupValues[1].toIntOrNull() ?: return text
        val day = m.groupValues[2].toIntOrNull() ?: return text
        val now = Calendar.getInstance()
        var year = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH) + 1
        if (month > currentMonth + 1) year -= 1
        return String.format(Locale.US, "%04d/%02d/%02d 12:00:00", year, month, day)
    }

    private fun extractExactDateTime(originalDate: String, values: List<String>): String? {
        val timeRegex = Regex("(?i)(上午|下午|AM|PM)?\\s*(\\d{1,2})[:：](\\d{2})(?:[:：](\\d{2}))?")
        for (value in values) {
            val match = timeRegex.find(value) ?: continue
            val period = match.groupValues[1].uppercase(Locale.US)
            var hour = match.groupValues[2].toIntOrNull() ?: continue
            val minute = match.groupValues[3].toIntOrNull() ?: continue
            val second = match.groupValues[4].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) continue

            if (period == "下午" || period == "PM") {
                if (hour in 1..11) hour += 12
            } else if (period == "上午" || period == "AM") {
                if (hour == 12) hour = 0
            }

            val day = originalDate.substringBefore(' ')
            if (!Regex("^\\d{4}/\\d{2}/\\d{2}$").matches(day)) continue
            return String.format(Locale.US, "%s %02d:%02d:%02d", day, hour, minute, second)
        }
        return null
    }

    private fun isRecentEnoughForDetail(date: String): Boolean {
        return try {
            val parser = SimpleDateFormat("yyyy/MM/dd", Locale.US)
            parser.isLenient = false
            val day = parser.parse(date.substringBefore(' ')) ?: return false
            val ageMs = System.currentTimeMillis() - day.time
            ageMs >= -86_400_000L && ageMs <= DETAIL_MAX_AGE_DAYS * 86_400_000L
        } catch (_: Exception) {
            false
        }
    }

    private fun captureFormal(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        val visible = descendantTexts(root).joinToString("\n")
        val tree = dumpTree(root, 650).take(40000)
        GoogleWalletDiagnosticStore.add(
            s,
            GoogleWalletDiagnosticCapture(
                time = System.currentTimeMillis(),
                packageName = GOOGLE_WALLET,
                eventType = -100,
                eventClass = "formal-sync/$label",
                eventText = label,
                visibleText = visible,
                tree = tree
            )
        )
    }

    private fun dumpTree(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val sb = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= maxNodes) return
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append("#").append(count++).append(" d=").append(depth)
                .append(" text=[").append(n.text ?: "").append("] desc=[")
                .append(n.contentDescription ?: "").append("] bounds=").append(r)
                .append(" clickable=").append(n.isClickable)
                .append(" scrollable=").append(n.isScrollable)
                .append(" class=").append(n.className ?: "")
                .append(" id=[").append(n.viewIdResourceName ?: "").append("]\n")
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun safeLabel(value: String): String =
        value.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fff_-]"), "_").take(24)

    private fun cleanNumeric(value: String): String =
        value.replace(Regex("[^0-9.]"), "").trimStart('0')

    private fun inferBank(cardName: String): String = when {
        cardName.contains("彰化銀行") || cardName.contains("彰銀") -> "彰銀"
        cardName.contains("台新") -> "台新"
        cardName.contains("國泰") -> "國泰"
        cardName.contains("玉山") -> "玉山"
        cardName.contains("中信") || cardName.contains("中國信託") -> "中信"
        cardName.contains("富邦") -> "富邦"
        cardName.contains("永豐") -> "永豐"
        else -> ""
    }

    private fun finish(returnToApp: Boolean) {
        val s = service
        running = false
        h.removeCallbacks(tickRunnable)
        if (s != null) {
            log(s, "=== Google Wallet 快速同步結束；new=$newThisRun detail=$detailsThisRun total=${GoogleWalletTransactionStore.load(s).size} ===")
            if (returnToApp) {
                s.packageManager.getLaunchIntentForPackage(SELF)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    s.startActivity(it)
                }
            }
        }
        pendingDetail = null
        service = null
    }

    private fun log(s: PayAccessibilityService, message: String) {
        val p = s.getSharedPreferences("v241", 0)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val old = p.getString("log", "") ?: ""
        p.edit().putString("log", ("[$time] $message\n" + old).take(40000)).apply()
    }
}
