package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GoogleWalletDiagnosticCapture(
    val time: Long,
    val packageName: String,
    val eventType: Int,
    val eventClass: String,
    val eventText: String,
    val visibleText: String,
    val tree: String
)

object GoogleWalletDiagnosticStore {
    private const val PREF = "google_wallet_diagnostic_captures"
    private const val KEY = "items"
    private const val MAX = 100

    fun add(c: Context, x: GoogleWalletDiagnosticCapture) {
        val list = load(c).toMutableList()
        val last = list.firstOrNull()
        // Do not let a normal Accessibility event swallow a formal-sync marker just
        // because both snapshots came from the same screen within 1.5 seconds.
        if (last != null &&
            last.packageName == x.packageName &&
            last.eventType == x.eventType &&
            last.eventClass == x.eventClass &&
            last.eventText == x.eventText &&
            last.visibleText == x.visibleText &&
            last.tree == x.tree &&
            x.time - last.time < 1500
        ) return

        list.add(0, x)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        save(c, list)
    }

    fun load(c: Context): List<GoogleWalletDiagnosticCapture> = try {
        val arr = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GoogleWalletDiagnosticCapture(
                time = o.optLong("time"),
                packageName = o.optString("packageName"),
                eventType = o.optInt("eventType"),
                eventClass = o.optString("eventClass"),
                eventText = o.optString("eventText"),
                visibleText = o.optString("visibleText"),
                tree = o.optString("tree")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Formal Google Wallet sync calls clear() after its controller has entered the
     * running state, so that starts a genuinely clean diagnostic session.
     *
     * Manual diagnostic also calls the same legacy clear() API. If it is started
     * after a formal sync, keep the formal-sync captures instead of wiping the
     * evidence we need for debugging the automatic flow.
     */
    fun clear(c: Context) {
        if (GoogleWalletSyncController.isRunning()) {
            c.getSharedPreferences(PREF, 0).edit().clear().apply()
            return
        }
        val formal = load(c).filter { it.eventClass.startsWith("formal-sync/") || it.eventType == -100 }
        if (formal.isEmpty()) {
            c.getSharedPreferences(PREF, 0).edit().clear().apply()
        } else {
            save(c, formal)
        }
    }

    private fun save(c: Context, list: List<GoogleWalletDiagnosticCapture>) {
        val arr = JSONArray()
        list.take(MAX).forEach { y ->
            arr.put(JSONObject()
                .put("time", y.time)
                .put("packageName", y.packageName)
                .put("eventType", y.eventType)
                .put("eventClass", y.eventClass)
                .put("eventText", y.eventText)
                .put("visibleText", y.visibleText)
                .put("tree", y.tree))
        }
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, arr.toString()).apply()
    }
}
