package dev.netvalve.rules

import dev.netvalve.data.model.EvaluationContext
import dev.netvalve.data.model.RuleAction
import dev.netvalve.data.model.RuleCondition

/**
 * The atomic unit the engine evaluates: a set of AND-ed [conditions] guarding an
 * [action]. Higher [priority] wins when several rules match the same flow.
 *
 * User-facing [dev.netvalve.data.model.AppRule]s are *compiled* into these (see
 * [toPolicyRules]); modules may also contribute rules directly, which is why the
 * engine speaks only in terms of PolicyRule and never in terms of AppRule.
 */
data class PolicyRule(
    val id: String,
    val conditions: List<RuleCondition>,
    val action: RuleAction,
    val priority: Int = 0,
) {
    fun matches(ctx: EvaluationContext): Boolean = conditions.all { it.matches(ctx) }
}

/**
 * The resolved decision for a flow: whether to block, and the (optional) caps in
 * bytes/second. `null` caps mean "do not shape this direction".
 */
data class EffectivePolicy(
    val blocked: Boolean = false,
    val downloadBytesPerSec: Long? = null,
    val uploadBytesPerSec: Long? = null,
    val matchedRuleId: String? = null,
    val warnThresholdPercent: Int? = null,
) {
    val isThrottled: Boolean get() = downloadBytesPerSec != null || uploadBytesPerSec != null
    val isRestricted: Boolean get() = blocked || isThrottled

    companion object {
        val Unrestricted = EffectivePolicy()
    }
}
