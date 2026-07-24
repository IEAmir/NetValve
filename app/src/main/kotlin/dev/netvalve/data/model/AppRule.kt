package dev.netvalve.data.model

import kotlinx.serialization.Serializable

/**
 * A friendly, per-app schedule. Compiled into [RuleCondition.TimeWindow] +
 * [RuleCondition.OnDaysOfWeek] by the rule compiler.
 */
@Serializable
data class Schedule(
    val startMinute: Int = 0,
    val endMinute: Int = 0,
    /** ISO days (Mon=1..Sun=7). Empty ⇒ every day. */
    val days: Set<Int> = emptySet(),
) {
    /** A zero-length, every-day schedule is treated as "no schedule". */
    val isEffectivelyUnset: Boolean get() = startMinute == endMinute && days.isEmpty()
}

/**
 * The persisted, user-facing configuration for a single app. This is the
 * ergonomic surface shown on the per-app detail screen. It is deliberately
 * distinct from the generic engine types ([RuleCondition]/[RuleAction]/
 * PolicyRule): [dev.netvalve.rules.toPolicyRules] compiles an [AppRule] down to
 * generic policy rules, so the UI stays simple while the engine stays generic
 * and extensible.
 *
 * @param enabled   master switch for this app's rule. When false the app is
 *                  still routed but the global default policy applies.
 * @param blocked   deny all access for this app (subject to [schedule]/network/
 *                  [extraConditions]).
 * @param throttleMode ALWAYS or BACKGROUND_ONLY (sugar for a foreground=false
 *                  condition on the throttle rule).
 * @param activeNetworks restrict the rule to these networks; empty ⇒ any.
 * @param extraConditions arbitrary additional conditions (charging, battery,
 *                  screen, roaming, …) — the generic hook that lets the engine
 *                  grow without a redesign.
 * @param warnThresholdPercent notify when session usage reaches this % of the
 *                  daily/soft cap (0..100), or null to disable.
 */
@Serializable
data class AppRule(
    val packageName: String,
    val enabled: Boolean = true,
    val blocked: Boolean = false,
    val download: BandwidthLimit = BandwidthLimit.Unlimited,
    val upload: BandwidthLimit = BandwidthLimit.Unlimited,
    val throttleMode: ThrottleMode = ThrottleMode.ALWAYS,
    val activeNetworks: Set<NetworkType> = emptySet(),
    val schedule: Schedule? = null,
    val extraConditions: List<RuleCondition> = emptyList(),
    val warnThresholdPercent: Int? = null,
    val note: String = "",
) {
    val hasActiveCap: Boolean
        get() = !download.isUnlimited || !upload.isUnlimited

    /** True if this rule does anything at all beyond the global default. */
    val isActive: Boolean
        get() = enabled && (blocked || hasActiveCap)
}
