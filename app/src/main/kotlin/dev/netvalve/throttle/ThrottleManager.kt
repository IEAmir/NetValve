package dev.netvalve.throttle

import dev.netvalve.data.model.Direction
import dev.netvalve.log.LogCategory
import dev.netvalve.log.LogLevel
import dev.netvalve.log.Logger
import dev.netvalve.rules.EffectivePolicy
import dev.netvalve.rules.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Owns the per-app, per-direction [TokenBucket]s and exposes the single hot-path
 * primitive the relay uses: [pace].
 *
 * ### Where throttling happens (see docs/THROTTLING.md)
 * ```
 * UPLOAD  : app → TUN → netstack → relay → [pace(uid,UPLOAD)]  → upstream.write() → net
 * DOWNLOAD: net → upstream.read() → [pace(uid,DOWNLOAD)] → relay → netstack → TUN → app
 * ```
 * A bucket is created lazily per (uid, direction) at flow-open with the cap from
 * [RuleEngine.policyForUid]. When rules or device state change, [revision] fires
 * and every live bucket's rate is refreshed in place, so a cap edit takes effect
 * on in-flight connections without reconnecting.
 *
 * Unlimited directions get a `null` bucket, and [pace] on `null` is a no-op — so
 * an unshaped flow pays essentially zero overhead.
 */
class ThrottleManager(
    private val ruleEngine: RuleEngine,
    scope: CoroutineScope,
    private val logger: Logger? = null,
) {
    private val buckets = ConcurrentHashMap<Long, TokenBucket>()

    init {
        // Refresh live buckets whenever the resolved rule set changes.
        ruleEngine.revision
            .onEach { refreshAll() }
            .launchIn(scope)
    }

    private fun key(uid: Int, direction: Direction): Long =
        (uid.toLong() shl 1) or (if (direction == Direction.DOWNLOAD) 1L else 0L)

    private fun capFor(policy: EffectivePolicy, direction: Direction): Long? =
        if (direction == Direction.DOWNLOAD) policy.downloadBytesPerSec else policy.uploadBytesPerSec

    /**
     * @return the bucket to use for this flow's [direction], or null if that
     *         direction is unlimited under the current policy.
     */
    fun bucketFor(uid: Int, direction: Direction): TokenBucket? {
        val cap = capFor(ruleEngine.policyForUid(uid), direction) ?: run {
            buckets.remove(key(uid, direction))
            return null
        }
        val k = key(uid, direction)
        val created = !buckets.containsKey(k)
        val bucket = buckets.getOrPut(k) { TokenBucket(capacityBytes = burstFor(cap), rateBytesPerSec = cap) }
        bucket.updateRate(cap, burstFor(cap))
        if (created) {
            logger?.i(
                LogCategory.THROTTLE,
                "bucket created dir=$direction rate=$cap B/s burst=${burstFor(cap)} B",
                uid = uid,
            )
        }
        return bucket
    }

    /**
     * Suspend until [bytes] of allowance are available on [bucket], then return.
     * A null bucket returns immediately (unlimited). Never drops.
     *
     * The single wait is clamped to [MAX_PACE_NANOS] so a pathological (tiny) cap
     * can never look like a permanent stall — item 7: pacing never blocks
     * indefinitely. For realistic caps the clamp never triggers (waits < 150 ms).
     */
    suspend fun pace(bucket: TokenBucket?, bytes: Long) {
        if (bucket == null || bytes <= 0) return
        val rawWait = bucket.reserveNanos(bytes)
        val waitNanos = min(rawWait, MAX_PACE_NANOS)

        if (logger?.isEnabled(LogLevel.DEBUG) == true) {
            logger.d(
                LogCategory.THROTTLE,
                "pace requested=$bytes rate=${bucket.configuredRate} burst=${bucket.configuredBurst} " +
                    "tokens=${bucket.availableTokens()} refill=${bucket.lastRefillAmount} " +
                    "waitNs=$rawWait${if (rawWait > MAX_PACE_NANOS) " (clamped→$waitNanos)" else ""}",
            )
        }
        if (rawWait > MAX_PACE_NANOS) {
            logger?.w(
                LogCategory.THROTTLE,
                "pace wait ${rawWait / 1_000_000} ms clamped to ${MAX_PACE_NANOS / 1_000_000} ms " +
                    "(cap too low for ${bytes}B chunk; throughput will exceed the cap)",
            )
        }
        if (waitNanos > 0) {
            // Round up to the next millisecond; sub-ms waits collapse to ~1 ms.
            delay((waitNanos + 999_999) / 1_000_000)
        }
    }

    fun releaseFlow(uid: Int) {
        // Buckets are shared per-uid across a uid's flows, so we keep them until
        // a rule refresh prunes unlimited ones. Nothing to do per-flow here.
    }

    private fun refreshAll() {
        // Recompute the rate of every live bucket from the current policy.
        buckets.forEach { (k, bucket) ->
            val uid = (k ushr 1).toInt()
            val direction = if (k and 1L == 1L) Direction.DOWNLOAD else Direction.UPLOAD
            val cap = capFor(ruleEngine.policyForUid(uid), direction)
            if (cap == null) {
                buckets.remove(k)
            } else {
                bucket.updateRate(cap, burstFor(cap))
            }
        }
    }

    companion object {
        /** ~250 ms of burst, floored so a full app-layer write never deadlocks. */
        fun burstFor(rateBytesPerSec: Long): Long =
            (rateBytesPerSec / 4).coerceAtLeast(64 * 1024)

        /** Upper bound on a single pace sleep (item 7: never block indefinitely). */
        const val MAX_PACE_NANOS: Long = 2_000_000_000L // 2 s
    }
}
