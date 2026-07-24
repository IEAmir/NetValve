package dev.netvalve.throttle

import kotlin.math.ceil
import kotlin.math.min

/**
 * A lazy-refill token bucket used to shape one direction of one app's traffic.
 *
 * ### Why a token bucket
 * A token bucket meters a *sustained* rate while tolerating short bursts up to
 * [capacityBytes]. Tokens (= bytes of allowance) accrue continuously at
 * [rate][updateRate] bytes/second and are spent as data passes. "Lazy refill"
 * means we never run a timer: we compute how many tokens *would* have accrued
 * since the last operation, using a monotonic clock, only when asked. That makes
 * it allocation-free, timer-free and cheap — critical on the hot path.
 *
 * ### Pacing, not dropping
 * Callers do not poll/spin. They call [reserveNanos], which subtracts the
 * requested bytes (allowing the balance to go *negative* — i.e. take on debt)
 * and returns how long to wait for that debt to be repaid at the current rate.
 * The caller then simply suspends for that long (see [ThrottleManager.pace]).
 * Because the relay suspends instead of dropping, TCP flow-control naturally
 * slows the sender and UDP is paced smoothly. Nothing is discarded here.
 *
 * Thread-safe: all mutable state is guarded by [lock].
 *
 * @param clock monotonic nanosecond source, injected for deterministic tests.
 */
class TokenBucket(
    capacityBytes: Long,
    rateBytesPerSec: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private val lock = Any()

    /** Upper bound on accrued allowance (burst size), in bytes. */
    private var capacity: Double = capacityBytes.coerceAtLeast(1).toDouble()

    /** Sustained rate in bytes/second. <= 0 is treated as "unlimited". */
    private var rate: Double = rateBytesPerSec.toDouble()

    /** Current allowance; may be negative to represent reserved-but-not-yet-earned debt. */
    private var tokens: Double = capacity

    private var lastNanos: Long = clock()

    /** Bytes added by the most recent refill — exposed purely for debug logging. */
    @Volatile private var lastRefillBytes: Double = 0.0

    val ratePerSecond: Long get() = synchronized(lock) { rate.toLong() }

    // ---- Introspection for instrumentation (see ThrottleManager debug logs) ----
    val configuredRate: Long get() = synchronized(lock) { rate.toLong() }
    val configuredBurst: Long get() = synchronized(lock) { capacity.toLong() }
    val lastRefillAmount: Long get() = lastRefillBytes.toLong()

    /**
     * Reserve [bytes] of allowance.
     * @return nanoseconds the caller should wait before sending; 0 if the
     *         allowance was already available. Unlimited buckets always return 0.
     */
    fun reserveNanos(bytes: Long): Long = synchronized(lock) {
        if (rate <= 0.0) return 0L // unlimited
        refillLocked()
        tokens -= bytes
        if (tokens >= 0.0) return 0L
        val deficit = -tokens
        // seconds = deficit / rate; convert to nanos, rounding up.
        return ceil(deficit / rate * 1_000_000_000.0).toLong().coerceAtLeast(0L)
    }

    /**
     * Change the sustained rate (and optionally the burst capacity) live, e.g.
     * when a rule changes while a flow is active. Accrued debt/allowance is
     * preserved; the balance is only clamped to the new capacity ceiling.
     */
    fun updateRate(newRateBytesPerSec: Long, newCapacityBytes: Long = -1) = synchronized(lock) {
        refillLocked()
        rate = newRateBytesPerSec.toDouble()
        if (newCapacityBytes > 0) {
            capacity = newCapacityBytes.toDouble()
            if (tokens > capacity) tokens = capacity
        }
    }

    /** For tests/introspection. */
    fun availableTokens(): Long = synchronized(lock) {
        refillLocked(); tokens.toLong()
    }

    private fun refillLocked() {
        val now = clock()
        val dt = now - lastNanos
        if (dt <= 0) { lastRefillBytes = 0.0; return }
        lastNanos = now
        if (rate <= 0.0) { lastRefillBytes = 0.0; return }
        val accrued = dt / 1_000_000_000.0 * rate
        val before = tokens
        tokens = min(capacity, tokens + accrued)
        lastRefillBytes = tokens - before
    }
}
