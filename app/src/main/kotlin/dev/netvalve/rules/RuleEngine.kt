package dev.netvalve.rules

import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.SelectionMode
import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.repository.AppSelectionRepository
import dev.netvalve.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime

/**
 * Orchestrates the pure [PolicyEvaluator] against live state. It combines the
 * settings, per-app rules, app selection and device state into an immutable
 * [Snapshot], then answers two questions the packet path asks on every flow:
 *
 *   * [isControlled] — should this UID be shaped at all (given selection mode)?
 *   * [policyForUid] — what is the effective block/cap decision *right now*?
 *
 * Resolution happens at flow-open (and can be re-checked periodically), never
 * per-packet, so it is cheap. The class is engine-only: no Android types, so it
 * is unit-testable with fakes for every dependency.
 */
class RuleEngine(
    private val settingsRepository: SettingsRepository,
    private val selectionRepository: AppSelectionRepository,
    private val appInfo: AppInfoLookup,
    private val deviceStateMonitor: DeviceStateMonitor,
    private val ownUid: Int,
    scope: CoroutineScope,
    private val evaluator: PolicyEvaluator = PolicyEvaluator(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    data class Snapshot(
        val settings: GlobalSettings,
        val rules: Map<String, AppRule>,
        val selected: Set<String>,
        val device: DeviceState,
    )

    val snapshot: StateFlow<Snapshot> =
        combine(
            settingsRepository.settings,
            settingsRepository.rules,
            selectionRepository.selectedPackages,
            deviceStateMonitor.state,
        ) { settings, rules, selected, device ->
            Snapshot(settings, rules, selected, device)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            Snapshot(GlobalSettings.Default, emptyMap(), emptySet(), DeviceState()),
        )

    /**
     * Monotonic revision that ticks whenever the resolved rule inputs change, so
     * the [dev.netvalve.throttle.ThrottleManager] knows to refresh live buckets.
     */
    val revision: StateFlow<Long> = MutableStateFlow(0L).also { flow ->
        snapshot
            .onEach { flow.value = flow.value + 1 }
            .launchIn(scope)
    }

    /** True when [uid]'s traffic should be routed/shaped under the current mode. */
    fun isControlled(uid: Int): Boolean {
        if (uid == ownUid) return false // never shape ourselves
        val snap = snapshot.value
        val pkgs = appInfo.packagesForUid(uid)
        if (pkgs.isEmpty()) {
            // Unknown UID: in ALL_EXCEPT it is controlled (route by default),
            // in ONLY_SELECTED it is not. Either way, never crash.
            return snap.settings.selectionMode == SelectionMode.ALL_EXCEPT
        }
        val anySelected = pkgs.any { it in snap.selected }
        return when (snap.settings.selectionMode) {
            SelectionMode.ONLY_SELECTED -> anySelected
            SelectionMode.ALL_EXCEPT -> !anySelected
        }
    }

    /** Effective decision for [uid] at the current instant. */
    fun policyForUid(uid: Int): EffectivePolicy {
        val snap = snapshot.value
        val pkgs = appInfo.packagesForUid(uid)
        // Prefer the package that actually has an active rule; else any package.
        val pkg = pkgs.firstOrNull { snap.rules[it]?.isActive == true } ?: pkgs.firstOrNull()
        val appRule: AppRule? = pkg?.let { snap.rules[it] }
        val ctx = snap.device.toEvaluationContext(pkg ?: "", clock())
        val policy = evaluator.evaluate(
            rules = appRule?.toPolicyRules().orEmpty(),
            defaultAction = snap.settings.defaultAction(),
            ctx = ctx,
        )
        return policy.copy(warnThresholdPercent = appRule?.warnThresholdPercent)
    }
}
