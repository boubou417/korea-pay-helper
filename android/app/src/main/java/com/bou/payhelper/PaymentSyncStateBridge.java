package com.bou.payhelper;

import tw.apostar.notificationpaytest.PayAccessibilityService;

/** Direct bridge to the AccessibilityService's explicit handoff API. */
public final class PaymentSyncStateBridge {
    private PaymentSyncStateBridge() {}

    public static boolean isJkoRunning(PayAccessibilityService s) {
        return s != null && s.isJkoSyncRunningForHandoff();
    }

    public static boolean isLineRunning(PayAccessibilityService s) {
        return s != null && s.isLinePaySyncRunningForHandoff();
    }

    public static boolean isPiRunning(PayAccessibilityService s) {
        return s != null && s.isPiSyncRunningForHandoff();
    }

    public static void prepareHandoff(PayAccessibilityService s) {
        if (s != null) s.forceStopPaymentSyncForHandoff();
    }
}
