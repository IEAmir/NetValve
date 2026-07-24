package dev.netvalve.network

import dev.netvalve.data.model.TransportProtocol
import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The raw kernel lookup, abstracted so the resolver's caching + fallback logic
 * is unit-testable without Android. The Android implementation delegates to
 * `ConnectivityManager.getConnectionOwnerUid` (API 29+).
 */
fun interface ConnectionOwnerLookup {
    /** @return owning UID, or a value <= 0 when unknown. May throw. */
    fun ownerUid(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int
}

/**
 * Attributes a flow to an app UID, robustly.
 *
 * `getConnectionOwnerUid` is the only supported non-root attribution API, but it
 * is racy (the socket may not be registered yet), it returns -1 for some flows,
 * and a few OEM ROMs throw or mis-report. This resolver therefore layers:
 *
 *  1. **Cache** — successful lookups are memoised per 4-tuple for a short TTL,
 *     which also absorbs the per-packet cost.
 *  2. **Primary lookup** — the kernel API, wrapped so an exception can never
 *     escape to the packet loop.
 *  3. **Single-app inference** — if the tunnel is currently controlling exactly
 *     one app, an unattributable flow is credited to it (documented heuristic).
 *  4. **Unknown bucket** — otherwise [UID_UNKNOWN], which the rest of the app
 *     treats as "apply the global default policy" and surfaces as *Unknown* in
 *     stats. Nothing crashes; traffic still flows.
 */
class UidResolver(
    private val lookup: ConnectionOwnerLookup,
    private val logger: Logger,
    private val singleControlledUidProvider: () -> Int? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = 15_000,
) {
    private data class Entry(val uid: Int, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    val failureCount = AtomicLong(0)

    fun resolve(ctx: FlowContext): Int {
        val key = ctx.shortKey()
        val now = clock()
        cache[key]?.let { if (it.expiresAt > now) return it.uid else cache.remove(key) }

        val protoInt = when (ctx.protocol) {
            TransportProtocol.TCP -> IPPROTO_TCP
            TransportProtocol.UDP -> IPPROTO_UDP
            else -> return UID_UNKNOWN
        }

        val raw = try {
            lookup.ownerUid(
                protoInt,
                InetSocketAddress(ctx.sourceAddress, ctx.sourcePort),
                InetSocketAddress(ctx.destinationAddress, ctx.destinationPort),
            )
        } catch (t: Throwable) {
            failureCount.incrementAndGet()
            // DEBUG so the logger's rate-limiter absorbs floods on flaky ROMs.
            logger.d(LogCategory.SYSTEM, "UID lookup threw for ${ctx.shortKey()}: ${t.message}")
            INVALID_UID
        }

        val resolved = when {
            raw > 0 -> raw
            else -> {
                val inferred = singleControlledUidProvider()
                if (inferred != null) {
                    logger.d(LogCategory.SYSTEM, "UID unattributable; inferring single controlled app $inferred")
                    inferred
                } else {
                    failureCount.incrementAndGet()
                    UID_UNKNOWN
                }
            }
        }

        // Only cache positive attributions; keep retrying the unknowns.
        if (resolved > 0) cache[key] = Entry(resolved, now + cacheTtlMillis)
        return resolved
    }

    fun invalidate() = cache.clear()

    companion object {
        const val IPPROTO_TCP = 6
        const val IPPROTO_UDP = 17

        /** Framework's "unknown" sentinel from getConnectionOwnerUid. */
        const val INVALID_UID = -1

        /** Our sentinel meaning "attributable to no known app" (default policy applies). */
        const val UID_UNKNOWN = -2
    }
}
