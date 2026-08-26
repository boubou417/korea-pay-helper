package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LinePayTransaction(
    val shop: String,
    val amount: String,
    val date: String,
    val capturedAt: Long,
    val paymentMethod: String = "",
    val paymentAccount: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val cardType: String = "",
    val cardName: String = "",
    val transactionId: String = "",
    val productName: String = "",
    val orderAmount: String = "",
    val actualPaid: String = "",
    val detailChecked: Boolean = false,
    val detailCapturedAt: Long = 0L
) {
    val key: String get() = "$date|$shop|$amount"
}

data class LinePayDetail(
    val paymentMethod: String = "",
    val paymentAccount: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val cardType: String = "",
    val cardName: String = "",
    val transactionId: String = "",
    val productName: String = "",
    val orderAmount: String = "",
    val actualPaid: String = ""
)

object LinePayTransactionStore {
    private const val PREF = "linepay_structured_transactions"
    private const val KEY = "items"

    fun load(c: Context): List<LinePayTransaction> = try {
        val a = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val method=o.optString("paymentMethod")
            val checked=o.optBoolean("detailChecked", false) && method.isNotBlank()
            LinePayTransaction(
                shop = o.optString("shop"), amount = o.optString("amount"),
                date = o.optString("date"), capturedAt = o.optLong("capturedAt"),
                paymentMethod = method,
                paymentAccount = o.optString("paymentAccount"),
                bank = o.optString("bank"), cardLast4 = o.optString("cardLast4"),
                cardType = o.optString("cardType"), cardName = o.optString("cardName"),
                transactionId = o.optString("transactionId"), productName = o.optString("productName"),
                orderAmount = o.optString("orderAmount"), actualPaid = o.optString("actualPaid"),
                detailChecked = checked,
                detailCapturedAt = if(checked) o.optLong("detailCapturedAt", 0L) else 0L
            )
        }
    } catch (_: Exception) { emptyList() }

    fun sanitize(c: Context): Int {
        val src = load(c)
        val rangeRe = Regex("^\\d{4}[./-]\\d{2}[./-]\\d{2}\\s*[~～-]\\s*\\d{4}[./-]\\d{2}[./-]\\d{2}$")
        fun moneyValue(v:String):String = v.replace(Regex("[^0-9]"), "").trimStart('0').ifBlank { "0" }
        val filtered = src.filterNot { rangeRe.matches(it.shop.trim()) }.toMutableList()
        val byTxn = filtered.filter { it.transactionId.isNotBlank() }.groupBy { it.transactionId }
        val removeKeys = HashSet<String>()
        byTxn.values.filter { it.size > 1 }.forEach { group ->
            val best = group.maxByOrNull { x ->
                var score = 0
                if (moneyValue(x.actualPaid) == moneyValue(x.amount)) score += 3
                if (moneyValue(x.orderAmount) == moneyValue(x.amount)) score += 2
                if (x.detailChecked) score += 1
                score
            }
            group.filter { it.key != best?.key }.forEach { removeKeys.add(it.key) }
        }
        val clean = filtered.filterNot { it.key in removeKeys }
        val removed = src.size - clean.size
        if (removed > 0) save(c, clean)
        return removed
    }

    fun merge(c: Context, incoming: List<LinePayTransaction>): Int {
        val map = LinkedHashMap<String, LinePayTransaction>()
        load(c).forEach { map[it.key] = it }
        var added = 0
        incoming.forEach { x ->
            val old=map[x.key]
            if (old==null) { map[x.key] = x; added++ }
            else if(x.capturedAt>old.capturedAt) map[x.key]=old.copy(capturedAt=x.capturedAt)
        }
        save(c, map.values.toList())
        return added
    }

    fun updateDetail(c:Context,key:String,d:LinePayDetail):Boolean{
        if(d.paymentMethod.isBlank()) return false
        var changed=false
        val now=System.currentTimeMillis()
        val list=load(c).map{x->
            if(x.key!=key) x else {
                changed=true
                x.copy(
                    paymentMethod=d.paymentMethod, paymentAccount=d.paymentAccount,
                    bank=d.bank, cardLast4=d.cardLast4, cardType=d.cardType, cardName=d.cardName,
                    transactionId=d.transactionId, productName=d.productName,
                    orderAmount=d.orderAmount, actualPaid=d.actualPaid,
                    detailChecked=true, detailCapturedAt=now
                )
            }
        }
        if(changed) save(c,list)
        return changed
    }

    private fun save(c: Context, list: List<LinePayTransaction>) {
        val a = JSONArray()
        list.sortedBy { it.date }.forEach { x ->
            a.put(JSONObject().put("shop", x.shop).put("amount", x.amount).put("date", x.date)
                .put("capturedAt", x.capturedAt)
                .put("paymentMethod",x.paymentMethod).put("paymentAccount",x.paymentAccount)
                .put("bank",x.bank).put("cardLast4",x.cardLast4)
                .put("cardType",x.cardType).put("cardName",x.cardName)
                .put("transactionId",x.transactionId).put("productName",x.productName)
                .put("orderAmount",x.orderAmount).put("actualPaid",x.actualPaid)
                .put("detailChecked", x.detailChecked).put("detailCapturedAt", x.detailCapturedAt))
        }
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, a.toString()).apply()
    }
}
