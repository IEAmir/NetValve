package dev.netvalve.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import dev.netvalve.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms the tunnel after a reboot, but only when the user had it enabled AND
 * opted into auto-start AND our VPN consent still stands ([VpnController.consentIntent]
 * is null). If consent was cleared while off, we stay down rather than popping a
 * dialog at boot.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var controller: VpnController
    @Inject lateinit var logger: Logger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val settings = settingsRepository.current()
                if (settings.enabled && settings.autoStartOnBoot && controller.consentIntent() == null) {
                    logger.i(LogCategory.SYSTEM, "Auto-starting tunnel after boot")
                    controller.start()
                }
            } catch (t: Throwable) {
                logger.e(LogCategory.ERROR, "boot restart failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
