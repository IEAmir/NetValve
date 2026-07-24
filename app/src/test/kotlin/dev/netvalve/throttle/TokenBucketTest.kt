package dev.netvalve.throttle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {

    private class FakeClock(var now: Long = 0L) { fun get(): Long = now }

    @Test
    fun unlimitedBucketNeverWaits() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacityBytes = 1000, rateBytesPerSec = 0, clock = clock::get)
        assertEquals(0L, bucket.reserveNanos(1_000_000))
        assertEquals(0L, bucket.reserveNanos(9_999_999))
    }

    @Test
    fun fullBucketAllowsBurstThenChargesForDebt() {
        val clock = FakeClock()
        // 1000 B capacity, 1000 B/s.
        val bucket = TokenBucket(capacityBytes = 1000, rateBytesPerSec = 1000, clock = clock::get)

        // Spend the full burst immediately.
        assertEquals(0L, bucket.reserveNanos(1000))

        // Next 1000 bytes must wait exactly 1 second (1000 B / 1000 B/s).
        val wait = bucket.reserveNanos(1000)
        assertEquals(1_000_000_000L, wait)
    }

    @Test
    fun lazyRefillAccruesOverElapsedTime() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacityBytes = 1000, rateBytesPerSec = 1000, clock = clock::get)
        bucket.reserveNanos(1000) // drain to 0

        // Advance half a second -> 500 bytes accrue.
        clock.now += 500_000_000L
        assertEquals(0L, bucket.reserveNanos(500))
        // Now empty again; another 500 costs 0.5s.
        assertEquals(500_000_000L, bucket.reserveNanos(500))
    }

    @Test
    fun refillIsCappedAtCapacity() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacityBytes = 1000, rateBytesPerSec = 1000, clock = clock::get)
        bucket.reserveNanos(1000) // empty
        clock.now += 10_000_000_000L // 10s -> would be 10000 tokens, capped at 1000
        assertTrue(bucket.availableTokens() <= 1000)
        // Only up to capacity is immediately available.
        assertEquals(0L, bucket.reserveNanos(1000))
        assertTrue(bucket.reserveNanos(1) > 0)
    }

    /**
     * Regression guard for the "throttling stalls traffic to zero" report: driving
     * the bucket through the exact pace() loop (ceil-to-ms waits) must yield a
     * sustained throughput close to the configured rate — never zero.
     */
    @Test
    fun sustainedThroughputMatchesRateNotZero() {
        var now = 0L
        val rate = 125_000L // 1 Mbps
        val burst = 65_536L
        val chunk = 16_384L
        val bucket = TokenBucket(burst, rate, clock = { now })

        var sent = 0L
        val horizon = 10_000_000_000L // 10 virtual seconds
        var guard = 0
        while (now < horizon && guard++ < 1_000_000) {
            val w = bucket.reserveNanos(chunk)
            if (w > 0) now += ((w + 999_999) / 1_000_000) * 1_000_000 // ceil to ms, like pace()
            sent += chunk
        }
        val achieved = sent * 1_000_000_000.0 / now
        assertTrue("throughput must be ~rate, not zero (achieved=$achieved)", achieved >= rate * 0.9)
        assertTrue("throughput must not wildly exceed rate (achieved=$achieved)", achieved <= rate * 1.2)
    }

    @Test
    fun updateRateChangesFuturePacing() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacityBytes = 1000, rateBytesPerSec = 1000, clock = clock::get)
        bucket.reserveNanos(1000) // empty
        bucket.updateRate(2000, 2000) // double the rate
        // 1000 bytes at 2000 B/s => 0.5s.
        assertEquals(500_000_000L, bucket.reserveNanos(1000))
    }
}
