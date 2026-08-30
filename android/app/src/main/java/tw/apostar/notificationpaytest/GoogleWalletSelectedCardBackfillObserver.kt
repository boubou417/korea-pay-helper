package tw.apostar.notificationpaytest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar
import java.util.Locale

/** Associates transactions shown on Wallet's card-specific page with the selected card. */
object GoogleWalletSelectedCardBackfillObserver {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val MAX_CARD_PAGES = 30
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var service: PayAccessibilityService? = null
    private var selectedCardName = ""
    private var selectedBank = ""
    private var selectedLast4 = ""
    private var selectedType = ""
    private var lastSavedSignature = ""
    private var page = 0
    private var lastPageSignature = ""
    private var noMove = 0
    private var lastScrollAt = 0L

    @JvmStatic fun start(s: PayAccessibilityService) {
        service = s
        if (running) return
        running = true
        selectedCardName = ""
        selectedBank = ""
        selectedLast4 = ""
        selectedType = ""
        lastSavedSignature = ""
        page = 0
        lastPageSignature = ""
        noMove = 0
        lastScrollAt = 0L
        handler.removeCallbacks(runner)
        handler.postDelayed(runner, 100L)
    }

    @JvmStatic fun stop() {
        running = false
        handler.removeCallbacks(runner)
        service = null
    }

    private val runner = object : Runnable {
        override fun run() {
            if (!running) return
            val s = service
            if (s == null || !GoogleWalletSyncControllerV74.isRunning()) { stop(); return }
            try { inspect(s) } catch (_: Throwable) { }
            if (running) handler.postDelayed(this, 180L)
        }
    }

    private fun inspect(s: PayAccessibilityService) {
        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null } ?: return
        if (root.packageName?.toString() != WALLET) return
        val vals = texts(root)
        if (vals.isEmpty()) return

        val selectedLine = vals.firstOrNull { raw ->
            inferBank(raw).isNotBlank() && extractLast4(raw).length == 4 &&
                (raw.contains("••") || raw.contains("··") || raw.contains("●●") || raw.contains("••••"))
        }
        if (selectedLine != null) {
            val bank = inferBank(selectedLine)
            val last4 = extractLast4(selectedLine)
            if (bank.isNotBlank() && last4.length == 4) {
                val changed = bank != selectedBank || last4 != selectedLast4
                selectedCardName = cleanCardName(selectedLine)
                selectedBank = bank
                selectedLast4 = last4
                selectedType = normalizeCard(selectedLine)
                if (changed) {
                    page = 0
                    lastPageSignature = ""
                    noMove = 0
                    record(s, "selected-card", "bank=$bank last4=$last4 card=$selectedCardName")
                }
            }
        }

        if (selectedBank.isBlank() || selectedLast4.length != 4) return

        val isGlobalHistory = vals.any { it == "全部" } && vals.any { it == "Google Pay" || it == "Google Pay" }
        if (isGlobalHistory) return
        if (vals.none { it == "交易" || it == "查看更多交易" }) return

        val rows = parseRows(root)
        if (rows.isEmpty()) return

        GoogleWalletTransactionStore.merge(s, rows)
        var saved = 0
        rows.forEach { tx ->
            val changed = GoogleWalletTransactionStore.updateDetail(
                s, tx.fallbackKey, "", "", "", "", "",
                selectedCardName, selectedBank, selectedLast4, selectedType,
                "wallet-selected-card-page-v83"
            )
            if (changed) saved++
        }

        val sig = "$selectedLast4:${rows.joinToString("|") { it.fallbackKey }}"
        if (sig != lastSavedSignature) {
            lastSavedSignature = sig
            record(s, "selected-card-transactions", "page=$page bank=$selectedBank last4=$selectedLast4 rows=${rows.size} saved=$saved keys=${rows.joinToString(" || ") { it.fallbackKey }}")
        }

        val unresolvedVisible = rows.any { tx ->
            GoogleWalletTransactionStore.load(s).firstOrNull { it.fallbackKey == tx.fallbackKey }?.let { needsCard(it) } == true
        }
        val pageSig = rows.joinToString("|") { it.fallbackKey }
        if (pageSig == lastPageSignature) noMove++ else noMove = 0
        lastPageSignature = pageSig

        // Keep walking the card-specific history. The important distinction is that this
        // page belongs to selectedLast4, so every transaction parsed here has strong card evidence.
        // Do not stop merely because the currently visible rows are already known.
        if (page < MAX_CARD_PAGES && System.currentTimeMillis() - lastScrollAt > 1100L && (unresolvedVisible || page < 8 || noMove < 3)) {
            page++
            lastScrollAt = System.currentTimeMillis()
            swipeUp(s, "card-history-page-$page")
        }
    }

    private fun needsCard(x: GoogleWalletTransaction): Boolean =
        !x.detailChecked || x.bank.isBlank() || x.cardLast4.length != 4 || x.cardName.isBlank()

    private fun swipeUp(s: PayAccessibilityService, label: String) {
        val path = Path().apply { moveTo(650f, 2180f); lineTo(650f, 620f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 550)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = try {
            s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    record(s, "card-history-scroll-complete", "page=$page label=$label")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    record(s, "card-history-scroll-cancelled", "page=$page label=$label")
                }
            }, null)
        } catch (_: Throwable) { false }
        record(s, "card-history-scroll-request", "page=$page accepted=$accepted label=$label")
    }

    private fun parseRows(root: AccessibilityNodeInfo): List<GoogleWalletTransaction> {
        val out = LinkedHashMap<String, GoogleWalletTransaction>()
        val dateRe = Regex("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$")
        val amountRe = Regex("^\\$[0-9,]+(?:\\.[0-9]{2})?$")
        val controls = setOf("交易", "查看更多交易", "憑證", "會員方案", "從主畫面移除", "搜尋", "返回", "在錢包中搜尋")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35) return
            try {
                if (n.isClickable) {
                    val v = texts(n)
                    val date = v.firstOrNull { dateRe.matches(it) }
                    val amount = v.firstOrNull { amountRe.matches(it) }
                    if (date != null && amount != null) {
                        val shop = v.firstOrNull { raw ->
                            val x = raw.trim()
                            x.isNotBlank() && !dateRe.matches(x) && !amountRe.matches(x) && x !in controls &&
                                !x.startsWith("末位數號碼為") && !x.contains("••")
                        }.orEmpty()
                        if (shop.isNotBlank()) {
                            val tx = GoogleWalletTransaction(shop, amount.removePrefix("$").replace(",", ""), normalizeDate(date), System.currentTimeMillis())
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
        val now = Calendar.getInstance()
        val month = m.groupValues[2].toIntOrNull() ?: return text
        val day = m.groupValues[3].toIntOrNull() ?: return text
        var year = m.groupValues[1].toIntOrNull() ?: now.get(Calendar.YEAR)
        if (m.groupValues[1].isBlank() && month > now.get(Calendar.MONTH) + 2) year--
        return String.format(Locale.US, "%04d/%02d/%02d 12:00:00", year, month, day)
    }

    private fun extractLast4(raw: String): String {
        val compact = raw.replace(Regex("[\\s\\u00a0\\u200b\\u200e\\u200f]"), "")
        return Regex("(?:[•·●∙⋅‧・▪◦]{1,})([0-9]{4})(?![0-9])").find(compact)?.groupValues?.getOrNull(1)
            ?: Regex("([0-9]{4})$").find(compact)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun cleanCardName(raw: String): String = raw.replace('\u00a0', ' ').replace("\u200b", "").replace("\u200e", "").replace("\u200f", "").replace(Regex("[\\s]*[•·●∙⋅‧・▪◦]{1,}[\\s]*[0-9 ]{4,}.*$"), "").trim()
    private fun normalizeCard(v: String): String = when { v.contains("Mastercard", true) || v.contains("萬事達") -> "Mastercard"; v.contains("Visa", true) -> "Visa"; v.contains("JCB", true) -> "JCB"; else -> "" }
    private fun inferBank(n: String): String = when { n.contains("彰化銀行") || n.contains("彰銀") -> "彰銀"; n.contains("台新") -> "台新"; n.contains("國泰") -> "國泰"; n.contains("玉山") -> "玉山"; n.contains("中信") || n.contains("中國信託") -> "中信"; n.contains("富邦") -> "富邦"; n.contains("永豐") -> "永豐"; n.contains("星展") || n.contains("DBS", true) -> "星展"; n.contains("聯邦") -> "聯邦"; n.contains("兆豐") -> "兆豐"; n.contains("第一銀行") || n.contains("一銀") -> "一銀"; n.contains("華南") -> "華南"; else -> "" }
    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) { if (n == null || depth > 35 || out.size > 600) return; try { n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add); n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let(out::add); for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1) } catch (_: Throwable) { } }
        walk(root, 0); return out.distinct()
    }
    private fun record(s: PayAccessibilityService, label: String, message: String) { try { GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(System.currentTimeMillis(), WALLET, -820, "formal-sync-v83/selected-card-history", label, message, "")) } catch (_: Throwable) { } }
}
