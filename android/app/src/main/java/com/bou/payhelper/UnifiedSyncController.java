package com.bou.payhelper;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.PayAccessibilityService;

/** V6.2.3: unified sync with per-wallet timeout, total watchdog and persistent result log. */
public final class UnifiedSyncController {
    private static final Handler H=new Handler(Looper.getMainLooper());
    private static final Object TOKEN=new Object();
    private static boolean running=false,scheduledRun=false,anyFailure=false;
    private static int stage=-1;
    private static long startedAt=0L,stageStartedAt=0L;
    private static String diagnostic="idle";
    private static Context runContext;
    private static final String[] STAGE_NAMES={"街口","LINE Pay","Pi 拍錢包","Google Pay"};
    private static final String[] stageResults={"PENDING","PENDING","PENDING","PENDING"};
    // Hard timeout for every source. If a source hangs it is skipped and the next one runs.
    private static final long[] WINDOWS={90_000L,120_000L,90_000L,120_000L};
    // Absolute safety watchdog: the phone must never stay in the sync chain indefinitely.
    private static final long MAX_RUN_MS=8*60_000L;
    private static final long MIN_STAGE_MS=4500L,POLL_MS=1000L;
    private static final String SELF="com.bou.payhelper";
    private UnifiedSyncController(){}

    public static synchronized boolean isRunning(){return running;}
    public static synchronized int getStage(){return stage;}
    public static synchronized long getStartedAt(){return startedAt;}
    public static synchronized boolean isScheduledRun(){return scheduledRun;}
    public static synchronized String getDiagnostic(){return diagnostic;}
    public static synchronized void acknowledgeImportedRun(){if(!running){stage=-1;scheduledRun=false;diagnostic="sync imported";}}
    private static synchronized void diag(String s){diagnostic=s;}

    public static synchronized boolean start(Context c,PayAccessibilityService service,boolean scheduled){
        if(running||service==null)return false;
        running=true;scheduledRun=scheduled;anyFailure=false;startedAt=System.currentTimeMillis();stage=-1;
        runContext=c.getApplicationContext();
        for(int i=0;i<stageResults.length;i++)stageResults[i]="PENDING";
        diagnostic=scheduled?"scheduled sync starting":"manual sync starting";
        SyncRunLogStore.start(runContext,scheduled,startedAt);
        H.removeCallbacksAndMessages(TOKEN);
        // Independent total watchdog. It is removed automatically when the run finishes.
        post(MAX_RUN_MS,()->hardAbort(service,"TOTAL_TIMEOUT_8_MIN"));
        startPreparedStage(runContext,service,0);
        return true;
    }

    private static void post(long ms,Runnable r){H.postAtTime(r,TOKEN,SystemClock.uptimeMillis()+ms);}
    private static boolean busy(PayAccessibilityService s,int n){switch(n){case 0:return PaymentSyncStateBridge.isJkoRunning(s);case 1:return PaymentSyncStateBridge.isLineRunning(s);case 2:return PaymentSyncStateBridge.isPiRunning(s);case 3:return GoogleWalletSyncControllerV3.isRunning();default:return false;}}
    private static void setStageResult(int s,String result){if(s<0||s>3)return;stageResults[s]=result;if(!"OK".equals(result))anyFailure=true;if(runContext!=null)SyncRunLogStore.stage(runContext,s,result);}

    private static void startPreparedStage(Context c,PayAccessibilityService service,int s){
        synchronized(UnifiedSyncController.class){if(!running)return;stage=s;stageStartedAt=System.currentTimeMillis();}
        diag("stage "+s+" prepare; active="+service.activePackageForUnifiedSync());
        post(250L,()->startStage(c,service,s));
    }

    private static void startStage(Context c,PayAccessibilityService service,int s){
        synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}
        try{
            switch(s){
                case 0:service.startJkoSync();break;
                case 1:service.startLinePayForUnifiedSync();break;
                case 2:service.startPiForUnifiedSync();break;
                case 3:PaymentSyncStateBridge.prepareHandoff(service);GoogleWalletSyncControllerV3.start(service);break;
                default:finishAtHome(service);return;
            }
            diag("stage "+s+" collector armed; active="+service.activePackageForUnifiedSync());
        }catch(Throwable e){
            setStageResult(s,"START_ERROR:"+e.getClass().getSimpleName());
            diag("stage "+s+" start exception="+e.getClass().getSimpleName());
            completeStage(c,service,s,false);
            return;
        }
        if(s>0){boolean launched=MainActivity.launchUnifiedStageFromForeground(s);diag("stage "+s+" foreground launch="+launched+" active="+service.activePackageForUnifiedSync());}
        post(2200L,()->verify(c,service,s));
    }

    private static void verify(Context c,PayAccessibilityService service,int s){
        synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}
        boolean b=busy(service,s);String active=service.activePackageForUnifiedSync();
        diag("stage "+s+" verify busy="+b+" active="+active);
        if(!b){setStageResult(s,"OK");completeStage(c,service,s,true);return;}
        if(s>0&&SELF.equals(active))MainActivity.launchUnifiedStageFromForeground(s);
        post(Math.max(0L,MIN_STAGE_MS-2200L),()->poll(c,service,s));
    }

    private static void poll(Context c,PayAccessibilityService service,int s){
        synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}
        long elapsed=System.currentTimeMillis()-stageStartedAt;boolean b=busy(service,s);String active=service.activePackageForUnifiedSync();
        diag("stage "+s+" poll busy="+b+" active="+active+" elapsed="+elapsed);
        if(!b&&elapsed>=MIN_STAGE_MS){setStageResult(s,"OK");completeStage(c,service,s,true);return;}
        if(elapsed>=WINDOWS[s]){
            setStageResult(s,"TIMEOUT:"+(WINDOWS[s]/1000)+"s");
            diag("stage "+s+" TIMEOUT active="+active);
            if(s==3)try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}
            PaymentSyncStateBridge.prepareHandoff(service);
            completeStage(c,service,s,false);
            return;
        }
        if(s>0&&SELF.equals(active))MainActivity.launchUnifiedStageFromForeground(s);
        post(POLL_MS,()->poll(c,service,s));
    }

    private static void completeStage(Context c,PayAccessibilityService service,int completedStage,boolean clean){
        synchronized(UnifiedSyncController.class){if(!running||stage!=completedStage)return;stage=completedStage+1;}
        int next=completedStage+1;
        diag("stage "+completedStage+" complete result="+stageResults[completedStage]+"; BACK to Pay Helper next="+next);
        backToPayHelperThen(c,service,next,0);
    }

    private static void backToPayHelperThen(Context c,PayAccessibilityService service,int next,int attempt){
        synchronized(UnifiedSyncController.class){if(!running)return;}
        String active=service.activePackageForUnifiedSync();
        if(SELF.equals(active)){
            diag("Pay Helper foreground reached after stage "+(next-1)+"; next="+next);
            if(next>3){finishAtHome(service);return;}
            post(300L,()->startPreparedStage(c,service,next));return;
        }
        if(attempt>=8){
            int prev=next-1;if(prev>=0&&prev<4&&"OK".equals(stageResults[prev]))setStageResult(prev,"BACK_TIMEOUT");else anyFailure=true;
            diag("BACK return timeout active="+active+"; continuing next="+next);
            if(next>3){finishAtHome(service);return;}
            post(300L,()->startPreparedStage(c,service,next));return;
        }
        boolean accepted=false;try{accepted=service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);}catch(Throwable ignored){}
        diag("BACK to Pay Helper attempt="+attempt+" accepted="+accepted+" active="+active+" next="+next);
        post(700L,()->backToPayHelperThen(c,service,next,attempt+1));
    }

    private static void hardAbort(PayAccessibilityService service,String reason){
        synchronized(UnifiedSyncController.class){if(!running)return;running=false;stage=4;anyFailure=true;diagnostic="FAILED: "+reason;}
        if(stage>=0&&stage<4&&"PENDING".equals(stageResults[stage]))setStageResult(stage,reason);
        try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}
        PaymentSyncStateBridge.prepareHandoff(service);
        if(runContext!=null)SyncRunLogStore.finish(runContext,"FAILED","整體同步超過 8 分鐘，已強制停止並返回手機桌面",System.currentTimeMillis());
        // Safety first: do not leave a wallet glowing all night even if Pay Helper cannot resume.
        try{service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);}catch(Throwable ignored){}
        H.removeCallbacksAndMessages(TOKEN);
    }

    public static void onPayHelperForeground(Activity activity){
        PayAccessibilityService service=PayAccessibilityService.Companion.getInstance();if(service==null)return;int s;
        synchronized(UnifiedSyncController.class){if(!running)return;s=stage;}
        if(s>0&&s<=3&&busy(service,s)){boolean launched=MainActivity.launchUnifiedStageFromForeground(s);diag("Pay Helper foreground handoff stage="+s+" launch="+launched+" active="+service.activePackageForUnifiedSync());}
    }

    public static synchronized void stop(Context c,boolean home){
        if(!running)return;running=false;stage=-1;scheduledRun=false;diagnostic="stopped";anyFailure=true;
        H.removeCallbacksAndMessages(TOKEN);try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}
        PaymentSyncStateBridge.prepareHandoff(PayAccessibilityService.Companion.getInstance());
        SyncRunLogStore.finish(c.getApplicationContext(),"FAILED","同步被手動停止",System.currentTimeMillis());
        if(home)returnHome(c);
    }

    private static void finishAtHome(PayAccessibilityService service){
        boolean scheduled;String status;String summary;
        synchronized(UnifiedSyncController.class){
            if(!running)return;running=false;stage=4;scheduled=scheduledRun;
            status=anyFailure?"PARTIAL":"SUCCESS";
            summary=(scheduled?"02:00 自動同步":"手動同步")+(anyFailure?"完成，但有來源失敗或逾時":"完成");
            diagnostic=scheduled?(anyFailure?"scheduled partial; waiting import":"scheduled completed; waiting import"):(anyFailure?"manual partial at Pay Helper":"manual completed at Pay Helper");
            H.removeCallbacksAndMessages(TOKEN);
        }
        try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}
        PaymentSyncStateBridge.prepareHandoff(service);
        if(runContext!=null)SyncRunLogStore.finish(runContext,status,summary,System.currentTimeMillis());
    }

    private static void returnHome(Context c){Intent i=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());if(i==null)return;i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);c.startActivity(i);}
}
