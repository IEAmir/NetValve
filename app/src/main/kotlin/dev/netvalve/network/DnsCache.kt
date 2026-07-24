package dev.netvalve.network

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps resolved IP addresses back to the hostname the app asked for, by
 * observing DNS answers as they pass through the tunnel. This is not used to
 * shape traffic in Stage 1, but it is the substrate for future modules
 * (domain filtering, per-domain quotas, parental control) — which is exactly
 * why it lives behind a small, stable surface now.
 *
 * Bounded (LRU-ish by insertion time) so it cannot grow without limit.
 */
class DnsCache(private val maxEntries: Int = 4_096) {
    private data class Entry(val hostname: String, val insertedAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun record(ip: InetAddress, hostname: String) {
        if (map.size >= maxEntries) evictOldest()
        map[ip.hostAddress ?: return] = Entry(hostname, System.currentTimeMillis())
    }

    fun hostnameFor(ip: InetAddress): String? = map[ip.hostAddress]?.hostname

    fun clear() = map.clear()

    private fun evictOldest() {
        // Cheap approximate eviction: drop ~10% oldest by insertion time.
        val threshold = map.values.map { it.insertedAt }.sorted()
            .getOrNull(maxEntries / 10) ?: return
        map.entries.removeIf { it.value.insertedAt <= threshold }
    }
}
