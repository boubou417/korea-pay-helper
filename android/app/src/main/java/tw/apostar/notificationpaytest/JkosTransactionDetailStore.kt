package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class JkosTransactionDetailCapture(
    val time: Long,
    val transactionKey: String,
    val text: String,
    val tree: String
)

object JkosTransactionDetailStore {
    private const val PREF = "jkos_transaction_detail_captures"
    private const val KEY = "items"
    private const val MAX = 30

    fun add(c: Context, transactionKey: String, text: String, tree: String) {
        val old = load(c).toMutableList()
        old.removeAll { it.transactionKey == transactionKey }
        old.add(0, JkosTransactionDetailCapture(System.currentTimeMillis(), transactionKey, text, tree))
        while (old.size > MAX) old.removeAt(old.lastIndex)
        val arr = JSONArray()
        old.forEach { x ->
            arr.put(JSONObject().put("time", x.time).put("transactionKey", x.transactionKey).put("text", x.text).put("tree", x.tree))
        }
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, arr.toString()).apply()
    }

    fun load(c: Context): List<JkosTransactionDetailCapture> = try {
        val arr = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            JkosTransactionDetailCapture(o.optLong("time"), o.optString("transactionKey"), o.optString("text"), o.optString("tree"))
        }
    } catch (_: Exception) { emptyList() }

    fun clear(c: Context) = c.getSharedPreferences(PREF, 0).edit().clear().apply()
}
