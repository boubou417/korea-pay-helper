package tw.apostar.notificationpaytest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** Google Wallet/GMS detail-page card metadata backfill. */
object GoogleWalletCardBackfillObserver {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val GMS = "com.google.android.gms"
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var service: PayAccessibilityService? = null
    private var ocrBusy = false
    private var detailKey = ""
    private var detailFirstSeenAt = 0L
    private var topCapturedKey = ""
    private var scrollRequestedKey = ""
    private var bottomCapturedKey = ""
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    @JvmStatic fun start(s: PayAccessibilityService) {
        service = s
        if (running) return
        running = true
        resetDetailState()
        handler.removeCallbacks(runner)
        handler.postDelayed(runner, 150L)
    }

    @JvmStatic fun stop() {
        running = false
        handler.removeCallbacks(runner)
        service = null
        ocrBusy = false
        resetDetailState()
    }

    private fun resetDetailState() {
        ocrBusy = false
        detailKey = ""
        detailFirstSeenAt = 0L
        topCapturedKey = ""
        scrollRequestedKey = ""
        bottomCapturedKey = ""
    }

    private val runner = object : Runnable {
        override fun run() {
            if (!running) return
            val s = service
            if (s == null || !GoogleWalletSyncControllerV74.isRunning()) { stop(); return }
            try { inspect(s) } catch (_: Throwable) { }
            if (running) handler.postDelayed(this, 150L)
        }
    }

    private fun inspect(s: PayAccessibilityService) {
        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null } ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != WALLET && pkg != GMS) return
        val vals = texts(root)
        if (vals.isEmpty()) return
        val joined = vals.joinToString(" ")
        val hasTransactionId = vals.any { it == "交易 ID" || it.equals("Transaction ID", true) }
        val hasDetailType = vals.any { it == "線上購物" || it == "使用手機購買" || it.contains("感應付款") }
        if (!hasTransactionId || !hasDetailType) return

        val stored = GoogleWalletTransactionStore.load(s)
        val candidates = stored.filter { joined.contains(it.shop, true) && amountVisible(vals, it.amount) }
        if (candidates.size != 1) return
        val tx = candidates.first()
        val key = tx.fallbackKey
        if (detailKey != key) {
            detailKey = key
            detailFirstSeenAt = System.currentTimeMillis()
            topCapturedKey = ""
            scrollRequestedKey = ""
            bottomCapturedKey = ""
            recordRaw(s, "detail-confirmed", "$key pkg=$pkg")
        }

        val bankLine = vals.firstOrNull { inferBank(it).isNotBlank() }
        if (bankLine != null) {
            val bank = inferBank(bankLine)
            val last4 = extractLast4(vals, bankLine)
            val cardName = cleanCardName(bankLine)
            save(s, tx, bank, last4, cardName, normalizeCard(cardName), if (last4.length == 4) "detail-observer-bank-last4" else "detail-observer-bank-only")
            if (last4.length == 4) return
        }

        // Wait until the real GMS detail has been stable. This prevents screenshots of the list page.
        if (System.currentTimeMillis() - detailFirstSeenAt < 450L || ocrBusy) return
        if (topCapturedKey != key) {
            topCapturedKey = key
            requestOcr(s, tx, "detail-top")
            return
        }
        if (scrollRequestedKey != key) {
            scrollRequestedKey = key
            scrollDetail(s, tx)
            return
        }
        if (bottomCapturedKey != key) {
            bottomCapturedKey = key
            requestOcr(s, tx, "detail-bottom")
        }
    }

    private fun scrollDetail(s: PayAccessibilityService, tx: GoogleWalletTransaction) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(630f, 2200f); lineTo(630f, 850f) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 420)).build()
        val ok = try { s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                recordRaw(s, "detail-scroll-complete", tx.fallbackKey)
                handler.postDelayed({ if (running && detailKey == tx.fallbackKey && bottomCapturedKey != tx.fallbackKey) { bottomCapturedKey = tx.fallbackKey; requestOcr(s, tx, "detail-bottom") } }, 450L)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) { recordRaw(s, "detail-scroll-cancelled", tx.fallbackKey) }
        }, null) } catch (_: Throwable) { false }
        recordRaw(s, "detail-scroll-request", "${tx.fallbackKey} accepted=$ok")
    }

    private fun requestOcr(s: PayAccessibilityService, tx: GoogleWalletTransaction, phase: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ocrBusy) return
        ocrBusy = true
        recordRaw(s, "ocr-request-$phase", tx.fallbackKey)
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(ss: AccessibilityService.ScreenshotResult) {
                    val buffer = ss.hardwareBuffer
                    val wrapped = try { Bitmap.wrapHardwareBuffer(buffer, ss.colorSpace) } catch (_: Throwable) { null }
                    val bitmap = try { wrapped?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
                    try { buffer.close() } catch (_: Throwable) { }
                    if (bitmap == null) { ocrBusy = false; recordRaw(s, "ocr-bitmap-null-$phase", tx.fallbackKey); return }
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result -> try { processOcrText(s, tx, result.text, phase) } catch (_: Throwable) { } }
                        .addOnFailureListener { e -> recordRaw(s, "ocr-failed-$phase", "${tx.fallbackKey} ${e.javaClass.simpleName}:${e.message}") }
                        .addOnCompleteListener { try { bitmap.recycle() } catch (_: Throwable) { }; ocrBusy = false }
                }
                override fun onFailure(errorCode: Int) { ocrBusy = false; recordRaw(s, "ocr-screenshot-failed-$phase", "${tx.fallbackKey} code=$errorCode") }
            })
        } catch (t: Throwable) { ocrBusy = false; recordRaw(s, "ocr-exception-$phase", "${tx.fallbackKey} ${t.javaClass.simpleName}:${t.message}") }
    }

    private fun processOcrText(s: PayAccessibilityService, tx: GoogleWalletTransaction, rawText: String, phase: String) {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val joined = lines.joinToString(" ")
        val bankLine = lines.firstOrNull { inferBank(it).isNotBlank() }
        val bank = bankLine?.let(::inferBank).orEmpty()
        if (bank.isBlank()) { recordRaw(s, "ocr-no-bank-$phase", "${tx.fallbackKey} text=${joined.take(900)}"); return }
        val last4 = extractLast4(lines, bankLine.orEmpty()).ifBlank { extractKnownCardLast4FromOcr(lines) }
        val cardName = cleanCardName(bankLine.orEmpty()).ifBlank { bankLine.orEmpty() }
        save(s, tx, bank, last4, cardName, normalizeCard(cardName), if (last4.length == 4) "screen-ocr-$phase-bank-last4" else "screen-ocr-$phase-bank-only")
        recordRaw(s, "ocr-saved-$phase", "${tx.fallbackKey} bank=$bank last4=$last4 card=$cardName text=${joined.take(900)}")
    }

    private fun extractKnownCardLast4FromOcr(lines: List<String>): String {
        val c = LinkedHashSet<String>()
        lines.forEach { raw ->
            val cardLooking = inferBank(raw).isNotBlank() || raw.contains("Mastercard", true) || raw.contains("Visa", true) || raw.contains("JCB", true) || raw.contains("萬事達") || raw.contains("卡")
            if (cardLooking) Regex("(?<![0-9])([0-9]{4})(?![0-9])").findAll(raw).forEach { c.add(it.groupValues[1]) }
        }
        return if (c.size == 1) c.first() else ""
    }

    private fun save(s: PayAccessibilityService, tx: GoogleWalletTransaction, bank: String, last4: String, cardName: String, cardType: String, source: String) {
        if (bank.isBlank()) return
        val changed = GoogleWalletTransactionStore.updateDetail(s, tx.fallbackKey, "", "", "", "", "", cardName, bank, last4, cardType, source)
        if (changed) recordRaw(s, "detail-observer-saved", "${tx.fallbackKey} bank=$bank last4=$last4 card=$cardName source=$source")
    }

    private fun amountVisible(vals: List<String>, amount: String): Boolean {
        val target = amountNumber(amount) ?: return false
        return vals.any { amountNumber(it)?.compareTo(target) == 0 }
    }
    private fun amountNumber(raw: String): java.math.BigDecimal? {
        val cleaned = raw.replace(",", "").replace(Regex("[^0-9.-]"), "")
        if (cleaned.isBlank() || cleaned == "-" || cleaned.length > 12) return null
        return try { cleaned.toBigDecimal().abs().stripTrailingZeros() } catch (_: Throwable) { null }
    }
    private fun extractLast4(vals: List<String>, bankLine: String): String {
        fun one(raw: String): String {
            val x = Regex("(?:[•·●∙⋅‧・▪◦]{1,}|末四碼|末4碼|尾號|後四碼|末位數(?:號碼)?(?:為)?)[\\s:：\\u00a0\\u200b\\u200e\\u200f]*([0-9](?:[\\s\\u00a0\\u200b\\u200e\\u200f]*[0-9]){3})", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.filter { it.isDigit() }?.takeLast(4).orEmpty()
            if (x.length == 4) return x
            return Regex("([0-9]{4})$").find(raw.replace(Regex("[\\s\\u00a0\\u200b\\u200e\\u200f]"), ""))?.groupValues?.getOrNull(1).orEmpty()
        }
        one(bankLine).takeIf { it.length == 4 }?.let { return it }
        val x = vals.map(::one).filter { it.length == 4 }.distinct()
        return if (x.size == 1) x.first() else ""
    }
    private fun cleanCardName(raw: String): String = raw.replace('\u00a0', ' ').replace("\u200b", "").replace("\u200e", "").replace("\u200f", "").replace(Regex("[\\s]*[•·●∙⋅‧・▪◦]{1,}[\\s]*[0-9 ]{4,}.*$"), "").replace(Regex("[\\s]+[0-9]{4}$"), "").trim(' ', ':', '：', '-', '–', '—', ',', '，')
    private fun normalizeCard(v: String): String = when { v.contains("Mastercard", true) || v.contains("萬事達") -> "Mastercard"; v.contains("Visa", true) -> "Visa"; v.contains("JCB", true) -> "JCB"; else -> "" }
    private fun inferBank(n: String): String = when { n.contains("彰化銀行") || n.contains("彰銀") -> "彰銀"; n.contains("台新") -> "台新"; n.contains("國泰") -> "國泰"; n.contains("玉山") -> "玉山"; n.contains("中信") || n.contains("中國信託") -> "中信"; n.contains("富邦") -> "富邦"; n.contains("永豐") -> "永豐"; n.contains("星展") || n.contains("DBS", true) -> "星展"; n.contains("聯邦") -> "聯邦"; n.contains("兆豐") -> "兆豐"; n.contains("第一銀行") || n.contains("一銀") -> "一銀"; n.contains("華南") -> "華南"; else -> "" }
    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) { if (n == null || d > 32 || out.size > 500) return; try { n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add); n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let(out::add); for (i in 0 until n.childCount) walk(n.getChild(i), d + 1) } catch (_: Throwable) { } }
        walk(root, 0); return out.distinct()
    }
    private fun recordRaw(s: PayAccessibilityService, label: String, message: String) { try { GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(System.currentTimeMillis(), GMS, -810, "formal-sync-v81/detail-ocr", label, message, "")) } catch (_: Throwable) { } }
}
