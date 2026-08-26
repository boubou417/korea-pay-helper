package tw.apostar.notificationpaytest

/**
 * Compatibility facade kept so the existing Capacitor bridge does not need to
 * change while Google Wallet sync is implemented by the real-UI V6 engine.
 */
object GoogleWalletSyncControllerV3 {
    @JvmStatic
    fun isRunning(): Boolean = GoogleWalletSyncControllerV6.isRunning()

    @JvmStatic
    fun start(s: PayAccessibilityService) = GoogleWalletSyncControllerV6.start(s)

    @JvmStatic
    fun stop(returnToApp: Boolean = true) = GoogleWalletSyncControllerV6.stop(returnToApp)

    @JvmStatic
    fun poke() = GoogleWalletSyncControllerV6.poke()
}
