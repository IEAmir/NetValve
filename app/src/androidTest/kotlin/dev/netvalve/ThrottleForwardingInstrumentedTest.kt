package dev.netvalve

import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.BandwidthLimit
import dev.netvalve.data.model.BandwidthUnit
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.SelectionMode
import dev.netvalve.data.model.TransportProtocol
import dev.netvalve.log.LogCategory
import dev.netvalve.log.LogEvent
import dev.netvalve.log.LogLevel
import dev.netvalve.log.Logger
import dev.netvalve.module.DefaultPolicyModule
import dev.netvalve.module.ModuleChain
import dev.netvalve.network.ConnectionManager
import dev.netvalve.network.DnsCache
import dev.netvalve.network.FlowContext
import dev.netvalve.network.FlowStream
import dev.netvalve.network.FlowSupervisor
import dev.netvalve.network.SocketProtector
import dev.netvalve.network.TunnelConfig
import dev.netvalve.network.UidResolver
import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.repository.AppSelectionRepository
import dev.netvalve.repository.AppUsageRecord
import dev.netvalve.repository.SettingsRepository
import dev.netvalve.repository.StatsRepository
import dev.netvalve.rules.DeviceState
import dev.netvalve.rules.DeviceStateMonitor
import dev.netvalve.rules.RuleEngine
import dev.netvalve.stats.StatsCollector
import dev.netvalve.throttle.ThrottleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * ON-DEVICE data-path validation. Runs the PRODUCTION Kotlin relay
 * (FlowSupervisor + ConnectionManager + ThrottleManager + TokenBucket +
 * RuleEngine) against a real loopback TCP server inside the Android runtime, and
 * asserts that a bandwidth limit REDUCES throughput (not blocks it), with zero
 * byte loss and no deadlock.
 *
 * This is the piece meant to run on a physical device / emulator or Firebase Test
 * Lab (`./gradlew connectedDebugAndroidTest`). It validates the shaping + relay
 * on real Android sockets. (The full VpnService packet-capture path with real
 * third-party apps is a manual/Firebase step — see docs/NETSTACK_EVIDENCE.md.)
 */
@RunWith(AndroidJUnit4::class)
class ThrottleForwardingInstrumentedTest {

    private object NoopLogger : Logger {
        override val events: Flow<List<LogEvent>> = emptyFlow()
        override fun setMinLevel(level: LogLevel) {}
        override fun isEnabled(level: LogLevel) = false
        override fun log(level: LogLevel, category: LogCategory, message: String, uid: Int?, packageName: String?) {}
        override suspend fun exportText() = ""
    }

    private val protector = object : SocketProtector {
        override fun protect(socket: java.net.Socket) = true
        override fun protect(socket: java.net.DatagramSocket) = true
    }

    private class AppSide : FlowStream {
        private val up = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        val down = AtomicLong(0)
        @Volatile var firstNanos = 0L
        @Volatile var lastNanos = 0L
        override fun read(dst: ByteArray): Int {
            while (true) {
                up.poll(50, TimeUnit.MILLISECONDS)
                if (closed) return -1
            }
        }
        override fun write(src: ByteArray, off: Int, len: Int) {
            if (firstNanos == 0L) firstNanos = System.nanoTime()
            lastNanos = System.nanoTime()
            down.addAndGet(len.toLong())
        }
        override fun close() { closed = true }
    }

    private fun supervisor(scope: CoroutineScope, downCap: BandwidthLimit): FlowSupervisor {
        val rule = AppRule(packageName = PKG, download = downCap, upload = BandwidthLimit.Unlimited)
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(GlobalSettings(selectionMode = SelectionMode.ONLY_SELECTED)) as Flow<GlobalSettings>
            override val rules = MutableStateFlow(mapOf(PKG to rule)) as Flow<Map<String, AppRule>>
            override suspend fun current() = GlobalSettings(selectionMode = SelectionMode.ONLY_SELECTED)
            override suspend fun currentRules() = mapOf(PKG to rule)
            override suspend fun updateSettings(transform: (GlobalSettings) -> GlobalSettings) {}
            override suspend fun upsertRule(rule: AppRule) {}
            override suspend fun removeRule(packageName: String) {}
            override suspend fun clearRules() {}
        }
        val selection = object : AppSelectionRepository {
            override val selectedPackages = MutableStateFlow(setOf(PKG)) as Flow<Set<String>>
            override suspend fun current() = setOf(PKG)
            override suspend fun setSelected(packages: Set<String>) {}
            override suspend fun setSelected(packageName: String, selected: Boolean) {}
            override suspend fun clear() {}
        }
        val appInfo = object : AppInfoLookup {
            override fun packagesForUid(uid: Int) = if (uid == UID) listOf(PKG) else emptyList()
            override fun uidForPackage(packageName: String) = if (packageName == PKG) UID else null
            override fun labelForPackage(packageName: String) = packageName
            override fun invalidate() {}
        }
        val monitor = object : DeviceStateMonitor {
            override val state: StateFlow<DeviceState> = MutableStateFlow(DeviceState())
            override fun start() {}
            override fun stop() {}
        }
        val statsRepo = object : StatsRepository {
            override fun perAppTotals() = flowOf(emptyList<AppUsageRecord>())
            override suspend fun current() = emptyList<AppUsageRecord>()
            override suspend fun upsert(records: List<AppUsageRecord>) {}
            override suspend fun reset() {}
        }
        val ruleEngine = RuleEngine(settings, selection, appInfo, monitor, ownUid = 1, scope = scope)
        val throttle = ThrottleManager(ruleEngine, scope, NoopLogger)
        val stats = StatsCollector(statsRepo, appInfo)
        val conn = ConnectionManager(stats, NoopLogger)
        val uid = UidResolver({ _, _, _ -> UID }, NoopLogger)
        val chain = ModuleChain(setOf(DefaultPolicyModule(ruleEngine)))
        return FlowSupervisor(scope, TunnelConfig(protector = protector), uid, ruleEngine, chain, throttle, conn, stats, NoopLogger, DnsCache())
    }

    private fun runDownload(totalBytes: Long, downCap: BandwidthLimit): Pair<Long, Double> {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                val s = server.accept(); val out = s.getOutputStream()
                val buf = ByteArray(64 * 1024); var sent = 0L
                while (sent < totalBytes) { val n = min(buf.size.toLong(), totalBytes - sent).toInt(); out.write(buf, 0, n); sent += n }
                out.flush(); s.shutdownOutput(); Thread.sleep(200); s.close()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sup = supervisor(scope, downCap)
        runBlocking { kotlinx.coroutines.delay(150) }
        val app = AppSide()
        val ctx = FlowContext(TransportProtocol.TCP, InetAddress.getByName("127.0.0.1"), 40000, InetAddress.getByName("127.0.0.1"), server.localPort)
        sup.onTcpFlow(ctx, app)
        val deadline = System.currentTimeMillis() + 60_000
        while (app.down.get() < totalBytes && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val got = app.down.get()
        val bps = if (app.lastNanos > app.firstNanos) got / ((app.lastNanos - app.firstNanos) / 1e9) else Double.POSITIVE_INFINITY
        scope.cancel(); server.close()
        return got to bps
    }

    @Test
    fun unthrottledDownloadForwardsAllBytes() {
        val total = 8L * 1024 * 1024
        val (got, _) = runDownload(total, BandwidthLimit.Unlimited)
        assertEquals("all bytes forwarded (connectivity, no loss, no deadlock)", total, got)
    }

    @Test
    fun throttleReducesThroughputInsteadOfBlocking() {
        val total = 8L * 1024 * 1024
        val cap = BandwidthLimit(2, BandwidthUnit.MB_S, true) // 2 MB/s
        val (got, bps) = runDownload(total, cap)
        assertEquals("no byte loss under throttle", total, got)
        assertTrue("throughput must be > 0 (not blocked): $bps B/s", bps > 512 * 1024)
        assertTrue("throughput must be near the 2 MB/s cap, not line-rate: $bps B/s", bps < 4.0 * 1024 * 1024)
    }

    companion object {
        private const val PKG = "dev.netvalve.selftest"
        private const val UID = 10999
    }
}
