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
 * Google Wallet sync controller that intentionally follows the stable global-history flow:
 * Wallet home -> 顯示更多 -> 查看更多交易 -> transaction history.
 *
 * Card selection is NOT part of the navigation path here. Card metadata is left to the
 * independent backfill/OCR observer so a card page can never block basic transaction import.
 */
object GoogleWalletGlobalFirstController {
    private const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val SELF = "com.bou.payhelper"
    private const val RECENT_DAYS = 90
    private const val MAX_PAGES = 40
    private const val HOME = 0
    private const val OPEN_MORE = 1
    private const val HISTORY = 2

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var scheduled = false
    private var service: PayAccessibilityService? = null
    private var state = HOME
    private var startedAt = 0L
    private var page = 0
    private var lastFp = ""
    private var noMove = 0
    private var knownPages = 0
    private var openTry = 0

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        if (running) return
        service = s
        running = true
        scheduled = false
        state = HOME
        startedAt = System.currentTimeMillis()
        page = 0
        lastFp = ""
        noMove = 0
        knownPages = 0
        openTry = 0

        val dedup = GoogleWalletTransactionStore.compactLegacyGlobalHistory(s)
        val pruned = GoogleWalletTransactionStore.pruneUnresolvedOlderThan(s, RECENT_DAYS)
        s.stopGoogleWalletDiagnostic(false)
        GoogleWalletDiagnosticStore.clear(s)
        record(s, "global-first-start", "existing=${GoogleWalletTransactionStore.load(s).size} dedup=$dedup pruned=$pruned")

        val launch = s.packageManager.getLaunchIntentForPackage(WALLET)
        if (launch == null) {
            record(s, "global-first-launch-missing", "")
            finish(true)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { s.startActivity(launch) } catch (t: Throwable) { record(s, "global-first-launch-error", t.message.orEmpty()) }
        schedule(1200)
    }

    @JvmStatic
    fun stop(returnToApp: Boolean = true) = finish(returnToApp)

    @JvmStatic
    fun poke() { if (running) schedule(80) }

    private val runner = Runnable { scheduled = false; safeTick() }

    private fun schedule(delay: Long = 450) {
        if (!running || scheduled) return
        scheduled = true
        handler.postDelayed(runner, delay)
    }

    private fun safeTick() {
        val s = service ?: return
        try { tick(s) } catch (t: Throwable) {
            record(s, "global-first-error-state$state", "${t.javaClass.simpleName}:${t.message}")
            schedule(700)
        }
    }

    private fun tick(s: PayAccessibilityService) {
        if (!running) return
        if (System.currentTimeMillis() - startedAt > 360_000L) {
            record(s, "global-first-timeout", "page=$page total=${GoogleWalletTransactionStore.load(s).size}")
            finish(true)
            return
        }
        val root = try { s.rootInActiveWindow } catch (_: Throwable) { null }
        if (root == null) { schedule(); return }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == SELF) {
            record(s, "global-first-returned-to-app", "state=$state")
            finish(false)
            return
        }
        if (pkg != WALLET) { schedule(); return }
        when (state) {
            HOME -> onHome(s, root)
            OPEN_MORE -> onOpenMore(s, root)
            HISTORY -> onHistory(s, root)
        }
    }

    private fun onHome(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val vals = texts(root)
        record(s, "global-first-home", "hasMore=${findExact(root, "顯示更多") != null} hasViewMore=${findExact(root, "查看更多交易") != null} hasCards=${vals.any { it.startsWith("末位數號碼為") }}")
        val more = findExact(root, "顯示更多")
        if (more != null) {
            state = OPEN_MORE
            openTry = 0
            schedule(850)
            tapNode(s, more, "global-more", false)
            return
        }
        val transactions = findExact(root, "查看更多交易")
        if (transactions != null) {
            state = HISTORY
            resetHistory()
            schedule(850)
            tapNode(s, transactions, "global-more-transactions-direct", false)
            return
        }
        openTry++
        if (openTry <= 8) { schedule(600); return }
        record(s, "global-first-home-more-missing", "tries=$openTry")
        finish(true)
    }

    private fun onOpenMore(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val more = findExact(root, "查看更多交易")
        if (more != null) {
            mergeRecent(s, parseRows(root))
            state = HISTORY
            resetHistory()
            schedule(900)
            tapNode(s, more, "global-more-transactions", false)
            return
        }
        val vals = texts(root)
        if (vals.any { it == "付款卡" }) {
            openTry++
            if (openTry <= 8) {
                schedule(700)
                tapText(s, root, "顯示更多", "global-more-retry$openTry")
                return
            }
        }
        schedule(500)
    }

    private fun onHistory(s: PayAccessibilityService, root: AccessibilityNodeInfo) {
        val vals = texts(root)
        if (findExact(root, "查看更多交易") != null && vals.none { it == "交易記錄" }) {
            findExact(root, "查看更多交易")?.let {
                schedule(850)
                tapNode(s, it, "global-more-transactions-retry", false)
                return
            }
        }
        val rows = parseRows(root)
        if (rows.isEmpty()) {
            page++
            if (page >= MAX_PAGES) {
                record(s, "global-first-history-empty-stop", "page=$page")
                finish(true)
                return
            }
            schedule(800)
            swipeUp(s, "empty-page$page")
            return
        }
        mergeRecent(s, rows)
        val stored = GoogleWalletTransactionStore.load(s).associateBy { it.fallbackKey }
        val relevant = rows.filter { isRecent(it.date) }
        val allKnown = relevant.isNotEmpty() && relevant.all { stored.containsKey(it.fallbackKey) }
        val fp = rows.joinToString("|") { it.fallbackKey }
        knownPages = if (allKnown) knownPages + 1 else 0
        noMove = if (fp == lastFp) noMove + 1 else 0
        lastFp = fp
        record(s, "global-first-history-page$page", "rows=${rows.size} relevant=${relevant.size} total=${stored.size} knownPages=$knownPages noMove=$noMove")
        if (knownPages >= 2 || noMove >= 3 || page >= MAX_PAGES) {
            finish(true)
            return
        }
        page++
        schedule(900)
        swipeUp(s, "page$page")
    }

    private fun resetHistory() {
        page = 0
        lastFp = ""
        noMove = 0
        knownPages = 0
    }

    private fun mergeRecent(s: PayAccessibilityService, rows: List<GoogleWalletTransaction>) {
        val keep = rows.filter { isRecent(it.date) }
        if (keep.isNotEmpty()) GoogleWalletTransactionStore.merge(s, keep)
    }

    private fun parseRows(root: AccessibilityNodeInfo): List<GoogleWalletTransaction> {
        val out = LinkedHashMap<String, GoogleWalletTransaction>()
        val dr = Regex("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$")
        val ar = Regex("^\\$[0-9,]+(?:\\.[0-9]{2})?$")
        val controls = setOf("交易", "交易記錄", "查看更多交易", "全部", "Google Pay", "Google\u00a0Pay", "搜尋", "返回", "在錢包中搜尋", "憑證", "會員方案", "從主畫面移除")
        fun merchantCandidate(x: String): Boolean {
            val v = x.trim()
            return v.isNotBlank() && !dr.matches(v) && !ar.matches(v) && v !in controls && !v.contains("••") && !v.startsWith("末位數號碼為")
        }
        fun merchantScore(x: String): Int {
            val v = x.trim()
            var score = v.length.coerceAtMost(80)
            if (v.length <= 2) score -= 100
            if (v.any { it.isWhitespace() }) score += 8
            if (v.any { it.isLetter() }) score += 5
            return score
        }
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35) return
            try {
                if (n.isClickable) {
                    val v = texts(n)
                    val date = v.firstOrNull { dr.matches(it) }
                    val amount = v.firstOrNull { ar.matches(it) }
                    if (date != null && amount != null) {
                        val shop = v.filter(::merchantCandidate).distinct().maxByOrNull(::merchantScore).orEmpty()
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
        val explicitYear = m.groupValues[1].toIntOrNull()
        val month = m.groupValues[2].toIntOrNull() ?: return text
        val day = m.groupValues[3].toIntOrNull() ?: return text
        val now = Calendar.getInstance()
        var year = explicitYear ?: now.get(Calendar.YEAR)
        if (explicitYear == null && month > now.get(Calendar.MONTH) + 2) year--
        return String.format(Locale.US, "%04d/%02d/%02d 12:00:00", year, month, day)
    }

    private fun isRecent(date: String): Boolean {
        return try {
            val f = SimpleDateFormat("yyyy/MM/dd", Locale.US)
            f.isLenient = false
            val d = f.parse(date.substringBefore(' ')) ?: return false
            val age = System.currentTimeMillis() - d.time
            age >= -86_400_000L && age <= RECENT_DAYS.toLong() * 86_400_000L
        } catch (_: Throwable) { false }
    }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 28 || out.size > 320) return
            try {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "Image" }?.let(out::add)
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Throwable) { }
        }
        walk(root, 0)
        return out
    }

    private fun findExact(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        try {
            if (root.text?.toString()?.trim() == target || root.contentDescription?.toString()?.trim() == target) return root
            for (i in 0 until root.childCount) root.getChild(i)?.let { findExact(it, target) }?.let { return it }
        } catch (_: Throwable) { }
        return null
    }

    private fun tapText(s: PayAccessibilityService, root: AccessibilityNodeInfo, text: String, label: String) {
        findExact(root, text)?.let { tapNode(s, it, label, false) }
    }

    private fun tapNode(s: PayAccessibilityService, node: AccessibilityNodeInfo, label: String, preferAction: Boolean) {
        if (preferAction) {
            try {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                record(s, "tap-$label-action", "accepted=$ok")
                if (ok) { poke(); return }
            } catch (_: Throwable) { }
        }
        tapAt(s, node, .5f, label)
    }

    private fun tapAt(s: PayAccessibilityService, node: AccessibilityNodeInfo, xf: Float, label: String) {
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Throwable) { return }
        if (r.isEmpty) return
        tapPoint(s, r.left + r.width() * xf.coerceIn(.12f, .88f), r.exactCenterY(), label)
    }

    private fun tapPoint(s: PayAccessibilityService, x: Float, y: Float, label: String) {
        try {
            val p = Path()
            p.moveTo(x, y)
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 90)).build()
            val ok = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) { record(s, "tap-$label-completed", "x=${x.toInt()} y=${y.toInt()}"); poke() }
                override fun onCancelled(gd: GestureDescription?) { poke() }
            }, null)
            record(s, "tap-$label-gesture", "accepted=$ok x=${x.toInt()} y=${y.toInt()}")
        } catch (t: Throwable) {
            record(s, "tap-$label-error", t.message.orEmpty())
            poke()
        }
    }

    private fun swipeUp(s: PayAccessibilityService, label: String) {
        val dm = s.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * .78f
        val y2 = dm.heightPixels * .30f
        val p = Path()
        p.moveTo(x, y1)
        p.lineTo(x, y2)
        try {
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 420)).build()
            s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) { poke() }
                override fun onCancelled(gd: GestureDescription?) { poke() }
            }, null)
            record(s, "swipe-$label", "")
        } catch (_: Throwable) { }
    }

    private fun record(s: PayAccessibilityService, label: String, msg: String) {
        try {
            GoogleWalletDiagnosticStore.add(s, GoogleWalletDiagnosticCapture(System.currentTimeMillis(), WALLET, -781, "formal-sync-global-first/$label", label, msg, ""))
        } catch (_: Throwable) { }
    }

    private fun finish(returnToApp: Boolean) {
        val s = service
        running = false
        scheduled = false
        handler.removeCallbacks(runner)
        if (s != null) {
            record(s, "global-first-finish", "page=$page total=${GoogleWalletTransactionStore.load(s).size}")
            val p = s.getSharedPreferences("v241", 0)
            val tm = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val old = p.getString("log", "") ?: ""
            p.edit().putString("log", ("[$tm] Google Wallet global-first finish total=${GoogleWalletTransactionStore.load(s).size}\n" + old).take(40000)).apply()
            if (returnToApp) {
                try {
                    s.packageManager.getLaunchIntentForPackage(SELF)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        s.startActivity(it)
                    }
                } catch (_: Throwable) { }
            }
        }
        service = null
    }
}
