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
 * V7: Google Wallet's 查看更多交易 page is a GLOBAL Google Pay history, not a
 * per-card history. Scan it once, use Transaction ID as the authoritative key,
 * and only assign a Wallet card when the detail screen provides enough evidence.
 */
object GoogleWalletSyncControllerV7 {
    private const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    private const val GOOGLE_PLAY_SERVICES = "com.google.android.gms"
    private const val SELF = "com.bou.payhelper"
    private const val MAX_MORE_ATTEMPTS = 5
    private const val MAX_PAGES = 24
    private const val DETAIL_MAX_AGE_DAYS = 60

    private const val HOME = 0
    private const val OPEN_MORE = 1
    private const val HISTORY = 2
    private const val DETAIL = 3

    private data class WalletCard(
        val name: String,
        val last4: String,
        val type: String
    )

    private data class DetailMeta(
        val exactDateTime: String,
        val transactionId: String,
        val transactionType: String,
        val virtualCardLast4: String,
        val virtualCardType: String,
        val card: WalletCard?,
        val cardMatchSource: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private var tickScheduled = false
    private var service: PayAccessibilityService? = null
    private var running = false
    private var startedAt = 0L
    private var state = HOME
    private var moreAttempts = 0
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
    private var walletCards: List<WalletCard> = emptyList()

    private var lastRateCaptureLabel = ""
    private var lastRateCaptureAt = 0L
    private var lastSurfaceKey = ""

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
        moreAttempts = 0
        actionStartedAt = 0L
        resetPageState()
        pendingDetail = null
        detailAttempts = 0
        newThisRun = 0
        detailsThisRun = 0
        walletCards = emptyList()
        lastRateCaptureLabel = ""
        lastRateCaptureAt = 0L
        lastSurfaceKey = ""
        seenThisRun.clear()
        detailVisitedThisRun.clear()

        val removed = GoogleWalletTransactionStore.compactLegacyGlobalHistory(s)
        knownAtStart.clear()
        GoogleWalletTransactionStore.load(s).forEach {
            knownAtStart.add(it.key)
            knownAtStart.add(it.fallbackKey)
        }

        s.stopGoogleWalletDiagnostic(false)
        GoogleWalletDiagnosticStore.clear(s)
        recordSynthetic(s, "v7-start", "existing=${GoogleWalletTransactionStore.load(s).size} legacyDuplicatesRemoved=$removed")
        log(s, "=== Google Wallet V7 全域同步開始；existing=${GoogleWalletTransactionStore.load(s).size} removed=$removed ===")

        val launch = s.packageManager.getLaunchIntentForPackage(GOOGLE_WALLET)
        if (launch == null) {
            recordSynthetic(s, "v7-launch-missing", "Google Wallet package not found")
            finish(true)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { s.startActivity(launch) }
        catch (t: Throwable) { recordSynthetic(s, "v7-launch-error", "${t.javaClass.simpleName}:${t.message}") }
        schedule(1200)
    }

    @JvmStatic
    fun stop(returnToApp: Boolean = true) = finish(returnToApp)

    @JvmStatic
    fun poke() {
        if (running) schedule(80)
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
        try { tick(s) }
        catch (t: Throwable) {
            recordSynthetic(s, "ERROR-state$state-${t.javaClass.simpleName}", t.message.orEmpty())
            log(s, "⚠ Google Wallet V7 tick exception state=$state ${t.javaClass.simpleName}:${t.message}")
            schedule(700)
        }
    }

    private fun tick(s: PayAccessibilityService) {
        if (System.currentTimeMillis() - startedAt > 300_000L) {
            recordSynthetic(s, "v7-timeout", "sync exceeded 5 minutes")
            finish(true)
            return
        }

        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null }
        if (root == null) {
            recordSyntheticRateLimited(s, "v7-wait-root", "root=null")
            schedule(450)
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        val accepted = when (state) {
            DETAIL -> pkg == GOOGLE_WALLET || pkg == GOOGLE_PLAY_SERVICES
            else -> pkg == GOOGLE_WALLET
        }
        if (!accepted) {
            recordSyntheticRateLimited(s, "v7-wait-package", "state=$state activePackage=$pkg")
            schedule(450)
            return
        }

        val surfaceKey = "$state|$pkg"
        if (surfaceKey != lastSurfaceKey) {
            lastSurfaceKey = surfaceKey
            captureFormal(s, root, "v7-surface-state$state-${safePackage(pkg)}")
        }

        when (state) {
            HOME -> processHome(s, root)
            OPEN_MORE -> processOpenMore(s, root)
            HISTORY -> processHistory(s, root)
            DETAIL -> processDetail(s, root)
            else -> finish(true)
        }
    }

    private fun processHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val cards = findCards(root)
        if (cards.isEmpty()) {
            captureFormalRateLimited(s, root, "v7-home-no-cards")
            schedule(600)
            return
        }
        walletCards = cards
        val selected = selectedCardLast4(root)
        captureFormal(s, root, "v7-home-selected-$selected-cards-${cards.joinToString("_") { it.last4 }}")

        moreAttempts = 0
        state = OPEN_MORE
        actionStartedAt = System.currentTimeMillis()
        schedule(900)
        tapHomeMore(s, root, "home-more")
    }

    private fun processOpenMore(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)
        val moreTransactions = findExactText(root, "查看更多交易")
        val previewRows = parseRows(root)
        if (moreTransactions != null || values.any { it == "交易" } && previewRows.isNotEmpty()) {
            mergeRows(s, previewRows)
            captureFormal(s, root, "v7-preview-rows-${previewRows.size}")
            if (moreTransactions == null) {
                schedule(450)
                return
            }
            state = HISTORY
            resetPageState()
            actionStartedAt = System.currentTimeMillis()
            schedule(900)
            tapNode(s, moreTransactions, "more-transactions")
            return
        }

        if (isWalletHome(root, values)) {
            if (System.currentTimeMillis() - actionStartedAt < 650L) {
                schedule(300)
                return
            }
            moreAttempts += 1
            captureFormal(s, root, "v7-home-more-retry$moreAttempts")
            if (moreAttempts > MAX_MORE_ATTEMPTS) {
                recordSynthetic(s, "v7-home-more-failed", "still on Wallet home")
                finish(true)
                return
            }
            actionStartedAt = System.currentTimeMillis()
            schedule(900)
            tapHomeMore(s, root, "home-more-retry$moreAttempts")
            return
        }

        captureFormalRateLimited(s, root, "v7-open-more-loading")
        schedule(450)
    }

    private fun processHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val values = descendantTexts(root)
        if (values.any { it == "查看更多交易" } && values.none { it == "交易記錄" }) {
            captureFormal(s, root, "v7-history-still-preview")
            findExactText(root, "查看更多交易")?.let {
                schedule(850)
                tapNode(s, it, "more-transactions-retry")
                return
            }
        }

        val rows = parseRows(root)
        if (rows.isEmpty()) {
            captureFormalRateLimited(s, root, "v7-history-rows-empty")
            schedule(500)
            return
        }

        val fingerprint = rows.joinToString("|") { it.fallbackKey }
        var pageNew = 0
        rows.forEach { tx ->
            if (seenThisRun.add(tx.fallbackKey) && tx.fallbackKey !in knownAtStart && tx.key !in knownAtStart) pageNew += 1
        }
        mergeRows(s, rows)
        newThisRun += pageNew

        val stored = GoogleWalletTransactionStore.load(s).associateBy { it.fallbackKey }
        val detailCandidate = rows.firstOrNull { tx ->
            tx.fallbackKey !in detailVisitedThisRun &&
                stored[tx.fallbackKey]?.detailChecked != true &&
                isRecentEnoughForDetail(tx.date)
        }

        if (detailCandidate != null) {
            val rowNode = findRowNode(root, detailCandidate)
            captureFormal(s, root, "v7-history-before-detail-${safeLabel(detailCandidate.shop)}")
            if (rowNode != null) {
                pendingDetail = detailCandidate
                detailAttempts = 0
                state = DETAIL
                actionStartedAt = System.currentTimeMillis()
                schedule(900)
                tapNode(s, rowNode, "detail-${safeLabel(detailCandidate.shop)}")
                return
            }
            detailVisitedThisRun.add(detailCandidate.fallbackKey)
        }

        val allKnown = rows.all { it.fallbackKey in knownAtStart || it.key in knownAtStart }
        consecutiveKnownPages = if (allKnown) consecutiveKnownPages + 1 else 0
        noMoveCount = if (fingerprint == lastFingerprint) noMoveCount + 1 else 0
        lastFingerprint = fingerprint
        recordSynthetic(s, "v7-history-page$page", "rows=${rows.size} new=$pageNew knownPages=$consecutiveKnownPages noMove=$noMoveCount")

        if (consecutiveKnownPages >= 2 || noMoveCount >= 2 || page >= MAX_PAGES) {
            finish(true)
            return
        }

        page += 1
        schedule(900)
        swipeUp(s, "history-page$page")
    }

    private fun processDetail(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val tx = pendingDetail
        if (tx == null) {
            state = HISTORY
            schedule(450)
            return
        }

        val values = descendantTexts(root)
        if (detailAttempts == 0 || detailAttempts == 4 || detailAttempts == 8 || detailAttempts == 12) {
            captureFormal(s, root, "v7-detail-${safeLabel(tx.shop)}-try$detailAttempts")
        }

        val meta = extractDetailMeta(root, tx, values)
        if (meta.exactDateTime.isNotBlank() || meta.transactionId.isNotBlank()) {
            val card = meta.card
            val updated = GoogleWalletTransactionStore.updateDetail(
                s,
                tx.fallbackKey,
                meta.exactDateTime,
                meta.transactionId,
                meta.transactionType,
                meta.virtualCardLast4,
                meta.virtualCardType,
                card?.name.orEmpty(),
                card?.let { inferBank(it.name) }.orEmpty(),
                card?.last4.orEmpty(),
                card?.type.orEmpty(),
                meta.cardMatchSource
            )
            if (updated) {
                detailsThisRun += 1
                recordSynthetic(
                    s,
                    "v7-detail-ok",
                    "${tx.shop} -> ${meta.exactDateTime} id=${meta.transactionId} virtual=${meta.virtualCardType}*${meta.virtualCardLast4} wallet=${card?.last4.orEmpty()} source=${meta.cardMatchSource}"
                )
            }
            detailVisitedThisRun.add(tx.fallbackKey)
            pendingDetail = null
            detailAttempts = 0
            state = HISTORY
            schedule(900)
            safeBack(s, "detail-success")
            return
        }

        val stillHistory = values.any { it == "交易記錄" } && values.any { it == tx.shop }
        detailAttempts += 1
        if (stillHistory && (detailAttempts == 4 || detailAttempts == 8)) {
            val rowNode = findRowNode(root, tx)
            if (rowNode != null) {
                schedule(800)
                if (detailAttempts == 4) tapNodeOffset(s, rowNode, 0.68f, "detail-retry-right-${safeLabel(tx.shop)}")
                else tapNodeOffset(s, rowNode, 0.35f, "detail-retry-left-${safeLabel(tx.shop)}")
                return
            }
        }

        if (detailAttempts < 16) {
            schedule(320)
            return
        }

        captureFormal(s, root, "v7-detail-${safeLabel(tx.shop)}-no-detail")
        detailVisitedThisRun.add(tx.fallbackKey)
        pendingDetail = null
        detailAttempts = 0
        state = HISTORY
        schedule(850)
        if (!stillHistory) safeBack(s, "detail-failed")
    }

    private fun extractDetailMeta(
        root: AccessibilityNodeInfo,
        tx: GoogleWalletTransaction,
        values: List<String>
    ): DetailMeta {
        val exact = extractExactDateTime(tx.date, values).orEmpty()
        val transactionId = extractTransactionId(root, values)
        val transactionType = values.firstOrNull {
            it == "使用手機購買" || it == "線上購物" || it.contains("感應付款")
        }.orEmpty()

        var virtualType = ""
        var virtualLast4 = ""
        val virtualRegex = Regex("(?i)(Mastercard|Visa|JCB)\\s*••\\s*([0-9 ]{4,})")
        for (value in values) {
            val m = virtualRegex.find(value) ?: continue
            virtualType = normalizeCardType(m.groupValues[1])
            virtualLast4 = m.groupValues[2].filter { it.isDigit() }.takeLast(4)
            if (virtualLast4.length == 4) break
        }

        // Strongest evidence: the detail explicitly mentions a Wallet card's last4.
        val compactAll = values.joinToString(" ").replace(" ", "")
        val explicit = walletCards.firstOrNull { compactAll.contains("••${it.last4}") }
        if (explicit != null) {
            return DetailMeta(exact, transactionId, transactionType, virtualLast4, virtualType, explicit, "explicit-wallet-last4")
        }

        // A virtual card number is not the physical Wallet card number. Only use
        // its brand when exactly one Wallet card has that brand; otherwise leave
        // card attribution blank instead of guessing.
        if (virtualType.isNotBlank()) {
            val sameType = walletCards.filter { normalizeCardType(it.type) == virtualType }
            if (sameType.size == 1) {
                return DetailMeta(exact, transactionId, transactionType, virtualLast4, virtualType, sameType.first(), "unique-card-type")
            }
        }

        return DetailMeta(exact, transactionId, transactionType, virtualLast4, virtualType, null, "")
    }

    private fun extractTransactionId(root: AccessibilityNodeInfo, values: List<String>): String {
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId("$GOOGLE_PLAY_SERVICES:id/UserVisibleTransactionId")
            val value = nodes?.firstOrNull()?.text?.toString()?.trim().orEmpty()
            if (value.isNotBlank()) return value
        } catch (_: Throwable) { }

        val index = values.indexOfFirst { it == "交易 ID" || it.equals("Transaction ID", true) }
        if (index >= 0 && index + 1 < values.size) return values[index + 1].trim()
        return ""
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
                            value.isNotBlank() && !dateRegex.matches(value) && !amountRegex.matches(value) &&
                                value !in controls && !value.startsWith("末位數號碼為") && !value.contains("••")
                        }.orEmpty()
                        if (shop.isNotBlank()) {
                            val tx = GoogleWalletTransaction(
                                shop = shop,
                                amount = amountText.replace("$", "").replace(",", ""),
                                date = normalizeWalletDate(dateText),
                                capturedAt = System.currentTimeMillis()
                            )
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

    private fun findRowNode(root: AccessibilityNodeInfo, tx: GoogleWalletTransaction): AccessibilityNodeInfo? {
        val parts = tx.date.substringBefore(' ').split('/')
        val dateLabel = if (parts.size >= 3) "${parts[1].toIntOrNull() ?: 0}月${parts[2].toIntOrNull() ?: 0}日" else ""
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35 || found != null) return
            try {
                if (n.isClickable) {
                    val values = descendantTexts(n)
                    val shopOk = values.any { it == tx.shop }
                    val dateOk = dateLabel.isNotBlank() && values.any { it == dateLabel }
                    val amountOk = values.any { cleanNumeric(it).let { a -> a.isNotBlank() && a == cleanNumeric(tx.amount) } }
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

    private fun findCards(root: AccessibilityNodeInfo): List<WalletCard> {
        val nodes = try { root.findAccessibilityNodeInfosByViewId("$GOOGLE_WALLET:id/Card") ?: emptyList() }
        catch (_: Throwable) { emptyList() }
        return nodes.mapNotNull { node ->
            val desc = descendantTexts(node).firstOrNull { it.startsWith("末位數號碼為") } ?: return@mapNotNull null
            val digits = Regex("末位數號碼為\\s*([0-9 ]+)").find(desc)?.groupValues?.getOrNull(1)?.filter { it.isDigit() }.orEmpty()
            val last4 = digits.takeLast(4)
            if (last4.length != 4) return@mapNotNull null
            var name = desc.substringAfter("的 ", desc).removeSuffix("卡片。").removeSuffix("卡片").trim()
            val type = when {
                name.contains("Mastercard", true) -> "Mastercard"
                name.contains("Visa", true) -> "Visa"
                name.contains("JCB", true) -> "JCB"
                else -> ""
            }
            if (type.isNotBlank()) name = name.replace(type, "", ignoreCase = true).trim()
            WalletCard(name, last4, type)
        }.distinctBy { it.last4 }
    }

    private fun selectedCardLast4(root: AccessibilityNodeInfo): String {
        for (value in descendantTexts(root)) {
            val m = Regex("••([0-9]{4})").find(value.replace(" ", ""))
            if (m != null) return m.groupValues[1]
        }
        return ""
    }

    private fun tapHomeMore(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        val node = findExactText(root, "顯示更多")
        if (node == null) {
            recordSynthetic(s, "tap-$label-missing", "顯示更多 not found")
            return
        }
        tapNode(s, node, label)
    }

    private fun tapNode(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String) =
        tapNodeOffset(s, node, 0.5f, label)

    private fun tapNodeOffset(s: PayAccessibilityService, node: AccessibilityNodeInfo, xFraction: Float, label: String) {
        val r = Rect()
        try { node.getBoundsInScreen(r) }
        catch (t: Throwable) {
            recordSynthetic(s, "tap-$label-bounds-error", "${t.javaClass.simpleName}:${t.message}")
            return
        }
        if (r.isEmpty) {
            recordSynthetic(s, "tap-$label-empty", "empty bounds")
            return
        }
        val x = r.left + r.width() * xFraction.coerceIn(0.15f, 0.85f)
        tapPoint(s, x, r.exactCenterY(), label)
    }

    private fun tapPoint(s: PayAccessibilityService, x: Float, y: Float, label: String) {
        recordSynthetic(s, "tap-$label-dispatch", "x=${x.toInt()} y=${y.toInt()}")
        try {
            val path = Path(); path.moveTo(x, y)
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
            val accepted = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordSynthetic(s, "tap-$label-completed", "x=${x.toInt()} y=${y.toInt()}")
                    poke()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordSynthetic(s, "tap-$label-cancelled", "x=${x.toInt()} y=${y.toInt()}")
                    poke()
                }
            }, null)
            recordSynthetic(s, "tap-$label-accepted", "accepted=$accepted x=${x.toInt()} y=${y.toInt()}")
            if (!accepted) poke()
        } catch (t: Throwable) {
            recordSynthetic(s, "tap-$label-error", "${t.javaClass.simpleName}:${t.message}")
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
            val path = Path(); path.moveTo(x, y1); path.lineTo(x, y2)
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 420)).build()
            val accepted = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { recordSynthetic(s, "swipe-$label-completed", "ok"); poke() }
                override fun onCancelled(gestureDescription: GestureDescription?) { recordSynthetic(s, "swipe-$label-cancelled", "cancelled"); poke() }
            }, null)
            recordSynthetic(s, "swipe-$label-accepted", "accepted=$accepted")
        } catch (t: Throwable) { recordSynthetic(s, "swipe-$label-error", "${t.javaClass.simpleName}:${t.message}"); poke() }
    }

    private fun safeBack(s: PayAccessibilityService, label: String) {
        recordSynthetic(s, "back-$label", "dispatch")
        try { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
        catch (t: Throwable) { recordSynthetic(s, "back-$label-error", "${t.javaClass.simpleName}:${t.message}") }
    }

    private fun mergeRows(s: PayAccessibilityService, rows: List<GoogleWalletTransaction>) {
        if (rows.isNotEmpty()) GoogleWalletTransactionStore.merge(s, rows)
    }

    private fun descendantTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 26 || out.size > 260) return
            try {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let { out.add(it) }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0)
        return out
    }

    private fun findExactText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        try {
            if (root.text?.toString()?.trim() == target || root.contentDescription?.toString()?.trim() == target) return root
            for (i in 0 until root.childCount) {
                val found = root.getChild(i)?.let { findExactText(it, target) }
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
        if (month > now.get(Calendar.MONTH) + 2) year -= 1
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
            if (period == "下午" || period == "PM") { if (hour in 1..11) hour += 12 }
            else if (period == "上午" || period == "AM") { if (hour == 12) hour = 0 }
            val day = originalDate.substringBefore(' ')
            if (Regex("^\\d{4}/\\d{2}/\\d{2}$").matches(day)) return String.format(Locale.US, "%s %02d:%02d:%02d", day, hour, minute, second)
        }
        return null
    }

    private fun isRecentEnoughForDetail(date: String): Boolean = try {
        val parser = SimpleDateFormat("yyyy/MM/dd", Locale.US); parser.isLenient = false
        val day = parser.parse(date.substringBefore(' ')) ?: return false
        val age = System.currentTimeMillis() - day.time
        age >= -86_400_000L && age <= DETAIL_MAX_AGE_DAYS * 86_400_000L
    } catch (_: Throwable) { false }

    private fun resetPageState() {
        page = 0; lastFingerprint = ""; noMoveCount = 0; consecutiveKnownPages = 0
    }

    private fun normalizeCardType(value: String): String = when {
        value.contains("Mastercard", true) -> "Mastercard"
        value.contains("Visa", true) -> "Visa"
        value.contains("JCB", true) -> "JCB"
        else -> value.trim()
    }

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

    private fun captureFormalRateLimited(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        val now = System.currentTimeMillis()
        if (label == lastRateCaptureLabel && now - lastRateCaptureAt < 1500L) return
        lastRateCaptureLabel = label; lastRateCaptureAt = now; captureFormal(s, root, label)
    }

    private fun recordSyntheticRateLimited(s: PayAccessibilityService, label: String, message: String) {
        val now = System.currentTimeMillis()
        if (label == lastRateCaptureLabel && now - lastRateCaptureAt < 1500L) return
        lastRateCaptureLabel = label; lastRateCaptureAt = now; recordSynthetic(s, label, message)
    }

    private fun captureFormal(s: PayAccessibilityService, root: AccessibilityNodeInfo, label: String) {
        try {
            val pkg = root.packageName?.toString().orEmpty()
            GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(
                System.currentTimeMillis(), pkg, -700, "formal-sync-v7/$label", label,
                descendantTexts(root).joinToString("\n"), dumpTree(root, 650).take(40000)
            ))
        } catch (t: Throwable) { log(s, "⚠ Google Wallet V7 capture[$label] ${t.javaClass.simpleName}:${t.message}") }
    }

    private fun recordSynthetic(s: PayAccessibilityService, label: String, message: String) {
        try { GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(
            System.currentTimeMillis(), GOOGLE_WALLET, -701, "formal-sync-v7/$label", label, message, ""
        )) } catch (_: Throwable) { }
    }

    private fun dumpTree(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val sb = StringBuilder(); var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= maxNodes) return
            try {
                val r = Rect(); n.getBoundsInScreen(r)
                sb.append("#").append(count++).append(" d=").append(depth)
                    .append(" text=[").append(n.text ?: "").append("] desc=[").append(n.contentDescription ?: "")
                    .append("] bounds=").append(r).append(" clickable=").append(n.isClickable)
                    .append(" scrollable=").append(n.isScrollable).append(" class=").append(n.className ?: "")
                    .append(" id=[").append(n.viewIdResourceName ?: "").append("]\n")
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0); return sb.toString()
    }

    private fun safeLabel(value: String): String = value.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fff_-]"), "_").take(24)
    private fun safePackage(value: String): String = value.replace('.', '_').take(48)
    private fun cleanNumeric(value: String): String = value.replace(Regex("[^0-9.]"), "").trimStart('0')

    private fun finish(returnToApp: Boolean) {
        val s = service
        running = false; tickScheduled = false; handler.removeCallbacks(tickRunnable)
        if (s != null) {
            recordSynthetic(s, "v7-finish", "new=$newThisRun detail=$detailsThisRun total=${GoogleWalletTransactionStore.load(s).size}")
            log(s, "=== Google Wallet V7 全域同步結束；new=$newThisRun detail=$detailsThisRun total=${GoogleWalletTransactionStore.load(s).size} ===")
            if (returnToApp) {
                try { s.packageManager.getLaunchIntentForPackage(SELF)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP); s.startActivity(it) } }
                catch (_: Throwable) { }
            }
        }
        pendingDetail = null; service = null
    }

    private fun log(s: PayAccessibilityService, message: String) {
        val p = s.getSharedPreferences("v241", 0)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val old = p.getString("log", "") ?: ""
        p.edit().putString("log", ("[$time] $message\n" + old).take(40000)).apply()
    }
}
