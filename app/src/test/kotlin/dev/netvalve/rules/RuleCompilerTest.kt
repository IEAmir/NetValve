package dev.netvalve.rules

import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.BandwidthLimit
import dev.netvalve.data.model.BandwidthUnit
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.NetworkType
import dev.netvalve.data.model.RuleAction
import dev.netvalve.data.model.RuleCondition
import dev.netvalve.data.model.Schedule
import dev.netvalve.data.model.ThrottleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCompilerTest {

    @Test
    fun disabledRuleCompilesToNothing() {
        val rule = AppRule("a", enabled = false, blocked = true)
        assertTrue(rule.toPolicyRules().isEmpty())
    }

    @Test
    fun blockedAppCompilesToBlockAtHighPriority() {
        val rules = AppRule("a", blocked = true).toPolicyRules()
        assertEquals(1, rules.size)
        assertEquals(RuleAction.Block, rules[0].action)
        assertEquals(100, rules[0].priority)
    }

    @Test
    fun cappedAppCompilesToThrottle() {
        val rule = AppRule(
            packageName = "a",
            download = BandwidthLimit(2, BandwidthUnit.MB_S, true),
            upload = BandwidthLimit.Unlimited,
        )
        val compiled = rule.toPolicyRules()
        assertEquals(1, compiled.size)
        val action = compiled[0].action as RuleAction.Throttle
        assertEquals(2L * 1024 * 1024, action.download!!.bytesPerSecondOrNull())
        assertEquals(null, action.upload)
        assertEquals(50, compiled[0].priority)
    }

    @Test
    fun backgroundOnlyAddsForegroundFalseCondition() {
        val rule = AppRule(
            packageName = "a",
            download = BandwidthLimit(1, BandwidthUnit.MBPS, true),
            throttleMode = ThrottleMode.BACKGROUND_ONLY,
        )
        val conds = rule.toPolicyRules()[0].conditions
        assertTrue(conds.any { it is RuleCondition.RequireForeground && !it.foreground })
    }

    @Test
    fun scheduleAndNetworkBecomeConditions() {
        val rule = AppRule(
            packageName = "a",
            download = BandwidthLimit(1, BandwidthUnit.MBPS, true),
            activeNetworks = setOf(NetworkType.MOBILE),
            schedule = Schedule(startMinute = 60, endMinute = 120, days = setOf(1, 2)),
        )
        val conds = rule.toPolicyRules()[0].conditions
        assertTrue(conds.any { it is RuleCondition.OnNetworkType && it.types == setOf(NetworkType.MOBILE) })
        assertTrue(conds.any { it is RuleCondition.TimeWindow })
        assertTrue(conds.any { it is RuleCondition.OnDaysOfWeek })
    }

    @Test
    fun defaultActionReflectsGlobalCaps() {
        assertEquals(RuleAction.Allow, GlobalSettings.Default.defaultAction())
        val withCap = GlobalSettings.Default.copy(
            defaultDownload = BandwidthLimit(1, BandwidthUnit.MBPS, true),
        ).defaultAction()
        assertTrue(withCap is RuleAction.Throttle)
    }
}
