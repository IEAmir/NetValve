package dev.netvalve.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.netvalve.log.LogEvent
import dev.netvalve.log.LogLevel
import dev.netvalve.log.Logger
import dev.netvalve.repository.LogRepository
import dev.netvalve.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogsUiState(
    val entries: List<LogEvent> = emptyList(),
    val minLevel: LogLevel = LogLevel.INFO,
    val query: String = "",
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger,
) : ViewModel() {

    private val minLevel = MutableStateFlow(LogLevel.INFO)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<LogsUiState> = combine(
        logRepository.recent(1000), minLevel, query,
    ) { entries, level, q ->
        val filtered = entries.filter { e ->
            e.level.ordinal >= level.ordinal &&
                (q.isBlank() || e.message.contains(q, ignoreCase = true) || e.category.name.contains(q, ignoreCase = true))
        }
        LogsUiState(filtered, level, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUiState())

    fun setMinLevel(level: LogLevel) {
        minLevel.value = level
        logger.setMinLevel(level)
        viewModelScope.launch { settingsRepository.updateSettings { it.copy(logLevel = level.ordinal) } }
    }

    fun setQuery(q: String) { query.value = q }

    fun clear() {
        viewModelScope.launch { logRepository.clear() }
    }

    /** Build the export text off the main thread and hand it to the share sheet. */
    fun export(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(logger.exportText()) }
    }
}
