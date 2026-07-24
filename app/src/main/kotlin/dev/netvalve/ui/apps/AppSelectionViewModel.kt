package dev.netvalve.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.netvalve.data.model.InstalledApp
import dev.netvalve.repository.AppSelectionRepository
import dev.netvalve.repository.InstalledAppsRepository
import dev.netvalve.service.VpnController
import dev.netvalve.service.VpnStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppRowUi(val app: InstalledApp, val selected: Boolean)

data class AppsUiState(
    val apps: List<AppRowUi> = emptyList(),
    val query: String = "",
    val includeSystem: Boolean = false,
    val loading: Boolean = true,
    val pendingRestart: Boolean = false,
    val tunnelActive: Boolean = false,
)

@HiltViewModel
class AppSelectionViewModel @Inject constructor(
    private val installedApps: InstalledAppsRepository,
    private val selectionRepository: AppSelectionRepository,
    private val controller: VpnController,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val includeSystem = MutableStateFlow(false)
    private val allApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val pendingRestart = MutableStateFlow(false)

    init { reload() }

    val uiState: StateFlow<AppsUiState> = combine(
        allApps, selectionRepository.selectedPackages, query, includeSystem, loading,
    ) { apps, selected, q, _, isLoading ->
        val filtered = apps
            .filter { q.isBlank() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .map { AppRowUi(it, it.packageName in selected) }
        Triple(filtered, isLoading, selected)
    }.combine(controller.status) { triple, status ->
        AppsUiState(
            apps = triple.first,
            query = query.value,
            includeSystem = includeSystem.value,
            loading = triple.second,
            pendingRestart = pendingRestart.value && status.isActive,
            tunnelActive = status.isActive,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    fun setQuery(q: String) { query.value = q }

    fun setIncludeSystem(value: Boolean) {
        includeSystem.value = value
        reload()
    }

    fun toggle(packageName: String, selected: Boolean) {
        viewModelScope.launch {
            selectionRepository.setSelected(packageName, selected)
            if (controller.status.value.isActive) pendingRestart.value = true
        }
    }

    /** Rebuild the tunnel so the new allow/deny list takes effect. */
    fun applyChanges() {
        controller.restart()
        pendingRestart.value = false
    }

    private fun reload() {
        viewModelScope.launch {
            loading.value = true
            allApps.value = installedApps.listUserApps(includeSystem = includeSystem.value)
            loading.value = false
        }
    }
}
