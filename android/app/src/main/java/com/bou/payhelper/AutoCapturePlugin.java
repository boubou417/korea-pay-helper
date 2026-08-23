package com.bou.payhelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

    private PayAccessibilityService service() {
        return PayAccessibilityService.Companion.getInstance();
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName cn = new ComponentName(getContext(), PayAccessibilityService.class);
        String flat = cn.flattenToString();
        String shortFlat = cn.flattenToShortString();
        return enabled.contains(flat) || enabled.contains(shortFlat) || enabled.contains(PayAccessibilityService.class.getName());
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject out = new JSObject();
        out.put("accessibilityEnabled", accessibilityEnabled());
        out.put("serviceConnected", service() != null);
        out.put("jkoCount", JkosTransactionStore.INSTANCE.load(getContext()).size());
        out.put("linePayCount", LinePayTransactionStore.INSTANCE.load(getContext()).size());
        out.put("piWalletCount", PiWalletTransactionStore.INSTANCE.load(getContext()).size());
        out.put("googleWalletCount", GoogleWalletTransactionStore.INSTANCE.load(getContext()).size());
        out.put("googleWalletRunning", GoogleWalletSyncControllerV3.isRunning());
        out.put("googleDiagnosticCount", GoogleWalletDiagnosticStore.INSTANCE.load(getContext()).size());
        call.resolve(out);
    }

    @PluginMethod
    public void openAccessibilitySettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    private boolean requireService(PluginCall call) {
        if (!accessibilityEnabled()) {
            call.reject("ACCESSIBILITY_NOT_ENABLED");
            return false;
        }
        if (service() == null) {
            call.reject("ACCESSIBILITY_SERVICE_NOT_CONNECTED");
            return false;
        }
        return true;
    }

    @PluginMethod
    public void syncJko(PluginCall call) {
        if (!requireService(call)) return;
        getActivity().runOnUiThread(() -> service().startJkoSync());
        call.resolve();
    }

    @PluginMethod
    public void syncLinePay(PluginCall call) {
        if (!requireService(call)) return;
        getActivity().runOnUiThread(() -> service().startLinePaySync());
        call.resolve();
    }

    @PluginMethod
    public void syncPiWallet(PluginCall call) {
        if (!requireService(call)) return;
        getActivity().runOnUiThread(() -> service().startPiSync());
        call.resolve();
    }

    @PluginMethod
    public void syncGoogleWallet(PluginCall call) {
        if (!requireService(call)) return;
        getActivity().runOnUiThread(() -> GoogleWalletSyncControllerV3.start(service()));
        call.resolve();
    }

    @PluginMethod
    public void stopGoogleWalletSync(PluginCall call) {
        GoogleWalletSyncControllerV3.stop(true);
        call.resolve();
    }

    @PluginMethod
    public void diagnoseGoogleWallet(PluginCall call) {
        if (!requireService(call)) return;
        getActivity().runOnUiThread(() -> service().startGoogleWalletDiagnostic());
        call.resolve();
    }

    @PluginMethod
    public void stopGoogleWalletDiagnostic(PluginCall call) {
        if (service() != null) getActivity().runOnUiThread(() -> service().stopGoogleWalletDiagnostic(true));
        call.resolve();
    }

    @PluginMethod
    public void getTransactions(PluginCall call) {
        JSArray items = new JSArray();

        for (JkosTransaction x : JkosTransactionStore.INSTANCE.load(getContext())) {
            JSObject o = base("JKOPAY", "街口", x.getKey(), x.getShop(), cleanAmount(x.getPrice()), x.getDate());
            o.put("paymentMethod", x.getPaymentMethod());
            o.put("paymentAccount", x.getPaymentAccount());
            o.put("bank", x.getBank());
            o.put("cardLast4", x.getCardLast4());
            o.put("status", x.getStatus());
            o.put("detailChecked", x.getDetailChecked());
            items.put(o);
        }

        for (LinePayTransaction x : LinePayTransactionStore.INSTANCE.load(getContext())) {
            JSObject o = base("LINE_PAY", "LINE Pay", x.getKey(), x.getShop(), cleanAmount(x.getAmount()), x.getDate());
            o.put("paymentMethod", x.getPaymentMethod());
            o.put("paymentAccount", x.getPaymentAccount());
            o.put("bank", x.getBank());
            o.put("cardLast4", x.getCardLast4());
            o.put("cardType", x.getCardType());
            o.put("cardName", x.getCardName());
            o.put("transactionId", x.getTransactionId());
            o.put("detailChecked", x.getDetailChecked());
            items.put(o);
        }

        for (PiWalletTransaction x : PiWalletTransactionStore.INSTANCE.load(getContext())) {
            String timeText = x.getPaymentTime().isEmpty() ? x.getDate() : x.getPaymentTime();
            JSObject o = base("PI_WALLET", "Pi 拍錢包", x.getKey(), x.getShop(), cleanAmount(x.getAmount()), timeText);
            o.put("paymentMethod", x.getPaymentMethod());
            o.put("paymentAccount", x.getPaymentAccount());
            o.put("bank", x.getBank());
            o.put("cardLast4", x.getCardLast4());
            o.put("status", x.getStatus());
            o.put("transactionId", x.getTransactionId());
            o.put("transactionType", x.getTransactionType());
            o.put("detailChecked", x.getDetailChecked());
            items.put(o);
        }

        for (GoogleWalletTransaction x : GoogleWalletTransactionStore.INSTANCE.load(getContext())) {
            JSObject o = base("GOOGLE_WALLET", "Google Pay", x.getKey(), x.getShop(), cleanAmount(x.getAmount()), x.getDate());
            o.put("paymentMethod", "Google Pay");
            o.put("paymentAccount", x.getCardName().isEmpty() ? "" : (x.getCardName() + (x.getCardLast4().isEmpty() ? "" : " *" + x.getCardLast4())));
            o.put("bank", x.getBank());
            o.put("cardLast4", x.getCardLast4());
            o.put("cardType", x.getCardType());
            o.put("cardName", x.getCardName());
            o.put("detailChecked", x.getDetailChecked());
            items.put(o);
        }

        JSObject out = new JSObject();
        out.put("transactions", items);
        call.resolve(out);
    }

    @PluginMethod
    public void getGoogleWalletDiagnostics(PluginCall call) {
        JSArray arr = new JSArray();
        List<GoogleWalletDiagnosticCapture> list = GoogleWalletDiagnosticStore.INSTANCE.load(getContext());
        for (GoogleWalletDiagnosticCapture x : list) {
            JSObject o = new JSObject();
            o.put("time", x.getTime());
            o.put("packageName", x.getPackageName());
            o.put("eventType", x.getEventType());
            o.put("eventClass", x.getEventClass());
            o.put("eventText", x.getEventText());
            o.put("visibleText", x.getVisibleText());
            o.put("tree", x.getTree());
            arr.put(o);
        }
        JSObject out = new JSObject();
        out.put("captures", arr);
        call.resolve(out);
    }

    @PluginMethod
    public void getSyncLog(PluginCall call) {
        JSObject out = new JSObject();
        out.put("log", getContext().getSharedPreferences("v241", Context.MODE_PRIVATE).getString("log", ""));
        call.resolve(out);
    }

    private static JSObject base(String source, String label, String key, String shop, String amount, String date) {
        JSObject o = new JSObject();
        o.put("source", source);
        o.put("sourceLabel", label);
        o.put("key", source + "|" + key);
        o.put("shop", shop);
        o.put("amount", amount);
        o.put("date", date);
        return o;
    }

    private static String cleanAmount(String value) {
        if (value == null) return "0";
        String cleaned = value.replaceAll("[^0-9.-]", "");
        if (cleaned.startsWith("-")) cleaned = cleaned.substring(1);
        return cleaned.isEmpty() ? "0" : cleaned;
    }
}
