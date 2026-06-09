package com.adbcommander

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "adb_commander_settings"
        )
        val KEY_TV_HOST = stringPreferencesKey("tv_host")
        val KEY_TV_PORT = intPreferencesKey("tv_port")
        val KEY_PAIRING_PORT = intPreferencesKey("pairing_port")
        val KEY_DEFAULT_COMMAND = stringPreferencesKey("default_command")

        const val DEFAULT_TV_HOST = ""
        const val DEFAULT_TV_PORT = 5555
        const val DEFAULT_PAIRING_PORT = 37155
        const val DEFAULT_COMMAND = """am start -a android.intent.action.VIEW -d "{URL}" -t "video/*""""
    }

    val tvHost = context.dataStore.data.map { it[KEY_TV_HOST] ?: DEFAULT_TV_HOST }
    val tvPort = context.dataStore.data.map { it[KEY_TV_PORT] ?: DEFAULT_TV_PORT }
    val pairingPort = context.dataStore.data.map { it[KEY_PAIRING_PORT] ?: DEFAULT_PAIRING_PORT }
    val defaultCommand = context.dataStore.data.map { it[KEY_DEFAULT_COMMAND] ?: DEFAULT_COMMAND }

    suspend fun getTvHost(): String = tvHost.first()
    suspend fun getTvPort(): Int = tvPort.first()
    suspend fun getPairingPort(): Int = pairingPort.first()
    suspend fun getDefaultCommand(): String = defaultCommand.first()

    suspend fun setTvHost(host: String) { context.dataStore.edit { it[KEY_TV_HOST] = host } }
    suspend fun setTvPort(port: Int) { context.dataStore.edit { it[KEY_TV_PORT] = port } }
    suspend fun setPairingPort(port: Int) { context.dataStore.edit { it[KEY_PAIRING_PORT] = port } }
    suspend fun setDefaultCommand(command: String) { context.dataStore.edit { it[KEY_DEFAULT_COMMAND] = command } }
}
