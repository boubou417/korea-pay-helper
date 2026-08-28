package com.bou.payhelper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.PayAccessibilityService;

/** V6.1.4 unified payment-history sync with explicit completion handoff. */
public final class UnifiedSyncController {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static boolean running = false;
    private static int stage = -1;
    private static long startedAt = 0L;
    private static long stageStartedAt = 0L;
    private static boolean scheduledRun = false;

    private static final long JKO_WINDOW = 370_000L;
    private static final long LINE_WINDOW = 310_000L;
    private static final long PI_WINDOW = 250_000L;
    private static final long GOOGLE_WINDOW = 310_000L;
    private static final long MIN_STAGE_MS = 2_500L;
    private static final long POLL_MS = 900L;

    private static final String PKG_JKO = "com.jkos.app";
    private static final String PKG_LINE = "jp.naver.line.android";
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

    /** Optional explicit completion hook for collectors that can notify the controller. */
    public static void collectorFinished(Context context, PayAccessibilityService service, int completedStage){
        synchronized(UnifiedSyncController.class){
            if(!running || stage!=completedStage) return;
        }
        post(250L,()->{
            synchronized(UnifiedSyncController.class){
                if(!running || stage!=completedStage) return;
            }
            advanceTo(context.getApplicationContext(),service,completedStage+1);
        });
    }

    private static void advanceTo(Context context,PayAccessibilityService service,int next){
        synchronized(UnifiedSyncController.class){ if(!running)return; }
        if(stage>=0){
            if(stage==3){ try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){} }
            PaymentSyncStateBridge.prepareHandoff(service);
        }
        if(next>3){ finish(context,service); return; }
        synchronized(UnifiedSyncController.class){ stage=next;stageStartedAt=System.currentTimeMillis(); }
        post(800L,()->startStage(context,service,next));
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

        // Android 15/16 may let the previous collector's return-to-app activity win
        // the window race even though the next collector already called startActivity().
        // Verify the expected payment app is really active. If it is still Pay Helper or
        // the previous source, explicitly bring the intended app to the foreground.
        post(1_600L,()->ensureStageAppVisible(context,service,s));
        post(4_500L,()->ensureStageAppVisible(context,service,s));
        post(MIN_STAGE_MS,()->pollStage(context,service,s));
    }

    private static void ensureStageAppVisible(Context context, PayAccessibilityService service, int s){
        synchronized(UnifiedSyncController.class){ if(!running || stage!=s)return; }
        if(isExpectedPackage(service,s)) return;

        String[] candidates;
        switch(s){
            case 0: candidates=new String[]{PKG_JKO}; break;
            case 1: candidates=new String[]{PKG_LINE,PKG_LINE_PAY}; break;
            case 2: candidates=new String[]{PKG_PI}; break;
            case 3: candidates=new String[]{PKG_GOOGLE}; break;
            default: return;
        }
        for(String pkg:candidates){
            try{
                Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
                if(launch==null) continue;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(launch);
                return;
            }catch(Throwable ignored){}
        }
    }

    private static boolean isExpectedPackage(PayAccessibilityService service,int s){
        try{
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(root==null || root.getPackageName()==null) return false;
            String pkg=root.getPackageName().toString();
            switch(s){
                case 0:return PKG_JKO.equals(pkg);
                case 1:return PKG_LINE.equals(pkg)||PKG_LINE_PAY.equals(pkg);
                case 2:return PKG_PI.equals(pkg);
                case 3:return PKG_GOOGLE.equals(pkg);
                default:return false;
            }
        }catch(Throwable ignored){ return false; }
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
