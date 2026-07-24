package dev.netvalve.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun bytesFormatting() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.5 KB", Format.bytes(1536))
        assertEquals("1.0 MB", Format.bytes(1024L * 1024L))
    }

    @Test
    fun rateFormatting() {
        assertEquals("1.0 MB/s", Format.rate(1024L * 1024L))
        assertEquals("0 B/s", Format.rate(0))
    }

    @Test
    fun durationFormatting() {
        assertEquals("0s", Format.duration(0))
        assertEquals("45s", Format.duration(45_000))
        assertEquals("2m 5s", Format.duration(125_000))
        assertEquals("1h 2m", Format.duration(3_720_000))
    }
}
