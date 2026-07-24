package dev.netvalve.service

/** Lifecycle state of the tunnel, surfaced to the UI via [VpnController]. */
enum class TunnelState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,     // tunnel up but all traffic passthrough (quick-pause)
    ERROR,
    REVOKED,    // another VPN took over / permission revoked
}

/** Full status snapshot for the UI. */
data class VpnStatus(
    val state: TunnelState = TunnelState.STOPPED,
    val message: String? = null,
    val controlledAppCount: Int = 0,
    val startedAtMillis: Long = 0,
) {
    val isActive: Boolean get() = state == TunnelState.RUNNING || state == TunnelState.PAUSED
}

/** Intent actions + extras used to command [NetValveVpnService]. */
object VpnActions {
    const val ACTION_START = "dev.netvalve.action.START"
    const val ACTION_STOP = "dev.netvalve.action.STOP"
    const val ACTION_PAUSE = "dev.netvalve.action.PAUSE"
    const val ACTION_RESUME = "dev.netvalve.action.RESUME"
    const val ACTION_RESTART = "dev.netvalve.action.RESTART" // rebuild after app-set change
}
