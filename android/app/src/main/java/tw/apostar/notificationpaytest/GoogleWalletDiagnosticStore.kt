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
    private const val MAX = 180

    @Synchronized
    fun add(c: Context, x: GoogleWalletDiagnosticCapture) {
        val list = load(c).toMutableList()
        val last = list.firstOrNull()
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
        saveSync(c, list)
    }

    @Synchronized
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

    @Synchronized
    fun clear(c: Context) {
        // Formal V4/V5 runs start with a completely clean diagnostic buffer.
        if (GoogleWalletSyncControllerV5.isRunning() || GoogleWalletSyncControllerV4.isRunning()) {
            c.getSharedPreferences(PREF, 0).edit().clear().commit()
            return
        }

        // Manual diagnostic started after formal sync keeps the formal evidence.
        val formal = load(c).filter {
            it.eventClass.startsWith("formal-sync-v5/") ||
                it.eventClass.startsWith("formal-sync-v4/") ||
                it.eventClass.startsWith("formal-sync-v3/") ||
                it.eventClass.startsWith("formal-sync/") ||
                it.eventType < 0
        }
        if (formal.isEmpty()) {
            c.getSharedPreferences(PREF, 0).edit().clear().commit()
        } else {
            saveSync(c, formal)
        }
    }

    private fun saveSync(c: Context, list: List<GoogleWalletDiagnosticCapture>) {
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
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, arr.toString()).commit()
    }
}
