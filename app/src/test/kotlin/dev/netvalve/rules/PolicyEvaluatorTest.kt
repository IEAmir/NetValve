package dev.netvalve.rules

import dev.netvalve.data.model.BandwidthLimit
import dev.netvalve.data.model.BandwidthUnit
import dev.netvalve.data.model.EvaluationContext
import dev.netvalve.data.model.RuleAction
import dev.netvalve.data.model.RuleCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEvaluatorTest {

    private val evaluator = PolicyEvaluator()
    private val ctx = EvaluationContext(appForeground = true, minuteOfDay = 12 * 60, dayOfWeek = 3)

    @Test
    fun noRulesFallsBackToAllow() {
        val policy = evaluator.evaluate(emptyList(), RuleAction.Allow, ctx)
        assertFalse(policy.blocked)
        assertNull(policy.downloadBytesPerSec)
        assertNull(policy.uploadBytesPerSec)
    }

    @Test
    fun noRulesUsesDefaultThrottle() {
        val default = RuleAction.Throttle(
            download = BandwidthLimit(1, BandwidthUnit.MB_S, true),
            upload = null,
        )
        val policy = evaluator.evaluate(emptyList(), default, ctx)
        assertEquals(1024L * 1024L, policy.downloadBytesPerSec)
        assertNull(policy.uploadBytesPerSec)
    }

    @Test
    fun blockBeatsThrottleByPriority() {
        val rules = listOf(
            PolicyRule("t", emptyList(), RuleAction.Throttle(BandwidthLimit(1, BandwidthUnit.MBPS, true), null), priority = 50),
            PolicyRule("b", emptyList(), RuleAction.Block, priority = 100),
        )
        val policy = evaluator.evaluate(rules, RuleAction.Allow, ctx)
        assertTrue(policy.blocked)
        assertEquals("b", policy.matchedRuleId)
    }

    @Test
    fun higherPriorityAllowOverridesThrottle() {
        val rules = listOf(
            PolicyRule("t", emptyList(), RuleAction.Throttle(BandwidthLimit(1, BandwidthUnit.MBPS, true), null), priority = 50),
            PolicyRule("allow", emptyList(), RuleAction.Allow, priority = 60),
        )
        val policy = evaluator.evaluate(rules, RuleAction.Allow, ctx)
        assertFalse(policy.blocked)
        assertNull(policy.downloadBytesPerSec)
        assertEquals("allow", policy.matchedRuleId)
    }

    @Test
    fun conditionGatesRuleMatching() {
        // Throttle only while in background; our ctx is foreground => must NOT match.
        val rules = listOf(
            PolicyRule(
                "bg",
                listOf(RuleCondition.RequireForeground(false)),
                RuleAction.Throttle(BandwidthLimit(1, BandwidthUnit.MBPS, true), null),
                priority = 50,
            ),
        )
        val fg = evaluator.evaluate(rules, RuleAction.Allow, ctx.copy(appForeground = true))
        assertNull(fg.downloadBytesPerSec) // did not match -> default Allow

        val bg = evaluator.evaluate(rules, RuleAction.Allow, ctx.copy(appForeground = false))
        assertEquals(125_000L, bg.downloadBytesPerSec) // 1 Mbps => 125000 B/s
    }

    @Test
    fun timeWindowWrapsMidnight() {
        val night = RuleCondition.TimeWindow(startMinute = 22 * 60, endMinute = 6 * 60)
        assertTrue(night.matches(ctx.copy(minuteOfDay = 23 * 60)))
        assertTrue(night.matches(ctx.copy(minuteOfDay = 3 * 60)))
        assertFalse(night.matches(ctx.copy(minuteOfDay = 12 * 60)))
    }
}
