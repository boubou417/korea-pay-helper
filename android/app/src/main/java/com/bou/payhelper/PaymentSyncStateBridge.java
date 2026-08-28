package com.bou.payhelper;

import java.lang.reflect.Field;

import tw.apostar.notificationpaytest.PayAccessibilityService;

/**
 * V6.1.3 bridge for handing off between the existing collectors.
 * The generated accessibility service keeps collector state private, so this
 * bridge resets only the known sync flags between sources.
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
        String[] flags = {
                "running", "linePayFormalMode", "piFormalMode",
                "linePayDiagnosticRunning", "piDiagnosticRunning",
                "googleWalletDiagnosticRunning", "linePayFormalScheduled",
                "piFormalScheduled", "transactionClickPending",
                "piDetailOpening", "piReturnPending", "linePayReturnPending"
        };
        for (String flag : flags) setBool(s, flag, false);

        // Important: do NOT clear the shared service Handler with
        // removeCallbacksAndMessages(null). JKO/LINE/Pi share that Handler; wiping
        // its queue during handoff can cancel a collector's startup/parse callbacks
        // and create the intermittent "stuck then timeout" behavior. Existing
        // delayed callbacks are guarded by the state flags reset above.
    }
}
