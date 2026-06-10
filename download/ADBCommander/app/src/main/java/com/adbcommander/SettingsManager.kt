package com.adbcommander

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists app settings (TV IP, ports, default command) using DataStore.
 */
class SettingsManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "adb_commander_settings"
        )

        val KEY_TV_HOST = stringPreferencesKey("tv_host")
        val KEY_TV_PORT = intPreferencesKey("tv_port")
        val KEY_PAIRING_PORT = intPreferencesKey("pairing_port")
        val KEY_DEFAULT_COMMAND = stringPreferencesKey("default_command")

        // Sensible defaults
        const val DEFAULT_TV_HOST = ""
        const val DEFAULT_TV_PORT = 5555
        const val DEFAULT_PAIRING_PORT = 37155
        const val DEFAULT_COMMAND =
            """am start -a android.intent.action.VIEW -d "{URL}" -t "{MIME}""""
    }

    // ── Flow-based getters ──────────────────────────────────────────────

    val tvHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TV_HOST] ?: DEFAULT_TV_HOST
    }

    val tvPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_TV_PORT] ?: DEFAULT_TV_PORT
    }

    val pairingPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_PAIRING_PORT] ?: DEFAULT_PAIRING_PORT
    }

    val defaultCommand: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_COMMAND] ?: DEFAULT_COMMAND
    }

    // ── Suspend getters (for one-shot reads) ────────────────────────────

    suspend fun getTvHost(): String = tvHost.first()
    suspend fun getTvPort(): Int = tvPort.first()
    suspend fun getPairingPort(): Int = pairingPort.first()
    suspend fun getDefaultCommand(): String = defaultCommand.first()

    // ── Setters ─────────────────────────────────────────────────────────

    suspend fun setTvHost(host: String) {
        context.dataStore.edit { prefs -> prefs[KEY_TV_HOST] = host }
    }

    suspend fun setTvPort(port: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_TV_PORT] = port }
    }

    suspend fun setPairingPort(port: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_PAIRING_PORT] = port }
    }

    suspend fun setDefaultCommand(command: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DEFAULT_COMMAND] = command }
    }
}
