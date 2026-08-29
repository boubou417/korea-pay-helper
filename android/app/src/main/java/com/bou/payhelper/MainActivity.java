package com.bou.payhelper;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.BridgeActivity;

import java.lang.ref.WeakReference;

import tw.apostar.notificationpaytest.PayAccessibilityService;

public class MainActivity extends BridgeActivity {
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AutoCapturePlugin.class);
        super.onCreate(savedInstanceState);
        NightlySyncReceiver.scheduleNext(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        NightlySyncReceiver.scheduleNext(this);
        maybeStartNightlySync();
        UnifiedSyncController.onPayHelperForeground(this);
    }

    private void maybeStartNightlySync() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(NightlySyncReceiver.EXTRA_NIGHTLY_SYNC, false)) return;
        intent.removeExtra(NightlySyncReceiver.EXTRA_NIGHTLY_SYNC);
        startNightlyWhenServiceReady(0);
    }

    private void startNightlyWhenServiceReady(int attempt) {
        if (UnifiedSyncController.isRunning()) return;
        PayAccessibilityService service = PayAccessibilityService.Companion.getInstance();
        if (service != null) {
            UnifiedSyncController.start(getApplicationContext(), service, true);
            return;
        }
        if (attempt < 8) handler.postDelayed(() -> startNightlyWhenServiceReady(attempt + 1), 500L);
    }

    public static boolean launchUnifiedStageFromForeground(int stage) {
        MainActivity a = current.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) return false;
        String primary;
        String fallback = null;
        switch (stage) {
            case 1: primary = "jp.naver.line.android"; fallback = "com.linepaytw.upay"; break;
            case 2: primary = "tw.com.pchome.android.pi"; break;
            case 3: primary = "com.google.android.apps.walletnfcrel"; break;
            default: return false;
        }
        Intent launch = a.getPackageManager().getLaunchIntentForPackage(primary);
        if (launch == null && fallback != null) launch = a.getPackageManager().getLaunchIntentForPackage(fallback);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { a.startActivity(launch); return true; } catch (Throwable ignored) { return false; }
    }
}
