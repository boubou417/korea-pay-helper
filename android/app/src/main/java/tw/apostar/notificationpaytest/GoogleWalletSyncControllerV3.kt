package tw.apostar.notificationpaytest

/** Compatibility facade used by the existing Capacitor bridge. */
object GoogleWalletSyncControllerV3 {
    @JvmStatic
    fun isRunning(): Boolean = GoogleWalletSyncControllerV74.isRunning()

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        GoogleWalletSyncControllerV74.start(s)
        GoogleWalletCardBackfillObserver.start(s)
    }

    @JvmStatic
    fun stop(returnToApp: Boolean = true) {
        GoogleWalletCardBackfillObserver.stop()
        GoogleWalletSyncControllerV74.stop(returnToApp)
    }

    @JvmStatic
    fun poke() = GoogleWalletSyncControllerV74.poke()
}
