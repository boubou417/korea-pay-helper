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

object GoogleWalletSyncControllerV4 {
    private const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    private const val SELF = "com.bou.payhelper"
    private const val MAX_CARD_OPEN_ATTEMPTS = 5
    private const val MAX_SELECT_ATTEMPTS = 5
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
    private var tickScheduled = false
    private var service: PayAccessibilityService? = null
    private var running = false
    private var startedAt = 0L
    private var state = HOME

    private var cardIndex = 0
    private var currentCardName = ""
    private var currentCardLast4 = ""
    private var currentCardType = ""
    private var cardOpenAttempts = 0
    private var selectAttempts = 0
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
        tickScheduled = false
        startedAt = System.currentTimeMillis()
        state = HOME
        cardIndex = 0
        currentCardName = ""
        currentCardLast4 = ""
        currentCardType = ""
        cardOpenAttempts = 0
        selectAttempts = 0
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
        recordSynthetic(s, "v4-start", "Google Wallet V4 start; existing=${knownAtStart.size}")
        log(s, "=== Google Wallet V4 快速同步開始；existing=${knownAtStart.size} ===")

        val launch = s.packageManager.getLaunchIntentForPackage(GOOGLE_WALLET)
        if (launch == null) {
            recordSynthetic(s, "v4-launch-missing", "Google Wallet package not found")
            finish(true)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            s.startActivity(launch)
        } catch (t: Throwable) {
            recordSynthetic(s, "v4-launch-error", "${t.javaClass.simpleName}:${t.message}")
            log(s, "⚠ Google Wallet V4 launch exception=${t.javaClass.simpleName}:${t.message}")
        }
        schedule(1200)
    }

    @JvmStatic
    fun stop(returnToApp: Boolean = true) {
        finish(returnToApp)
    }

    @JvmStatic
    fun poke() {
        if (!running) return
        schedule(80)
    }

    private val tickRunnable = Runnable {
        tickScheduled = false
        safeTick()
    }

    private fun schedule(delay: Long = 500L) {
        if (!running || tickScheduled) return
        tickScheduled = true
        handler.postDelayed(tickRunnable, delay)
    }

    private fun safeTick() {
        val s = service ?: return
        if (!running) return
        try {
            tick(s)
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V4 tick exception state=$state ${t.javaClass.simpleName}:${t.message}")
            val root = try { s.rootInActiveWindow } catch (_: Throwable) { null }
            if (root != null && root.packageName?.toString() == GOOGLE_WALLET) {
                captureFormal(s, root, "ERROR-state${state}-${t.javaClass.simpleName}")
            } else {
                recordSynthetic(s, "ERROR-state${state}-${t.javaClass.simpleName}", t.message.orEmpty())
            }
            schedule(700)
        }
    }

    private fun tick(s: PayAccessibilityService) {
        if (System.currentTimeMillis() - startedAt > 300_000L) {
            recordSynthetic(s, "v4-timeout", "sync exceeded 5 minutes")
            finish(true)
            return
        }

        val root = try { s.rootInActiveWindow } catch (t: Throwable) {
            recordSynthetic(s, "v4-root-error", "${t.javaClass.simpleName}:${t.message}")
            null
        }
        if (root == null) {
            recordSyntheticRateLimited(s, "v4-wait-root", "rootInActiveWindow=null")
            schedule(450)
            return
        }
        val pkg = try { root.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }
        if (pkg != GOOGLE_WALLET) {
            recordSyntheticRateLimited(s, "v4-wait-package", "activePackage=$pkg")
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
            captureFormalRateLimited(s, root, "v4-home-no-cards")
            schedule(600)
            return
        }
        if (cardIndex >= cards.size) {
            recordSynthetic(s, "v4-all-cards-done", "new=$newThisRun detail=$detailsThisRun")
            finish(true)
            return
        }

        val target = cards[cardIndex]
        currentCardName = target.name
        currentCardLast4 = target.last4
        currentCardType = target.type

        val selected = selectedCardLast4(root)
        if (selected.isNotBlank() && selected != target.last4) {
            selectAttempts += 1
            captureFormal(s, root, "v4-select-${target.last4}-attempt$selectAttempts-current-$selected")
            if (selectAttempts > MAX_SELECT_ATTEMPTS) {
                log(s, "⚠ Google Wallet V4 無法選到卡 *${target.last4}，跳過")
                cardIndex += 1
                selectAttempts = 0
                schedule(500)
                return
            }
            val selectedNode = cards.firstOrNull { it.last4 == selected }?.node
            actionStartedAt = System.currentTimeMillis()
            schedule(900)
            tapCardVisibleArea(s, target.node, selectedNode, "select-${target.last4}")
            return
        }

        selectAttempts = 0
        cardOpenAttempts = 0
        actionStartedAt = System.currentTimeMillis()
        pendingDetail = null
        detailAttempts = 0
        resetPageState()

        captureFormal(s, root, "v4-home-card-${target.last4}-selected-$selected")
        state = CARD_OPENING
        schedule(900)
        tapNode(s, target.node, "open-card-${target.last4}")
    }

    private fun processCardOpening(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)
        val more = findExactText(root, "查看更多交易")
        if (more != null) {
            mergeRows(s, parseRows(root))
            captureFormal(s, root, "v4-card-${currentCardLast4}-before-more")
            state = HISTORY
            resetPageState()
            schedule(900)
            tapNode(s, more, "more-${currentCardLast4}")
            return
        }

        if (isWalletHome(root, values)) {
            if (System.currentTimeMillis() - actionStartedAt < 650L) {
                schedule(300)
                return
            }
            cardOpenAttempts += 1
            captureFormal(s, root, "v4-card-${currentCardLast4}-still-home-$cardOpenAttempts")
            if (cardOpenAttempts > MAX_CARD_OPEN_ATTEMPTS) {
                log(s, "⚠ Google Wallet V4 *$currentCardLast4 多次無法開卡，跳過")
                cardIndex += 1
                state = HOME
                selectAttempts = 0
                schedule(500)
                return
            }
            val card = findCards(root).firstOrNull { it.last4 == currentCardLast4 }
            if (card == null) {
                schedule(450)
                return
            }
            actionStartedAt = System.currentTimeMillis()
            schedule(900)
            tapNode(s, card.node, "open-card-${currentCardLast4}-retry$cardOpenAttempts")
            return
        }

        captureFormalRateLimited(s, root, "v4-card-${currentCardLast4}-opening")
        if (System.currentTimeMillis() - actionStartedAt > 8000L) {
            state = RETURN_HOME
            returnAttempts = 0
            schedule(600)
            safeBack(s, "card-opening-timeout")
            return
        }
        schedule(450)
    }

    private fun processHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)
        if (values.any { it == "查看更多交易" } && values.none { it == "交易記錄" }) {
            captureFormal(s, root, "v4-history-${currentCardLast4}-still-card-page")
            state = CARD_OPENING
            actionStartedAt = System.currentTimeMillis()
            schedule(450)
            return
        }

        val rows = parseRows(root)
        if (rows.isEmpty()) {
            captureFormalRateLimited(s, root, "v4-history-${currentCardLast4}-rows-empty")
            schedule(500)
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
            captureFormal(s, root, "v4-history-${currentCardLast4}-before-detail-${safeLabel(detailCandidate.shop)}")
            if (rowNode != null) {
                pendingDetail = detailCandidate
                detailAttempts = 0
                state = DETAIL
                schedule(750)
                tapNode(s, rowNode, "detail-${safeLabel(detailCandidate.shop)}")
                return
            }
            detailVisitedThisRun.add(detailCandidate.key)
        }

        val allKnown = rows.all { knownAtStart.contains(it.key) }
        consecutiveKnownPages = if (allKnown) consecutiveKnownPages + 1 else 0
        noMoveCount = if (fingerprint == lastFingerprint) noMoveCount + 1 else 0
        lastFingerprint = fingerprint

        if (consecutiveKnownPages >= 2 || noMoveCount >= 2 || page >= MAX_PAGES_PER_CARD) {
            finishCurrentCard(s)
            return
        }

        page += 1
        schedule(850)
        swipeUp(s, "history-${currentCardLast4}-page$page")
    }

    private fun processDetail(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val tx = pendingDetail
        if (tx == null) {
            state = HISTORY
            schedule(400)
            return
        }

        val values = descendantTexts(root)
        if (detailAttempts == 0 || detailAttempts == 4 || detailAttempts == 9) {
            captureFormal(s, root, "v4-detail-${currentCardLast4}-${safeLabel(tx.shop)}-try$detailAttempts")
        }

        val exact = extractExactDateTime(tx.date, values)
        if (exact != null) {
            if (GoogleWalletTransactionStore.updateDetailTime(s, tx.key, exact)) {
                detailsThisRun += 1
                log(s, "✓ Google Wallet V4 明細時間 ${tx.shop} -> $exact")
            }
            detailVisitedThisRun.add(tx.key)
            pendingDetail = null
            detailAttempts = 0
            state = HISTORY
            schedule(850)
            safeBack(s, "detail-success")
            return
        }

        val stillHistory = values.any { it == "交易記錄" } && values.any { it == tx.shop }
        detailAttempts += 1
        if (stillHistory && detailAttempts == 4) {
            val rowNode = findRowNode(root, tx)
            if (rowNode != null) {
                schedule(700)
                tapNode(s, rowNode, "detail-retry-${safeLabel(tx.shop)}")
                return
            }
        }

        if (detailAttempts < 14) {
            schedule(300)
            return
        }

        captureFormal(s, root, "v4-detail-${currentCardLast4}-${safeLabel(tx.shop)}-no-time")
        detailVisitedThisRun.add(tx.key)
        pendingDetail = null
        detailAttempts = 0
        state = HISTORY
        schedule(800)
        if (!stillHistory) safeBack(s, "detail-no-time")
    }

    private fun finishCurrentCard(s: PayAccessibilityService) {
        recordSynthetic(s, "v4-card-${currentCardLast4}-done", "page=$page new=$newThisRun detail=$detailsThisRun")
        state = RETURN_HOME
        returnAttempts = 0
        schedule(700)
        safeBack(s, "finish-card-history")
    }

    private fun processReturnHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        if (isWalletHome(root)) {
            cardIndex += 1
            currentCardName = ""
            currentCardLast4 = ""
            currentCardType = ""
            selectAttempts = 0
            cardOpenAttempts = 0
            resetPageState()
            state = HOME
            schedule(700)
            return
        }

        returnAttempts += 1
        captureFormalRateLimited(s, root, "v4-return-home-$returnAttempts")
        if (returnAttempts > 5) {
            log(s, "⚠ Google Wallet V4 無法返回首頁，結束同步")
            finish(true)
            return
        }
        schedule(700)
        safeBack(s, "return-home-$returnAttempts")
    }

    private fun selectedCardLast4(root: AccessibilityNodeInfo): String {
        val values = descendantTexts(root)
        for (value in values) {
            val compact = value.replace(" ", "")
            val m = Regex("••([0-9]{4})").find(compact)
            if (m != null) return m.groupValues[1]
        }
        return ""
    }

    private fun tapCardVisibleArea(
        s: PayAccessibilityService,
        target: AccessibilityNodeInfo,
        selected: AccessibilityNodeInfo?,
        label: String
    ) {
        val tr = Rect()
        try { target.getBoundsInScreen(tr) } catch (_: Throwable) { return }
        if (tr.isEmpty) return
        var x = tr.exactCenterX()
        val y = tr.exactCenterY()
        if (selected != null) {
            val sr = Rect()
            try { selected.getBoundsInScreen(sr) } catch (_: Throwable) { }
            if (!sr.isEmpty) {
                if (tr.right > sr.right + 20) {
                    x = (maxOf(sr.right, tr.left) + tr.right) / 2f
                } else if (tr.left < sr.left - 20) {
                    x = (tr.left + minOf(sr.left, tr.right)) / 2f
                }
            }
        }
        tapPoint(s, x, y, label)
    }

    private fun tapNode(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String) {
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (t: Throwable) {
            recordSynthetic(s, "tap-$label-bounds-error", "${t.javaClass.simpleName}:${t.message}")
            return
        }
        if (r.isEmpty) {
            recordSynthetic(s, "tap-$label-empty", "empty bounds")
            return
        }
        tapPoint(s, r.exactCenterX(), r.exactCenterY(), label)
    }

    private fun tapPoint(s: PayAccessibilityService, x: Float, y: Float, label: String) {
        recordSynthetic(s, "tap-$label-dispatch", "x=${x.toInt()} y=${y.toInt()}")
        try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
                .build()
            val accepted = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordSynthetic(s, "tap-$label-completed", "x=${x.toInt()} y=${y.toInt()}")
                    log(s, "✓ Google Wallet V4 tap[$label] completed ${x.toInt()},${y.toInt()}")
                    poke()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordSynthetic(s, "tap-$label-cancelled", "x=${x.toInt()} y=${y.toInt()}")
                    log(s, "⚠ Google Wallet V4 tap[$label] cancelled ${x.toInt()},${y.toInt()}")
                    poke()
                }
            }, null)
            recordSynthetic(s, "tap-$label-accepted", "accepted=$accepted x=${x.toInt()} y=${y.toInt()}")
            if (!accepted) poke()
        } catch (t: Throwable) {
            recordSynthetic(s, "tap-$label-error", "${t.javaClass.simpleName}:${t.message}")
            log(s, "⚠ Google Wallet V4 tap[$label] exception=${t.javaClass.simpleName}:${t.message}")
            poke()
        }
    }

    private fun swipeUp(s: PayAccessibilityService, label: String) {
        val dm = s.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * 0.78f
        val y2 = dm.heightPixels * 0.30f
        recordSynthetic(s, "swipe-$label-dispatch", "from=${y1.toInt()} to=${y2.toInt()}")
        try {
            val path = Path()
            path.moveTo(x, y1)
            path.lineTo(x, y2)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
                .build()
            val accepted = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordSynthetic(s, "swipe-$label-completed", "ok")
                    poke()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordSynthetic(s, "swipe-$label-cancelled", "cancelled")
                    poke()
                }
            }, null)
            recordSynthetic(s, "swipe-$label-accepted", "accepted=$accepted")
        } catch (t: Throwable) {
            recordSynthetic(s, "swipe-$label-error", "${t.javaClass.simpleName}:${t.message}")
            poke()
        }
    }

    private fun safeBack(s: PayAccessibilityService, label: String) {
        recordSynthetic(s, "back-$label", "dispatch")
        try {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (t: Throwable) {
            recordSynthetic(s, "back-$label-error", "${t.javaClass.simpleName}:${t.message}")
        }
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
            } catch (_: Throwable) { }
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
            } catch (_: Throwable) { }
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
            if (n == null || depth > 24 || out.size > 200) return
            try {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                n.contentDescription?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "Image" }
                    ?.let { out.add(it) }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
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
                val child = root.getChild(i) ?: continue
                val found = findExactText(child, target)
                if (found != null) return found
            }
        } catch (_: Throwable) { }
        return null
    }

    private fun isWalletHome(root: AccessibilityNodeInfo, values: List<String> = descendantTexts(root)): Boolean {
        if (values.any { it == "查看更多交易" } || values.any { it == "交易記錄" }) return false
        return values.any { it == "付款卡" } && findCards(root).isNotEmpty()
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
            val day = parser.parse(date.substringBefore(' '))
            if (day == null) false else {
                val age = System.currentTimeMillis() - day.time
                age >= -86_400_000L && age <= DETAIL_MAX_AGE_DAYS * 86_400_000L
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun resetPageState() {
        page = 0
        lastFingerprint = ""
        noMoveCount = 0
        consecutiveKnownPages = 0
    }

    private fun captureFormalRateLimited(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        val now = System.currentTimeMillis()
        if (label == lastRateCaptureLabel && now - lastRateCaptureAt < 1500L) return
        lastRateCaptureLabel = label
        lastRateCaptureAt = now
        captureFormal(s, root, label)
    }

    private fun recordSyntheticRateLimited(s: PayAccessibilityService, label: String, message: String) {
        val now = System.currentTimeMillis()
        if (label == lastRateCaptureLabel && now - lastRateCaptureAt < 1500L) return
        lastRateCaptureLabel = label
        lastRateCaptureAt = now
        recordSynthetic(s, label, message)
    }

    private fun captureFormal(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        try {
            GoogleWalletDiagnosticStore.add(
                s,
                GoogleWalletDiagnosticCapture(
                    time = System.currentTimeMillis(),
                    packageName = GOOGLE_WALLET,
                    eventType = -400,
                    eventClass = "formal-sync-v4/$label",
                    eventText = label,
                    visibleText = descendantTexts(root).joinToString("\n"),
                    tree = dumpTree(root, 650).take(40000)
                )
            )
        } catch (t: Throwable) {
            log(s, "⚠ Google Wallet V4 capture[$label] ${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun recordSynthetic(s: PayAccessibilityService, label: String, message: String) {
        try {
            GoogleWalletDiagnosticStore.add(
                s,
                GoogleWalletDiagnosticCapture(
                    time = System.currentTimeMillis(),
                    packageName = GOOGLE_WALLET,
                    eventType = -401,
                    eventClass = "formal-sync-v4/$label",
                    eventText = label,
                    visibleText = message,
                    tree = ""
                )
            )
        } catch (_: Throwable) { }
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
            } catch (_: Throwable) { }
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
        tickScheduled = false
        handler.removeCallbacks(tickRunnable)
        if (s != null) {
            recordSynthetic(s, "v4-finish", "new=$newThisRun detail=$detailsThisRun")
            log(s, "=== Google Wallet V4 快速同步結束；new=$newThisRun detail=$detailsThisRun total=${GoogleWalletTransactionStore.load(s).size} ===")
            if (returnToApp) {
                try {
                    s.packageManager.getLaunchIntentForPackage(SELF)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        s.startActivity(it)
                    }
                } catch (_: Throwable) { }
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
