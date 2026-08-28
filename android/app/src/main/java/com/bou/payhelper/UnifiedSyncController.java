package com.bou.payhelper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.PayAccessibilityService;

/** V6.1.3 unified payment-history sync with collector-aware handoff. */
public final class UnifiedSyncController {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static boolean running = false;
    private static int stage = -1;
    private static long startedAt = 0L;
    private static long stageStartedAt = 0L;
    private static boolean scheduledRun = false;

    // The outer controller must not time out before the individual collector's
    // own safety timeout. Otherwise a healthy collector can be killed mid-parse.
    private static final long JKO_WINDOW = 370_000L;
    private static final long LINE_WINDOW = 310_000L;
    private static final long PI_WINDOW = 250_000L;
    private static final long GOOGLE_WINDOW = 310_000L;
    private static final long MIN_STAGE_MS = 2_500L;
    private static final long POLL_MS = 900L;

    private UnifiedSyncController() {}
    public static synchronized boolean isRunning(){ return running; }
    public static synchronized int getStage(){ return stage; }
    public static synchronized long getStartedAt(){ return startedAt; }
    public static synchronized boolean isScheduledRun(){ return scheduledRun; }

    public static synchronized boolean start(Context context, PayAccessibilityService service, boolean fromSchedule){
        if(running || service==null) return false;
        running=true; scheduledRun=fromSchedule; startedAt=System.currentTimeMillis(); stage=-1;
        H.removeCallbacksAndMessages(TOKEN);
        advanceTo(context.getApplicationContext(),service,0);
        return true;
    }

    private static void post(long delay,Runnable r){ H.postAtTime(r,TOKEN,SystemClock.uptimeMillis()+delay); }

    private static long timeoutFor(int s){
        switch(s){case 0:return JKO_WINDOW;case 1:return LINE_WINDOW;case 2:return PI_WINDOW;case 3:return GOOGLE_WINDOW;default:return 10_000L;}
    }

    private static boolean collectorBusy(PayAccessibilityService service,int s){
        switch(s){
            case 0:return PaymentSyncStateBridge.isJkoRunning(service);
            case 1:return PaymentSyncStateBridge.isLineRunning(service);
            case 2:return PaymentSyncStateBridge.isPiRunning(service);
            case 3:return GoogleWalletSyncControllerV3.isRunning();
            default:return false;
        }
    }

    private static void advanceTo(Context context,PayAccessibilityService service,int next){
        synchronized(UnifiedSyncController.class){ if(!running)return; }
        // Release previous collector state only. Do not globally wipe the
        // accessibility service's shared callback queue during handoff.
        if(stage>=0){
            if(stage==3){ try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){} }
            PaymentSyncStateBridge.prepareHandoff(service);
        }
        if(next>3){ finish(context,service); return; }
        synchronized(UnifiedSyncController.class){ stage=next;stageStartedAt=System.currentTimeMillis(); }
        post(450L,()->startStage(context,service,next));
    }

    private static void startStage(Context context,PayAccessibilityService service,int s){
        synchronized(UnifiedSyncController.class){ if(!running || stage!=s)return; }
        try{
            switch(s){
                case 0: service.startJkoSync(); break;
                case 1: service.startLinePaySync(); break;
                case 2: service.startPiSync(); break;
                case 3: GoogleWalletSyncControllerV3.start(service); break;
                default: finish(context,service); return;
            }
        }catch(Throwable ignored){
            post(700L,()->advanceTo(context,service,s+1));
            return;
        }
        post(MIN_STAGE_MS,()->pollStage(context,service,s));
    }

    private static void pollStage(Context context,PayAccessibilityService service,int s){
        synchronized(UnifiedSyncController.class){ if(!running || stage!=s)return; }
        long elapsed=System.currentTimeMillis()-stageStartedAt;
        boolean busy=collectorBusy(service,s);
        if(!busy && elapsed>=MIN_STAGE_MS){
            advanceTo(context,service,s+1);
            return;
        }
        if(elapsed>=timeoutFor(s)){
            // Outer timeout is a last-resort safety net. Each collector gets the
            // first chance to hit its own timeout, save partial data, and stop cleanly.
            if(s==3){try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}}
            PaymentSyncStateBridge.prepareHandoff(service);
            post(600L,()->advanceTo(context,service,s+1));
            return;
        }
        post(POLL_MS,()->pollStage(context,service,s));
    }

    public static synchronized void stop(Context context,boolean returnHome){
        if(!running)return;
        running=false;stage=-1;H.removeCallbacksAndMessages(TOKEN);
        try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}
        PayAccessibilityService s=PayAccessibilityService.Companion.getInstance();
        PaymentSyncStateBridge.prepareHandoff(s);
        if(returnHome)returnHome(context);
    }

    private static void finish(Context context,PayAccessibilityService service){
        synchronized(UnifiedSyncController.class){ if(!running)return;running=false;stage=4;H.removeCallbacksAndMessages(TOKEN); }
        try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}
        PaymentSyncStateBridge.prepareHandoff(service);
        returnHome(context);
    }

    private static void returnHome(Context context){
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if(launch==null)return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }
}
