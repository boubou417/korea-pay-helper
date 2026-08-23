package tw.apostar.notificationpaytest

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
    private var stage = 0 // 0=wallet home, 1=card activity, 2=full history
    private var cardIndex = 0
    private var currentCardName = ""
    private var currentCardLast4 = ""
    private var currentCardType = ""
    private var page = 0
    private var lastFingerprint = ""
    private var noMoveCount = 0
    private var consecutiveKnownPages = 0
    private val knownAtStart = HashSet<String>()
    private val seenThisRun = HashSet<String>()
    private var newThisRun = 0

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        if (running) return
        service = s
        running = true
        startedAt = System.currentTimeMillis()
        stage = 0
        cardIndex = 0
        currentCardName = ""
        currentCardLast4 = ""
        currentCardType = ""
        page = 0
        lastFingerprint = ""
        noMoveCount = 0
        consecutiveKnownPages = 0
        newThisRun = 0
        seenThisRun.clear()
        knownAtStart.clear()
        knownAtStart.addAll(GoogleWalletTransactionStore.load(s).map { it.key })

        // Keep the old diagnostic available for troubleshooting, but formal sync does
        // not depend on notifications or manual diagnostic capture.
        s.stopGoogleWalletDiagnostic(false)
        log(s, "=== Google Wallet 快速同步開始；existing=${knownAtStart.size} ===")

        val launch = s.packageManager.getLaunchIntentForPackage(GOOGLE_WALLET)
        if (launch == null) {
            log(s, "⚠ 找不到 Google Wallet App")
            finish(returnToApp = true)
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
        if (System.currentTimeMillis() - startedAt > 180_000L) {
            log(s, "⚠ Google Wallet 同步超過 3 分鐘，自動結束")
            finish(true)
            return
        }

        val root = s.rootInActiveWindow
        if (root == null || root.packageName?.toString() != GOOGLE_WALLET) {
            schedule(500)
            return
        }

        when (stage) {
            0 -> processWalletHome(s, root)
            1 -> processCardActivity(s, root)
            2 -> processHistory(s, root)
            else -> finish(true)
        }
    }

    private fun processWalletHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val cards = findCards(root)
        if (cards.isEmpty()) {
            schedule(550)
            return
        }
        if (cardIndex >= cards.size) {
            log(s, "✓ Google Wallet 全部卡片同步完成；new=$newThisRun total=${GoogleWalletTransactionStore.load(s).size}")
            finish(true)
            return
        }

        val card = cards[cardIndex]
        currentCardName = card.name
        currentCardLast4 = card.last4
        currentCardType = card.type
        page = 0
        lastFingerprint = ""
        noMoveCount = 0
        consecutiveKnownPages = 0

        log(s, "Google Wallet card ${cardIndex + 1}/${cards.size}: ${card.name} *${card.last4}")
        if (clickSelfOrParent(card.node)) {
            stage = 1
            schedule(900)
        } else {
            log(s, "⚠ Google Wallet 卡片無法點擊，跳過 ${card.name} *${card.last4}")
            cardIndex += 1
            schedule(500)
        }
    }

    private fun processCardActivity(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        // The card activity exposes the latest three transactions before "查看更多交易".
        mergeRows(s, parseRows(root))

        val more = findExactText(root, "查看更多交易")
        if (more != null && clickSelfOrParent(more)) {
            stage = 2
            page = 0
            lastFingerprint = ""
            noMoveCount = 0
            consecutiveKnownPages = 0
            log(s, "Google Wallet ${currentCardLast4}: 進入查看更多交易")
            schedule(900)
            return
        }

        // If a transient event still shows the wallet home after clicking the card,
        // retry instead of clicking another card.
        schedule(550)
    }

    private fun processHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val rows = parseRows(root)
        if (rows.isEmpty()) {
            schedule(550)
            return
        }

        val pageKeys = rows.map { it.key }
        val fingerprint = pageKeys.joinToString("|")
        val newUnique = rows.count { seenThisRun.add(it.key) && !knownAtStart.contains(it.key) }
        mergeRows(s, rows)
        newThisRun += newUnique

        val allKnown = rows.all { knownAtStart.contains(it.key) }
        consecutiveKnownPages = if (allKnown) consecutiveKnownPages + 1 else 0

        if (fingerprint == lastFingerprint) noMoveCount += 1 else noMoveCount = 0
        lastFingerprint = fingerprint

        log(s, "Google Wallet ${currentCardLast4}: page=$page rows=${rows.size} new=$newUnique knownPages=$consecutiveKnownPages noMove=$noMoveCount")

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

    private fun finishCurrentCard(s: PayAccessibilityService) {
        log(s, "✓ Google Wallet card *$currentCardLast4 掃描完成")
        // History -> card activity -> wallet home.
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        h.postDelayed({
            if (!running) return@postDelayed
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            cardIndex += 1
            stage = 0
            currentCardName = ""
            currentCardLast4 = ""
            currentCardType = ""
            schedule(1000)
        }, 650)
    }

    private fun mergeRows(s: PayAccessibilityService, rows: List<GoogleWalletTransaction>) {
        if (rows.isEmpty()) return
        GoogleWalletTransactionStore.merge(s, rows)
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
                        val amount = amountText.replace("$", "").replace(",", "")
                        val tx = GoogleWalletTransaction(
                            shop = shop,
                            amount = amount,
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

    private fun findCards(root: AccessibilityNodeInfo): List<CardInfo> {
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId("$GOOGLE_WALLET:id/Card") ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return nodes.mapNotNull { node ->
            val texts = descendantTexts(node)
            val desc = texts.firstOrNull { it.startsWith("末位數號碼為") } ?: return@mapNotNull null
            val digits = Regex("末位數號碼為\\s*([0-9 ]+)").find(desc)?.groupValues?.getOrNull(1)
                ?.filter { it.isDigit() }.orEmpty()
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
            CardInfo(name = name, last4 = last4, type = type, node = node)
        }.distinctBy { it.last4 }
    }

    private fun descendantTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 20 || out.size > 80) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let { out.add(it) }
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

    private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = node
        repeat(7) {
            val current = p ?: return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = current.parent
        }
        return false
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
            log(s, "=== Google Wallet 快速同步結束；new=$newThisRun total=${GoogleWalletTransactionStore.load(s).size} ===")
            if (returnToApp) {
                s.packageManager.getLaunchIntentForPackage(SELF)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    s.startActivity(it)
                }
            }
        }
        service = null
    }

    private fun log(s: PayAccessibilityService, message: String) {
        val p = s.getSharedPreferences("v241", 0)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val old = p.getString("log", "") ?: ""
        p.edit().putString("log", ("[$time] $message\n" + old).take(40000)).apply()
    }
}
