package dev.netvalve.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.Ipv6Mode
import dev.netvalve.data.model.SelectionMode
import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.repository.AppSelectionRepository
import dev.netvalve.repository.SettingsRepository
import dev.netvalve.rules.DeviceState
import dev.netvalve.rules.DeviceStateMonitor
import dev.netvalve.service.BatteryOptimizations
import dev.netvalve.service.TunnelState
import dev.netvalve.service.VpnController
import dev.netvalve.service.VpnStatus
import dev.netvalve.stats.StatsCollector
import dev.netvalve.stats.StatsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ControlledAppRow(val packageName: String, val label: String, val rule: AppRule?)

data class DashboardUiState(
    val status: VpnStatus = VpnStatus(),
    val stats: StatsSnapshot = StatsSnapshot(),
    val settings: GlobalSettings = GlobalSettings.Default,
    val selectedCount: Int = 0,
    val controlledApps: List<ControlledAppRow> = emptyList(),
    val activeRuleCount: Int = 0,
    val device: DeviceState = DeviceState(),
    val ignoringBatteryOptimizations: Boolean = true,
) {
    val running: Boolean get() = status.isActive
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: VpnController,
    private val settingsRepository: SettingsRepository,
    private val selectionRepository: AppSelectionRepository,
    private val statsCollector: StatsCollector,
    private val appInfo: AppInfoLookup,
    deviceStateMonitor: DeviceStateMonitor,
) : ViewModel() {

    private val partial = combine(
        controller.status,
        statsCollector.snapshot,
        selectionRepository.selectedPackages,
        settingsRepository.settings,
        deviceStateMonitor.state,
    ) { status, stats, selected, settings, device ->
        Partial(status, stats, selected, settings, device)
    }

    val uiState: StateFlow<DashboardUiState> =
        combine(partial, settingsRepository.rules) { p, rules ->
            val controlled = p.selected.sorted().map { pkg ->
                ControlledAppRow(pkg, appInfo.labelForPackage(pkg), rules[pkg])
            }
            DashboardUiState(
                status = p.status,
                stats = p.stats,
                settings = p.settings,
                selectedCount = p.selected.size,
                controlledApps = controlled,
                activeRuleCount = rules.values.count { it.isActive },
                device = p.device,
                ignoringBatteryOptimizations = BatteryOptimizations.isIgnoringBatteryOptimizations(context),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun pauseResume() {
        if (uiState.value.status.state == TunnelState.PAUSED) controller.resume() else controller.pause()
    }

    fun setSelectionMode(mode: SelectionMode) = update { it.copy(selectionMode = mode) }
    fun setIpv6Mode(mode: Ipv6Mode) = update { it.copy(ipv6Mode = mode) }
    fun setExemptDns(value: Boolean) = update { it.copy(exemptDns = value) }
    fun setAutoStart(value: Boolean) = update { it.copy(autoStartOnBoot = value) }

    /** Applying a mode change while running needs a tunnel rebuild. */
    fun applyAndMaybeRestart() {
        if (uiState.value.running) controller.restart()
    }

    private fun update(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch { settingsRepository.updateSettings(transform) }
    }

    private data class Partial(
        val status: VpnStatus,
        val stats: StatsSnapshot,
        val selected: Set<String>,
        val settings: GlobalSettings,
        val device: DeviceState,
    )
}
