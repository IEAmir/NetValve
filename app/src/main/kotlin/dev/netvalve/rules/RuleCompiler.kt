package dev.netvalve.rules

import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.RuleAction
import dev.netvalve.data.model.RuleCondition
import dev.netvalve.data.model.ThrottleMode

/**
 * Compiles the ergonomic per-app [AppRule] into generic [PolicyRule]s. Keeping
 * this translation in one place is what lets the UI expose a simple, opinionated
 * form while the engine stays fully generic.
 *
 * Priorities: Block (100) > Throttle (50) so a block always wins over a cap.
 */
fun AppRule.toPolicyRules(): List<PolicyRule> {
    if (!enabled) return emptyList()

    val base = buildList {
        if (activeNetworks.isNotEmpty()) add(RuleCondition.OnNetworkType(activeNetworks))
        schedule?.takeIf { !it.isEffectivelyUnset }?.let { s ->
            if (s.startMinute != s.endMinute) add(RuleCondition.TimeWindow(s.startMinute, s.endMinute))
            if (s.days.isNotEmpty()) add(RuleCondition.OnDaysOfWeek(s.days))
        }
        addAll(extraConditions)
    }

    return when {
        blocked -> listOf(
            PolicyRule(id = "$packageName#block", conditions = base, action = RuleAction.Block, priority = 100),
        )
        hasActiveCap -> {
            val conds = if (throttleMode == ThrottleMode.BACKGROUND_ONLY) {
                base + RuleCondition.RequireForeground(false)
            } else {
                base
            }
            listOf(
                PolicyRule(
                    id = "$packageName#throttle",
                    conditions = conds,
                    action = RuleAction.Throttle(
                        download = download.takeUnless { it.isUnlimited },
                        upload = upload.takeUnless { it.isUnlimited },
                    ),
                    priority = 50,
                ),
            )
        }
        else -> emptyList()
    }
}

/** The engine's fallback action when no per-app rule matches a controlled flow. */
fun GlobalSettings.defaultAction(): RuleAction {
    val d = defaultDownload.takeUnless { it.isUnlimited }
    val u = defaultUpload.takeUnless { it.isUnlimited }
    return if (d == null && u == null) RuleAction.Allow else RuleAction.Throttle(d, u)
}
