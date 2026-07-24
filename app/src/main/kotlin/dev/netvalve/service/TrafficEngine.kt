package dev.netvalve.service

import dev.netvalve.data.model.Ipv6Mode
import dev.netvalve.log.LogCategory
import dev.netvalve.log.Logger
import dev.netvalve.module.ModuleChain
import dev.netvalve.network.ConnectionManager
import dev.netvalve.network.DnsCache
import dev.netvalve.network.FlowSupervisor
import dev.netvalve.network.PacketPipeline
import dev.netvalve.network.PacketPipelineFactory
import dev.netvalve.network.SocketProtector
import dev.netvalve.network.TunnelConfig
import dev.netvalve.network.UidResolver
import dev.netvalve.repository.StatsRepository
import dev.netvalve.rules.DeviceStateMonitor
import dev.netvalve.rules.RuleEngine
import dev.netvalve.stats.StatsCollector
import dev.netvalve.stats.StatsSnapshot
import dev.netvalve.throttle.ThrottleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one tunnel *session*: it assembles the [FlowSupervisor], starts the
 * build-selected [PacketPipeline], drives stats sampling + periodic persistence,
 * and tears everything down cleanly. It is engine-agnostic — it only knows the
 * [PacketPipeline] interface, so loopback vs netstack is transparent here.
 *
 * All singletons (rule engine, throttle, stats, …) are injected; only the
 * per-session pieces (IO scope, supervisor, pipeline instance) are created in
 * [start] and released in [stop].
 */
@Singleton
class TrafficEngine @Inject constructor(
    private val pipelineFactory: PacketPipelineFactory,
    private val ruleEngine: RuleEngine,
    private val throttleManager: ThrottleManager,
    private val connectionManager: ConnectionManager,
    private val stats: StatsCollector,
    private val uidResolver: UidResolver,
    private val logger: Logger,
    private val dnsCache: DnsCache,
    private val moduleChain: ModuleChain,
    private val statsRepository: StatsRepository,
    private val deviceStateMonitor: DeviceStateMonitor,
) {
    @Volatile var isRunning: Boolean = false
        private set

    private val paused = java.util.concurrent.atomic.AtomicBoolean(false)

    private var sessionScope: CoroutineScope? = null
    private var pipeline: PacketPipeline? = null

    val statsFlow: StateFlow<StatsSnapshot> = stats.snapshot

    /** "Pause all": new flows relay unshaped until resumed. */
    fun setPaused(value: Boolean) { paused.set(value) }
    val isPaused: Boolean get() = paused.get()

    suspend fun start(
        tunFd: Int,
        protector: SocketProtector,
        mtu: Int,
        ipv6Mode: Ipv6Mode,
        exemptDns: Boolean,
        dnsServers: List<String>,
    ) {
        if (isRunning) return
        paused.set(false)
        deviceStateMonitor.start()
        stats.startSession()

        // Relay dispatcher: each flow runs two coroutines that BLOCK on socket /
        // netstack reads. Plain Dispatchers.IO caps at 64 threads, so a single
        // busy page (dozens of concurrent connections) would exhaust the pool —
        // pace() delays could not resume and reads never released, making the
        // tunnel "work for a few seconds then stop". A larger dedicated
        // parallelism gives blocking relays their own headroom without starving
        // the shared IO pool used by Room/DataStore/stats. (True 300+ scale with
        // low RAM ultimately wants NIO upstreams — see docs/LIMITATIONS.md.)
        val relayDispatcher = Dispatchers.IO.limitedParallelism(RELAY_PARALLELISM)
        val scope = CoroutineScope(SupervisorJob() + relayDispatcher)
        sessionScope = scope

        val config = TunnelConfig(
            mtu = mtu,
            dnsServers = dnsServers,
            ipv6Mode = ipv6Mode,
            exemptDns = exemptDns,
            protector = protector,
        )
        val supervisor = FlowSupervisor(
            scope = scope,
            config = config,
            uidResolver = uidResolver,
            ruleEngine = ruleEngine,
            moduleChain = moduleChain,
            throttleManager = throttleManager,
            connectionManager = connectionManager,
            stats = stats,
            logger = logger,
            dnsCache = dnsCache,
            paused = { paused.get() },
        )

        val p = pipelineFactory.create()
        // Engine visibility (debug item 1): make it unmistakable which packet
        // engine is running. The loopback engine does NOT forward upstream, so a
        // controlled app will appear to "stall" — that is expected for that build.
        val engineName = p.javaClass.simpleName
        if (dev.netvalve.BuildConfig.USE_NETSTACK) {
            logger.i(LogCategory.SYSTEM, "Packet engine: $engineName (netstack, real forwarding)")
        } else {
            logger.w(
                LogCategory.SYSTEM,
                "Packet engine: $engineName (LOOPBACK — does NOT forward upstream; controlled apps will " +
                    "have no connectivity). Build with -Pnetvalve.netstack=true for real traffic.",
            )
        }
        p.start(tunFd, config, supervisor)
        pipeline = p
        isRunning = true
        logger.i(LogCategory.SYSTEM, "Tunnel engine started (ipv6=$ipv6Mode, exemptDns=$exemptDns)")

        // Sample + publish stats (also feeds the notification) at a low cadence.
        scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MILLIS)
                stats.sample()
            }
        }
        // Checkpoint cumulative per-app totals so they survive process death.
        scope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MILLIS)
                runCatching { statsRepository.upsert(stats.checkpoint()) }
            }
        }
    }

    suspend fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { pipeline?.stop() }
        pipeline = null
        runCatching { statsRepository.upsert(stats.checkpoint()) }
        sessionScope?.cancel()
        sessionScope = null
        deviceStateMonitor.stop()
        logger.i(LogCategory.SYSTEM, "Tunnel engine stopped")
    }

    companion object {
        private const val SAMPLE_INTERVAL_MILLIS = 1_000L
        private const val CHECKPOINT_INTERVAL_MILLIS = 15_000L

        /** Max concurrent blocking relay slots (≈ half this many simultaneous flows). */
        private const val RELAY_PARALLELISM = 512
    }
}
