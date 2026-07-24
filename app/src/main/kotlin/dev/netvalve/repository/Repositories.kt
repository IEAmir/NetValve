package dev.netvalve.repository

import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.GlobalSettings
import dev.netvalve.data.model.InstalledApp
import kotlinx.coroutines.flow.Flow

/**
 * Persists tunnel-wide settings and the per-app rule set (DataStore-backed).
 */
interface SettingsRepository {
    val settings: Flow<GlobalSettings>

    /** Per-app rules keyed by package name. Apps without an entry use defaults. */
    val rules: Flow<Map<String, AppRule>>

    suspend fun current(): GlobalSettings
    suspend fun currentRules(): Map<String, AppRule>
    suspend fun updateSettings(transform: (GlobalSettings) -> GlobalSettings)
    suspend fun upsertRule(rule: AppRule)
    suspend fun removeRule(packageName: String)
    suspend fun clearRules()
}

/**
 * Persists the set of user-selected package names (DataStore-backed). The
 * ONLY_SELECTED / ALL_EXCEPT interpretation lives in [GlobalSettings.selectionMode].
 */
interface AppSelectionRepository {
    val selectedPackages: Flow<Set<String>>
    suspend fun current(): Set<String>
    suspend fun setSelected(packages: Set<String>)
    suspend fun setSelected(packageName: String, selected: Boolean)
    suspend fun clear()
}

/**
 * uid <-> package lookup used by the engine for attribution and by the UI for
 * labels. Deliberately synchronous and cached; see InstalledAppsProvider.
 */
interface AppInfoLookup {
    /** All packages sharing [uid] (usually one; more with a shared user id). */
    fun packagesForUid(uid: Int): List<String>
    fun uidForPackage(packageName: String): Int?
    fun labelForPackage(packageName: String): String
    /** Force a refresh of the cache (e.g. after install/uninstall). */
    fun invalidate()
}

/** Enumerates launchable installed apps for the selection screen. */
interface InstalledAppsRepository : AppInfoLookup {
    suspend fun listUserApps(includeSystem: Boolean = false): List<InstalledApp>
}
