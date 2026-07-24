package dev.netvalve.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    onOpenApp: (String) -> Unit,
    viewModel: AppSelectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Apps") })

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilterChip(
                selected = state.includeSystem,
                onClick = { viewModel.setIncludeSystem(!state.includeSystem) },
                label = { Text("Show system apps") },
            )
        }

        if (state.pendingRestart) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Selection changed — restart tunnel to apply", color = MaterialTheme.colorScheme.tertiary)
                androidx.compose.material3.TextButton(onClick = viewModel::applyChanges) { Text("Apply") }
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.apps.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "No apps match",
                subtitle = "Try a different search, or enable system apps.",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.apps, key = { it.app.packageName }) { row ->
                    AppRow(
                        label = row.app.label,
                        packageName = row.app.packageName,
                        isSystem = row.app.isSystem,
                        selected = row.selected,
                        onToggle = { viewModel.toggle(row.app.packageName, it) },
                        onOpen = { onOpenApp(row.app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    label: String,
    packageName: String,
    isSystem: Boolean,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!selected) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LetterAvatar(label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (isSystem) "$packageName · system" else packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpen) { Icon(Icons.Filled.Tune, contentDescription = "Configure") }
        Checkbox(checked = selected, onCheckedChange = onToggle)
    }
}

@Composable
private fun LetterAvatar(label: String) {
    Box(
        Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
