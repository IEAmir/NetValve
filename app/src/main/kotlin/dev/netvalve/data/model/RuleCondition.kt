package dev.netvalve.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A generic, optional pre-condition on a policy rule. Conditions are pure,
 * serializable predicates over an [EvaluationContext]; a rule fires only when
 * **all** of its conditions match (logical AND). Express OR by attaching several
 * rules to the same target.
 *
 * This deliberately models device/context state generically (network, power,
 * screen, foreground, schedule) so that future modules — quotas, parental
 * control, domain/firewall rules — can add new condition types **without
 * touching the engine**: add a subclass and a `matches` body, nothing else.
 *
 * Each subclass carries a stable [SerialName] so persisted rules survive class
 * renames/refactors (the JSON discriminator is the short name, not the FQN).
 */
@Serializable
sealed interface RuleCondition {
    fun matches(ctx: EvaluationContext): Boolean

    /** Human-readable summary for the UI and logs. */
    fun describe(): String

    /** Current network is one of [types]. Empty set ⇒ matches any network. */
    @Serializable
    @SerialName("network")
    data class OnNetworkType(val types: Set<NetworkType>) : RuleCondition {
        override fun matches(ctx: EvaluationContext) =
            types.isEmpty() || ctx.network in types

        override fun describe() =
            if (types.isEmpty()) "on any network" else "on ${types.joinToString("/") { it.name.lowercase() }}"
    }

    /** Require (or forbid) roaming. */
    @Serializable
    @SerialName("roaming")
    data class RequireRoaming(val roaming: Boolean) : RuleCondition {
        override fun matches(ctx: EvaluationContext) = ctx.roaming == roaming
        override fun describe() = if (roaming) "while roaming" else "while not roaming"
    }

    /** Require (or forbid) the charger being connected. */
    @Serializable
    @SerialName("charging")
    data class RequireCharging(val charging: Boolean) : RuleCondition {
        override fun matches(ctx: EvaluationContext) = ctx.charging == charging
        override fun describe() = if (charging) "while charging" else "while on battery"
    }

    /** Battery strictly below [percent]. */
    @Serializable
    @SerialName("battery_below")
    data class BatteryBelow(val percent: Int) : RuleCondition {
        override fun matches(ctx: EvaluationContext) = ctx.batteryPercent < percent
        override fun describe() = "battery < $percent%"
    }

    /** Require the screen to be on or off. */
    @Serializable
    @SerialName("screen")
    data class RequireScreen(val on: Boolean) : RuleCondition {
        override fun matches(ctx: EvaluationContext) = ctx.screenOn == on
        override fun describe() = if (on) "while screen on" else "while screen off"
    }

    /** Require the app to be in (or out of) the foreground. */
    @Serializable
    @SerialName("foreground")
    data class RequireForeground(val foreground: Boolean) : RuleCondition {
        override fun matches(ctx: EvaluationContext) = ctx.appForeground == foreground
        override fun describe() = if (foreground) "while in foreground" else "while in background"
    }

    /**
     * Time-of-day window, in minutes since midnight. [startMinute] inclusive,
     * [endMinute] exclusive. Windows that wrap past midnight (start > end) are
     * supported, e.g. 22:00 -> 06:00.
     */
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(val startMinute: Int, val endMinute: Int) : RuleCondition {
        override fun matches(ctx: EvaluationContext): Boolean {
            val m = ctx.minuteOfDay
            return if (startMinute <= endMinute) {
                m in startMinute until endMinute
            } else {
                // Wraps midnight: [start,1440) U [0,end)
                m >= startMinute || m < endMinute
            }
        }

        override fun describe() = "%02d:%02d–%02d:%02d".format(
            startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60,
        )
    }

    /** Active only on the given ISO days of week (Mon=1..Sun=7). */
    @Serializable
    @SerialName("days")
    data class OnDaysOfWeek(val days: Set<Int>) : RuleCondition {
        override fun matches(ctx: EvaluationContext) =
            days.isEmpty() || ctx.dayOfWeek in days

        override fun describe(): String {
            if (days.isEmpty()) return "any day"
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            return days.sorted().joinToString("/") { names[it - 1] }
        }
    }
}
