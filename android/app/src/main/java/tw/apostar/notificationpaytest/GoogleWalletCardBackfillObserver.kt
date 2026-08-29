package tw.apostar.notificationpaytest

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * Safety-net observer for Google Wallet detail pages.
 *
 * First use Accessibility text. If Google/GMS visually renders the issuer/card chip but
 * does not expose it in the accessibility tree, Android 11+ screenshot OCR reads the
 * actual pixels on screen and persists bank/card metadata back to the same transaction.
 */
object GoogleWalletCardBackfillObserver {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val GMS = "com.google.android.gms"
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var service: PayAccessibilityService? = null
    private var ocrBusy = false
    private var lastOcrAt = 0L
    private var lastOcrKey = ""
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        service = s
        if (running) return
        running = true
        ocrBusy = false
        lastOcrAt = 0L
        lastOcrKey = ""
        handler.removeCallbacks(runner)
        handler.postDelayed(runner, 200L)
    }

    @JvmStatic
    fun stop() {
        running = false
        handler.removeCallbacks(runner)
        service = null
        ocrBusy = false
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
            if (running) handler.postDelayed(this, 200L)
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

        // Identify the transaction independently of card metadata. Merchant + amount is
        // normally visible to Accessibility even on builds that hide the card chip.
        val stored = GoogleWalletTransactionStore.load(s)
        val candidates = stored.filter { tx ->
            joined.contains(tx.shop, ignoreCase = true) && amountVisible(vals, tx.amount)
        }
        if (candidates.size != 1) return
        val tx = candidates.first()

        // Fast path: Accessibility itself exposes the bank/card chip.
        val bankLine = vals.firstOrNull { inferBank(it).isNotBlank() }
        if (bankLine != null) {
            val bank = inferBank(bankLine)
            val last4 = extractLast4(vals, bankLine)
            val cardName = cleanCardName(bankLine)
            val cardType = normalizeCard(cardName)
            save(s, tx, bank, last4, cardName, cardType,
                if (last4.length == 4) "detail-observer-bank-last4" else "detail-observer-bank-only")
            if (bank.isNotBlank() && last4.length == 4) return
        }

        // Pixel fallback: the user can see the chip but Google does not expose it to the
        // accessibility tree. OCR the actual screen instead of guessing node structure.
        requestOcr(s, tx)
    }

    private fun requestOcr(s: PayAccessibilityService, tx: GoogleWalletTransaction) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ocrBusy) return
        val now = System.currentTimeMillis()
        if (lastOcrKey == tx.fallbackKey && now - lastOcrAt < 900L) return
        lastOcrKey = tx.fallbackKey
        lastOcrAt = now
        ocrBusy = true

        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val wrapped = try { Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) } catch (_: Throwable) { null }
                    val bitmap = try { wrapped?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
                    try { buffer.close() } catch (_: Throwable) { }
                    if (bitmap == null) {
                        ocrBusy = false
                        recordRaw(s, "ocr-bitmap-null", tx.fallbackKey)
                        return
                    }
                    val image = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            try { processOcrText(s, tx, result.text) } catch (_: Throwable) { }
                        }
                        .addOnFailureListener { e -> recordRaw(s, "ocr-failed", "${tx.fallbackKey} ${e.javaClass.simpleName}:${e.message}") }
                        .addOnCompleteListener {
                            try { bitmap.recycle() } catch (_: Throwable) { }
                            ocrBusy = false
                        }
                }

                override fun onFailure(errorCode: Int) {
                    ocrBusy = false
                    recordRaw(s, "ocr-screenshot-failed", "${tx.fallbackKey} code=$errorCode")
                }
            })
        } catch (t: Throwable) {
            ocrBusy = false
            recordRaw(s, "ocr-exception", "${tx.fallbackKey} ${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun processOcrText(s: PayAccessibilityService, tx: GoogleWalletTransaction, rawText: String) {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val joined = lines.joinToString(" ")
        val bankLine = lines.firstOrNull { inferBank(it).isNotBlank() }
        val bank = bankLine?.let(::inferBank).orEmpty()
        if (bank.isBlank()) {
            recordRaw(s, "ocr-no-bank", "${tx.fallbackKey} text=${joined.take(600)}")
            return
        }

        val last4 = extractLast4(lines, bankLine.orEmpty()).ifBlank { extractKnownCardLast4FromOcr(lines) }
        val cardName = cleanCardName(bankLine.orEmpty()).ifBlank { bankLine.orEmpty() }
        val cardType = normalizeCard(cardName)
        save(s, tx, bank, last4, cardName, cardType,
            if (last4.length == 4) "screen-ocr-bank-last4" else "screen-ocr-bank-only")
        recordRaw(s, "ocr-saved", "${tx.fallbackKey} bank=$bank last4=$last4 card=$cardName text=${joined.take(600)}")
    }

    private fun extractKnownCardLast4FromOcr(lines: List<String>): String {
        val candidates = LinkedHashSet<String>()
        lines.forEach { raw ->
            // OCR often turns bullets into punctuation or drops them entirely. Restrict
            // fallback to card-looking lines so date/time/transaction-id digits are ignored.
            val cardLooking = inferBank(raw).isNotBlank() || raw.contains("Mastercard", true) ||
                raw.contains("Visa", true) || raw.contains("JCB", true) || raw.contains("萬事達") || raw.contains("卡")
            if (!cardLooking) return@forEach
            Regex("(?<![0-9])([0-9]{4})(?![0-9])").findAll(raw).forEach { candidates.add(it.groupValues[1]) }
        }
        return if (candidates.size == 1) candidates.first() else ""
    }

    private fun save(
        s: PayAccessibilityService,
        tx: GoogleWalletTransaction,
        bank: String,
        last4: String,
        cardName: String,
        cardType: String,
        source: String
    ) {
        if (bank.isBlank()) return
        val changed = GoogleWalletTransactionStore.updateDetail(
            s, tx.fallbackKey, "", "", "", "", "",
            cardName, bank, last4, cardType, source
        )
        if (changed) record(s, tx, bank, last4, cardName, source)
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
                .find(raw)?.groupValues?.getOrNull(1)?.filter { it.isDigit() }?.takeLast(4).orEmpty()
            if (explicit.length == 4) return explicit
            val compact = raw.replace(Regex("[\\s\\u00a0\\u200b\\u200e\\u200f]"), "")
            return Regex("([0-9]{4})$").find(compact)?.groupValues?.getOrNull(1).orEmpty()
        }
        last4From(bankLine).takeIf { it.length == 4 }?.let { return it }
        val explicit = vals.map(::last4From).filter { it.length == 4 }.distinct()
        return if (explicit.size == 1) explicit.first() else ""
    }

    private fun cleanCardName(raw: String): String = raw
        .replace('\u00a0', ' ')
        .replace("\u200b", "")
        .replace("\u200e", "")
        .replace("\u200f", "")
        .replace(Regex("[\\s]*[•·●∙⋅‧・▪◦]{1,}[\\s]*[0-9 ]{4,}.*$"), "")
        .replace(Regex("[\\s]+[0-9]{4}$"), "")
        .trim(' ', ':', '：', '-', '–', '—', ',', '，')

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

    private fun record(s: PayAccessibilityService, tx: GoogleWalletTransaction, bank: String, last4: String, cardName: String, source: String) {
        recordRaw(s, "detail-observer-saved", "${tx.fallbackKey} bank=$bank last4=$last4 card=$cardName source=$source")
    }

    private fun recordRaw(s: PayAccessibilityService, label: String, message: String) {
        try {
            GoogleWalletDiagnosticStore.add(
                s,
                GoogleWalletDiagnosticCapture(
                    System.currentTimeMillis(), GMS, -800,
                    "formal-sync-v80/screen-ocr", label, message, ""
                )
            )
        } catch (_: Throwable) { }
    }
}
