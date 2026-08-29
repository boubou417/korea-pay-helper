package com.bou.payhelper;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent summary of the latest unified sync and a separate last 02:00 result. */
public final class SyncRunLogStore {
    private static final String PREF="payhelper_sync_run";
    private SyncRunLogStore(){}
    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private static boolean currentScheduled(Context c){return p(c).getBoolean("scheduled",false);}

    public static void start(Context c, boolean scheduled, long startedAt){
        SharedPreferences.Editor e=p(c).edit()
                .putBoolean("scheduled",scheduled).putLong("startedAt",startedAt).putLong("finishedAt",0L)
                .putString("status","RUNNING").putString("summary",scheduled?"02:00 自動同步進行中":"手動同步進行中")
                .putString("jko","PENDING").putString("line","PENDING").putString("pi","PENDING").putString("google","PENDING")
                .putInt("added",0).putInt("matched",0).putInt("unmatched",0);
        if(scheduled)e.putLong("night.startedAt",startedAt).putLong("night.finishedAt",0L).putString("night.status","RUNNING")
                .putString("night.summary","02:00 自動同步進行中").putString("night.jko","PENDING").putString("night.line","PENDING")
                .putString("night.pi","PENDING").putString("night.google","PENDING").putInt("night.added",0).putInt("night.matched",0).putInt("night.unmatched",0);
        e.apply();
    }

    public static void stage(Context c,int stage,String result){
        String key=stage==0?"jko":stage==1?"line":stage==2?"pi":"google";
        SharedPreferences.Editor e=p(c).edit().putString(key,result);
        if(currentScheduled(c))e.putString("night."+key,result);
        e.apply();
    }

    public static void finish(Context c,String status,String summary,long finishedAt){
        SharedPreferences.Editor e=p(c).edit().putString("status",status).putString("summary",summary).putLong("finishedAt",finishedAt);
        if(currentScheduled(c))e.putString("night.status",status).putString("night.summary",summary).putLong("night.finishedAt",finishedAt);
        e.apply();
    }

    public static void imported(Context c,int added,int matched,int unmatched){
        SharedPreferences.Editor e=p(c).edit().putInt("added",added).putInt("matched",matched).putInt("unmatched",unmatched);
        if(currentScheduled(c))e.putInt("night.added",added).putInt("night.matched",matched).putInt("night.unmatched",unmatched);
        e.apply();
    }

    public static void failedToStart(Context c,String reason){
        long now=System.currentTimeMillis();
        p(c).edit().putBoolean("scheduled",true).putLong("startedAt",now).putLong("finishedAt",now)
                .putString("status","FAILED").putString("summary",reason).putString("jko","NOT_STARTED").putString("line","NOT_STARTED").putString("pi","NOT_STARTED").putString("google","NOT_STARTED")
                .putLong("night.startedAt",now).putLong("night.finishedAt",now).putString("night.status","FAILED").putString("night.summary",reason)
                .putString("night.jko","NOT_STARTED").putString("night.line","NOT_STARTED").putString("night.pi","NOT_STARTED").putString("night.google","NOT_STARTED")
                .apply();
    }

    public static SharedPreferences read(Context c){return p(c);}
}
