package dev.netvalve.ui.appdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.repository.SettingsRepository
import dev.netvalve.stats.AppStat
import dev.netvalve.stats.StatsCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppDetailUiState(
    val packageName: String = "",
    val label: String = "",
    val draft: RuleDraft = RuleDraft(""),
    val stat: AppStat? = null,
    val saved: Boolean = true,
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appInfo: AppInfoLookup,
    private val statsCollector: StatsCollector,
) : ViewModel() {

    private val packageName = MutableStateFlow("")
    private val draft = MutableStateFlow(RuleDraft(""))
    private val saved = MutableStateFlow(true)

    private val statFlow = combine(packageName, statsCollector.snapshot) { pkg, snap ->
        val uid = appInfo.uidForPackage(pkg)
        snap.perApp.firstOrNull { it.uid == uid }
    }

    val uiState: StateFlow<AppDetailUiState> =
        combine(packageName, draft, saved, statFlow) { pkg, d, isSaved, stat ->
            AppDetailUiState(
                packageName = pkg,
                label = if (pkg.isBlank()) "" else appInfo.labelForPackage(pkg),
                draft = d,
                stat = stat,
                saved = isSaved,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppDetailUiState())

    fun load(pkg: String) {
        packageName.value = pkg
        viewModelScope.launch {
            val rule = settingsRepository.rules.first()[pkg]
            draft.value = RuleDraft.from(pkg, rule)
            saved.value = true
        }
    }

    fun edit(transform: (RuleDraft) -> RuleDraft) {
        draft.value = transform(draft.value)
        saved.value = false
    }

    fun save() {
        viewModelScope.launch {
            settingsRepository.upsertRule(draft.value.toAppRule())
            saved.value = true
        }
    }

    fun deleteRule() {
        viewModelScope.launch {
            settingsRepository.removeRule(packageName.value)
            draft.value = RuleDraft(packageName.value)
            saved.value = true
        }
    }
}
