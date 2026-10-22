package com.agri.raphael.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "raphael_settings")

class PreferencesStore(private val context: Context) {
    companion object {
        val KEY_GATEWAY_URL = stringPreferencesKey("gateway_base_url")
        val KEY_MODE_DEFAULT = stringPreferencesKey("mode_default")
    }

    val gatewayUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GATEWAY_URL] ?: "http://127.0.0.1:8765"
    }

    val selectedMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MODE_DEFAULT] ?: "fast"
    }

    suspend fun setGatewayUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GATEWAY_URL] = url
        }
    }

    suspend fun setSelectedMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MODE_DEFAULT] = mode
        }
    }
}
