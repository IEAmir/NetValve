package dev.netvalve.stats

import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.repository.AppUsageRecord
import dev.netvalve.repository.StatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, low-overhead traffic accounting. The packet path calls the
 * `record*`/`on*` methods (all lock-free atomics); the service samples once per
 * second — but only while the UI is visible or the tunnel is up — to compute
 * live/avg/peak throughput and publish a [StatsSnapshot].
 *
 * Cumulative per-app byte totals are checkpointed to [StatsRepository] so they
 * survive process death; on start the persisted totals are loaded as a baseline.
 */
class StatsCollector(
    private val statsRepository: StatsRepository,
    private val appInfo: AppInfoLookup,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Counters {
        val up = AtomicLong(0)
        val down = AtomicLong(0)
        @Volatile var lastActiveMillis = 0L
        val upMeter = ThroughputMeter()
        val downMeter = ThroughputMeter()
        @Volatile var liveUp = 0L
        @Volatile var liveDown = 0L
    }

    private val perUid = ConcurrentHashMap<Int, Counters>()
    private val totalUp = AtomicLong(0)
    private val totalDown = AtomicLong(0)

    private val activeConnections = AtomicInteger(0)
    private val throttledConnections = AtomicLong(0)
    private val blockedConnections = AtomicLong(0)
    private val dnsQueries = AtomicLong(0)
    private val connectLatencySum = AtomicLong(0)
    private val connectLatencyCount = AtomicLong(0)

    @Volatile private var sessionStart = 0L
    @Volatile private var lastReset = clock()

    private val globalUpMeter = ThroughputMeter()
    private val globalDownMeter = ThroughputMeter()

    private val _snapshot = MutableStateFlow(StatsSnapshot())
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    // ---- lifecycle ----------------------------------------------------------

    /** Load persisted per-app baselines and mark the session start. */
    suspend fun startSession() {
        sessionStart = clock()
        statsRepository.current().forEach { rec ->
            val c = perUid.getOrPut(rec.uid) { Counters() }
            c.up.set(rec.totalUpload)
            c.down.set(rec.totalDownload)
            c.lastActiveMillis = rec.lastActiveMillis
            totalUp.addAndGet(rec.totalUpload)
            totalDown.addAndGet(rec.totalDownload)
        }
    }

    // ---- hot path (lock-free) ----------------------------------------------

    fun recordUpload(uid: Int, bytes: Long) {
        if (bytes <= 0) return
        counters(uid).let { it.up.addAndGet(bytes); it.lastActiveMillis = clock() }
        totalUp.addAndGet(bytes)
    }

    fun recordDownload(uid: Int, bytes: Long) {
        if (bytes <= 0) return
        counters(uid).let { it.down.addAndGet(bytes); it.lastActiveMillis = clock() }
        totalDown.addAndGet(bytes)
    }

    fun onFlowOpened(uid: Int, throttled: Boolean, blocked: Boolean) {
        if (blocked) { blockedConnections.incrementAndGet(); return }
        activeConnections.incrementAndGet()
        if (throttled) throttledConnections.incrementAndGet()
    }

    fun onFlowClosed(uid: Int) {
        activeConnections.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun onDnsQuery() { dnsQueries.incrementAndGet() }

    fun recordConnectLatency(millis: Long) {
        if (millis < 0) return
        connectLatencySum.addAndGet(millis)
        connectLatencyCount.incrementAndGet()
    }

    private fun counters(uid: Int) = perUid.getOrPut(uid) { Counters() }

    // ---- sampling (called ~1 Hz) -------------------------------------------

    fun sample(): StatsSnapshot {
        val tUp = totalUp.get()
        val tDown = totalDown.get()
        val liveUp = globalUpMeter.sample(tUp)
        val liveDown = globalDownMeter.sample(tDown)
        val now = clock()

        val apps = perUid.entries.map { (uid, c) ->
            val up = c.up.get(); val down = c.down.get()
            c.liveUp = c.upMeter.sample(up)
            c.liveDown = c.downMeter.sample(down)
            AppStat(
                uid = uid,
                packageName = appInfo.packagesForUid(uid).firstOrNull(),
                uploadBytes = up,
                downloadBytes = down,
                liveUploadBps = c.liveUp,
                liveDownloadBps = c.liveDown,
                active = now - c.lastActiveMillis < ACTIVE_WINDOW_MILLIS,
            )
        }.sortedByDescending { it.uploadBytes + it.downloadBytes }

        val latencyCount = connectLatencyCount.get()
        val snap = StatsSnapshot(
            totalUpload = tUp,
            totalDownload = tDown,
            liveUploadBps = liveUp,
            liveDownloadBps = liveDown,
            avgUploadBps = globalUpMeter.average(tUp),
            avgDownloadBps = globalDownMeter.average(tDown),
            peakUploadBps = globalUpMeter.peakBps,
            peakDownloadBps = globalDownMeter.peakBps,
            activeConnections = activeConnections.get(),
            throttledConnections = throttledConnections.get(),
            blockedConnections = blockedConnections.get(),
            dnsQueries = dnsQueries.get(),
            avgConnectLatencyMillis = if (latencyCount > 0) connectLatencySum.get() / latencyCount else 0,
            sessionStartMillis = sessionStart,
            lastResetMillis = lastReset,
            perApp = apps,
        )
        _snapshot.value = snap
        return snap
    }

    /** Snapshot the cumulative per-app totals for durable persistence. */
    fun checkpoint(): List<AppUsageRecord> = perUid.entries.map { (uid, c) ->
        AppUsageRecord(
            uid = uid,
            packageName = appInfo.packagesForUid(uid).firstOrNull().orEmpty(),
            totalUpload = c.up.get(),
            totalDownload = c.down.get(),
            lastActiveMillis = c.lastActiveMillis,
        )
    }

    suspend fun reset() {
        perUid.clear()
        totalUp.set(0); totalDown.set(0)
        throttledConnections.set(0); blockedConnections.set(0); dnsQueries.set(0)
        connectLatencySum.set(0); connectLatencyCount.set(0)
        globalUpMeter.reset(); globalDownMeter.reset()
        lastReset = clock()
        sessionStart = clock()
        statsRepository.reset()
        _snapshot.value = StatsSnapshot(sessionStartMillis = sessionStart, lastResetMillis = lastReset)
    }

    companion object {
        private const val ACTIVE_WINDOW_MILLIS = 5_000L
    }
}
