package dev.netvalve.rules

import dev.netvalve.data.model.EvaluationContext
import dev.netvalve.data.model.RuleAction

/**
 * Pure, deterministic resolution of a rule set against a context. No Android, no
 * coroutines, no time source — everything it needs is in the [EvaluationContext].
 * This is the most safety-critical piece of logic in the app and is exhaustively
 * unit-tested (see PolicyEvaluatorTest).
 *
 * Precedence model — predictable and easy to reason about:
 *  1. Consider only rules whose conditions all match.
 *  2. Sort matches by descending [PolicyRule.priority] (ties keep input order).
 *  3. The first matching rule decides the outcome:
 *       - [RuleAction.Block]    -> blocked.
 *       - [RuleAction.Allow]    -> unrestricted (an explicit exception/override).
 *       - [RuleAction.Throttle] -> those caps.
 *  4. If nothing matches, fall back to [defaultAction] (the global default).
 *
 * Because Block is emitted at a higher priority than Throttle by the compiler, a
 * block always beats a cap for the same app; an Allow rule placed above a
 * throttle rule acts as a carve-out.
 */
class PolicyEvaluator {

    fun evaluate(
        rules: List<PolicyRule>,
        defaultAction: RuleAction,
        ctx: EvaluationContext,
    ): EffectivePolicy {
        val winner = rules
            .asSequence()
            .filter { it.matches(ctx) }
            .sortedByDescending { it.priority }
            .firstOrNull()

        return if (winner != null) {
            fold(winner.action, winner.id)
        } else {
            fold(defaultAction, matchedRuleId = null)
        }
    }

    private fun fold(action: RuleAction, matchedRuleId: String?): EffectivePolicy = when (action) {
        is RuleAction.Allow -> EffectivePolicy(matchedRuleId = matchedRuleId)
        is RuleAction.Block -> EffectivePolicy(blocked = true, matchedRuleId = matchedRuleId)
        is RuleAction.Throttle -> EffectivePolicy(
            blocked = false,
            downloadBytesPerSec = action.download?.bytesPerSecondOrNull(),
            uploadBytesPerSec = action.upload?.bytesPerSecondOrNull(),
            matchedRuleId = matchedRuleId,
        )
    }
}
