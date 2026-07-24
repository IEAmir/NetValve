package dev.netvalve.utils

import java.util.Locale

/** Formatting helpers shared by the UI and the notification. Pure + testable. */
object Format {

    /** Human byte count, binary units: 1536 -> "1.5 KB". */
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = value.toDouble()
        var i = -1
        do { v /= 1024.0; i++ } while (v >= 1024.0 && i < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** Human throughput: bytes/second -> "1.5 MB/s". */
    fun rate(bytesPerSec: Long): String = "${bytes(bytesPerSec)}/s"

    /** Compact duration: 3725_000 -> "1h 2m". */
    fun duration(millis: Long): String {
        if (millis <= 0) return "0s"
        val s = millis / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }
}
