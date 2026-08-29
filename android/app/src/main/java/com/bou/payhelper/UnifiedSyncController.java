package com.bou.payhelper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.PayAccessibilityService;

/** V6.1.4 unified payment-history sync with explicit state reset + package launch. */
public final class UnifiedSyncController {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static boolean running = false;
    private static int stage = -1;
    private static long startedAt = 0L;
    private static long stageStartedAt = 0L;
    private static boolean scheduledRun = false;

    private static final long JKO_WINDOW = 90_000L;
    private static final long LINE_WINDOW = 120_000L;
    private static final long PI_WINDOW = 90_000L;
    private static final long GOOGLE_WINDOW = 120_000L;
    private static final long MIN_STAGE_MS = 3_500L;
    private static final long POLL_MS = 900L;

    private static final String PKG_JKO = "com.jkos.app";
    private static final String PKG_LINE_APP = "jp.naver.line.android";
    private static final String PKG_LINE_PAY = "com.linepaytw.upay";
    private static final String PKG_PI = "tw.com.pchome.android.pi";
    private static final String PKG_GOOGLE = "com.google.android.apps.walletnfcrel";

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
    private static long timeoutFor(int s){ switch(s){case 0:return JKO_WINDOW;case 1:return LINE_WINDOW;case 2:return PI_WINDOW;case 3:return GOOGLE_WINDOW;default:return 10_000L;} }
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
        if(stage>=0){
            if(stage==3){ try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){} }
            PaymentSyncStateBridge.prepareHandoff(service);
        }
        if(next>3){ finish(context,service); return; }
        synchronized(UnifiedSyncController.class){ stage=next;stageStartedAt=System.currentTimeMillis(); }
        // Give the service reset a short gap before starting the next collector.
        post(650L,()->startStage(context,service,next,0));
    }

    private static void startStage(Context context,PayAccessibilityService service,int s,int attempt){
        synchronized(UnifiedSyncController.class){ if(!running || stage!=s)return; }
        try{
            switch(s){
                case 0:
                    service.startJkoSync();
                    forceLaunch(context,PKG_JKO,null);
                    break;
                case 1:
                    service.startLinePaySync();
                    // Explicitly launch LINE after the collector is armed. Do not rely on
                    // launchLineForPay() being the only foreground transition mechanism.
                    forceLaunch(context,PKG_LINE_APP,PKG_LINE_PAY);
                    break;
                case 2:
                    service.startPiSync();
                    forceLaunch(context,PKG_PI,null);
                    break;
                case 3:
                    GoogleWalletSyncControllerV3.start(service);
                    forceLaunch(context,PKG_GOOGLE,null);
                    break;
                default:
                    finish(context,service); return;
            }
        }catch(Throwable ignored){
            if(attempt<1){
                PaymentSyncStateBridge.prepareHandoff(service);
                post(800L,()->startStage(context,service,s,attempt+1));
            }else post(700L,()->advanceTo(context,service,s+1));
            return;
        }

        // Verify that the collector actually armed. If not, reset/retry once instead of
        // showing a false LINE/Pi stage while nothing owns Accessibility events.
        post(1400L,()->verifyStage(context,service,s,attempt));
    }

    private static void verifyStage(Context context,PayAccessibilityService service,int s,int attempt){
        synchronized(UnifiedSyncController.class){ if(!running || stage!=s)return; }
        if(collectorBusy(service,s)){
            post(Math.max(0L,MIN_STAGE_MS-1400L),()->pollStage(context,service,s));
            return;
        }
        if(attempt<1){
            PaymentSyncStateBridge.prepareHandoff(service);
            post(700L,()->startStage(context,service,s,attempt+1));
        }else{
            // Collector could not arm; skip it rather than leaving UI stuck on that stage.
            post(600L,()->advanceTo(context,service,s+1));
        }
    }

    private static boolean forceLaunch(Context context,String primary,String fallback){
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(primary);
        if(launch==null && fallback!=null) launch=context.getPackageManager().getLaunchIntentForPackage(fallback);
        if(launch==null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try{ context.startActivity(launch); return true; }catch(Throwable ignored){ return false; }
    }

    private static void pollStage(Context context,PayAccessibilityService service,int s){
        synchronized(UnifiedSyncController.class){ if(!running || stage!=s)return; }
        long elapsed=System.currentTimeMillis()-stageStartedAt;
        boolean busy=collectorBusy(service,s);
        if(!busy && elapsed>=MIN_STAGE_MS){ advanceTo(context,service,s+1); return; }
        if(elapsed>=timeoutFor(s)){
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
