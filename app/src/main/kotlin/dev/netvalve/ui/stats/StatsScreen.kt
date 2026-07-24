package dev.netvalve.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netvalve.ui.components.EmptyState
import dev.netvalve.ui.components.LabeledRow
import dev.netvalve.ui.components.SectionCard
import dev.netvalve.ui.components.StatTile
import dev.netvalve.utils.Format

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val s = state.snapshot

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Statistics") },
            actions = { TextButton(onClick = viewModel::reset) { Text("Reset") } },
        )
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "Totals") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile("Downloaded", Format.bytes(s.totalDownload))
                        StatTile("Uploaded", Format.bytes(s.totalUpload))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile("Live ↓", Format.rate(s.liveDownloadBps))
                        StatTile("Avg ↓", Format.rate(s.avgDownloadBps))
                        StatTile("Peak ↓", Format.rate(s.peakDownloadBps))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile("Live ↑", Format.rate(s.liveUploadBps))
                        StatTile("Avg ↑", Format.rate(s.avgUploadBps))
                        StatTile("Peak ↑", Format.rate(s.peakUploadBps))
                    }
                }
            }
            item {
                SectionCard(title = "Connections") {
                    LabeledRow("Active", s.activeConnections.toString())
                    LabeledRow("Throttled (session)", s.throttledConnections.toString())
                    LabeledRow("Blocked (session)", s.blockedConnections.toString())
                    LabeledRow("DNS queries", s.dnsQueries.toString())
                    LabeledRow("Avg connect latency", "${s.avgConnectLatencyMillis} ms")
                    LabeledRow("Session", Format.duration(s.sessionDurationMillis))
                }
            }
            if (state.apps.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.QueryStats,
                        title = "No traffic recorded yet",
                        subtitle = "Turn on the tunnel and use a controlled app.",
                        modifier = Modifier.height(220.dp),
                    )
                }
            } else {
                item { Text("Per-app", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                items(state.apps, key = { it.uid }) { app -> AppStatCard(app) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun AppStatCard(app: AppStatRow) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (app.active) Text("● live", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("↓ total", Format.bytes(app.downloadBytes))
            StatTile("↑ total", Format.bytes(app.uploadBytes))
            StatTile("↓ live", Format.rate(app.liveDownloadBps))
            StatTile("↑ live", Format.rate(app.liveUploadBps))
        }
    }
}
