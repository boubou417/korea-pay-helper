package com.bou.payhelper;

import android.os.Handler;

import java.lang.reflect.Field;

import tw.apostar.notificationpaytest.PayAccessibilityService;

/**
 * Temporary V6.1.2 bridge for handing off between the existing collectors.
 * The generated accessibility service keeps collector state private, so this
 * bridge resets only the known sync flags/callback queue between sources.
 * Release builds are not minified, therefore these field names are stable in
 * this test branch. No transaction-store data is touched.
 */
public final class PaymentSyncStateBridge {
    private PaymentSyncStateBridge() {}

    private static Object get(PayAccessibilityService s, String name) {
        try {
            Field f = s.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(s);
        } catch (Throwable ignored) { return null; }
    }

    private static boolean getBool(PayAccessibilityService s, String name) {
        Object v = get(s, name);
        return v instanceof Boolean && (Boolean) v;
    }

    private static void setBool(PayAccessibilityService s, String name, boolean value) {
        try {
            Field f = s.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(s, value);
        } catch (Throwable ignored) {}
    }

    public static boolean isJkoRunning(PayAccessibilityService s) {
        return s != null && getBool(s, "running");
    }

    public static boolean isLineRunning(PayAccessibilityService s) {
        return s != null && getBool(s, "linePayFormalMode");
    }

    public static boolean isPiRunning(PayAccessibilityService s) {
        return s != null && getBool(s, "piFormalMode");
    }

    public static void prepareHandoff(PayAccessibilityService s) {
        if (s == null) return;
        // Stop all formal/diagnostic state that can reject the next collector.
        String[] flags = {
                "running", "linePayFormalMode", "piFormalMode",
                "linePayDiagnosticRunning", "piDiagnosticRunning",
                "googleWalletDiagnosticRunning", "linePayFormalScheduled",
                "piFormalScheduled", "transactionClickPending",
                "piDetailOpening", "piReturnPending", "linePayReturnPending"
        };
        for (String flag : flags) setBool(s, flag, false);

        Object h = get(s, "h");
        if (h instanceof Handler) {
            // Cancel only the accessibility service's pending collector callbacks.
            // UnifiedSyncController owns a different Handler, so its handoff timer survives.
            ((Handler) h).removeCallbacksAndMessages(null);
        }
    }
}
