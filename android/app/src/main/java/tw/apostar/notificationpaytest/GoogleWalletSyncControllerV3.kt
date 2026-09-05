package tw.apostar.notificationpaytest

/** Compatibility facade used by the existing Capacitor bridge. */
object GoogleWalletSyncControllerV3 {
    @JvmStatic
    fun isRunning(): Boolean = GoogleWalletGlobalFirstController.isRunning()

    @JvmStatic
    fun start(s: PayAccessibilityService) {
        // Stable navigation order: Wallet home -> 顯示更多 -> 查看更多交易 -> history.
        // Card selection must never block the basic transaction import.
        GoogleWalletGlobalFirstController.start(s)
        GoogleWalletCardBackfillObserver.start(s)
    }

    @JvmStatic
    fun stop(returnToApp: Boolean = true) {
        GoogleWalletCardBackfillObserver.stop()
        GoogleWalletSelectedCardBackfillObserver.stop()
        GoogleWalletGlobalFirstController.stop(returnToApp)
    }

    @JvmStatic
    fun poke() = GoogleWalletGlobalFirstController.poke()
}
