package com.bou.payhelper;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** Daily 02:00-ish local-time scheduler for the unified payment sync. */
public class NightlySyncReceiver extends BroadcastReceiver {
    private static final int REQUEST_CODE = 6102;
    public static final String EXTRA_NIGHTLY_SYNC = "com.bou.payhelper.EXTRA_NIGHTLY_SYNC";

    @Override public void onReceive(Context context, Intent intent) {
        scheduleNext(context);
        launchPayHelperHost(context);
    }

    private static void launchPayHelperHost(Context context) {
        Intent launch = new Intent(context, MainActivity.class)
                .putExtra(EXTRA_NIGHTLY_SYNC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            PendingIntent pi = PendingIntent.getActivity(context, 6103, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(context, 0, null, null, null, null, opts.toBundle());
            } else {
                pi.send();
            }
        } catch (Throwable ignored) {
            try { context.startActivity(launch); } catch (Throwable ignored2) {}
        }
    }

    public static void scheduleNext(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, NightlySyncReceiver.class).setAction("com.bou.payhelper.NIGHTLY_SYNC");
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar now = Calendar.getInstance();
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 2);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        }
    }
}
