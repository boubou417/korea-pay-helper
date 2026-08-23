package tw.apostar.notificationpaytest

/**
 * Compatibility facade kept so the existing Capacitor bridge does not need to
 * change while Google Wallet sync is implemented by the gesture-only V4 engine.
 */
object GoogleWalletSyncControllerV3 {
    @JvmStatic
    fun isRunning(): Boolean = GoogleWalletSyncControllerV4.isRunning()

    @JvmStatic
    fun start(s: PayAccessibilityService) = GoogleWalletSyncControllerV4.start(s)

    @JvmStatic
    fun stop(returnToApp: Boolean = true) = GoogleWalletSyncControllerV4.stop(returnToApp)

    @JvmStatic
    fun poke() = GoogleWalletSyncControllerV4.poke()
}
