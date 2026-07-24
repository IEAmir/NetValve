package dev.netvalve.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json

/** Single Preferences DataStore for all persisted config (settings, rules, selection). */
val Context.netValveDataStore: DataStore<Preferences> by preferencesDataStore(name = "netvalve_prefs")

/**
 * Lenient JSON used for persisting model objects. [ignoreUnknownKeys] keeps old
 * saved data readable after model evolution; [encodeDefaults] keeps round-trips
 * stable and explicit.
 */
val NetValveJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}
