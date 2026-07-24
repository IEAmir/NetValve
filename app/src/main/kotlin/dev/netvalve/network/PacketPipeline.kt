package dev.netvalve.network

/**
 * The packet engine boundary. This is the single seam that separates all of
 * NetValve's product logic (Kotlin: rules, throttling, stats, UI) from the
 * protocol machinery (TCP/IP state, checksums, reassembly).
 *
 * ### Why this interface exists
 * The requirements mandate a *mature* networking stack rather than a hand-rolled
 * TCP/IP implementation. The production engine ([netstack] source set) is a thin
 * Kotlin adapter over a gVisor **netstack** library compiled to an AAR with
 * gomobile — the same approach used by well-known non-root apps (Rethink, Intra).
 * A second, pure-Kotlin implementation ([loopback] source set) satisfies this
 * interface for development, CI and instrumentation without the native toolchain
 * (it accounts/attributes outbound packets but does not forward — see
 * docs/LIMITATIONS.md).
 *
 * Both implementations are chosen at build time via the `netvalve.netstack`
 * Gradle flag, so the rest of the app depends only on this interface.
 */
interface PacketPipeline {
    val isRunning: Boolean

    /**
     * Begin processing the TUN. [tunFd] is the file descriptor from
     * VpnService.Builder.establish(); ownership of reading/writing it belongs to
     * the engine. New flows are delivered to [handler].
     */
    fun start(tunFd: Int, config: TunnelConfig, handler: FlowHandler)

    /** Stop processing and release the TUN + all engine resources. Idempotent. */
    fun stop()
}
