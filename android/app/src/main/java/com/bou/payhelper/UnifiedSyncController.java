package com.bou.payhelper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.PayAccessibilityService;

/** V6.1.7 unified sync. Wallet handoff is owned by AccessibilityService. */
public final class UnifiedSyncController {
    private static final Handler H=new Handler(Looper.getMainLooper());
    private static final Object TOKEN=new Object();
    private static boolean running=false,scheduledRun=false; private static int stage=-1; private static long startedAt=0L,stageStartedAt=0L; private static String diagnostic="idle";
    private static final long[] WINDOWS={90_000L,120_000L,90_000L,120_000L};
    private static final long MIN_STAGE_MS=4500L,POLL_MS=1000L;
    private UnifiedSyncController(){}
    public static synchronized boolean isRunning(){return running;} public static synchronized int getStage(){return stage;} public static synchronized long getStartedAt(){return startedAt;} public static synchronized boolean isScheduledRun(){return scheduledRun;} public static synchronized String getDiagnostic(){return diagnostic;}
    private static synchronized void diag(String s){diagnostic=s;}
    public static synchronized boolean start(Context c,PayAccessibilityService service,boolean scheduled){if(running||service==null)return false;running=true;scheduledRun=scheduled;startedAt=System.currentTimeMillis();stage=-1;diagnostic="starting";H.removeCallbacksAndMessages(TOKEN);advanceTo(c.getApplicationContext(),service,0);return true;}
    private static void post(long ms,Runnable r){H.postAtTime(r,TOKEN,SystemClock.uptimeMillis()+ms);}
    private static boolean busy(PayAccessibilityService s,int n){switch(n){case 0:return PaymentSyncStateBridge.isJkoRunning(s);case 1:return PaymentSyncStateBridge.isLineRunning(s);case 2:return PaymentSyncStateBridge.isPiRunning(s);case 3:return GoogleWalletSyncControllerV3.isRunning();default:return false;}}
    private static void advanceTo(Context c,PayAccessibilityService service,int next){synchronized(UnifiedSyncController.class){if(!running)return;}if(next>3){finish(c,service);return;}synchronized(UnifiedSyncController.class){stage=next;stageStartedAt=System.currentTimeMillis();}diag("stage "+next+" prepare; active="+service.activePackageForUnifiedSync());post(250L,()->startStage(c,service,next));}
    private static void startStage(Context c,PayAccessibilityService service,int s){synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}try{switch(s){case 0:service.startJkoSync();break;case 1:service.startLinePayForUnifiedSync();break;case 2:service.startPiForUnifiedSync();break;case 3:PaymentSyncStateBridge.prepareHandoff(service);GoogleWalletSyncControllerV3.start(service);break;default:finish(c,service);return;}diag("stage "+s+" start called; active="+service.activePackageForUnifiedSync());}catch(Throwable e){diag("stage "+s+" start exception="+e.getClass().getSimpleName());post(800L,()->advanceTo(c,service,s+1));return;}post(2200L,()->verify(c,service,s));}
    private static void verify(Context c,PayAccessibilityService service,int s){synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}boolean b=busy(service,s);diag("stage "+s+" verify busy="+b+" active="+service.activePackageForUnifiedSync());if(!b){post(600L,()->advanceTo(c,service,s+1));return;}post(Math.max(0L,MIN_STAGE_MS-2200L),()->poll(c,service,s));}
    private static void poll(Context c,PayAccessibilityService service,int s){synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}long elapsed=System.currentTimeMillis()-stageStartedAt;boolean b=busy(service,s);diag("stage "+s+" poll busy="+b+" active="+service.activePackageForUnifiedSync()+" elapsed="+elapsed);if(!b&&elapsed>=MIN_STAGE_MS){advanceTo(c,service,s+1);return;}if(elapsed>=WINDOWS[s]){diag("stage "+s+" TIMEOUT active="+service.activePackageForUnifiedSync());if(s==3)try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}PaymentSyncStateBridge.prepareHandoff(service);post(600L,()->advanceTo(c,service,s+1));return;}post(POLL_MS,()->poll(c,service,s));}
    public static synchronized void stop(Context c,boolean home){if(!running)return;running=false;stage=-1;diagnostic="stopped";H.removeCallbacksAndMessages(TOKEN);try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}PaymentSyncStateBridge.prepareHandoff(PayAccessibilityService.Companion.getInstance());if(home)returnHome(c);}
    private static void finish(Context c,PayAccessibilityService service){synchronized(UnifiedSyncController.class){if(!running)return;running=false;stage=4;diagnostic="completed";H.removeCallbacksAndMessages(TOKEN);}try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}PaymentSyncStateBridge.prepareHandoff(service);returnHome(c);}
    private static void returnHome(Context c){Intent i=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());if(i==null)return;i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);c.startActivity(i);}
}
