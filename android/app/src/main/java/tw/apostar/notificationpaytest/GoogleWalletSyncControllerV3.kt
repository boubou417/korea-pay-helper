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

object GoogleWalletSyncControllerV3 {
    private const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    private const val SELF = "com.bou.payhelper"
    private const val MAX_CARD_OPEN_ATTEMPTS = 5
    private const val MAX_PAGES_PER_CARD = 20
    private const val DETAIL_MAX_AGE_DAYS = 45

    private const val HOME = 0
    private const val CARD_OPENING = 1
    private const val HISTORY = 2
    private const val DETAIL = 3
    private const val RETURN_HOME = 4

    private data class CardInfo(
        val name: String,
        val last4: String,
        val type: String,
        val node: AccessibilityNodeInfo
    )

    private val handler = Handler(Looper.getMainLooper())
    private var service: PayAccessibilityService? = null
    private var running = false
    private var startedAt = 0L
    private var state = HOME

    private var cardIndex = 0
    private var currentCardName = ""
    private var currentCardLast4 = ""
    private var currentCardType = ""
    private var cardOpenAttempts = 0
    private var actionStartedAt = 0L

    private var page = 0
    private var lastFingerprint = ""
    private var noMoveCount = 0
    private var consecutiveKnownPages = 0

    private val knownAtStart = HashSet<String>()
    private val seenThisRun = HashSet<String>()
    private val detailVisitedThisRun = HashSet<String>()
    private var pendingDetail: GoogleWalletTransaction? = null
    private var detailAttempts = 0
    private var newThisRun = 0
    private var detailsThisRun = 0
    private var returnAttempts = 0

    private var lastRateCaptureLabel = ""
    private var lastRateCaptureAt = 0L

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        if (running) return

        service = s
        running = true
        startedAt = System.currentTimeMillis()
        state = HOME
        cardIndex = 0
        currentCardName = ""
        currentCardLast4 = ""
        currentCardType = ""
        cardOpenAttempts = 0
        actionStartedAt = 0L
        resetPageState()
        pendingDetail = null
        detailAttempts = 0
        newThisRun = 0
        detailsThisRun = 0
        returnAttempts = 0
        lastRateCaptureLabel = ""
        lastRateCaptureAt = 0L
        knownAtStart.clear()
        knownAtStart.addAll(GoogleWalletTransactionStore.load(s).map { it.key })
        seenThisRun.clear()
        detailVisitedThisRun.clear()

        s.stopGoogleWalletDiagnostic(false)
        GoogleWalletDiagnosticStore.clear(s)
        log(s, "=== Google Wallet V3 快速同步開始；existing=${knownAtStart.size} ===")

        val launch = s.packageManager.getLaunchIntentForPackage(GOOGLE_WALLET)
        if (launch == null) {
            log(s, "⚠ Google Wallet V3 找不到 Wallet App")
            finish(true)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            s.startActivity(launch)
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V3 launch exception=${t.javaClass.simpleName}:${t.message}")
        }
        schedule(1200)
    }

    @JvmStatic
    fun stop(returnToApp: Boolean = true) {
        finish(returnToApp)
    }

    @JvmStatic
    fun poke() {
        if (running) schedule(100)
    }

    private val tickRunnable = Runnable { safeTick() }

    private fun schedule(delay: Long = 500L) {
        if (!running) return
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delay)
    }

    private fun safeTick() {
        val s = service ?: return
        if (!running) return
        try {
            tick(s)
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V3 tick exception state=$state ${t.javaClass.simpleName}:${t.message}")
            val root = try { s.rootInActiveWindow } catch (_: Throwable) { null }
            if (root != null && root.packageName?.toString() == GOOGLE_WALLET) {
                captureFormal(s, root, "ERROR-state${state}-${t.javaClass.simpleName}")
            }
            schedule(700)
        }
    }

    private fun tick(s: PayAccessibilityService) {
        if (System.currentTimeMillis() - startedAt > 300_000L) {
            log(s, "⚠ Google Wallet V3 超過 5 分鐘，自動結束")
            finish(true)
            return
        }

        val root = s.rootInActiveWindow
        if (root == null || root.packageName?.toString() != GOOGLE_WALLET) {
            schedule(450)
            return
        }

        when (state) {
            HOME -> processHome(s, root)
            CARD_OPENING -> processCardOpening(s, root)
            HISTORY -> processHistory(s, root)
            DETAIL -> processDetail(s, root)
            RETURN_HOME -> processReturnHome(s, root)
            else -> finish(true)
        }
    }

    private fun processHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val cards = findCards(root)
        if (cards.isEmpty()) {
            captureFormalRateLimited(s, root, "v3-home-no-cards")
            schedule(600)
            return
        }
        if (cardIndex >= cards.size) {
            log(s, "✓ Google Wallet V3 全部卡片完成；new=$newThisRun detail=$detailsThisRun total=${GoogleWalletTransactionStore.load(s).size}")
            finish(true)
            return
        }

        val card = cards[cardIndex]
        currentCardName = card.name
        currentCardLast4 = card.last4
        currentCardType = card.type
        cardOpenAttempts = 0
        actionStartedAt = System.currentTimeMillis()
        pendingDetail = null
        detailAttempts = 0
        resetPageState()

        captureFormal(s, root, "v3-home-card-${card.last4}")
        log(s, "Google Wallet V3 card ${cardIndex + 1}/${cards.size}: ${card.name} *${card.last4}")

        // State and next tick are committed before touching Wallet UI.
        state = CARD_OPENING
        schedule(800)
        val ok = safeActionClick(s, card.node, "v3-card-${card.last4}-initial")
        log(s, "Google Wallet V3 card *${card.last4} initial click=$ok")
    }

    private fun processCardOpening(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)
        val more = findExactText(root, "查看更多交易")
        if (more != null) {
            mergeRows(s, parseRows(root))
            captureFormal(s, root, "v3-card-${currentCardLast4}-before-more")
            state = HISTORY
            resetPageState()
            schedule(850)
            val ok = safeClickRobust(s, more, "v3-more-${currentCardLast4}")
            log(s, "Google Wallet V3 *$currentCardLast4 查看更多交易 click=$ok")
            return
        }

        if (isWalletHome(root, values)) {
            val elapsed = System.currentTimeMillis() - actionStartedAt
            if (elapsed < 650L) {
                schedule(300)
                return
            }

            cardOpenAttempts += 1
            captureFormal(s, root, "v3-card-${currentCardLast4}-still-home-$cardOpenAttempts")
            if (cardOpenAttempts > MAX_CARD_OPEN_ATTEMPTS) {
                log(s, "⚠ Google Wallet V3 *$currentCardLast4 多次無法開卡，跳過")
                nextCardFromHome()
                return
            }

            val card = findCards(root).firstOrNull { it.last4 == currentCardLast4 }
            if (card == null) {
                log(s, "⚠ Google Wallet V3 首頁暫時找不到卡 *$currentCardLast4")
                schedule(450)
                return
            }

            actionStartedAt = System.currentTimeMillis()
            schedule(850)
            val ok = if (cardOpenAttempts == 1) {
                safeActionClick(s, card.node, "v3-card-${currentCardLast4}-retry-action")
            } else {
                safeGestureClick(s, card.node, "v3-card-${currentCardLast4}-retry-gesture$cardOpenAttempts")
            }
            log(s, "Google Wallet V3 *$currentCardLast4 retry=$cardOpenAttempts click=$ok")
            return
        }

        captureFormalRateLimited(s, root, "v3-card-${currentCardLast4}-opening")
        if (System.currentTimeMillis() - actionStartedAt > 8000L) {
            log(s, "⚠ Google Wallet V3 *$currentCardLast4 卡片頁載入逾時，返回首頁")
            state = RETURN_HOME
            returnAttempts = 0
            schedule(700)
            safeBack(s)
            return
        }
        schedule(450)
    }

    private fun processHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)

        if (values.any { it == "查看更多交易" } && values.none { it == "交易記錄" }) {
            captureFormal(s, root, "v3-history-${currentCardLast4}-still-card")
            val more = findExactText(root, "查看更多交易")
            schedule(800)
            if (more != null) safeClickRobust(s, more, "v3-more-${currentCardLast4}-retry")
            return
        }

        val rows = parseRows(root)
        if (rows.isEmpty()) {
            captureFormalRateLimited(s, root, "v3-history-${currentCardLast4}-rows-empty")
            schedule(500)
            return
        }

        val fingerprint = rows.joinToString("|") { it.key }
        val newUnique = rows.count { seenThisRun.add(it.key) && !knownAtStart.contains(it.key) }
        mergeRows(s, rows)
        newThisRun += newUnique

        val stored = GoogleWalletTransactionStore.load(s).associateBy { it.key }
        val candidate = rows.firstOrNull { tx ->
            tx.key !in detailVisitedThisRun &&
                stored[tx.key]?.detailChecked != true &&
                isRecentEnoughForDetail(tx.date)
        }

        if (candidate != null) {
            val row = findRowNode(root, candidate)
            captureFormal(s, root, "v3-history-${currentCardLast4}-before-detail-${safeLabel(candidate.shop)}")
            if (row != null) {
                pendingDetail = candidate
                detailAttempts = 0
                actionStartedAt = System.currentTimeMillis()
                state = DETAIL
                schedule(650)
                val ok = safeClickRobust(s, row, "v3-detail-${safeLabel(candidate.shop)}")
                log(s, "Google Wallet V3 開啟明細 ${candidate.shop} click=$ok")
                return
            }
            detailVisitedThisRun.add(candidate.key)
        }

        val allKnown = rows.all { knownAtStart.contains(it.key) }
        consecutiveKnownPages = if (allKnown) consecutiveKnownPages + 1 else 0
        noMoveCount = if (fingerprint == lastFingerprint) noMoveCount + 1 else 0
        lastFingerprint = fingerprint

        log(s, "Google Wallet V3 *$currentCardLast4 page=$page rows=${rows.size} new=$newUnique knownPages=$consecutiveKnownPages noMove=$noMoveCount")
        if (consecutiveKnownPages >= 2 || noMoveCount >= 2 || page >= MAX_PAGES_PER_CARD) {
            startReturnHome(s)
            return
        }

        val scrollable = findScrollable(root)
        val moved = try {
            scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V3 scroll exception=${t.javaClass.simpleName}:${t.message}")
            false
        }
        if (!moved) {
            startReturnHome(s)
            return
        }
        page += 1
        schedule(700)
    }

    private fun processDetail(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val tx = pendingDetail
        if (tx == null) {
            state = HISTORY
            schedule(350)
            return
        }

        val values = descendantTexts(root)
        if (detailAttempts == 0 || detailAttempts == 4 || detailAttempts == 9) {
            captureFormal(s, root, "v3-detail-${currentCardLast4}-${safeLabel(tx.shop)}-try$detailAttempts")
        }

        val exact = extractExactDateTime(tx.date, values)
        if (exact != null) {
            if (GoogleWalletTransactionStore.updateDetailTime(s, tx.key, exact)) {
                detailsThisRun += 1
                log(s, "✓ Google Wallet V3 明細時間 ${tx.shop} -> $exact")
            }
            detailVisitedThisRun.add(tx.key)
            pendingDetail = null
            detailAttempts = 0
            state = HISTORY
            schedule(750)
            safeBack(s)
            return
        }

        val stillHistory = values.any { it == "交易記錄" } && values.any { it == tx.shop }
        detailAttempts += 1
        if (stillHistory && detailAttempts == 4) {
            val row = findRowNode(root, tx)
            if (row != null) {
                log(s, "Google Wallet V3 明細仍在列表，gesture fallback ${tx.shop}")
                safeGestureClick(s, row, "v3-detail-retry-${safeLabel(tx.shop)}")
            }
        }

        if (detailAttempts < 14) {
            schedule(280)
            return
        }

        captureFormal(s, root, "v3-detail-${currentCardLast4}-${safeLabel(tx.shop)}-no-time")
        detailVisitedThisRun.add(tx.key)
        pendingDetail = null
        detailAttempts = 0
        state = HISTORY
        schedule(700)
        if (!stillHistory) safeBack(s)
    }

    private fun startReturnHome(s: PayAccessibilityService) {
        log(s, "✓ Google Wallet V3 card *$currentCardLast4 掃描完成，返回首頁")
        state = RETURN_HOME
        returnAttempts = 0
        schedule(650)
        safeBack(s)
    }

    private fun processReturnHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)
        if (isWalletHome(root, values)) {
            cardIndex += 1
            currentCardName = ""
            currentCardLast4 = ""
            currentCardType = ""
            state = HOME
            schedule(650)
            return
        }

        returnAttempts += 1
        captureFormalRateLimited(s, root, "v3-return-home-$returnAttempts")
        if (returnAttempts <= 4) {
            schedule(650)
            safeBack(s)
            return
        }

        log(s, "⚠ Google Wallet V3 返回首頁失敗，結束避免誤操作")
        finish(true)
    }

    private fun nextCardFromHome() {
        cardIndex += 1
        currentCardName = ""
        currentCardLast4 = ""
        currentCardType = ""
        state = HOME
        resetPageState()
        schedule(550)
    }

    private fun resetPageState() {
        page = 0
        lastFingerprint = ""
        noMoveCount = 0
        consecutiveKnownPages = 0
    }

    private fun isWalletHome(root: AccessibilityNodeInfo, values: List<String>): Boolean {
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
            try {
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
            } catch (_: Throwable) {
                return
            }
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
            try {
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
            } catch (_: Throwable) {
                return
            }
        }
        walk(root, 0)
        return found
    }

    private fun findCards(root: AccessibilityNodeInfo): List<CardInfo> {
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId("$GOOGLE_WALLET:id/Card") ?: emptyList()
        } catch (_: Throwable) {
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
            try {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                n.contentDescription?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "Image" }
                    ?.let { out.add(it) }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) {
                return
            }
        }
        walk(root, 0)
        return out
    }

    private fun findExactText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        try {
            val text = root.text?.toString()?.trim().orEmpty()
            val desc = root.contentDescription?.toString()?.trim().orEmpty()
            if (text == target || desc == target) return root
            for (i in 0 until root.childCount) {
                val found = root.getChild(i)?.let { findExactText(it, target) }
                if (found != null) return found
            }
        } catch (_: Throwable) {
            return null
        }
        return null
    }

    private fun safeActionClick(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String): Boolean {
        try {
            var p: AccessibilityNodeInfo? = node
            repeat(7) { level ->
                val current = p ?: return@repeat
                if (current.isClickable) {
                    val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    log(s, "Google Wallet V3 click[$label] ACTION_CLICK level=$level accepted=$ok")
                    if (ok) return true
                }
                p = current.parent
            }
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V3 click[$label] exception=${t.javaClass.simpleName}:${t.message}")
        }
        return false
    }

    private fun safeClickRobust(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String): Boolean {
        if (safeActionClick(s, node, label)) return true
        return safeGestureClick(s, node, "$label-fallback")
    }

    private fun safeGestureClick(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String): Boolean {
        try {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.isEmpty) return false
            val p = Path()
            p.moveTo(r.exactCenterX(), r.exactCenterY())
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 90))
                .build()
            val accepted = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    log(s, "✓ Google Wallet V3 gesture[$label] completed ${r.centerX()},${r.centerY()}")
                    poke()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    log(s, "⚠ Google Wallet V3 gesture[$label] cancelled ${r.centerX()},${r.centerY()}")
                    poke()
                }
            }, null)
            log(s, "Google Wallet V3 gesture[$label] accepted=$accepted bounds=$r")
            return accepted
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V3 gesture[$label] exception=${t.javaClass.simpleName}:${t.message}")
            return false
        }
    }

    private fun safeBack(s: PayAccessibilityService): Boolean {
        return try {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {
            false
        }
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (root.isScrollable) return root
            for (i in 0 until root.childCount) {
                val found = root.getChild(i)?.let { findScrollable(it) }
                if (found != null) return found
            }
        } catch (_: Throwable) {
            return null
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
            val parsed = parser.parse(date.substringBefore(' '))
            if (parsed == null) {
                false
            } else {
                val age = System.currentTimeMillis() - parsed.time
                age >= -86_400_000L && age <= DETAIL_MAX_AGE_DAYS * 86_400_000L
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun captureFormalRateLimited(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        val now = System.currentTimeMillis()
        if (label == lastRateCaptureLabel && now - lastRateCaptureAt < 1500L) return
        lastRateCaptureLabel = label
        lastRateCaptureAt = now
        captureFormal(s, root, label)
    }

    private fun captureFormal(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        try {
            val visible = descendantTexts(root).joinToString("\n")
            val tree = dumpTree(root, 650).take(40000)
            GoogleWalletDiagnosticStore.add(
                s,
                GoogleWalletDiagnosticCapture(
                    time = System.currentTimeMillis(),
                    packageName = GOOGLE_WALLET,
                    eventType = -300,
                    eventClass = "formal-sync-v3/$label",
                    eventText = label,
                    visibleText = visible,
                    tree = tree
                )
            )
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V3 capture[$label] exception=${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun dumpTree(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val sb = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= maxNodes) return
            try {
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
            } catch (_: Throwable) {
                return
            }
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
        handler.removeCallbacks(tickRunnable)
        if (s != null) {
            log(s, "=== Google Wallet V3 快速同步結束；new=$newThisRun detail=$detailsThisRun total=${GoogleWalletTransactionStore.load(s).size} ===")
            if (returnToApp) {
                s.packageManager.getLaunchIntentForPackage(SELF)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    try {
                        s.startActivity(it)
                    } catch (_: Throwable) {
                    }
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
