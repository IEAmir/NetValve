package dev.netvalve.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netvalve.log.LogEvent
import dev.netvalve.log.LogLevel
import dev.netvalve.ui.components.EmptyState
import dev.netvalve.ui.navigation.AppActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    actions: AppActions,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Logs") },
            actions = {
                IconButton(onClick = { viewModel.export(actions.exportLogs) }) { Icon(Icons.Filled.Share, "Export") }
                IconButton(onClick = viewModel::clear) { Icon(Icons.Filled.Delete, "Clear") }
            },
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.minLevel == level,
                    onClick = { viewModel.setMinLevel(level) },
                    label = { Text(level.name) },
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Filter logs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        if (state.entries.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Article,
                title = "No log entries yet",
                subtitle = "Events appear here as the tunnel runs.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(state.entries) { entry -> LogRow(entry) }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun LogRow(entry: LogEvent) {
    val color = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "${timeFormat.format(Date(entry.timestampMillis))}  ${entry.level.name.take(1)}/${entry.category.name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            entry.message + (entry.packageName?.let { "  ($it)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
    }
}
