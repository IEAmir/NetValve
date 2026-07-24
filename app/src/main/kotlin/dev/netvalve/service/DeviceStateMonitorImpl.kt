package dev.netvalve.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import dev.netvalve.data.model.NetworkType
import dev.netvalve.rules.DeviceState
import dev.netvalve.rules.DeviceStateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android [DeviceStateMonitor]. Aggregates connectivity (transport + roaming),
 * power (charging + level), screen, and foreground-app signals into a single
 * [DeviceState] flow that the [dev.netvalve.rules.RuleEngine] consumes.
 *
 * Foreground detection uses [UsageStatsManager], which needs the PACKAGE_USAGE_STATS
 * appop. If it is not granted, [DeviceState.foregroundPackage] stays null and
 * "background-only" rules simply never see an app as foreground — a documented,
 * safe degradation rather than a crash. [isUsageAccessGranted] lets the UI prompt.
 */
class DeviceStateMonitorImpl(
    private val context: Context,
) : DeviceStateMonitor {

    private val _state = MutableStateFlow(DeviceState())
    override val state: StateFlow<DeviceState> = _state.asStateFlow()

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var scope: CoroutineScope? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                else -> NetworkType.OTHER
            }
            val roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            _state.update { it.copy(network = type, roaming = roaming) }
        }

        override fun onLost(network: Network) {
            _state.update { it.copy(network = NetworkType.NONE) }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> _state.update { it.copy(screenOn = true) }
                Intent.ACTION_SCREEN_OFF -> _state.update { it.copy(screenOn = false) }
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
            }
        }
    }

    override fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s

        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // Battery is a sticky broadcast; this returns the current value immediately.
        val sticky = context.registerReceiver(systemReceiver, filter)
        sticky?.let { updateBattery(it) }
        _state.update {
            it.copy(
                screenOn = powerManager?.isInteractive ?: true,
                usageAccessGranted = isUsageAccessGranted(),
            )
        }

        // Poll foreground app while running (cheap; only when usage access granted).
        s.launch { foregroundLoop() }
    }

    override fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(systemReceiver) }
        scope?.cancel()
        scope = null
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        _state.update { it.copy(batteryPercent = pct, charging = charging) }
    }

    private suspend fun foregroundLoop() {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        while (scope?.isActive == true) {
            if (isUsageAccessGranted()) {
                val fg = queryForegroundPackage(usm)
                _state.update { it.copy(foregroundPackage = fg, usageAccessGranted = true) }
            } else {
                _state.update { it.copy(foregroundPackage = null, usageAccessGranted = false) }
            }
            delay(FOREGROUND_POLL_MILLIS)
        }
    }

    private fun queryForegroundPackage(usm: UsageStatsManager): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - FOREGROUND_LOOKBACK_MILLIS, now)
        var last: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                last = e.packageName
            }
        }
        return last
    }

    fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val FOREGROUND_POLL_MILLIS = 2_000L
        private const val FOREGROUND_LOOKBACK_MILLIS = 10_000L
    }
}
