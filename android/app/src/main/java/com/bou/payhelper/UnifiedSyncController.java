package com.bou.payhelper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import tw.apostar.notificationpaytest.GoogleWalletSyncControllerV3;
import tw.apostar.notificationpaytest.PayAccessibilityService;

/** V6.1.5 unified sync. Orchestrator callbacks live on their own Handler. */
public final class UnifiedSyncController {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static boolean running=false; private static int stage=-1; private static long startedAt=0L,stageStartedAt=0L; private static boolean scheduledRun=false;
    private static String diagnostic="idle";
    private static final long[] WINDOWS={90_000L,120_000L,90_000L,120_000L};
    private static final long MIN_STAGE_MS=3500L,POLL_MS=900L;
    private static final String PKG_JKO="com.jkos.app",PKG_LINE_APP="jp.naver.line.android",PKG_LINE_PAY="com.linepaytw.upay",PKG_PI="tw.com.pchome.android.pi",PKG_GOOGLE="com.google.android.apps.walletnfcrel";
    private UnifiedSyncController(){}
    public static synchronized boolean isRunning(){return running;} public static synchronized int getStage(){return stage;} public static synchronized long getStartedAt(){return startedAt;} public static synchronized boolean isScheduledRun(){return scheduledRun;} public static synchronized String getDiagnostic(){return diagnostic;}
    private static synchronized void diag(String s){diagnostic=s;}
    public static synchronized boolean start(Context c,PayAccessibilityService service,boolean scheduled){if(running||service==null)return false;running=true;scheduledRun=scheduled;startedAt=System.currentTimeMillis();stage=-1;diagnostic="starting";H.removeCallbacksAndMessages(TOKEN);advanceTo(c.getApplicationContext(),service,0);return true;}
    private static void post(long ms,Runnable r){H.postAtTime(r,TOKEN,SystemClock.uptimeMillis()+ms);}
    private static boolean busy(PayAccessibilityService s,int n){switch(n){case 0:return PaymentSyncStateBridge.isJkoRunning(s);case 1:return PaymentSyncStateBridge.isLineRunning(s);case 2:return PaymentSyncStateBridge.isPiRunning(s);case 3:return GoogleWalletSyncControllerV3.isRunning();default:return false;}}
    private static void advanceTo(Context c,PayAccessibilityService service,int next){synchronized(UnifiedSyncController.class){if(!running)return;}if(stage>=0){diag("handoff "+stage+" -> "+next);if(stage==3)try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}PaymentSyncStateBridge.prepareHandoff(service);}if(next>3){finish(c,service);return;}synchronized(UnifiedSyncController.class){stage=next;stageStartedAt=System.currentTimeMillis();}diag("stage "+next+" reset done; waiting launch");post(650L,()->startStage(c,service,next,0));}
    private static void startStage(Context c,PayAccessibilityService service,int s,int attempt){synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}boolean launched=false;try{switch(s){case 0:service.startJkoSync();launched=forceLaunch(c,PKG_JKO,null);break;case 1:service.startLinePaySync();launched=forceLaunch(c,PKG_LINE_APP,PKG_LINE_PAY);break;case 2:service.startPiSync();launched=forceLaunch(c,PKG_PI,null);break;case 3:GoogleWalletSyncControllerV3.start(service);launched=forceLaunch(c,PKG_GOOGLE,null);break;default:finish(c,service);return;}diag("stage "+s+" start called; launch="+launched+" attempt="+attempt);}catch(Throwable e){diag("stage "+s+" start exception: "+e.getClass().getSimpleName());if(attempt<1){PaymentSyncStateBridge.prepareHandoff(service);post(800L,()->startStage(c,service,s,attempt+1));}else post(700L,()->advanceTo(c,service,s+1));return;}post(1400L,()->verify(c,service,s,attempt));}
    private static void verify(Context c,PayAccessibilityService service,int s,int attempt){synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}boolean b=busy(service,s);diag("stage "+s+" verify busy="+b+" attempt="+attempt);if(b){post(Math.max(0L,MIN_STAGE_MS-1400L),()->poll(c,service,s));return;}if(attempt<1){PaymentSyncStateBridge.prepareHandoff(service);post(700L,()->startStage(c,service,s,attempt+1));}else post(600L,()->advanceTo(c,service,s+1));}
    private static boolean forceLaunch(Context c,String primary,String fallback){Intent i=c.getPackageManager().getLaunchIntentForPackage(primary);String used=primary;if(i==null&&fallback!=null){i=c.getPackageManager().getLaunchIntentForPackage(fallback);used=fallback;}if(i==null){diag("launch intent missing: "+primary+(fallback==null?"":" / "+fallback));return false;}i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);try{c.startActivity(i);diag("startActivity OK: "+used);return true;}catch(Throwable e){diag("startActivity FAIL "+used+": "+e.getClass().getSimpleName());return false;}}
    private static void poll(Context c,PayAccessibilityService service,int s){synchronized(UnifiedSyncController.class){if(!running||stage!=s)return;}long elapsed=System.currentTimeMillis()-stageStartedAt;boolean b=busy(service,s);if(!b&&elapsed>=MIN_STAGE_MS){diag("stage "+s+" completed -> "+(s+1));advanceTo(c,service,s+1);return;}if(elapsed>=WINDOWS[s]){diag("stage "+s+" TIMEOUT -> "+(s+1));if(s==3)try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}PaymentSyncStateBridge.prepareHandoff(service);post(600L,()->advanceTo(c,service,s+1));return;}post(POLL_MS,()->poll(c,service,s));}
    public static synchronized void stop(Context c,boolean home){if(!running)return;running=false;stage=-1;diagnostic="stopped";H.removeCallbacksAndMessages(TOKEN);try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}PaymentSyncStateBridge.prepareHandoff(PayAccessibilityService.Companion.getInstance());if(home)returnHome(c);}
    private static void finish(Context c,PayAccessibilityService service){synchronized(UnifiedSyncController.class){if(!running)return;running=false;stage=4;diagnostic="completed; returning Pay Helper";H.removeCallbacksAndMessages(TOKEN);}try{GoogleWalletSyncControllerV3.stop(false);}catch(Throwable ignored){}PaymentSyncStateBridge.prepareHandoff(service);returnHome(c);}
    private static void returnHome(Context c){Intent i=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());if(i==null)return;i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);c.startActivity(i);}
}
