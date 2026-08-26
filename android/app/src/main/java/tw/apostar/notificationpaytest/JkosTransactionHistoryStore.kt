package tw.apostar.notificationpaytest
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
data class JkosHistoryCapture(val time:Long,val text:String)
object JkosTransactionHistoryStore{
 private const val P="jkos_transaction_history"; private const val K="items"; private const val MAX=50
 private val hints=listOf("交易紀錄","交易记录","消費紀錄","付款紀錄","付款记录","交易明細","交易详情","交易詳情")
 fun looksLikeHistory(t:String)=hints.any{t.contains(it)}
 fun add(c:Context,t:String,time:Long){if(t.isBlank()||!looksLikeHistory(t))return;val l=load(c).toMutableList();if(l.firstOrNull()?.text==t)return;l.add(0,JkosHistoryCapture(time,t));while(l.size>MAX)l.removeAt(l.lastIndex);val a=JSONArray();l.forEach{a.put(JSONObject().put("time",it.time).put("text",it.text))};c.getSharedPreferences(P,0).edit().putString(K,a.toString()).apply()}
 fun load(c:Context):List<JkosHistoryCapture>{return try{val a=JSONArray(c.getSharedPreferences(P,0).getString(K,"[]")?:"[]");(0 until a.length()).map{i->val o=a.getJSONObject(i);JkosHistoryCapture(o.optLong("time"),o.optString("text"))}}catch(_:Exception){emptyList()}}
 fun clear(c:Context)=c.getSharedPreferences(P,0).edit().clear().apply()
}
