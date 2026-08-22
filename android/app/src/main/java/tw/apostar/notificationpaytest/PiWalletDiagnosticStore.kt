package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PiWalletDiagnosticCapture(
    val time: Long,
    val packageName: String,
    val eventType: Int,
    val eventClass: String,
    val eventText: String,
    val visibleText: String,
    val tree: String
)

object PiWalletDiagnosticStore {
    private const val PREF = "piwallet_diagnostic_captures"
    private const val KEY = "items"
    private const val MAX = 80

    fun add(c: Context, x: PiWalletDiagnosticCapture) {
        val list = load(c).toMutableList()
        val last = list.firstOrNull()
        if (last != null && last.packageName == x.packageName &&
            last.visibleText == x.visibleText && last.tree == x.tree && x.time-last.time < 1500) return
        list.add(0, x)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        val arr = JSONArray()
        list.forEach { y ->
            arr.put(JSONObject()
                .put("time", y.time).put("packageName", y.packageName)
                .put("eventType", y.eventType).put("eventClass", y.eventClass)
                .put("eventText", y.eventText).put("visibleText", y.visibleText)
                .put("tree", y.tree))
        }
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, arr.toString()).apply()
    }

    fun load(c: Context): List<PiWalletDiagnosticCapture> = try {
        val a = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o=a.getJSONObject(i)
            PiWalletDiagnosticCapture(
                o.optLong("time"), o.optString("packageName"), o.optInt("eventType"),
                o.optString("eventClass"), o.optString("eventText"),
                o.optString("visibleText"), o.optString("tree")
            )
        }
    } catch (_: Exception) { emptyList() }

    fun clear(c: Context) = c.getSharedPreferences(PREF, 0).edit().clear().apply()
}
