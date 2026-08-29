package com.bou.payhelper;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent summary of the latest unified sync, especially the 02:00 scheduled run. */
public final class SyncRunLogStore {
    private static final String PREF="payhelper_sync_run";
    private SyncRunLogStore(){}
    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    public static void start(Context c, boolean scheduled, long startedAt){
        p(c).edit()
                .putBoolean("scheduled",scheduled)
                .putLong("startedAt",startedAt)
                .putLong("finishedAt",0L)
                .putString("status","RUNNING")
                .putString("summary",scheduled?"02:00 自動同步進行中":"手動同步進行中")
                .putString("jko","PENDING").putString("line","PENDING")
                .putString("pi","PENDING").putString("google","PENDING")
                .putInt("added",0).putInt("matched",0).putInt("unmatched",0)
                .apply();
    }

    public static void stage(Context c,int stage,String result){
        String key=stage==0?"jko":stage==1?"line":stage==2?"pi":"google";
        p(c).edit().putString(key,result).apply();
    }

    public static void finish(Context c,String status,String summary,long finishedAt){
        p(c).edit().putString("status",status).putString("summary",summary).putLong("finishedAt",finishedAt).apply();
    }

    public static void imported(Context c,int added,int matched,int unmatched){
        p(c).edit().putInt("added",added).putInt("matched",matched).putInt("unmatched",unmatched).apply();
    }

    public static void failedToStart(Context c,String reason){
        long now=System.currentTimeMillis();
        p(c).edit().putBoolean("scheduled",true).putLong("startedAt",now).putLong("finishedAt",now)
                .putString("status","FAILED").putString("summary",reason)
                .putString("jko","NOT_STARTED").putString("line","NOT_STARTED")
                .putString("pi","NOT_STARTED").putString("google","NOT_STARTED").apply();
    }

    public static SharedPreferences read(Context c){return p(c);}
}
