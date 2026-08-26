package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class JkosTreeCapture(val time:Long,val page:Int,val sample:Int,val text:String)

object JkosTransactionTreeStore {
    private const val P="jkos_transaction_tree_v221"
    private const val K="items"
    private const val MAX=40

    fun add(c:Context,page:Int,sample:Int,text:String,time:Long=System.currentTimeMillis()){
        if(text.isBlank()) return
        val l=load(c).toMutableList()
        l.add(0,JkosTreeCapture(time,page,sample,text.take(30000)))
        while(l.size>MAX) l.removeAt(l.lastIndex)
        val a=JSONArray()
        l.forEach { a.put(JSONObject().put("time",it.time).put("page",it.page).put("sample",it.sample).put("text",it.text)) }
        c.getSharedPreferences(P,0).edit().putString(K,a.toString()).apply()
    }

    fun load(c:Context):List<JkosTreeCapture> = try {
        val a=JSONArray(c.getSharedPreferences(P,0).getString(K,"[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o=a.getJSONObject(i)
            JkosTreeCapture(o.optLong("time"),o.optInt("page"),o.optInt("sample"),o.optString("text"))
        }
    } catch(_:Exception){ emptyList() }

    fun clear(c:Context)=c.getSharedPreferences(P,0).edit().clear().apply()
}
