package dev.netvalve.network

/**
 * Creates the build-selected [PacketPipeline]. A factory (not a direct
 * injection) because a fresh pipeline is created per tunnel session. The
 * concrete factory is provided by whichever engine source set is active
 * (loopback vs netstack) — see app/build.gradle.kts.
 */
interface PacketPipelineFactory {
    fun create(): PacketPipeline
}
