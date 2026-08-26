package tw.apostar.notificationpaytest

/**
 * Compatibility facade kept so the existing Capacitor bridge does not need to
 * change while Google Wallet sync is implemented by the dual-package V5 engine.
 */
object GoogleWalletSyncControllerV3 {
    @JvmStatic
    fun isRunning(): Boolean = GoogleWalletSyncControllerV5.isRunning()

    @JvmStatic
    fun start(s: PayAccessibilityService) = GoogleWalletSyncControllerV5.start(s)

    @JvmStatic
    fun stop(returnToApp: Boolean = true) = GoogleWalletSyncControllerV5.stop(returnToApp)

    @JvmStatic
    fun poke() = GoogleWalletSyncControllerV5.poke()
}
