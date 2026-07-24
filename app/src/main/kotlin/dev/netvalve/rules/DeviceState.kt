package dev.netvalve.rules

import dev.netvalve.data.model.EvaluationContext
import dev.netvalve.data.model.NetworkType
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

/**
 * Device-wide state sampled by [DeviceStateMonitor]. Per-app foreground state is
 * represented here as [foregroundPackage] and specialised per-app when building
 * an [EvaluationContext].
 *
 * @param usageAccessGranted whether PACKAGE_USAGE_STATS was granted. When false,
 *        foreground detection degrades to a coarse "our process lifecycle"
 *        heuristic and [foregroundPackage] stays null (see DeviceStateMonitor).
 */
data class DeviceState(
    val network: NetworkType = NetworkType.NONE,
    val roaming: Boolean = false,
    val charging: Boolean = false,
    val batteryPercent: Int = 100,
    val screenOn: Boolean = true,
    val foregroundPackage: String? = null,
    val usageAccessGranted: Boolean = false,
) {
    fun toEvaluationContext(packageName: String, now: LocalDateTime): EvaluationContext =
        EvaluationContext(
            network = network,
            roaming = roaming,
            charging = charging,
            batteryPercent = batteryPercent,
            screenOn = screenOn,
            appForeground = foregroundPackage != null && foregroundPackage == packageName,
            minuteOfDay = now.hour * 60 + now.minute,
            dayOfWeek = now.dayOfWeek.value, // Mon=1..Sun=7
        )
}

/**
 * Streams [DeviceState]. An interface so the engine can be unit-tested with a
 * fake, and so the Android implementation (broadcasts, ConnectivityManager,
 * UsageStatsManager) is swappable.
 */
interface DeviceStateMonitor {
    val state: StateFlow<DeviceState>
    fun start()
    fun stop()
}
