package tw.apostar.notificationpaytest

/** Compatibility facade used by the existing Capacitor bridge. */
object GoogleWalletSyncControllerV3 {
    @JvmStatic
    fun isRunning(): Boolean = GoogleWalletSyncControllerV71.isRunning()

    @JvmStatic
    fun start(s: PayAccessibilityService) = GoogleWalletSyncControllerV71.start(s)

    @JvmStatic
    fun stop(returnToApp: Boolean = true) = GoogleWalletSyncControllerV71.stop(returnToApp)

    @JvmStatic
    fun poke() = GoogleWalletSyncControllerV71.poke()
}
