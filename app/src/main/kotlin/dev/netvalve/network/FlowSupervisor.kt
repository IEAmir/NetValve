package dev.netvalve.network

import dev.netvalve.data.model.Direction
import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import dev.netvalve.module.FlowOpenInput
import dev.netvalve.module.FlowVerdict
import dev.netvalve.module.ModuleChain
import dev.netvalve.rules.RuleEngine
import dev.netvalve.stats.StatsCollector
import dev.netvalve.throttle.PacingQueue
import dev.netvalve.throttle.ThrottleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Implements [FlowHandler]: for each flow the engine accepts, it attributes the
 * UID, asks the [ModuleChain] for a verdict, then either blocks or relays the
 * bytes to a protected upstream socket — inserting the token bucket exactly on
 * the write path in each direction (see docs/THROTTLING.md).
 *
 * All relay work runs on [scope] (an IO-dispatched SupervisorJob), so blocking
 * socket reads never touch the main thread and one failing flow cannot cancel
 * its siblings.
 */
class FlowSupervisor(
    private val scope: CoroutineScope,
    private val config: TunnelConfig,
    private val uidResolver: UidResolver,
    private val ruleEngine: RuleEngine,
    private val moduleChain: ModuleChain,
    private val throttleManager: ThrottleManager,
    private val connectionManager: ConnectionManager,
    private val stats: StatsCollector,
    private val logger: Logger,
    private val dnsCache: DnsCache,
    /** When true, new flows are relayed unshaped ("pause all"). */
    private val paused: () -> Boolean = { false },
) : FlowHandler {

    override fun onTcpFlow(ctx: FlowContext, appSide: FlowStream) {
        scope.launch { relayTcp(ctx, appSide) }
    }

    override fun onUdpFlow(ctx: FlowContext, appSide: DatagramStream) {
        scope.launch { relayUdp(ctx, appSide) }
    }

    // ---- TCP ---------------------------------------------------------------

    private suspend fun relayTcp(ctx: FlowContext, appSide: FlowStream) {
        val uid = uidResolver.resolve(ctx)
        val verdict = if (paused() || !ruleEngine.isControlled(uid)) {
            // Paused or not-controlled: relay unshaped so connectivity is preserved.
            FlowVerdict(blocked = false, downloadBytesPerSec = null, uploadBytesPerSec = null, warnThresholdPercent = null, tags = emptyMap())
        } else {
            moduleChain.evaluate(FlowOpenInput(ctx, uid))
        }

        stats.onFlowOpened(uid, throttled = verdict.isThrottled, blocked = verdict.blocked)

        if (verdict.blocked) {
            logger.i(LogCategory.BLOCK, "reset ${ctx.shortKey()}", uid = uid)
            appSide.close() // netstack turns this into a TCP RST
            moduleChain.onFlowClose(ctx, uid)
            return
        }

        val upstream = try {
            connectionManager.connectTcp(ctx, config.protector)
        } catch (t: Throwable) {
            logger.w(LogCategory.ERROR, "connect failed ${ctx.shortKey()}: ${t.message}", uid = uid)
            appSide.close()
            stats.onFlowClosed(uid)
            moduleChain.onFlowClose(ctx, uid)
            return
        }

        val upBucket = throttleManager.bucketFor(uid, Direction.UPLOAD)
        val downBucket = throttleManager.bucketFor(uid, Direction.DOWNLOAD)

        try {
            coroutineScope {
                // app -> upstream (UPLOAD)
                launch {
                    val buf = ByteArray(RELAY_BUFFER)
                    while (isActive) {
                        val n = appSide.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        throttleManager.pace(upBucket, n.toLong())
                        upstream.write(buf, 0, n)
                        stats.recordUpload(uid, n.toLong())
                        moduleChain.onBytes(ctx, uid, Direction.UPLOAD, n)
                    }
                    runCatching { upstream.close() }
                }
                // upstream -> app (DOWNLOAD)
                launch {
                    val buf = ByteArray(RELAY_BUFFER)
                    while (isActive) {
                        val n = upstream.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        throttleManager.pace(downBucket, n.toLong())
                        appSide.write(buf, 0, n)
                        stats.recordDownload(uid, n.toLong())
                        moduleChain.onBytes(ctx, uid, Direction.DOWNLOAD, n)
                    }
                    runCatching { appSide.close() }
                }
            }
        } catch (_: Throwable) {
            // fallthrough to cleanup
        } finally {
            runCatching { upstream.close() }
            runCatching { appSide.close() }
            stats.onFlowClosed(uid)
            moduleChain.onFlowClose(ctx, uid)
        }
    }

    // ---- UDP ---------------------------------------------------------------

    private suspend fun relayUdp(ctx: FlowContext, appSide: DatagramStream) {
        val uid = uidResolver.resolve(ctx)
        val verdict = if (paused() || !ruleEngine.isControlled(uid)) {
            FlowVerdict(blocked = false, downloadBytesPerSec = null, uploadBytesPerSec = null, warnThresholdPercent = null, tags = emptyMap())
        } else {
            moduleChain.evaluate(FlowOpenInput(ctx, uid))
        }
        val dnsExempt = config.exemptDns && ctx.isDns

        stats.onFlowOpened(uid, throttled = verdict.isThrottled && !dnsExempt, blocked = verdict.blocked)

        if (verdict.blocked) {
            logger.i(LogCategory.BLOCK, "drop udp ${ctx.shortKey()}", uid = uid)
            appSide.close()
            moduleChain.onFlowClose(ctx, uid)
            return
        }

        val upstream = try {
            connectionManager.openUdp(ctx, config.protector).also { it.setTimeout(UDP_IDLE_TIMEOUT_MILLIS) }
        } catch (t: Throwable) {
            logger.w(LogCategory.ERROR, "udp open failed ${ctx.shortKey()}: ${t.message}", uid = uid)
            appSide.close(); moduleChain.onFlowClose(ctx, uid); return
        }

        // Paced, NOT dropped: a bounded queue smooths bursts; tail-drop is the
        // documented last resort only when the app sustainably outruns its cap.
        val upBucket = if (dnsExempt) null else throttleManager.bucketFor(uid, Direction.UPLOAD)
        val downBucket = if (dnsExempt) null else throttleManager.bucketFor(uid, Direction.DOWNLOAD)
        val egressQueue = PacingQueue<ByteArray>(UDP_QUEUE_BYTES, PacingQueue.DropPolicy.DROP_OLDEST)

        try {
            coroutineScope {
                // reader: app -> queue
                launch {
                    while (isActive) {
                        val dg = appSide.receive() ?: break
                        if (ctx.isDns) { stats.onDnsQuery() }
                        if (upBucket == null) {
                            upstream.send(dg)
                            stats.recordUpload(uid, dg.size.toLong())
                        } else if (!egressQueue.offer(dg, dg.size.toLong())) {
                            logger.d(LogCategory.THROTTLE, "udp tail-drop ${ctx.shortKey()}", uid = uid)
                        }
                    }
                }
                // pacer: queue -> upstream (UPLOAD)
                if (upBucket != null) launch {
                    while (isActive) {
                        val dg = egressQueue.poll()
                        if (dg == null) { kotlinx.coroutines.delay(5); continue }
                        throttleManager.pace(upBucket, dg.size.toLong())
                        upstream.send(dg)
                        stats.recordUpload(uid, dg.size.toLong())
                        moduleChain.onBytes(ctx, uid, Direction.UPLOAD, dg.size)
                    }
                }
                // upstream -> app (DOWNLOAD)
                launch {
                    while (isActive) {
                        val data = upstream.receive() ?: break
                        throttleManager.pace(downBucket, data.size.toLong())
                        appSide.send(data)
                        stats.recordDownload(uid, data.size.toLong())
                        moduleChain.onBytes(ctx, uid, Direction.DOWNLOAD, data.size)
                        coroutineContext.ensureActive()
                    }
                }
            }
        } catch (_: Throwable) {
            // fallthrough
        } finally {
            runCatching { upstream.close() }
            runCatching { appSide.close() }
            stats.onFlowClosed(uid)
            moduleChain.onFlowClose(ctx, uid)
        }
    }

    companion object {
        private const val RELAY_BUFFER = 16 * 1024
        private const val UDP_QUEUE_BYTES = 256 * 1024L
        private const val UDP_IDLE_TIMEOUT_MILLIS = 30_000
    }
}
