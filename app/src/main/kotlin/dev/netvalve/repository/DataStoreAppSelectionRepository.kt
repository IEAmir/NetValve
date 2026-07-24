package dev.netvalve.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore-backed [AppSelectionRepository] storing the chosen package names. */
class DataStoreAppSelectionRepository(
    private val dataStore: DataStore<Preferences>,
) : AppSelectionRepository {

    private val selectedKey = stringSetPreferencesKey("selected_packages")

    override val selectedPackages: Flow<Set<String>> =
        dataStore.data.map { it[selectedKey] ?: emptySet() }

    override suspend fun current(): Set<String> = selectedPackages.first()

    override suspend fun setSelected(packages: Set<String>) {
        dataStore.edit { it[selectedKey] = packages }
    }

    override suspend fun setSelected(packageName: String, selected: Boolean) {
        dataStore.edit { prefs ->
            val cur = (prefs[selectedKey] ?: emptySet()).toMutableSet()
            if (selected) cur.add(packageName) else cur.remove(packageName)
            prefs[selectedKey] = cur
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(selectedKey) }
    }
}
