package dev.netvalve.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single, injectable entry point the UI uses to observe and command the
 * tunnel. It never touches the packet path — it just publishes [status] (updated
 * by the service) and sends intents. Consent is surfaced as an [Intent] the
 * Activity launches with an ActivityResult contract (VpnService.prepare must be
 * called from a UI context).
 */
@Singleton
class VpnController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    /** @return an intent to show the system VPN-consent dialog, or null if already granted. */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    /** True when another app currently holds the VPN grant (informational). */
    fun requiresConsent(): Boolean = consentIntent() != null

    fun start() = send(VpnActions.ACTION_START)
    fun stop() = send(VpnActions.ACTION_STOP)
    fun pause() = send(VpnActions.ACTION_PAUSE)
    fun resume() = send(VpnActions.ACTION_RESUME)

    /** Rebuild the tunnel after the controlled app set changed (see limitations). */
    fun restart() = send(VpnActions.ACTION_RESTART)

    /** Called by the service to publish lifecycle changes. */
    fun publish(status: VpnStatus) { _status.value = status }
    fun publishState(state: TunnelState, message: String? = null) {
        _status.update { it.copy(state = state, message = message) }
    }

    private fun send(action: String) {
        val intent = Intent(context, NetValveVpnService::class.java).setAction(action)
        if (action == VpnActions.ACTION_START) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            // Non-start commands target an already-running service.
            context.startService(intent)
        }
    }
}
