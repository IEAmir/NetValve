package dev.netvalve.throttle

/**
 * A bounded, byte-accounted FIFO used to *smooth* (pace) UDP datagrams rather
 * than drop them. Datagrams arriving from netstack are enqueued here; a drain
 * coroutine dequeues them, waits on the token bucket, then forwards — so bursts
 * are spread over time instead of discarded.
 *
 * Dropping is the explicit last resort: only when the queue is already holding
 * [capacityBytes] worth of backlog (the app is sustainably exceeding its cap)
 * does [offer] discard, per [dropPolicy]. Every drop is counted so it is visible
 * in stats/logs. This protects latency-sensitive apps (VoIP/gaming) under normal
 * conditions while bounding memory under abuse.
 *
 * Not internally coupled to coroutines, so it is unit-testable in isolation.
 */
class PacingQueue<T>(
    val capacityBytes: Long,
    val dropPolicy: DropPolicy = DropPolicy.DROP_NEWEST,
) {
    enum class DropPolicy { DROP_NEWEST, DROP_OLDEST }

    private data class Node<T>(val item: T, val size: Long)

    private val deque = ArrayDeque<Node<T>>()
    private var bytesQueued = 0L

    var droppedCount = 0L
        private set
    var droppedBytes = 0L
        private set

    val queuedBytes: Long get() = synchronized(this) { bytesQueued }
    val size: Int get() = synchronized(this) { deque.size }

    /**
     * Enqueue [item] of [size] bytes.
     * @return true if accepted, false if it (or a displaced item) had to be dropped.
     */
    fun offer(item: T, size: Long): Boolean = synchronized(this) {
        if (size > capacityBytes) {
            // A single datagram larger than the whole budget can never be paced.
            droppedCount++; droppedBytes += size
            return false
        }
        when (dropPolicy) {
            DropPolicy.DROP_NEWEST -> {
                if (bytesQueued + size > capacityBytes) {
                    droppedCount++; droppedBytes += size
                    return false
                }
            }
            DropPolicy.DROP_OLDEST -> {
                while (bytesQueued + size > capacityBytes && deque.isNotEmpty()) {
                    val old = deque.removeFirst()
                    bytesQueued -= old.size
                    droppedCount++; droppedBytes += old.size
                }
            }
        }
        deque.addLast(Node(item, size))
        bytesQueued += size
        return true
    }

    fun poll(): T? = synchronized(this) {
        val node = deque.removeFirstOrNull() ?: return null
        bytesQueued -= node.size
        node.item
    }

    fun clear() = synchronized(this) {
        deque.clear(); bytesQueued = 0
    }
}
