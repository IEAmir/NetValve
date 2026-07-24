package dev.netvalve.stats

/** Live per-app usage row shown on the stats + dashboard screens. */
data class AppStat(
    val uid: Int,
    val packageName: String?,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val liveUploadBps: Long,
    val liveDownloadBps: Long,
    val active: Boolean,
)

/**
 * An immutable snapshot of all counters at one sampling instant. The UI collects
 * a [kotlinx.coroutines.flow.StateFlow] of these.
 */
data class StatsSnapshot(
    val totalUpload: Long = 0,
    val totalDownload: Long = 0,
    val liveUploadBps: Long = 0,
    val liveDownloadBps: Long = 0,
    val avgUploadBps: Long = 0,
    val avgDownloadBps: Long = 0,
    val peakUploadBps: Long = 0,
    val peakDownloadBps: Long = 0,
    val activeConnections: Int = 0,
    val throttledConnections: Long = 0,
    val blockedConnections: Long = 0,
    val dnsQueries: Long = 0,
    val avgConnectLatencyMillis: Long = 0,
    val sessionStartMillis: Long = 0,
    val lastResetMillis: Long = 0,
    val perApp: List<AppStat> = emptyList(),
) {
    val sessionDurationMillis: Long
        get() = if (sessionStartMillis == 0L) 0 else System.currentTimeMillis() - sessionStartMillis
}

/**
 * Converts a stream of cumulative byte counts into live/peak/average rates. Uses
 * a monotonic clock; no allocation on the sampling path.
 */
class ThroughputMeter(private val clock: () -> Long = System::nanoTime) {
    private var startNanos = clock()
    private var lastNanos = startNanos
    private var lastBytes = 0L
    var peakBps = 0L
        private set

    /** @return instantaneous bytes/second since the previous [sample]. */
    fun sample(cumulativeBytes: Long): Long {
        val now = clock()
        val dt = now - lastNanos
        val live = if (dt > 0) ((cumulativeBytes - lastBytes) * 1_000_000_000.0 / dt).toLong() else 0L
        lastNanos = now
        lastBytes = cumulativeBytes
        if (live > peakBps) peakBps = live
        return live.coerceAtLeast(0)
    }

    fun average(cumulativeBytes: Long): Long {
        val elapsed = clock() - startNanos
        return if (elapsed > 0) (cumulativeBytes * 1_000_000_000.0 / elapsed).toLong() else 0L
    }

    fun reset() {
        startNanos = clock(); lastNanos = startNanos; lastBytes = 0; peakBps = 0
    }
}
