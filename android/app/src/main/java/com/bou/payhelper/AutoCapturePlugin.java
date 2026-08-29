package com.bou.payhelper;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.List;

import tw.apostar.notificationpaytest.GoogleWalletDiagnosticCapture;
import tw.apostar.notificationpaytest.GoogleWalletDiagnosticStore;
import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.GoogleWalletTransaction;
import tw.apostar.notificationpaytest.GoogleWalletTransactionStore;
import tw.apostar.notificationpaytest.JkosTransaction;
import tw.apostar.notificationpaytest.JkosTransactionStore;
import tw.apostar.notificationpaytest.LinePayTransaction;
import tw.apostar.notificationpaytest.LinePayTransactionStore;
import tw.apostar.notificationpaytest.PayAccessibilityService;
import tw.apostar.notificationpaytest.PiWalletTransaction;
import tw.apostar.notificationpaytest.PiWalletTransactionStore;

@CapacitorPlugin(name = "AutoCapture")
public class AutoCapturePlugin extends Plugin {
    private PayAccessibilityService service(){return PayAccessibilityService.Companion.getInstance();}
    private boolean accessibilityEnabled(){String enabled=Settings.Secure.getString(getContext().getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);if(enabled==null)return false;ComponentName cn=new ComponentName(getContext(),PayAccessibilityService.class);return enabled.contains(cn.flattenToString())||enabled.contains(cn.flattenToShortString())||enabled.contains(PayAccessibilityService.class.getName());}
    private JSObject result(String prefix,boolean scheduledDefault){SharedPreferences p=SyncRunLogStore.read(getContext());JSObject o=new JSObject();String q=prefix.isEmpty()?"":prefix+".";o.put("scheduled",prefix.isEmpty()?p.getBoolean("scheduled",scheduledDefault):true);o.put("startedAt",p.getLong(q+"startedAt",0));o.put("finishedAt",p.getLong(q+"finishedAt",0));o.put("status",p.getString(q+"status","NONE"));o.put("summary",p.getString(q+"summary","尚無同步記錄"));o.put("jko",p.getString(q+"jko","NONE"));o.put("line",p.getString(q+"line","NONE"));o.put("pi",p.getString(q+"pi","NONE"));o.put("google",p.getString(q+"google","NONE"));o.put("added",p.getInt(q+"added",0));o.put("matched",p.getInt(q+"matched",0));o.put("unmatched",p.getInt(q+"unmatched",0));return o;}
    private JSObject lastSyncResult(){return result("",false);} private JSObject lastNightlyResult(){return result("night",true);}
    @PluginMethod public void getStatus(PluginCall call){JSObject out=new JSObject();out.put("accessibilityEnabled",accessibilityEnabled());out.put("serviceConnected",service()!=null);out.put("jkoCount",JkosTransactionStore.INSTANCE.load(getContext()).size());out.put("linePayCount",LinePayTransactionStore.INSTANCE.load(getContext()).size());out.put("piWalletCount",PiWalletTransactionStore.INSTANCE.load(getContext()).size());out.put("googleWalletCount",GoogleWalletTransactionStore.INSTANCE.load(getContext()).size());out.put("googleWalletRunning",GoogleWalletSyncControllerV3.isRunning());out.put("googleDiagnosticCount",GoogleWalletDiagnosticStore.INSTANCE.load(getContext()).size());out.put("unifiedSyncRunning",UnifiedSyncController.isRunning());out.put("unifiedSyncStage",UnifiedSyncController.getStage());out.put("unifiedSyncDiagnostic",UnifiedSyncController.getDiagnostic());out.put("unifiedSyncScheduledRun",UnifiedSyncController.isScheduledRun());out.put("lastSyncResult",lastSyncResult());out.put("lastNightlyResult",lastNightlyResult());out.put("nightlySyncEnabled",true);out.put("nightlySyncHour",2);NightlySyncReceiver.scheduleNext(getContext());call.resolve(out);}
    @PluginMethod public void openAccessibilitySettings(PluginCall call){Intent i=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(i);call.resolve();}
    private boolean requireService(PluginCall call){if(!accessibilityEnabled()){call.reject("ACCESSIBILITY_NOT_ENABLED");return false;}if(service()==null){call.reject("ACCESSIBILITY_SERVICE_NOT_CONNECTED");return false;}return true;}
    @PluginMethod public void syncAll(PluginCall call){if(!requireService(call))return;boolean ok=UnifiedSyncController.start(getContext(),service(),false);if(!ok){call.reject("SYNC_ALREADY_RUNNING");return;}JSObject o=new JSObject();o.put("started",true);o.put("order","JKOPAY,LINE_PAY,PI_WALLET,GOOGLE_WALLET");call.resolve(o);}
    @PluginMethod public void stopSyncAll(PluginCall call){UnifiedSyncController.stop(getContext(),true);call.resolve();}
    @PluginMethod public void scheduleNightlySync(PluginCall call){NightlySyncReceiver.scheduleNext(getContext());JSObject o=new JSObject();o.put("enabled",true);o.put("hour",2);call.resolve(o);}
    @PluginMethod public void acknowledgeImportedRun(PluginCall call){UnifiedSyncController.acknowledgeImportedRun();call.resolve();}
    @PluginMethod public void recordImportResult(PluginCall call){int added=call.getInt("added",0);int matched=call.getInt("matched",0);int unmatched=call.getInt("unmatched",0);SyncRunLogStore.imported(getContext(),added,matched,unmatched);call.resolve();}
    @PluginMethod public void getLastSyncResult(PluginCall call){call.resolve(lastSyncResult());}
    @PluginMethod public void getLastNightlyResult(PluginCall call){call.resolve(lastNightlyResult());}
    @PluginMethod public void goDeviceHome(PluginCall call){PayAccessibilityService s=service();boolean requested=false;if(s!=null){try{requested=s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);}catch(Throwable ignored){}}JSObject o=new JSObject();o.put("homeRequested",requested);call.resolve(o);}
    @PluginMethod public void syncJko(PluginCall call){if(!requireService(call))return;getActivity().runOnUiThread(()->service().startJkoSync());call.resolve();}
    @PluginMethod public void syncLinePay(PluginCall call){if(!requireService(call))return;getActivity().runOnUiThread(()->service().startLinePaySync());call.resolve();}
    @PluginMethod public void syncPiWallet(PluginCall call){if(!requireService(call))return;getActivity().runOnUiThread(()->service().startPiSync());call.resolve();}
    @PluginMethod public void syncGoogleWallet(PluginCall call){if(!requireService(call))return;getActivity().runOnUiThread(()->GoogleWalletSyncControllerV3.start(service()));call.resolve();}
    @PluginMethod public void stopGoogleWalletSync(PluginCall call){GoogleWalletSyncControllerV3.stop(true);call.resolve();}
    @PluginMethod public void diagnoseGoogleWallet(PluginCall call){if(!requireService(call))return;getActivity().runOnUiThread(()->service().startGoogleWalletDiagnostic());call.resolve();}
    @PluginMethod public void stopGoogleWalletDiagnostic(PluginCall call){if(service()!=null)getActivity().runOnUiThread(()->service().stopGoogleWalletDiagnostic(true));call.resolve();}
    @PluginMethod public void getTransactions(PluginCall call){JSArray items=new JSArray();for(JkosTransaction x:JkosTransactionStore.INSTANCE.load(getContext())){JSObject o=base("JKOPAY","街口",x.getKey(),x.getShop(),cleanAmount(x.getPrice()),x.getDate());o.put("paymentMethod",x.getPaymentMethod());o.put("paymentAccount",x.getPaymentAccount());o.put("bank",x.getBank());o.put("cardLast4",x.getCardLast4());o.put("status",x.getStatus());o.put("detailChecked",x.getDetailChecked());items.put(o);}for(LinePayTransaction x:LinePayTransactionStore.INSTANCE.load(getContext())){JSObject o=base("LINE_PAY","LINE Pay",x.getKey(),x.getShop(),cleanAmount(x.getAmount()),x.getDate());o.put("paymentMethod",x.getPaymentMethod());o.put("paymentAccount",x.getPaymentAccount());o.put("bank",x.getBank());o.put("cardLast4",x.getCardLast4());o.put("cardType",x.getCardType());o.put("cardName",x.getCardName());o.put("transactionId",x.getTransactionId());o.put("detailChecked",x.getDetailChecked());items.put(o);}for(PiWalletTransaction x:PiWalletTransactionStore.INSTANCE.load(getContext())){String t=x.getPaymentTime().isEmpty()?x.getDate():x.getPaymentTime();JSObject o=base("PI_WALLET","Pi 拍錢包",x.getKey(),x.getShop(),cleanAmount(x.getAmount()),t);o.put("paymentMethod",x.getPaymentMethod());o.put("paymentAccount",x.getPaymentAccount());o.put("bank",x.getBank());o.put("cardLast4",x.getCardLast4());o.put("status",x.getStatus());o.put("transactionId",x.getTransactionId());o.put("transactionType",x.getTransactionType());o.put("detailChecked",x.getDetailChecked());items.put(o);}for(GoogleWalletTransaction x:GoogleWalletTransactionStore.INSTANCE.load(getContext())){JSObject o=base("GOOGLE_WALLET","Google Pay",x.getKey(),x.getShop(),cleanAmount(x.getAmount()),x.getDate());o.put("paymentMethod","Google Pay");o.put("paymentAccount",x.getCardName().isEmpty()?"":x.getCardName()+(x.getCardLast4().isEmpty()?"":" *"+x.getCardLast4()));o.put("bank",x.getBank());o.put("cardLast4",x.getCardLast4());o.put("cardType",x.getCardType());o.put("cardName",x.getCardName());o.put("transactionId",x.getTransactionId());o.put("transactionType",x.getTransactionType());o.put("virtualCardLast4",x.getVirtualCardLast4());o.put("virtualCardType",x.getVirtualCardType());o.put("cardMatchSource",x.getCardMatchSource());o.put("detailChecked",x.getDetailChecked());items.put(o);}JSObject out=new JSObject();out.put("transactions",items);call.resolve(out);}
    @PluginMethod public void getGoogleWalletDiagnostics(PluginCall call){JSArray arr=new JSArray();List<GoogleWalletDiagnosticCapture> list=GoogleWalletDiagnosticStore.INSTANCE.load(getContext());for(GoogleWalletDiagnosticCapture x:list){JSObject o=new JSObject();o.put("time",x.getTime());o.put("packageName",x.getPackageName());o.put("eventType",x.getEventType());o.put("eventClass",x.getEventClass());o.put("eventText",x.getEventText());o.put("visibleText",x.getVisibleText());o.put("tree",x.getTree());arr.put(o);}JSObject out=new JSObject();out.put("captures",arr);call.resolve(out);}
    @PluginMethod public void getSyncLog(PluginCall call){JSObject out=new JSObject();out.put("log",getContext().getSharedPreferences("v241",Context.MODE_PRIVATE).getString("log",""));call.resolve(out);}
    private static JSObject base(String source,String label,String key,String shop,String amount,String date){JSObject o=new JSObject();o.put("source",source);o.put("sourceLabel",label);o.put("key",source+"|"+key);o.put("shop",shop);o.put("amount",amount);o.put("date",date);return o;}
    private static String cleanAmount(String value){if(value==null)return"0";String c=value.replaceAll("[^0-9.-]","");if(c.startsWith("-"))c=c.substring(1);return c.isEmpty()?"0":c;}
}
