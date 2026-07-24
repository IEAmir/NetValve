package dev.netvalve.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.netvalve.network.UidResolver
import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.stats.StatsCollector
import dev.netvalve.stats.StatsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppStatRow(
    val uid: Int,
    val label: String,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val liveUploadBps: Long,
    val liveDownloadBps: Long,
    val active: Boolean,
)

data class StatsUiState(
    val snapshot: StatsSnapshot = StatsSnapshot(),
    val apps: List<AppStatRow> = emptyList(),
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsCollector: StatsCollector,
    private val appInfo: AppInfoLookup,
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = statsCollector.snapshot.map { snap ->
        val rows = snap.perApp.map { s ->
            val label = when (s.uid) {
                UidResolver.UID_UNKNOWN -> "Unknown"
                else -> s.packageName?.let { appInfo.labelForPackage(it) } ?: "uid ${s.uid}"
            }
            AppStatRow(s.uid, label, s.uploadBytes, s.downloadBytes, s.liveUploadBps, s.liveDownloadBps, s.active)
        }
        StatsUiState(snap, rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun reset() {
        viewModelScope.launch { statsCollector.reset() }
    }
}
