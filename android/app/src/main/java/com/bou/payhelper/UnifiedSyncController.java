package com.bou.payhelper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.PayAccessibilityService;

/**
 * V6.1 unified daily sync runner.
 * Runs the four already-tested transaction-history collectors one by one.
 * Each source gets a bounded window so one broken/login-expired wallet cannot
 * block the rest of the nightly job. At the end Pay Helper is brought home;
 * the Web layer imports the native stores when it becomes visible.
 */
public final class UnifiedSyncController {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean running = false;
    private static int stage = -1;
    private static long startedAt = 0L;
    private static boolean scheduledRun = false;

    // Conservative first test timings. Existing collectors stop themselves when
    // they finish; these are only maximum windows before moving to the next app.
    private static final long JKO_WINDOW = 45_000L;
    private static final long LINE_WINDOW = 70_000L;
    private static final long PI_WINDOW = 45_000L;
    private static final long GOOGLE_WINDOW = 90_000L;

    private UnifiedSyncController() {}

    public static synchronized boolean isRunning() { return running; }
    public static synchronized int getStage() { return stage; }
    public static synchronized long getStartedAt() { return startedAt; }
    public static synchronized boolean isScheduledRun() { return scheduledRun; }

    public static synchronized boolean start(Context context, PayAccessibilityService service, boolean fromSchedule) {
        if (running || service == null) return false;
        running = true;
        scheduledRun = fromSchedule;
        startedAt = System.currentTimeMillis();
        stage = 0;
        H.removeCallbacksAndMessages(TOKEN);
        runStage(context.getApplicationContext(), service, 0);
        return true;
    }

    private static final Object TOKEN = new Object();

    private static void post(long delay, Runnable r) {
        H.postAtTime(r, TOKEN, android.os.SystemClock.uptimeMillis() + delay);
    }

    private static void runStage(Context context, PayAccessibilityService service, int next) {
        synchronized (UnifiedSyncController.class) {
            if (!running) return;
            stage = next;
        }
        try {
            switch (next) {
                case 0:
                    service.startJkoSync();
                    post(JKO_WINDOW, () -> runStage(context, service, 1));
                    break;
                case 1:
                    service.startLinePaySync();
                    post(LINE_WINDOW, () -> runStage(context, service, 2));
                    break;
                case 2:
                    service.startPiSync();
                    post(PI_WINDOW, () -> runStage(context, service, 3));
                    break;
                case 3:
                    GoogleWalletSyncControllerV3.start(service);
                    post(GOOGLE_WINDOW, () -> finish(context));
                    break;
                default:
                    finish(context);
                    break;
            }
        } catch (Throwable ignored) {
            // A source can fail because its app is logged out / unavailable.
            // Continue to the next source rather than abandoning the whole run.
            long wait = next == 0 ? 1500L : 1200L;
            post(wait, () -> runStage(context, service, next + 1));
        }
    }

    public static synchronized void stop(Context context, boolean returnHome) {
        if (!running) return;
        running = false;
        stage = -1;
        H.removeCallbacksAndMessages(TOKEN);
        try { GoogleWalletSyncControllerV3.stop(false); } catch (Throwable ignored) {}
        if (returnHome) returnHome(context);
    }

    private static void finish(Context context) {
        synchronized (UnifiedSyncController.class) {
            if (!running) return;
            running = false;
            stage = 4;
            H.removeCallbacksAndMessages(TOKEN);
        }
        try { GoogleWalletSyncControllerV3.stop(false); } catch (Throwable ignored) {}
        returnHome(context);
    }

    private static void returnHome(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }
}
