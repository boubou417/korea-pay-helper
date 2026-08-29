package tw.apostar.notificationpaytest

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Independent safety-net observer for Google Wallet transaction detail pages.
 *
 * Some Google/Android builds render the issuer/card chip visibly but expose it to
 * Accessibility differently from the rest of the detail page. The main sync parser
 * therefore can occasionally miss bank/card metadata even though the user can see it.
 * This observer watches the active detail page while the normal V74 controller runs,
 * identifies the matching stored transaction by merchant + amount, and persists any
 * bank/card metadata it can read. Bank-only metadata is still useful because Pay Helper
 * can safely match it when that bank has exactly one eligible configured card.
 */
object GoogleWalletCardBackfillObserver {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val GMS = "com.google.android.gms"
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var service: PayAccessibilityService? = null

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        service = s
        if (running) return
        running = true
        handler.removeCallbacks(runner)
        handler.postDelayed(runner, 250L)
    }

    @JvmStatic
    fun stop() {
        running = false
        handler.removeCallbacks(runner)
        service = null
    }

    private val runner = object : Runnable {
        override fun run() {
            if (!running) return
            val s = service
            if (s == null || !GoogleWalletSyncControllerV74.isRunning()) {
                stop()
                return
            }
            try { inspect(s) } catch (_: Throwable) { }
            if (running) handler.postDelayed(this, 250L)
        }
    }

    private fun inspect(s: PayAccessibilityService) {
        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null } ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != WALLET && pkg != GMS) return

        val vals = texts(root)
        if (vals.isEmpty()) return
        val joined = vals.joinToString(" ")
        val looksDetail = vals.any { it == "交易 ID" || it.equals("Transaction ID", true) } ||
            vals.any { it == "線上購物" || it == "使用手機購買" || it.contains("感應付款") }
        if (!looksDetail) return

        val bankLine = vals.firstOrNull { inferBank(it).isNotBlank() } ?: return
        val bank = inferBank(bankLine)
        if (bank.isBlank()) return

        val stored = GoogleWalletTransactionStore.load(s)
        val candidates = stored.filter { tx ->
            joined.contains(tx.shop, ignoreCase = true) && amountVisible(vals, tx.amount)
        }
        if (candidates.size != 1) return
        val tx = candidates.first()

        val last4 = extractLast4(vals, bankLine)
        val cardName = cleanCardName(bankLine)
        val cardType = normalizeCard(cardName)

        val changed = GoogleWalletTransactionStore.updateDetail(
            s,
            tx.fallbackKey,
            "",
            "",
            "",
            "",
            "",
            cardName,
            bank,
            last4,
            cardType,
            if (last4.length == 4) "detail-observer-bank-last4" else "detail-observer-bank-only"
        )
        if (changed) {
            record(s, tx, bank, last4, cardName)
        }
    }

    private fun amountVisible(vals: List<String>, amount: String): Boolean {
        val target = amountNumber(amount) ?: return false
        return vals.any { raw ->
            val n = amountNumber(raw)
            n != null && n.compareTo(target) == 0
        }
    }

    private fun amountNumber(raw: String): java.math.BigDecimal? {
        val cleaned = raw.replace(",", "").replace(Regex("[^0-9.-]"), "")
        if (cleaned.isBlank() || cleaned == "-" || cleaned.length > 12) return null
        return try { cleaned.toBigDecimal().abs().stripTrailingZeros() } catch (_: Throwable) { null }
    }

    private fun extractLast4(vals: List<String>, bankLine: String): String {
        fun last4From(raw: String): String {
            val explicit = Regex("(?:[•·●∙⋅‧・▪◦]{1,}|末四碼|末4碼|尾號|後四碼|末位數(?:號碼)?(?:為)?)[\\s:：\\u00a0\\u200b\\u200e\\u200f]*([0-9](?:[\\s\\u00a0\\u200b\\u200e\\u200f]*[0-9]){3})", RegexOption.IGNORE_CASE)
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.filter { it.isDigit() }
                ?.takeLast(4)
                .orEmpty()
            if (explicit.length == 4) return explicit

            val compact = raw.replace(Regex("[\\s\\u00a0\\u200b\\u200e\\u200f]"), "")
            val tail = Regex("([0-9]{4})$").find(compact)?.groupValues?.getOrNull(1).orEmpty()
            return tail.takeIf { it.length == 4 } ?: ""
        }

        last4From(bankLine).takeIf { it.length == 4 }?.let { return it }

        val explicit = vals.map(::last4From).filter { it.length == 4 }.distinct()
        if (explicit.size == 1) return explicit.first()

        val standalone = vals.mapNotNull { raw ->
            val trimmed = raw.trim()
            val digits = trimmed.filter { it.isDigit() }
            if (digits.length == 4 && trimmed.none { it.isLetter() } &&
                !trimmed.contains(':') && !trimmed.contains('/') && !trimmed.contains('-')) digits else null
        }.distinct()
        return if (standalone.size == 1) standalone.first() else ""
    }

    private fun cleanCardName(raw: String): String {
        return raw
            .replace('\u00a0', ' ')
            .replace("\u200b", "")
            .replace("\u200e", "")
            .replace("\u200f", "")
            .replace(Regex("[\\s]*[•·●∙⋅‧・▪◦]{1,}[\\s]*[0-9 ]{4,}.*$"), "")
            .trim(' ', ':', '：', '-', '–', '—', ',', '，')
    }

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
            if (n == null || depth > 32 || out.size > 500) return
            try {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let(out::add)
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0)
        return out.distinct()
    }

    private fun record(s: PayAccessibilityService, tx: GoogleWalletTransaction, bank: String, last4: String, cardName: String) {
        try {
            GoogleWalletDiagnosticStore.add(
                s,
                GoogleWalletDiagnosticCapture(
                    System.currentTimeMillis(),
                    GMS,
                    -790,
                    "formal-sync-v79/detail-observer",
                    "detail-observer-saved",
                    "${tx.fallbackKey} bank=$bank last4=$last4 card=$cardName",
                    ""
                )
            )
        } catch (_: Throwable) { }
    }
}
