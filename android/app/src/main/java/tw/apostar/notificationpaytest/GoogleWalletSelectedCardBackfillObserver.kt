package tw.apostar.notificationpaytest

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar
import java.util.Locale

/**
 * Associates transactions shown on Wallet's card-specific page with the selected card.
 * This is stronger evidence than the global Google Pay history: the card-specific page
 * belongs to the card whose CardMetadata is shown immediately before opening it.
 */
object GoogleWalletSelectedCardBackfillObserver {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var service: PayAccessibilityService? = null
    private var selectedCardName = ""
    private var selectedBank = ""
    private var selectedLast4 = ""
    private var selectedType = ""
    private var lastSavedSignature = ""

    @JvmStatic fun start(s: PayAccessibilityService) {
        service = s
        if (running) return
        running = true
        selectedCardName = ""
        selectedBank = ""
        selectedLast4 = ""
        selectedType = ""
        lastSavedSignature = ""
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
            if (running) handler.postDelayed(this, 120L)
        }
    }

    private fun inspect(s: PayAccessibilityService) {
        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null } ?: return
        if (root.packageName?.toString() != WALLET) return
        val vals = texts(root)
        if (vals.isEmpty()) return

        // CardMetadata on Wallet home identifies the actually selected card. Prefer a
        // compact "issuer/card ••1234" line; do not use the other carousel cards.
        val selectedLine = vals.firstOrNull { raw ->
            inferBank(raw).isNotBlank() && extractLast4(raw).length == 4 &&
                (raw.contains("••") || raw.contains("··") || raw.contains("●●"))
        }
        if (selectedLine != null) {
            val bank = inferBank(selectedLine)
            val last4 = extractLast4(selectedLine)
            if (bank.isNotBlank() && last4.length == 4) {
                selectedCardName = cleanCardName(selectedLine)
                selectedBank = bank
                selectedLast4 = last4
                selectedType = normalizeCard(selectedLine)
                record(s, "selected-card", "bank=$bank last4=$last4 card=$selectedCardName")
            }
        }

        if (selectedBank.isBlank() || selectedLast4.length != 4) return

        // The global Google Pay history has these filter chips. Never infer a physical
        // card there because that history mixes cards.
        val isGlobalHistory = vals.any { it == "全部" } && vals.any { it == "Google Pay" || it == "Google Pay" }
        if (isGlobalHistory) return

        // Card-specific transaction panel/page. This is where today's $270 transaction
        // appears in the diagnostic while the selected card is 彰銀 ••0102.
        if (vals.none { it == "交易" || it == "查看更多交易" }) return
        val rows = parseRows(root)
        if (rows.isEmpty()) return

        GoogleWalletTransactionStore.merge(s, rows)
        var saved = 0
        rows.forEach { tx ->
            val changed = GoogleWalletTransactionStore.updateDetail(
                s, tx.fallbackKey,
                "", "", "", "", "",
                selectedCardName, selectedBank, selectedLast4, selectedType,
                "wallet-selected-card-page-v82"
            )
            if (changed) saved++
        }
        val sig = "$selectedLast4:${rows.joinToString("|") { it.fallbackKey }}"
        if (sig != lastSavedSignature) {
            lastSavedSignature = sig
            record(s, "selected-card-transactions", "bank=$selectedBank last4=$selectedLast4 rows=${rows.size} saved=$saved keys=${rows.joinToString(" || ") { it.fallbackKey }}")
        }
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
                            val tx = GoogleWalletTransaction(
                                shop,
                                amount.replace("$", "").replace(",", ""),
                                normalizeDate(date),
                                System.currentTimeMillis()
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

    private fun cleanCardName(raw: String): String = raw
        .replace('\u00a0', ' ')
        .replace("\u200b", "")
        .replace("\u200e", "")
        .replace("\u200f", "")
        .replace(Regex("[\\s]*[•·●∙⋅‧・▪◦]{1,}[\\s]*[0-9 ]{4,}.*$"), "")
        .trim()

    private fun normalizeCard(v: String): String = when {
        v.contains("Mastercard", true) || v.contains("萬事達") -> "Mastercard"
        v.contains("Visa", true) -> "Visa"
        v.contains("JCB", true) -> "JCB"
        else -> ""
    }

    private fun inferBank(n: String): String = when {
        n.contains("彰化銀行") || n.contains("彰銀") -> "彰銀"
        n.contains("台新") -> "台新"
        n.contains("國泰") -> "國泰"
        n.contains("玉山") -> "玉山"
        n.contains("中信") || n.contains("中國信託") -> "中信"
        n.contains("富邦") -> "富邦"
        n.contains("永豐") -> "永豐"
        n.contains("星展") || n.contains("DBS", true) -> "星展"
        n.contains("聯邦") -> "聯邦"
        n.contains("兆豐") -> "兆豐"
        n.contains("第一銀行") || n.contains("一銀") -> "一銀"
        n.contains("華南") -> "華南"
        else -> ""
    }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35 || out.size > 600) return
            try {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let(out::add)
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0)
        return out.distinct()
    }

    private fun record(s: PayAccessibilityService, label: String, message: String) {
        try {
            GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(
                System.currentTimeMillis(), WALLET, -820,
                "formal-sync-v82/selected-card", label, message, ""
            ))
        } catch (_: Throwable) { }
    }
}
