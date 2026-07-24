package dev.netvalve.network

import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import dev.netvalve.stats.StatsCollector
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Creates and owns the *upstream* sockets that carry a flow to the real
 * internet. Every socket is [protected][SocketProtector] before connecting so
 * its packets bypass our own TUN. TCP connect latency is measured and fed to
 * [StatsCollector].
 *
 * These upstream sockets are the second half of the throttling pipeline: the
 * relay writes to [TcpUpstream.write] / [UdpUpstream.send] only after the token
 * bucket has granted allowance (see ThrottleManager / FlowSupervisor).
 */
class ConnectionManager(
    private val stats: StatsCollector,
    private val logger: Logger,
    private val connectTimeoutMillis: Int = 10_000,
) {
    fun connectTcp(ctx: FlowContext, protector: SocketProtector): TcpUpstream {
        val socket = Socket()
        // Protect BEFORE connect so the SYN is not routed back into the tunnel.
        if (!protector.protect(socket)) {
            logger.w(LogCategory.ERROR, "protect() failed for ${ctx.shortKey()}")
        }
        val started = System.nanoTime()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(ctx.destinationAddress, ctx.destinationPort), connectTimeoutMillis)
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        stats.recordConnectLatency(latencyMs)
        return TcpUpstream(socket)
    }

    fun openUdp(ctx: FlowContext, protector: SocketProtector): UdpUpstream {
        val socket = DatagramSocket()
        if (!protector.protect(socket)) {
            logger.w(LogCategory.ERROR, "protect() failed for ${ctx.shortKey()}")
        }
        socket.connect(InetSocketAddress(ctx.destinationAddress, ctx.destinationPort))
        return UdpUpstream(socket)
    }
}

/** Blocking TCP upstream. Reads/writes are done on an IO dispatcher by the relay. */
class TcpUpstream(private val socket: Socket) {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    fun read(dst: ByteArray): Int = input.read(dst)

    fun write(src: ByteArray, off: Int, len: Int) {
        output.write(src, off, len)
        output.flush()
    }

    fun close() {
        runCatching { socket.close() }
    }
}

/** Blocking UDP upstream, connected to the flow's destination. */
class UdpUpstream(private val socket: DatagramSocket) {
    fun send(data: ByteArray) {
        socket.send(DatagramPacket(data, data.size))
    }

    /** @return payload bytes received, or null on timeout/close. */
    fun receive(bufferSize: Int = 64 * 1024): ByteArray? {
        val buf = ByteArray(bufferSize)
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            socket.receive(pkt)
            buf.copyOf(pkt.length)
        } catch (_: Throwable) {
            null
        }
    }

    fun setTimeout(millis: Int) { socket.soTimeout = millis }

    fun close() {
        runCatching { socket.close() }
    }
}
