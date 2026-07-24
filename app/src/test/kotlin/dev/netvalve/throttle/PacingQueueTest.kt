package dev.netvalve.throttle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingQueueTest {

    @Test
    fun fifoOrderPreserved() {
        val q = PacingQueue<Int>(capacityBytes = 1000)
        assertTrue(q.offer(1, 100))
        assertTrue(q.offer(2, 100))
        assertTrue(q.offer(3, 100))
        assertEquals(1, q.poll())
        assertEquals(2, q.poll())
        assertEquals(3, q.poll())
        assertNull(q.poll())
    }

    @Test
    fun dropNewestRejectsOnOverflow() {
        val q = PacingQueue<Int>(capacityBytes = 100, dropPolicy = PacingQueue.DropPolicy.DROP_NEWEST)
        assertTrue(q.offer(1, 60))
        assertFalse(q.offer(2, 60)) // 60+60 > 100 -> reject the new one
        assertEquals(1L, q.droppedCount)
        assertEquals(1, q.poll()) // original retained
        assertNull(q.poll())
    }

    @Test
    fun dropOldestEvictsToMakeRoom() {
        val q = PacingQueue<Int>(capacityBytes = 100, dropPolicy = PacingQueue.DropPolicy.DROP_OLDEST)
        assertTrue(q.offer(1, 60))
        assertTrue(q.offer(2, 60)) // evicts #1, keeps #2
        assertEquals(1L, q.droppedCount)
        assertEquals(2, q.poll())
        assertNull(q.poll())
    }

    @Test
    fun oversizeItemAlwaysDropped() {
        val q = PacingQueue<Int>(capacityBytes = 100)
        assertFalse(q.offer(1, 200))
        assertEquals(1L, q.droppedCount)
        assertEquals(200L, q.droppedBytes)
        assertEquals(0, q.size)
    }

    @Test
    fun byteAccountingTracksQueueDepth() {
        val q = PacingQueue<Int>(capacityBytes = 1000)
        q.offer(1, 300); q.offer(2, 200)
        assertEquals(500L, q.queuedBytes)
        q.poll()
        assertEquals(200L, q.queuedBytes)
    }
}
