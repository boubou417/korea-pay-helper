package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PiWalletTransaction(
    val shop: String,
    val amount: String,
    val date: String,
    val capturedAt: Long,
    val transactionId: String = "",
    val paymentTime: String = "",
    val completedTime: String = "",
    val status: String = "",
    val paymentMethod: String = "",
    val paymentAccount: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val transactionType: String = "",
    val note: String = "",
    val detailChecked: Boolean = false,
    val detailCapturedAt: Long = 0L
) {
    val key: String get() = "$date|$shop|$amount"
}

data class PiWalletDetail(
    val transactionId: String = "",
    val shop: String = "",
    val amount: String = "",
    val paymentTime: String = "",
    val completedTime: String = "",
    val status: String = "",
    val paymentMethod: String = "",
    val paymentAccount: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val transactionType: String = "",
    val note: String = ""
)

object PiWalletTransactionStore {
    private const val PREF = "piwallet_structured_transactions"
    private const val KEY = "items"
    private const val LEGACY_DETAIL_DAYS = 30L

    fun load(c: Context): List<PiWalletTransaction> = try {
        val a = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PiWalletTransaction(
                shop=o.optString("shop"), amount=o.optString("amount"), date=o.optString("date"),
                capturedAt=o.optLong("capturedAt"), transactionId=o.optString("transactionId"),
                paymentTime=o.optString("paymentTime"), completedTime=o.optString("completedTime"),
                status=o.optString("status"), paymentMethod=o.optString("paymentMethod"),
                paymentAccount=o.optString("paymentAccount"), bank=o.optString("bank"),
                cardLast4=o.optString("cardLast4"), transactionType=o.optString("transactionType"),
                note=o.optString("note"), detailChecked=o.optBoolean("detailChecked", false),
                detailCapturedAt=o.optLong("detailCapturedAt",0L)
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun isHistorical(dateText:String):Boolean = try {
        val f=java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.TAIWAN)
        f.isLenient=false
        val tx=f.parse(dateText)?.time ?: 0L
        tx>0L && System.currentTimeMillis()-tx > LEGACY_DETAIL_DAYS*24L*60L*60L*1000L
    } catch (_:Exception) { false }

    fun normalizeInvalidCardDetails(c: Context): Int {
        var changed=0
        val genericBanks=setOf("信用卡付款","信用卡","付款","付款方式","刷卡","信用卡支付")
        val list=load(c).map{x->
            val cardLike=x.paymentMethod.contains("信用卡") || x.bank.contains("信用卡") || x.cardLast4.isNotBlank()
            val bankOk=x.bank.isNotBlank() && x.bank !in genericBanks
            val cardOk=Regex("^\\d{4}$").matches(x.cardLast4)
            // Older Pi detail layouts do not expose the bank/card view IDs. Once we
            // have a real transactionId for a >30-day record, treat that record as
            // settled instead of resetting detailChecked on every daily sync.
            val acceptedLegacy=x.transactionId.isNotBlank() && isHistorical(x.date)
            if(x.detailChecked && cardLike && (!bankOk || !cardOk) && !acceptedLegacy){
                changed++
                x.copy(
                    paymentMethod=if(x.paymentMethod.isNotBlank()) x.paymentMethod else "信用卡付款",
                    paymentAccount="", bank=if(bankOk) x.bank else "", cardLast4=if(cardOk) x.cardLast4 else "",
                    detailChecked=false, detailCapturedAt=0L
                )
            } else x
        }
        if(changed>0) save(c,list)
        return changed
    }

    fun merge(c: Context, incoming: List<PiWalletTransaction>): Int {
        val map=LinkedHashMap<String,PiWalletTransaction>()
        load(c).forEach{ map[it.key]=it }
        var added=0
        incoming.forEach{x->
            val old=map[x.key]
            if(old==null){ map[x.key]=x; added++ }
            else if(x.capturedAt>old.capturedAt) map[x.key]=old.copy(capturedAt=x.capturedAt)
        }
        save(c,map.values.toList())
        return added
    }

    fun updateDetail(c:Context,key:String,d:PiWalletDetail):Boolean{
        if(d.transactionId.isBlank() && d.paymentMethod.isBlank() && d.cardLast4.isBlank()) return false
        val genericBanks=setOf("信用卡付款","信用卡","付款","付款方式","刷卡","信用卡支付")
        val normalizedBank=d.bank.trim().takeUnless{it in genericBanks}.orEmpty()
        val normalizedLast4=d.cardLast4.trim().takeIf{Regex("^\\d{4}$").matches(it)}.orEmpty()
        val cardLike=d.paymentMethod.contains("信用卡") || d.bank.contains("信用卡") || normalizedLast4.isNotBlank()
        val dateText=key.substringBefore('|')
        val oldHistorical=isHistorical(dateText)
        val complete=d.transactionId.isNotBlank() && if(cardLike) {
            (normalizedBank.isNotBlank() && normalizedLast4.isNotBlank()) || oldHistorical
        } else d.paymentMethod.isNotBlank()
        val method=if(cardLike && normalizedBank.isNotBlank() && normalizedLast4.isNotBlank()) "信用卡" else d.paymentMethod
        val account=if(normalizedBank.isNotBlank() && normalizedLast4.isNotBlank()) "$normalizedBank [*$normalizedLast4]" else d.paymentAccount.takeIf{!cardLike}.orEmpty()
        var changed=false
        val now=System.currentTimeMillis()
        val list=load(c).map{x->
            if(x.key!=key) x else {
                changed=true
                x.copy(
                    transactionId=d.transactionId.ifBlank{x.transactionId},
                    paymentTime=d.paymentTime.ifBlank{x.paymentTime}, completedTime=d.completedTime.ifBlank{x.completedTime},
                    status=d.status.ifBlank{x.status}, paymentMethod=method.ifBlank{x.paymentMethod}, paymentAccount=account,
                    bank=normalizedBank, cardLast4=normalizedLast4,
                    transactionType=d.transactionType.ifBlank{x.transactionType}, note=d.note.ifBlank{x.note},
                    detailChecked=complete, detailCapturedAt=now
                )
            }
        }
        if(changed) save(c,list)
        return changed && complete
    }

    private fun save(c:Context,list:List<PiWalletTransaction>){
        val a=JSONArray()
        list.sortedBy{it.date}.forEach{x->
            a.put(JSONObject()
                .put("shop",x.shop).put("amount",x.amount).put("date",x.date).put("capturedAt",x.capturedAt)
                .put("transactionId",x.transactionId).put("paymentTime",x.paymentTime).put("completedTime",x.completedTime)
                .put("status",x.status).put("paymentMethod",x.paymentMethod).put("paymentAccount",x.paymentAccount)
                .put("bank",x.bank).put("cardLast4",x.cardLast4).put("transactionType",x.transactionType)
                .put("note",x.note).put("detailChecked",x.detailChecked).put("detailCapturedAt",x.detailCapturedAt))
        }
        c.getSharedPreferences(PREF,0).edit().putString(KEY,a.toString()).apply()
    }
}
