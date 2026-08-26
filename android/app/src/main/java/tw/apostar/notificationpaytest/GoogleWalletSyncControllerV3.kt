package tw.apostar.notificationpaytest

/** Compatibility facade used by the existing Capacitor bridge. */
object GoogleWalletSyncControllerV3 {
    @JvmStatic
    fun isRunning(): Boolean = GoogleWalletSyncControllerV7.isRunning()

    @JvmStatic
    fun start(s: PayAccessibilityService) = GoogleWalletSyncControllerV7.start(s)

    @JvmStatic
    fun stop(returnToApp: Boolean = true) = GoogleWalletSyncControllerV7.stop(returnToApp)

    @JvmStatic
    fun poke() = GoogleWalletSyncControllerV7.poke()
}
