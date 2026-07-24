package dev.netvalve.ui.appdetail

import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.BandwidthLimit
import dev.netvalve.data.model.BandwidthUnit
import dev.netvalve.data.model.NetworkType
import dev.netvalve.data.model.RuleCondition
import dev.netvalve.data.model.Schedule
import dev.netvalve.data.model.ThrottleMode

/**
 * A flat, UI-friendly projection of an [AppRule]. The per-app screen edits this,
 * and [toAppRule] compiles it back into the persisted model (which the engine
 * then compiles into generic policy rules). Two translation layers keep the UI
 * simple and the engine generic.
 */
data class RuleDraft(
    val packageName: String,
    val enabled: Boolean = true,
    val blocked: Boolean = false,
    val downloadEnabled: Boolean = false,
    val downloadValue: Long = 1,
    val downloadUnit: BandwidthUnit = BandwidthUnit.MBPS,
    val uploadEnabled: Boolean = false,
    val uploadValue: Long = 1,
    val uploadUnit: BandwidthUnit = BandwidthUnit.MBPS,
    val backgroundOnly: Boolean = false,
    val wifi: Boolean = false,
    val mobile: Boolean = false,
    val chargingOnly: Boolean = false,
    val roamingOnly: Boolean = false,
    val batteryBelowEnabled: Boolean = false,
    val batteryBelow: Int = 20,
    val scheduleEnabled: Boolean = false,
    val startHour: Int = 22,
    val endHour: Int = 6,
    val days: Set<Int> = emptySet(),
    val warnEnabled: Boolean = false,
    val warnPercent: Int = 80,
) {
    fun toAppRule(): AppRule {
        val networks = buildSet {
            if (wifi) add(NetworkType.WIFI)
            if (mobile) add(NetworkType.MOBILE)
        }
        val extra = buildList {
            if (chargingOnly) add(RuleCondition.RequireCharging(true))
            if (roamingOnly) add(RuleCondition.RequireRoaming(true))
            if (batteryBelowEnabled) add(RuleCondition.BatteryBelow(batteryBelow))
        }
        return AppRule(
            packageName = packageName,
            enabled = enabled,
            blocked = blocked,
            download = if (downloadEnabled) BandwidthLimit(downloadValue, downloadUnit, true) else BandwidthLimit.Unlimited,
            upload = if (uploadEnabled) BandwidthLimit(uploadValue, uploadUnit, true) else BandwidthLimit.Unlimited,
            throttleMode = if (backgroundOnly) ThrottleMode.BACKGROUND_ONLY else ThrottleMode.ALWAYS,
            activeNetworks = networks,
            schedule = if (scheduleEnabled) Schedule(startHour * 60, endHour * 60, days) else null,
            extraConditions = extra,
            warnThresholdPercent = if (warnEnabled) warnPercent else null,
        )
    }

    companion object {
        fun from(packageName: String, rule: AppRule?): RuleDraft {
            if (rule == null) return RuleDraft(packageName)
            val extra = rule.extraConditions
            return RuleDraft(
                packageName = packageName,
                enabled = rule.enabled,
                blocked = rule.blocked,
                downloadEnabled = !rule.download.isUnlimited,
                downloadValue = rule.download.value.coerceAtLeast(1),
                downloadUnit = rule.download.unit,
                uploadEnabled = !rule.upload.isUnlimited,
                uploadValue = rule.upload.value.coerceAtLeast(1),
                uploadUnit = rule.upload.unit,
                backgroundOnly = rule.throttleMode == ThrottleMode.BACKGROUND_ONLY,
                wifi = NetworkType.WIFI in rule.activeNetworks,
                mobile = NetworkType.MOBILE in rule.activeNetworks,
                chargingOnly = extra.any { it is RuleCondition.RequireCharging && it.charging },
                roamingOnly = extra.any { it is RuleCondition.RequireRoaming && it.roaming },
                batteryBelowEnabled = extra.any { it is RuleCondition.BatteryBelow },
                batteryBelow = (extra.firstOrNull { it is RuleCondition.BatteryBelow } as? RuleCondition.BatteryBelow)?.percent ?: 20,
                scheduleEnabled = rule.schedule != null,
                startHour = (rule.schedule?.startMinute ?: 1320) / 60,
                endHour = (rule.schedule?.endMinute ?: 360) / 60,
                days = rule.schedule?.days ?: emptySet(),
                warnEnabled = rule.warnThresholdPercent != null,
                warnPercent = rule.warnThresholdPercent ?: 80,
            )
        }
    }
}
