package dev.netvalve.network

import dev.netvalve.data.model.Ipv6Mode
import dev.netvalve.data.model.TransportProtocol
import java.net.InetAddress

/**
 * Immutable identity of a single L4 flow, as observed at the TUN. Used for
 * attribution, logging and connection keys.
 */
data class FlowContext(
    val protocol: TransportProtocol,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destinationAddress: InetAddress,
    val destinationPort: Int,
) {
    val isIpv6: Boolean get() = destinationAddress.address.size == 16
    val isDns: Boolean get() = destinationPort == 53 || destinationPort == 853
    fun shortKey(): String =
        "${protocol.name}:${sourceAddress.hostAddress}:$sourcePort>${destinationAddress.hostAddress}:$destinationPort"
}

/**
 * Everything the packet engine needs to build/route the tunnel. The engine owns
 * neither app-selection nor policy; it is handed the fully-resolved runtime knobs.
 */
data class TunnelConfig(
    val mtu: Int = 1500,
    val ipv4Address: String = "10.111.0.2",
    val ipv4Prefix: Int = 32,
    val ipv6Address: String = "fd00:6e76:6376::2",
    val ipv6Prefix: Int = 128,
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val ipv6Mode: Ipv6Mode = Ipv6Mode.RELAY,
    val exemptDns: Boolean = true,
    val protector: SocketProtector,
)

/**
 * Protects an upstream socket from being routed back through our own VPN (the
 * framework's VpnService.protect). We pass `java.net` socket types — which are
 * NOT Android types — so the network layer stays framework-free while avoiding
 * fragile file-descriptor reflection. The VpnService implementation simply
 * forwards to its protect(Socket)/protect(DatagramSocket) overloads.
 *
 * Sockets must be protected *before* connecting, so the connection's own packets
 * do not re-enter the TUN and loop.
 */
interface SocketProtector {
    fun protect(socket: java.net.Socket): Boolean
    fun protect(socket: java.net.DatagramSocket): Boolean
}

/**
 * The TUN side of a TCP flow, surfaced by the engine as a blocking byte stream.
 * (gVisor netstack terminates the TCP connection and hands us these bytes.)
 * Blocking by design; the relay runs it on an IO dispatcher.
 */
interface FlowStream {
    /** Blocking read; returns number of bytes, or -1 at end of stream. */
    fun read(dst: ByteArray): Int
    /** Blocking write of [len] bytes from [off]. */
    fun write(src: ByteArray, off: Int, len: Int)
    fun close()
}

/** The TUN side of a UDP flow: whole datagrams to/from the app. */
interface DatagramStream {
    /** Next datagram from the app, or null once closed. */
    fun receive(): ByteArray?
    fun send(data: ByteArray)
    fun close()
}

/**
 * The engine calls back into the app for every new flow it accepts on the TUN.
 * The implementation ([FlowSupervisor]) attributes, applies policy, and relays.
 * Handlers must return promptly; long-running relay work is launched onto the
 * engine's coroutine scope by the supervisor.
 */
interface FlowHandler {
    fun onTcpFlow(ctx: FlowContext, appSide: FlowStream)
    fun onUdpFlow(ctx: FlowContext, appSide: DatagramStream)
}
