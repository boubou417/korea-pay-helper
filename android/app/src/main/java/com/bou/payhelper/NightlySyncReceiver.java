package com.bou.payhelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

import tw.apostar.notificationpaytest.PayAccessibilityService;

/** Daily 02:00-ish local-time scheduler for the unified payment sync. */
public class NightlySyncReceiver extends BroadcastReceiver {
    private static final int REQUEST_CODE = 6102;

    @Override public void onReceive(Context context, Intent intent) {
        scheduleNext(context);
        PayAccessibilityService service = PayAccessibilityService.Companion.getInstance();
        if (service != null && !UnifiedSyncController.isRunning()) {
            UnifiedSyncController.start(context, service, true);
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

        // Inexact alarm is intentional: it does not require exact-alarm special access.
        // Android may defer it somewhat under Doze; opening Pay Helper re-arms the next run.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        }
    }
}
