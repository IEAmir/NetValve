package dev.netvalve.network.loopback

import android.os.ParcelFileDescriptor
import dev.netvalve.data.model.TransportProtocol
import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import dev.netvalve.network.FlowContext
import dev.netvalve.network.FlowHandler
import dev.netvalve.network.PacketPipeline
import dev.netvalve.network.TunnelConfig
import dev.netvalve.network.UidResolver
import dev.netvalve.stats.StatsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.InetAddress

/**
 * DEVELOPMENT / CI engine (default build; no native toolchain required).
 *
 * It reads packets from the TUN, parses IPv4 headers enough to attribute a UID
 * and account **outbound** bytes, applies block-by-drop, and logs — but it does
 * NOT forward traffic upstream (that requires the netstack engine). So with this
 * engine, controlled apps have no connectivity while the tunnel is up; it exists
 * to exercise VPN start/stop, app routing, attribution, stats and the UI without
 * building the Go AAR. See docs/LIMITATIONS.md.
 *
 * Production builds use `-Pnetvalve.netstack=true` (NetstackPacketPipeline).
 */
class LoopbackPacketPipeline(
    private val stats: StatsCollector,
    private val uidResolver: UidResolver,
    private val logger: Logger,
) : PacketPipeline {

    @Volatile private var running = false
    private var pfd: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null

    override val isRunning: Boolean get() = running

    override fun start(tunFd: Int, config: TunnelConfig, handler: FlowHandler) {
        if (running) return
        running = true
        logger.w(
            LogCategory.SYSTEM,
            "Loopback engine active: traffic is accounted + dropped, NOT forwarded. " +
                "Build with -Pnetvalve.netstack=true for real forwarding.",
        )
        val descriptor = ParcelFileDescriptor.adoptFd(tunFd)
        pfd = descriptor
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { readLoop(FileInputStream(descriptor.fileDescriptor), config.mtu) }
    }

    override fun stop() {
        running = false
        scope?.cancel()
        scope = null
        runCatching { pfd?.close() }
        pfd = null
    }

    private var droppedBytes = 0L
    private var droppedPackets = 0L
    private var lastWarnAtMillis = 0L

    private suspend fun readLoop(input: FileInputStream, mtu: Int) {
        val packet = ByteArray(mtu.coerceAtLeast(1500))
        while (scope?.isActive == true && running) {
            val n = try {
                input.read(packet)
            } catch (_: Throwable) {
                break // fd closed on stop()
            }
            if (n <= 0) continue
            parseAndAccount(packet, n)
            // Make the "no forwarding" reality visible: every packet read here is
            // dropped, not sent upstream. Warn periodically so a stalled app is
            // unambiguously attributed to the loopback engine, not to throttling.
            droppedBytes += n
            droppedPackets++
            val now = System.currentTimeMillis()
            if (now - lastWarnAtMillis > 5_000) {
                lastWarnAtMillis = now
                logger.w(
                    LogCategory.SYSTEM,
                    "Loopback engine dropped $droppedPackets pkts / $droppedBytes B (NOT forwarded). " +
                        "This is why controlled apps stall. Use the netstack engine for real traffic.",
                )
            }
        }
    }

    /** Minimal IPv4 parse: version/IHL, protocol, addresses, L4 ports. */
    private fun parseAndAccount(buf: ByteArray, len: Int) {
        if (len < 20) return
        val version = (buf[0].toInt() ushr 4) and 0xF
        if (version != 4) return // loopback stub handles IPv4 only
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl) return
        val protoByte = buf[9].toInt() and 0xFF
        val protocol = when (protoByte) {
            6 -> TransportProtocol.TCP
            17 -> TransportProtocol.UDP
            else -> TransportProtocol.OTHER
        }
        val src = InetAddress.getByAddress(buf.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(buf.copyOfRange(16, 20))
        var srcPort = 0
        var dstPort = 0
        if ((protocol == TransportProtocol.TCP || protocol == TransportProtocol.UDP) && len >= ihl + 4) {
            srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
            dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        }
        val ctx = FlowContext(protocol, src, srcPort, dst, dstPort)
        val uid = uidResolver.resolve(ctx)
        // Everything read from the TUN is app -> internet (upload).
        stats.recordUpload(uid, len.toLong())
        if (ctx.isDns) stats.onDnsQuery()
    }
}
