package com.adbcommander

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val KEY_AUTO_EXECUTE = booleanPreferencesKey("auto_execute")
        val KEY_SELECTED_PRESET = intPreferencesKey("selected_preset")

        const val DEFAULT_TV_HOST = ""
        const val DEFAULT_TV_PORT = 5555
        const val DEFAULT_PAIRING_PORT = 0
        const val DEFAULT_COMMAND = """am start -a android.intent.action.VIEW -d "{URL}" -t "video/*""""
        const val DEFAULT_AUTO_EXECUTE = false

        // Built-in command presets
        val COMMAND_PRESETS = listOf(
            CommandPreset(0, "Open Video", """am start -a android.intent.action.VIEW -d "{URL}" -t "video/*""""),
            CommandPreset(1, "Open in Browser", """am start -a android.intent.action.VIEW -d "{URL}""""),
            CommandPreset(2, "Open in VLC", """am start -a android.intent.action.VIEW -d "{URL}" -t "video/*" -n org.videolan.vlc/org.videolan.vlc.gui.video.VideoPlayerActivity"""),
            CommandPreset(3, "Open in MX Player", """am start -a android.intent.action.VIEW -d "{URL}" -t "video/*" -n com.mxtech.videoplayer.ad/com.mxtech.videoplayer.ad.ActivityScreen"""),
            CommandPreset(4, "Custom Command", "")
        )
    }

    val tvHost = context.dataStore.data.map { it[KEY_TV_HOST] ?: DEFAULT_TV_HOST }
    val tvPort = context.dataStore.data.map { it[KEY_TV_PORT] ?: DEFAULT_TV_PORT }
    val pairingPort = context.dataStore.data.map { it[KEY_PAIRING_PORT] ?: DEFAULT_PAIRING_PORT }
    val defaultCommand = context.dataStore.data.map { it[KEY_DEFAULT_COMMAND] ?: DEFAULT_COMMAND }
    val autoExecute = context.dataStore.data.map { it[KEY_AUTO_EXECUTE] ?: DEFAULT_AUTO_EXECUTE }
    val selectedPreset = context.dataStore.data.map { it[KEY_SELECTED_PRESET] ?: 0 }

    suspend fun getTvHost(): String = tvHost.first()
    suspend fun getTvPort(): Int = tvPort.first()
    suspend fun getPairingPort(): Int = pairingPort.first()
    suspend fun getDefaultCommand(): String = defaultCommand.first()
    suspend fun getAutoExecute(): Boolean = autoExecute.first()
    suspend fun getSelectedPreset(): Int = selectedPreset.first()

    suspend fun setTvHost(host: String) { context.dataStore.edit { it[KEY_TV_HOST] = host } }
    suspend fun setTvPort(port: Int) { context.dataStore.edit { it[KEY_TV_PORT] = port } }
    suspend fun setPairingPort(port: Int) { context.dataStore.edit { it[KEY_PAIRING_PORT] = port } }
    suspend fun setDefaultCommand(command: String) { context.dataStore.edit { it[KEY_DEFAULT_COMMAND] = command } }
    suspend fun setAutoExecute(auto: Boolean) { context.dataStore.edit { it[KEY_AUTO_EXECUTE] = auto } }
    suspend fun setSelectedPreset(preset: Int) { context.dataStore.edit { it[KEY_SELECTED_PRESET] = preset } }
}

data class CommandPreset(
    val id: Int,
    val name: String,
    val command: String
)
