package dev.netvalve.network

import dev.netvalve.data.model.TransportProtocol
import dev.netvalve.log.LogCategory
import dev.netvalve.log.LogEvent
import dev.netvalve.log.LogLevel
import dev.netvalve.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class UidResolverFallbackTest {

    private object NoopLogger : Logger {
        override val events: Flow<List<LogEvent>> = emptyFlow()
        override fun setMinLevel(level: LogLevel) {}
        override fun isEnabled(level: LogLevel): Boolean = false
        override fun log(level: LogLevel, category: LogCategory, message: String, uid: Int?, packageName: String?) {}
        override suspend fun exportText(): String = ""
    }

    private fun ctx() = FlowContext(
        protocol = TransportProtocol.TCP,
        sourceAddress = InetAddress.getByName("10.0.0.2"),
        sourcePort = 40000,
        destinationAddress = InetAddress.getByName("93.184.216.34"),
        destinationPort = 443,
    )

    @Test
    fun successfulLookupIsReturnedAndCached() {
        val calls = AtomicInteger(0)
        val lookup = ConnectionOwnerLookup { _, _: InetSocketAddress, _: InetSocketAddress ->
            calls.incrementAndGet(); 12345
        }
        val resolver = UidResolver(lookup, NoopLogger)
        assertEquals(12345, resolver.resolve(ctx()))
        assertEquals(12345, resolver.resolve(ctx())) // served from cache
        assertEquals(1, calls.get())
    }

    @Test
    fun invalidUidWithoutInferenceYieldsUnknown() {
        val resolver = UidResolver({ _, _, _ -> -1 }, NoopLogger)
        assertEquals(UidResolver.UID_UNKNOWN, resolver.resolve(ctx()))
        assertEquals(1, resolver.failureCount.get())
    }

    @Test
    fun invalidUidFallsBackToSingleControlledApp() {
        val resolver = UidResolver(
            lookup = { _, _, _ -> -1 },
            logger = NoopLogger,
            singleControlledUidProvider = { 999 },
        )
        assertEquals(999, resolver.resolve(ctx()))
    }

    @Test
    fun lookupExceptionNeverCrashesAndCounts() {
        val resolver = UidResolver({ _, _, _ -> throw RuntimeException("OEM quirk") }, NoopLogger)
        // Must not throw; must degrade to unknown.
        assertEquals(UidResolver.UID_UNKNOWN, resolver.resolve(ctx()))
        // one for the exception, one for the unattributable fallback
        assertEquals(2, resolver.failureCount.get())
    }
}
