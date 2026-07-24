package dev.netvalve.log

import dev.netvalve.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Default [Logger]. Two sinks:
 *  1. an in-memory ring buffer (last [ringCapacity] events) exposed as a flow
 *     for the live viewer;
 *  2. batched, bounded persistence to Room via [LogRepository].
 *
 * Hot-path safety:
 *  - a line below the current minimum level costs one volatile read and returns;
 *  - DEBUG/INFO lines in high-frequency categories are rate-limited per
 *    (category,uid) so a busy connection cannot flood the log or the DB writer;
 *  - persistence is offloaded to a background coroutine draining a conflated-ish
 *    channel, so `log()` never blocks the caller on I/O.
 */
class NetValveLogger(
    private val repository: LogRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ringCapacity: Int = 500,
    private val maxPersistedRows: Int = 5_000,
) : Logger {

    @Volatile private var minLevel: LogLevel = LogLevel.INFO

    private val ring = ArrayDeque<LogEvent>(ringCapacity)
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    override val events: Flow<List<LogEvent>> = _events.asStateFlow()

    private val writeChannel = Channel<LogEvent>(capacity = 1024)
    private val lastEmittedNanos = ConcurrentHashMap<Long, Long>()
    private val insertedSinceTrim = AtomicLong(0)

    init {
        // Background persistence: batch drains to reduce DB churn.
        scope.launch {
            val batch = ArrayList<LogEvent>(64)
            for (event in writeChannel) {
                batch.add(event)
                // Opportunistically coalesce anything already queued.
                while (true) {
                    val next = writeChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                    if (batch.size >= 200) break
                }
                runCatching { repository.insert(batch) }
                if (insertedSinceTrim.addAndGet(batch.size.toLong()) >= 1_000) {
                    insertedSinceTrim.set(0)
                    runCatching { repository.trimTo(maxPersistedRows) }
                }
                batch.clear()
            }
        }
    }

    override fun setMinLevel(level: LogLevel) { minLevel = level }

    override fun isEnabled(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    override fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        uid: Int?,
        packageName: String?,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        if (isRateLimited(level, category, uid)) return

        val event = LogEvent(clock(), level, category, message, uid, packageName)

        synchronized(ring) {
            if (ring.size >= ringCapacity) ring.removeFirst()
            ring.addLast(event)
            _events.value = ring.toList()
        }
        // Non-blocking hand-off to the persistence coroutine.
        writeChannel.trySend(event)
    }

    override suspend fun exportText(): String {
        val header = "NetValve log export — ${java.time.Instant.ofEpochMilli(clock())}\n" +
            "level\ttime\tcategory\tuid\tpackage\tmessage\n"
        return buildString {
            append(header)
            for (e in repository.all()) {
                append(e.level.name).append('\t')
                append(java.time.Instant.ofEpochMilli(e.timestampMillis)).append('\t')
                append(e.category.name).append('\t')
                append(e.uid?.toString() ?: "-").append('\t')
                append(e.packageName ?: "-").append('\t')
                append(e.message).append('\n')
            }
        }
    }

    /** Suppress bursts of low-severity, high-frequency lines. WARN/ERROR always pass. */
    private fun isRateLimited(level: LogLevel, category: LogCategory, uid: Int?): Boolean {
        if (level.ordinal >= LogLevel.WARNING.ordinal) return false
        val hot = category == LogCategory.THROTTLE ||
            category == LogCategory.CONNECTION_OPEN ||
            category == LogCategory.CONNECTION_CLOSE ||
            category == LogCategory.DNS
        if (!hot) return false
        val key = (category.ordinal.toLong() shl 40) or ((uid ?: -1).toLong() and 0xFFFFFFFFL)
        val now = System.nanoTime()
        val last = lastEmittedNanos.put(key, now)
        return last != null && (now - last) < MIN_INTERVAL_NANOS
    }

    companion object {
        private const val MIN_INTERVAL_NANOS = 250_000_000L // 250 ms
    }
}
