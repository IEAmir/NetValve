package dev.netvalve.data.model

import kotlinx.serialization.Serializable

/**
 * Units the UI offers for expressing a cap. The conversion factors are fixed and
 * documented so tests can assert them exactly:
 *
 *  - bit rates use SI (decimal) factors, matching how ISPs and speed tests quote
 *    bandwidth (1 kbps = 1000 bits/s);
 *  - byte rates use binary (IEC) factors, matching how file managers display
 *    throughput (1 KB/s = 1024 bytes/s).
 */
enum class BandwidthUnit(val label: String) {
    KBPS("kbps") {
        override fun toBytesPerSecond(value: Long): Long = value * 1_000L / 8L
    },
    MBPS("Mbps") {
        override fun toBytesPerSecond(value: Long): Long = value * 1_000_000L / 8L
    },
    KB_S("KB/s") {
        override fun toBytesPerSecond(value: Long): Long = value * 1024L
    },
    MB_S("MB/s") {
        override fun toBytesPerSecond(value: Long): Long = value * 1024L * 1024L
    };

    abstract fun toBytesPerSecond(value: Long): Long
}

/**
 * A single directional cap. [enabled] mirrors the "enable/disable this cap"
 * toggle in the UI; a disabled or non-positive cap means "unlimited".
 */
@Serializable
data class BandwidthLimit(
    val value: Long = 0,
    val unit: BandwidthUnit = BandwidthUnit.MBPS,
    val enabled: Boolean = false,
) {
    val isUnlimited: Boolean get() = !enabled || value <= 0

    /** @return sustained rate in bytes/second, or `null` when unlimited. */
    fun bytesPerSecondOrNull(): Long? = if (isUnlimited) null else unit.toBytesPerSecond(value)

    companion object {
        val Unlimited = BandwidthLimit(value = 0, unit = BandwidthUnit.MBPS, enabled = false)

        /** Convenience for tests / defaults: an enabled cap in bytes/second. */
        fun ofBytesPerSecond(bytesPerSec: Long): BandwidthLimit {
            // Choose the friendliest unit for display without losing precision.
            return when {
                bytesPerSec % (1024L * 1024L) == 0L ->
                    BandwidthLimit(bytesPerSec / (1024L * 1024L), BandwidthUnit.MB_S, true)
                else -> BandwidthLimit(bytesPerSec / 1024L, BandwidthUnit.KB_S, true)
            }
        }
    }
}
