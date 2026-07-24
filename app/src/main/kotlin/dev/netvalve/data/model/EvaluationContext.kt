package dev.netvalve.data.model

/**
 * The full set of facts a [RuleCondition] may inspect when deciding whether it
 * matches. It is a plain, immutable value object so the policy engine is a pure
 * function of (rules, context) and is trivially unit-testable with no Android or
 * coroutine dependencies.
 *
 * [appForeground] is per-app (per-UID) state; everything else is device-wide.
 */
data class EvaluationContext(
    val network: NetworkType = NetworkType.NONE,
    val roaming: Boolean = false,
    val charging: Boolean = false,
    val batteryPercent: Int = 100,
    val screenOn: Boolean = true,
    val appForeground: Boolean = false,
    /** Minutes since local midnight, 0..1439. */
    val minuteOfDay: Int = 0,
    /** ISO-8601 day of week: Monday=1 .. Sunday=7. */
    val dayOfWeek: Int = 1,
)
