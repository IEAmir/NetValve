package dev.netvalve.ui.appdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netvalve.data.model.BandwidthUnit
import dev.netvalve.ui.components.LabeledRow
import dev.netvalve.ui.components.SectionCard
import dev.netvalve.utils.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    onBack: () -> Unit,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(packageName) { viewModel.load(packageName) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val d = state.draft

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.label.ifBlank { packageName }, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = viewModel::save, enabled = !state.saved) {
                    Icon(Icons.Filled.Save, "Save")
                }
            },
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Live per-app stats
            SectionCard(title = "This app") {
                val s = state.stat
                LabeledRow("Downloaded", Format.bytes(s?.downloadBytes ?: 0))
                LabeledRow("Uploaded", Format.bytes(s?.uploadBytes ?: 0))
                LabeledRow("Live ↓ / ↑", "${Format.rate(s?.liveDownloadBps ?: 0)}  /  ${Format.rate(s?.liveUploadBps ?: 0)}")
            }

            // Access
            SectionCard(title = "Access") {
                SwitchRow("Block all traffic", d.blocked) { viewModel.edit { r -> r.copy(blocked = it) } }
                SwitchRow("Rule enabled", d.enabled) { viewModel.edit { r -> r.copy(enabled = it) } }
            }

            // Bandwidth caps
            SectionCard(title = "Bandwidth limits") {
                CapEditor(
                    label = "Download cap",
                    enabled = d.downloadEnabled,
                    value = d.downloadValue,
                    unit = d.downloadUnit,
                    onEnabled = { viewModel.edit { r -> r.copy(downloadEnabled = it) } },
                    onValue = { viewModel.edit { r -> r.copy(downloadValue = it) } },
                    onUnit = { viewModel.edit { r -> r.copy(downloadUnit = it) } },
                )
                Spacer(Modifier.height(12.dp))
                CapEditor(
                    label = "Upload cap",
                    enabled = d.uploadEnabled,
                    value = d.uploadValue,
                    unit = d.uploadUnit,
                    onEnabled = { viewModel.edit { r -> r.copy(uploadEnabled = it) } },
                    onValue = { viewModel.edit { r -> r.copy(uploadValue = it) } },
                    onUnit = { viewModel.edit { r -> r.copy(uploadUnit = it) } },
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow("Throttle in background only", d.backgroundOnly) {
                    viewModel.edit { r -> r.copy(backgroundOnly = it) }
                }
            }

            // Conditions
            SectionCard(title = "Conditions (optional)") {
                Text(
                    "The rule applies only when ALL selected conditions hold.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(d.wifi, { viewModel.edit { r -> r.copy(wifi = !r.wifi) } }, label = { Text("Wi‑Fi") })
                    FilterChip(d.mobile, { viewModel.edit { r -> r.copy(mobile = !r.mobile) } }, label = { Text("Mobile") })
                    FilterChip(d.roamingOnly, { viewModel.edit { r -> r.copy(roamingOnly = !r.roamingOnly) } }, label = { Text("Roaming") })
                    FilterChip(d.chargingOnly, { viewModel.edit { r -> r.copy(chargingOnly = !r.chargingOnly) } }, label = { Text("Charging") })
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Only when battery below ${d.batteryBelow}%", d.batteryBelowEnabled) {
                    viewModel.edit { r -> r.copy(batteryBelowEnabled = it) }
                }
            }

            // Schedule
            SectionCard(title = "Schedule (optional)") {
                SwitchRow("Enable schedule", d.scheduleEnabled) { viewModel.edit { r -> r.copy(scheduleEnabled = it) } }
                if (d.scheduleEnabled) {
                    Spacer(Modifier.height(8.dp))
                    HourStepper("Start", d.startHour) { viewModel.edit { r -> r.copy(startHour = it) } }
                    HourStepper("End", d.endHour) { viewModel.edit { r -> r.copy(endHour = it) } }
                    Spacer(Modifier.height(8.dp))
                    DayPicker(d.days) { day ->
                        viewModel.edit { r -> r.copy(days = r.days.toMutableSet().apply { if (!add(day)) remove(day) }) }
                    }
                }
            }

            // Warning threshold
            SectionCard(title = "Usage warning") {
                SwitchRow("Warn at ${d.warnPercent}% of cap", d.warnEnabled) {
                    viewModel.edit { r -> r.copy(warnEnabled = it) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save, enabled = !state.saved) { Text("Save rule") }
                OutlinedButton(onClick = viewModel::deleteRule) { Text("Clear rule") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CapEditor(
    label: String,
    enabled: Boolean,
    value: Long,
    unit: BandwidthUnit,
    onEnabled: (Boolean) -> Unit,
    onValue: (Long) -> Unit,
    onUnit: (BandwidthUnit) -> Unit,
) {
    SwitchRow(label, enabled, onEnabled)
    if (enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value.toString(),
                onValueChange = { onValue(it.filter(Char::isDigit).toLongOrNull()?.coerceAtLeast(1) ?: 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BandwidthUnit.entries.forEach { u ->
                    FilterChip(selected = unit == u, onClick = { onUnit(u) }, label = { Text(u.label) })
                }
            }
        }
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((hour + 23) % 24) }) { Text("−") }
        Spacer(Modifier.width(8.dp))
        Text("%02d:00".format(hour), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange((hour + 1) % 24) }) { Text("+") }
    }
}

@Composable
private fun DayPicker(days: Set<Int>, onToggle: (Int) -> Unit) {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEachIndexed { index, name ->
            val day = index + 1
            FilterChip(selected = day in days, onClick = { onToggle(day) }, label = { Text(name) })
        }
    }
}
