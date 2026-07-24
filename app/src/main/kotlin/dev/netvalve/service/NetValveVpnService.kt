package dev.netvalve.service

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.Ipv6Mode
import dev.netvalve.data.model.SelectionMode
import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import dev.netvalve.network.SocketProtector
import dev.netvalve.repository.AppSelectionRepository
import dev.netvalve.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Socket
import javax.inject.Inject

/**
 * The VpnService that hosts the tunnel. Responsibilities:
 *  - build the TUN with the correct per-app allow/deny list + routes;
 *  - run as a typed foreground service with a live status notification;
 *  - delegate all packet work to [TrafficEngine];
 *  - handle coexistence: [onRevoke] (another VPN / revoked permission), rebuilds
 *    on app-set change, and clean stop.
 *
 * It is also the [SocketProtector]: upstream sockets are protected via the
 * framework overloads so their packets bypass our own TUN.
 */
@AndroidEntryPoint
class NetValveVpnService : VpnService(), SocketProtector {

    @Inject lateinit var trafficEngine: TrafficEngine
    @Inject lateinit var controller: VpnController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var selectionRepository: AppSelectionRepository
    @Inject lateinit var logger: Logger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var controlledCount = 0

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            VpnActions.ACTION_START, null -> handleStart()
            VpnActions.ACTION_STOP -> handleStop(persist = true)
            VpnActions.ACTION_RESTART -> handleRestart()
            VpnActions.ACTION_PAUSE -> handlePauseResume(pause = true)
            VpnActions.ACTION_RESUME -> handlePauseResume(pause = false)
        }
        // STICKY: if the process is killed, the system recreates the service with
        // a null intent, which we treat as START and rebuild from persisted state.
        return START_STICKY
    }

    private fun handleStart() {
        if (trafficEngine.isRunning) return
        // Post the foreground notification immediately to satisfy the FGS timeout,
        // then do the (fast) establish work off the main thread.
        controller.publishState(TunnelState.STARTING)
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, VpnStatus(TunnelState.STARTING), trafficEngine.statsFlow.value))
        scope.launch { establishAndStart() }
    }

    private suspend fun establishAndStart() {
        val settings = settingsRepository.current()
        val selected = selectionRepository.current()

        val pfd = try {
            buildTun(settings, selected)
        } catch (t: Throwable) {
            logger.e(LogCategory.ERROR, "establish failed: ${t.message}")
            fail("Could not establish VPN: ${t.message}")
            return
        }
        if (pfd == null) {
            // prepare() not satisfied or revoked mid-flight.
            fail("VPN permission not granted or was revoked")
            return
        }
        tunInterface = pfd

        settingsRepository.updateSettings { it.copy(enabled = true) }
        trafficEngine.start(
            tunFd = pfd.detachFd(),
            protector = this,
            mtu = MTU,
            ipv6Mode = settings.ipv6Mode,
            exemptDns = settings.exemptDns,
            dnsServers = DEFAULT_DNS,
        )
        controller.publish(
            VpnStatus(TunnelState.RUNNING, controlledAppCount = controlledCount, startedAtMillis = System.currentTimeMillis()),
        )
        logger.i(LogCategory.SYSTEM, "Tunnel running; controlling $controlledCount app(s)")
        observeStatsForNotification()
    }

    private fun buildTun(settings: GlobalSettings, selected: Set<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("NetValve")
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            // Always route IPv6 into the tunnel so it cannot bypass shaping; the
            // engine either relays it (RELAY) or fast-rejects it (FAST_REJECT).
            .addAddress(TUN_IPV6, 128)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")

        applyAppList(builder, settings.selectionMode, selected)
        builder.setBlocking(true)
        return builder.establish()
    }

    /**
     * Applies the per-app routing list. Note the platform constraint: the
     * allow/deny list is fixed at establish() — changing it needs a rebuild
     * (see [handleRestart]).
     */
    private fun applyAppList(builder: Builder, mode: SelectionMode, selected: Set<String>) {
        var count = 0
        when (mode) {
            SelectionMode.ONLY_SELECTED -> {
                if (selected.isEmpty()) {
                    // Allow only ourselves => effectively control nothing, instead
                    // of the default (which would capture ALL apps).
                    runCatching { builder.addAllowedApplication(packageName) }
                    logger.w(LogCategory.SYSTEM, "ONLY_SELECTED with no apps selected: controlling nothing")
                } else {
                    for (pkg in selected) {
                        try { builder.addAllowedApplication(pkg); count++ }
                        catch (_: PackageManager.NameNotFoundException) {
                            logger.w(LogCategory.SYSTEM, "allowed app not found: $pkg")
                        }
                    }
                }
            }
            SelectionMode.ALL_EXCEPT -> {
                // Never route our own traffic (prevents loops).
                runCatching { builder.addDisallowedApplication(packageName) }
                for (pkg in selected) {
                    try { builder.addDisallowedApplication(pkg) }
                    catch (_: PackageManager.NameNotFoundException) {
                        logger.w(LogCategory.SYSTEM, "disallowed app not found: $pkg")
                    }
                }
                count = selected.size // number of apps excluded from the tunnel
            }
        }
        controlledCount = count
    }

    private fun handleStop(persist: Boolean) {
        scope.launch {
            trafficEngine.stop()
            closeTun()
            if (persist) settingsRepository.updateSettings { it.copy(enabled = false) }
            controller.publish(VpnStatus(TunnelState.STOPPED))
            stopForegroundCompat()
            stopSelf()
        }
    }

    /** Rebuild the tunnel to pick up a changed controlled-app set. */
    private fun handleRestart() {
        scope.launch {
            trafficEngine.stop()
            closeTun()
            establishAndStart()
        }
    }

    private fun handlePauseResume(pause: Boolean) {
        trafficEngine.setPaused(pause)
        val state = if (pause) TunnelState.PAUSED else TunnelState.RUNNING
        controller.publishState(state)
        refreshNotification(VpnStatus(state, controlledAppCount = controlledCount))
        logger.i(LogCategory.SYSTEM, if (pause) "Paused (passthrough)" else "Resumed")
    }

    /**
     * Called by the framework when another VPN is prepared/started or the user
     * revokes our permission. We must stop gracefully — the OS has already torn
     * down our TUN.
     */
    override fun onRevoke() {
        logger.w(LogCategory.SYSTEM, "VPN revoked (another VPN started or permission withdrawn)")
        scope.launch {
            trafficEngine.stop()
            closeTun()
            settingsRepository.updateSettings { it.copy(enabled = false) }
            controller.publish(VpnStatus(TunnelState.REVOKED, message = "Another VPN took over"))
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun observeStatsForNotification() {
        scope.launch {
            trafficEngine.statsFlow.collect { snapshot ->
                val state = if (trafficEngine.isPaused) TunnelState.PAUSED else TunnelState.RUNNING
                refreshNotification(VpnStatus(state, controlledAppCount = controlledCount), snapshot)
            }
        }
    }

    private fun refreshNotification(status: VpnStatus, snapshot: dev.netvalve.stats.StatsSnapshot = trafficEngine.statsFlow.value) {
        val mgr = getSystemService(android.app.NotificationManager::class.java)
        mgr.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, status, snapshot))
    }

    private fun fail(message: String) {
        controller.publish(VpnStatus(TunnelState.ERROR, message = message))
        scope.launch { settingsRepository.updateSettings { it.copy(enabled = false) } }
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeTun() {
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- SocketProtector ---------------------------------------------------
    // Delegate to the VpnService implementations (qualified super to avoid the
    // same-signature recursion, since our interface declares the same methods).
    override fun protect(socket: Socket): Boolean = super<VpnService>.protect(socket)
    override fun protect(socket: DatagramSocket): Boolean = super<VpnService>.protect(socket)

    companion object {
        private const val MTU = 1500
        private const val TUN_IPV4 = "10.111.0.2"
        private const val TUN_IPV6 = "fd00:6e76:6376::2"
        private val DEFAULT_DNS = listOf("1.1.1.1", "8.8.8.8")
    }
}
