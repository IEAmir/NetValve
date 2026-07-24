package dev.netvalve.data

import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.BandwidthLimit
import dev.netvalve.data.model.BandwidthUnit
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.Ipv6Mode
import dev.netvalve.data.model.NetworkType
import dev.netvalve.data.model.RuleCondition
import dev.netvalve.data.model.Schedule
import dev.netvalve.data.model.SelectionMode
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the persistence contract: settings + rules (including the sealed
 * [RuleCondition] hierarchy) survive a JSON round-trip unchanged. This is the
 * same [Json] configuration the DataStore repository uses.
 */
class PersistenceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Test
    fun bandwidthUnitConversionsAreExact() {
        assertEquals(125L, BandwidthUnit.KBPS.toBytesPerSecond(1))       // 1 kbps
        assertEquals(125_000L, BandwidthUnit.MBPS.toBytesPerSecond(1))   // 1 Mbps
        assertEquals(1024L, BandwidthUnit.KB_S.toBytesPerSecond(1))      // 1 KB/s
        assertEquals(1024L * 1024L, BandwidthUnit.MB_S.toBytesPerSecond(1))
    }

    @Test
    fun globalSettingsRoundTrip() {
        val original = GlobalSettings(
            selectionMode = SelectionMode.ALL_EXCEPT,
            defaultDownload = BandwidthLimit(5, BandwidthUnit.MBPS, true),
            defaultUpload = BandwidthLimit.Unlimited,
            ipv6Mode = Ipv6Mode.FAST_REJECT,
            exemptDns = false,
            logLevel = 2,
            autoStartOnBoot = true,
            enabled = true,
        )
        val decoded = json.decodeFromString(GlobalSettings.serializer(), json.encodeToString(GlobalSettings.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun appRuleWithSealedConditionsRoundTrip() {
        val original = AppRule(
            packageName = "com.example.app",
            blocked = false,
            download = BandwidthLimit(500, BandwidthUnit.KB_S, true),
            upload = BandwidthLimit(1, BandwidthUnit.MBPS, true),
            activeNetworks = setOf(NetworkType.MOBILE, NetworkType.WIFI),
            schedule = Schedule(startMinute = 1320, endMinute = 360, days = setOf(1, 2, 3)),
            extraConditions = listOf(
                RuleCondition.RequireCharging(true),
                RuleCondition.BatteryBelow(15),
                RuleCondition.RequireRoaming(false),
            ),
            warnThresholdPercent = 80,
        )
        val decoded = json.decodeFromString(AppRule.serializer(), json.encodeToString(AppRule.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun ruleMapRoundTrip() {
        val serializer = MapSerializer(String.serializer(), AppRule.serializer())
        val map = mapOf(
            "a" to AppRule("a", blocked = true),
            "b" to AppRule("b", download = BandwidthLimit(2, BandwidthUnit.MB_S, true)),
        )
        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, map))
        assertEquals(map, decoded)
    }
}
