package dev.netvalve.network.netstack

import dev.netvalve.data.model.TransportProtocol
import dev.netvalve.network.DatagramStream
import dev.netvalve.network.FlowContext
import dev.netvalve.network.FlowHandler
import dev.netvalve.network.FlowStream
import dev.netvalve.network.PacketPipeline
import dev.netvalve.network.TunnelConfig
import java.net.InetAddress

// gomobile-generated bindings from ./netstack (built into app/libs/netstack.aar).
// Package = <-javapkg>.<go package> = dev.netvalve.bridge. gomobile lower-cases
// the first letter of funcs/methods; if you change the Go API, re-verify these
// names against the AAR's classes.jar.
import dev.netvalve.bridge.Bridge
import dev.netvalve.bridge.Handler
import dev.netvalve.bridge.TCPConn
import dev.netvalve.bridge.Tunnel
import dev.netvalve.bridge.UDPConn

/**
 * PRODUCTION engine. A thin adapter over the gVisor **netstack** bridge: the Go
 * side owns TCP/IP correctness on the TUN and, for each accepted flow, calls back
 * into [Handler] (implemented here) with the original 4-tuple plus a byte-stream
 * handle. This class translates those into [FlowContext] + [FlowStream]/
 * [DatagramStream] and hands them to the [FlowSupervisor] — which dials the
 * protected upstream socket and inserts the token bucket. No TCP/IP is
 * implemented in Kotlin; we only shuttle bytes and apply policy.
 */
class NetstackPacketPipeline(
    /** Sink for Go-side log lines (level 0..3 = DEBUG..ERROR). Surfaces netstack
     *  liveness/errors in the app's Logs screen + logcat — critical for diagnosis. */
    private val logSink: (Int, String) -> Unit = { _, _ -> },
) : PacketPipeline {

    @Volatile private var tunnel: Tunnel? = null
    @Volatile private var running = false

    override val isRunning: Boolean get() = running

    override fun start(tunFd: Int, config: TunnelConfig, handler: FlowHandler) {
        if (running) return
        val goHandler = BridgeHandler(handler, logSink)
        // NewTunnel takes ownership of the TUN fd and starts the netstack loop.
        tunnel = Bridge.newTunnel(tunFd.toLong(), config.mtu.toLong(), config.ipv6Mode.name, goHandler)
        running = true
    }

    override fun stop() {
        running = false
        runCatching { tunnel?.stop() }
        tunnel = null
    }

    /** Bridges Go netstack callbacks to the Kotlin [FlowHandler]. */
    private class BridgeHandler(
        private val handler: FlowHandler,
        private val logSink: (Int, String) -> Unit,
    ) : Handler {

        override fun handleTCP(srcIp: String, srcPort: Long, dstIp: String, dstPort: Long, conn: TCPConn) {
            val ctx = FlowContext(
                TransportProtocol.TCP,
                InetAddress.getByName(srcIp), srcPort.toInt(),
                InetAddress.getByName(dstIp), dstPort.toInt(),
            )
            handler.onTcpFlow(ctx, TcpConnStream(conn))
        }

        override fun handleUDP(srcIp: String, srcPort: Long, dstIp: String, dstPort: Long, conn: UDPConn) {
            val ctx = FlowContext(
                TransportProtocol.UDP,
                InetAddress.getByName(srcIp), srcPort.toInt(),
                InetAddress.getByName(dstIp), dstPort.toInt(),
            )
            handler.onUdpFlow(ctx, UdpConnStream(conn))
        }

        override fun log(level: Long, msg: String) = logSink(level.toInt(), msg)
    }

    /** [FlowStream] over a Go netstack TCP connection.
     *
     * gomobile maps Go `Read(p []byte) (int, error)` to `long read(byte[]) throws
     * Exception`: a non-nil error (including io.EOF) surfaces as a thrown
     * exception rather than a negative return. We translate EOF/closed to -1 so
     * the relay's `if (n < 0) break` contract holds. */
    private class TcpConnStream(private val conn: TCPConn) : FlowStream {
        override fun read(dst: ByteArray): Int =
            try { conn.read(dst).toInt() } catch (_: Exception) { -1 } // EOF/closed
        override fun write(src: ByteArray, off: Int, len: Int) {
            // gomobile write takes a full byte[]; slice when needed (usually off==0).
            if (off == 0 && len == src.size) conn.write(src) else conn.write(src.copyOfRange(off, off + len))
        }
        override fun close() { runCatching { conn.close() } }
    }

    /** [DatagramStream] over a Go netstack UDP association. */
    private class UdpConnStream(private val conn: UDPConn) : DatagramStream {
        override fun receive(): ByteArray? = runCatching { conn.receive() }.getOrNull()
        override fun send(data: ByteArray) { runCatching { conn.send(data) } }
        override fun close() { runCatching { conn.close() } }
    }
}
