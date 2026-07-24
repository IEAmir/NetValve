package dev.netvalve.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netvalve.BuildConfig
import dev.netvalve.data.model.Ipv6Mode
import dev.netvalve.data.model.SelectionMode
import dev.netvalve.service.TunnelState
import dev.netvalve.ui.components.SectionCard
import dev.netvalve.ui.components.StatTile
import dev.netvalve.ui.navigation.AppActions
import dev.netvalve.utils.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    actions: AppActions,
    onOpenApps: () -> Unit,
    onOpenApp: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("NetValve") })
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusHero(state, actions, onPauseResume = viewModel::pauseResume) }

            if (!BuildConfig.USE_NETSTACK) {
                item {
                    SectionCard {
                        Text(
                            "Development engine (loopback)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This build does NOT forward traffic upstream, so controlled apps will lose " +
                                "connectivity while the tunnel is on (this is not a throttling bug). Build the " +
                                "netstack engine (./netstack/build-aar.sh, then -Pnetvalve.netstack=true) for real traffic.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!state.device.usageAccessGranted) {
                item {
                    SetupCard(
                        icon = { Icon(Icons.Filled.Visibility, null) },
                        title = "Enable usage access",
                        body = "Needed so \"throttle in background only\" rules can tell which app is in the foreground. NetValve works without it, but background-only rules stay inactive.",
                        buttonText = "Grant usage access",
                        onClick = actions.requestUsageAccess,
                    )
                }
            }
            if (!state.ignoringBatteryOptimizations) {
                item {
                    SetupCard(
                        icon = { Icon(Icons.Filled.BatteryAlert, null) },
                        title = "Allow background running",
                        body = "Exempt NetValve from battery optimization so the tunnel isn't killed in Doze. Some vendors need an extra auto-start step.",
                        buttonText = "Fix battery settings",
                        onClick = actions.requestBatteryExemption,
                        secondaryText = "Vendor settings",
                        onSecondary = actions.openVendorSettings,
                    )
                }
            }

            item { SummaryCard(state, onOpenApps, onOpenApp) }
            item { SettingsCard(state, viewModel) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun StatusHero(state: DashboardUiState, actions: AppActions, onPauseResume: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (state.status.state) {
                        TunnelState.RUNNING -> "Protected"
                        TunnelState.PAUSED -> "Paused (passthrough)"
                        TunnelState.STARTING -> "Starting…"
                        TunnelState.ERROR -> "Error"
                        TunnelState.REVOKED -> "Revoked"
                        TunnelState.STOPPED -> "Off"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                state.status.message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (state.running) {
                    Text(
                        "Session ${Format.duration(state.stats.sessionDurationMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = state.status.isActive,
                onCheckedChange = { actions.toggleVpn(it) },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("Download", Format.rate(state.stats.liveDownloadBps))
            StatTile("Upload", Format.rate(state.stats.liveUploadBps))
            StatTile("Active", state.stats.activeConnections.toString())
        }
        if (state.running) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onPauseResume) {
                Icon(Icons.Filled.Bolt, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.status.state == TunnelState.PAUSED) "Resume shaping" else "Pause all")
            }
        }
    }
}

@Composable
private fun SetupCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    buttonText: String,
    onClick: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onClick) { Text(buttonText) }
            if (secondaryText != null && onSecondary != null) {
                FilledTonalButton(onClick = onSecondary) { Text(secondaryText) }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: DashboardUiState, onOpenApps: () -> Unit, onOpenApp: (String) -> Unit) {
    SectionCard(title = "Controlled apps") {
        val modeText = when (state.settings.selectionMode) {
            SelectionMode.ONLY_SELECTED -> "${state.selectedCount} selected"
            SelectionMode.ALL_EXCEPT -> "All except ${state.selectedCount}"
        }
        Text("$modeText · ${state.activeRuleCount} active rule(s)", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        if (state.controlledApps.isEmpty()) {
            Text(
                "No apps selected yet. Pick apps to route through the tunnel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.controlledApps.take(12).forEach { app ->
                    AssistChip(onClick = { onOpenApp(app.packageName) }, label = { Text(app.label) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenApps) { Text("Manage apps") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(state: DashboardUiState, viewModel: DashboardViewModel) {
    SectionCard(title = "Tunnel settings") {
        // Selection mode
        LabeledSwitchRow(
            label = "Only control selected apps",
            checked = state.settings.selectionMode == SelectionMode.ONLY_SELECTED,
            onCheckedChange = {
                viewModel.setSelectionMode(if (it) SelectionMode.ONLY_SELECTED else SelectionMode.ALL_EXCEPT)
                viewModel.applyAndMaybeRestart()
            },
        )
        LabeledSwitchRow(
            label = "Relay IPv6 (off = fast-reject)",
            checked = state.settings.ipv6Mode == Ipv6Mode.RELAY,
            onCheckedChange = {
                viewModel.setIpv6Mode(if (it) Ipv6Mode.RELAY else Ipv6Mode.FAST_REJECT)
                viewModel.applyAndMaybeRestart()
            },
        )
        LabeledSwitchRow(
            label = "Exempt DNS from throttling",
            checked = state.settings.exemptDns,
            onCheckedChange = { viewModel.setExemptDns(it) },
        )
        LabeledSwitchRow(
            label = "Auto-start after reboot",
            checked = state.settings.autoStartOnBoot,
            onCheckedChange = { viewModel.setAutoStart(it) },
        )
    }
}

@Composable
private fun LabeledSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
