package dev.netvalve.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.netvalve.data.model.AppRule
import dev.netvalve.data.model.GlobalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * DataStore-backed [SettingsRepository]. Settings and the per-app rule map are
 * each stored as a single JSON blob under their own key, so reads/writes are
 * atomic and the schema evolves gracefully (unknown keys are ignored on read).
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : SettingsRepository {

    private val settingsKey = stringPreferencesKey("settings_json")
    private val rulesKey = stringPreferencesKey("rules_json")
    private val rulesSerializer = MapSerializer(String.serializer(), AppRule.serializer())

    override val settings: Flow<GlobalSettings> = dataStore.data.map { prefs ->
        prefs[settingsKey]?.let { runCatching { json.decodeFromString(GlobalSettings.serializer(), it) }.getOrNull() }
            ?: GlobalSettings.Default
    }

    override val rules: Flow<Map<String, AppRule>> = dataStore.data.map { prefs ->
        prefs[rulesKey]?.let { runCatching { json.decodeFromString(rulesSerializer, it) }.getOrNull() }
            ?: emptyMap()
    }

    override suspend fun current(): GlobalSettings = settings.first()
    override suspend fun currentRules(): Map<String, AppRule> = rules.first()

    override suspend fun updateSettings(transform: (GlobalSettings) -> GlobalSettings) {
        dataStore.edit { prefs ->
            val existing = prefs[settingsKey]
                ?.let { runCatching { json.decodeFromString(GlobalSettings.serializer(), it) }.getOrNull() }
                ?: GlobalSettings.Default
            prefs[settingsKey] = json.encodeToString(GlobalSettings.serializer(), transform(existing))
        }
    }

    override suspend fun upsertRule(rule: AppRule) {
        dataStore.edit { prefs ->
            val map = readRules(prefs).toMutableMap()
            map[rule.packageName] = rule
            prefs[rulesKey] = json.encodeToString(rulesSerializer, map)
        }
    }

    override suspend fun removeRule(packageName: String) {
        dataStore.edit { prefs ->
            val map = readRules(prefs).toMutableMap()
            if (map.remove(packageName) != null) {
                prefs[rulesKey] = json.encodeToString(rulesSerializer, map)
            }
        }
    }

    override suspend fun clearRules() {
        dataStore.edit { it[rulesKey] = json.encodeToString(rulesSerializer, emptyMap()) }
    }

    private fun readRules(prefs: Preferences): Map<String, AppRule> =
        prefs[rulesKey]?.let { runCatching { json.decodeFromString(rulesSerializer, it) }.getOrNull() } ?: emptyMap()
}
