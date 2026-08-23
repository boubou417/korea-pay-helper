package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GoogleWalletTransaction(
    val shop: String,
    val amount: String,
    val date: String,
    val capturedAt: Long,
    val cardName: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val cardType: String = "",
    val detailChecked: Boolean = false
) {
    val key: String get() = "$date|$shop|$amount|$cardLast4"
}

object GoogleWalletTransactionStore {
    private const val PREF = "google_wallet_transactions"
    private const val KEY = "items"
    private const val MAX = 500

    fun load(c: Context): List<GoogleWalletTransaction> = try {
        val raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GoogleWalletTransaction(
                shop = o.optString("shop"),
                amount = o.optString("amount"),
                date = o.optString("date"),
                capturedAt = o.optLong("capturedAt"),
                cardName = o.optString("cardName"),
                bank = o.optString("bank"),
                cardLast4 = o.optString("cardLast4"),
                cardType = o.optString("cardType"),
                detailChecked = o.optBoolean("detailChecked", false)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun merge(c: Context, incoming: List<GoogleWalletTransaction>): Int {
        if (incoming.isEmpty()) return 0
        val current = load(c).toMutableList()
        val index = current.mapIndexed { i, x -> x.key to i }.toMap().toMutableMap()
        var added = 0

        incoming.forEach { x ->
            val oldIndex = index[x.key]
            if (oldIndex == null) {
                current.add(0, x)
                index[x.key] = 0
                added += 1
            } else {
                val old = current[oldIndex]
                current[oldIndex] = old.copy(
                    cardName = x.cardName.ifBlank { old.cardName },
                    bank = x.bank.ifBlank { old.bank },
                    cardLast4 = x.cardLast4.ifBlank { old.cardLast4 },
                    cardType = x.cardType.ifBlank { old.cardType },
                    detailChecked = old.detailChecked || x.detailChecked,
                    capturedAt = maxOf(old.capturedAt, x.capturedAt)
                )
            }
        }

        current.sortByDescending { it.date }
        while (current.size > MAX) current.removeAt(current.lastIndex)
        save(c, current)
        return added
    }

    private fun save(c: Context, list: List<GoogleWalletTransaction>) {
        val arr = JSONArray()
        list.forEach { x ->
            arr.put(JSONObject()
                .put("shop", x.shop)
                .put("amount", x.amount)
                .put("date", x.date)
                .put("capturedAt", x.capturedAt)
                .put("cardName", x.cardName)
                .put("bank", x.bank)
                .put("cardLast4", x.cardLast4)
                .put("cardType", x.cardType)
                .put("detailChecked", x.detailChecked))
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
