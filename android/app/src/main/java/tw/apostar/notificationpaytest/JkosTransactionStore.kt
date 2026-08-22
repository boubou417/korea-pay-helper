package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class JkosTransaction(
    val shop: String,
    val price: String,
    val type: String,
    val date: String,
    val capturedAt: Long,
    val paymentMethod: String = "",
    val paymentAccount: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val status: String = "",
    val orderAmount: String = "",
    val actualPaid: String = "",
    val detailChecked: Boolean = false,
    val detailCapturedAt: Long = 0L
) {
    val key: String get() = "$date|$shop|$price|$type"
}

data class JkosTransactionDetail(
    val paymentMethod: String = "",
    val paymentAccount: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val status: String = "",
    val orderAmount: String = "",
    val actualPaid: String = ""
)

object JkosTransactionStore {
    private const val PREF = "jkos_structured_transactions"
    private const val KEY = "items"
    private const val MAX = 500

    fun addAll(c: Context, items: List<JkosTransaction>): Int {
        if (items.isEmpty()) return 0
        val old = load(c).toMutableList()
        val index = old.mapIndexed { i, x -> x.key to i }.toMap().toMutableMap()
        var added = 0
        items.forEach { item ->
            if (item.shop.isBlank() || item.date.isBlank() || item.price.isBlank()) return@forEach
            val at = index[item.key]
            if (at == null) {
                old.add(0, item)
                added++
                index.clear(); old.forEachIndexed { i, x -> index[x.key] = i }
            } else {
                val prev = old[at]
                old[at] = prev.copy(
                    shop = item.shop, price = item.price, type = item.type, date = item.date,
                    capturedAt = maxOf(prev.capturedAt, item.capturedAt)
                )
            }
        }
        while (old.size > MAX) old.removeAt(old.lastIndex)
        save(c, old)
        return added
    }

    fun isDetailComplete(d: JkosTransactionDetail): Boolean = d.paymentMethod.isNotBlank()

    fun enrich(c: Context, key: String, d: JkosTransactionDetail): Boolean {
        if (!isDetailComplete(d)) return false
        val items = load(c).toMutableList()
        val i = items.indexOfFirst { it.key == key }
        if (i < 0) return false
        val old = items[i]
        items[i] = old.copy(
            paymentMethod = d.paymentMethod,
            paymentAccount = d.paymentAccount,
            bank = d.bank,
            cardLast4 = d.cardLast4,
            status = d.status,
            orderAmount = d.orderAmount,
            actualPaid = d.actualPaid,
            detailChecked = true,
            detailCapturedAt = System.currentTimeMillis()
        )
        save(c, items)
        return true
    }

    fun load(c: Context): List<JkosTransaction> = try {
        val arr = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            JkosTransaction(
                shop = o.optString("shop"),
                price = o.optString("price"),
                type = o.optString("type"),
                date = o.optString("date"),
                capturedAt = o.optLong("capturedAt"),
                paymentMethod = o.optString("paymentMethod"),
                paymentAccount = o.optString("paymentAccount"),
                bank = o.optString("bank"),
                cardLast4 = o.optString("cardLast4"),
                status = o.optString("status"),
                orderAmount = o.optString("orderAmount"),
                actualPaid = o.optString("actualPaid"),
                detailChecked = o.optBoolean("detailChecked", false) && o.optString("paymentMethod").isNotBlank(),
                detailCapturedAt = o.optLong("detailCapturedAt", 0L)
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun save(c: Context, items: List<JkosTransaction>) {
        val arr = JSONArray()
        items.forEach { x ->
            arr.put(JSONObject()
                .put("shop", x.shop).put("price", x.price).put("type", x.type)
                .put("date", x.date).put("capturedAt", x.capturedAt)
                .put("paymentMethod", x.paymentMethod).put("paymentAccount", x.paymentAccount)
                .put("bank", x.bank).put("cardLast4", x.cardLast4)
                .put("status", x.status).put("orderAmount", x.orderAmount).put("actualPaid", x.actualPaid)
                .put("detailChecked", x.detailChecked).put("detailCapturedAt", x.detailCapturedAt))
        }
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(c: Context) = c.getSharedPreferences(PREF, 0).edit().clear().apply()
}
