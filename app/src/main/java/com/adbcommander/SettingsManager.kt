package com.adbcommander

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
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
        val KEY_DEFAULT_COMMAND = stringPreferencesKey("default_command")
        val KEY_AUTO_EXECUTE = booleanPreferencesKey("auto_execute")
        val KEY_SELECTED_PRESET = stringPreferencesKey("selected_preset")
        val KEY_CONTENT_TYPE = stringPreferencesKey("content_type")
        val KEY_SELECTED_TV_NAME = stringPreferencesKey("selected_tv_name")

        const val DEFAULT_TV_HOST = ""
        const val DEFAULT_TV_PORT = 5555
        // v2.2.0: Default command is now package-agnostic (no Cx Player component).
        // Lets the TV's own intent resolver pick the handler for the MIME type.
        const val DEFAULT_COMMAND = """am start -a android.intent.action.VIEW -d {URL} -t {MIME}"""
        const val DEFAULT_AUTO_EXECUTE = false
        const val CONTENT_TYPE_URL = "url"
        const val CONTENT_TYPE_FILE = "file"

        // v2.2.0: The default preset name (used when none is persisted).
        const val DEFAULT_PRESET_NAME = "SmartTube"

        // ── Preset constants ─────────────────────────────────────────
        private const val PRESETS_PREFS_NAME = "adb_commander_presets"
        private const val KEY_PRESETS_JSON = "presets_json"

        // Built-in presets — bare {URL}/{MIME}/{FILE} placeholders, NO surrounding quotes.
        // shellEscape() in AdbManager adds single quotes at runtime.
        // stripQuotesAroundToken() strips any accidental quotes before escaping.
        //
        // v2.2.0: Purged "Universal Default" (Cx Player / com.cxinventor.file.explorer),
        // "Send to TV Downloads", and "APK Installer" per cleanup pass.
        val BUILT_IN_PRESETS = listOf(
            Preset("SmartTube", """am start -a android.intent.action.VIEW -d {URL} -n org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity""")
        )
    }

    data class Preset(val name: String, val command: String) {
        /** Does this preset use the {FILE} placeholder (for local file sharing)? */
        val usesFile: Boolean get() = command.contains("{FILE}")

        /** Does this preset use the {URL} placeholder (for URL sharing)? */
        val usesUrl: Boolean get() = command.contains("{URL}")
    }

    // ── DataStore settings ───────────────────────────────────────────

    val tvHost = context.dataStore.data.map { it[KEY_TV_HOST] ?: DEFAULT_TV_HOST }
    val tvPort = context.dataStore.data.map { it[KEY_TV_PORT] ?: DEFAULT_TV_PORT }
    val defaultCommand = context.dataStore.data.map { it[KEY_DEFAULT_COMMAND] ?: DEFAULT_COMMAND }
    val autoExecute = context.dataStore.data.map { it[KEY_AUTO_EXECUTE] ?: DEFAULT_AUTO_EXECUTE }
    val selectedPreset = context.dataStore.data.map { it[KEY_SELECTED_PRESET] ?: DEFAULT_PRESET_NAME }
    val contentType = context.dataStore.data.map { it[KEY_CONTENT_TYPE] ?: CONTENT_TYPE_URL }
    val selectedTvName = context.dataStore.data.map { it[KEY_SELECTED_TV_NAME] ?: "" }

    suspend fun getTvHost(): String = tvHost.first()
    suspend fun getTvPort(): Int = tvPort.first()
    suspend fun getDefaultCommand(): String = defaultCommand.first()
    suspend fun getAutoExecute(): Boolean = autoExecute.first()
    suspend fun getSelectedPreset(): String = selectedPreset.first()
    suspend fun getContentType(): String = contentType.first()
    suspend fun getSelectedTvName(): String = selectedTvName.first()

    suspend fun setTvHost(host: String) { context.dataStore.edit { it[KEY_TV_HOST] = host } }
    suspend fun setTvPort(port: Int) { context.dataStore.edit { it[KEY_TV_PORT] = port } }
    suspend fun setDefaultCommand(command: String) { context.dataStore.edit { it[KEY_DEFAULT_COMMAND] = command } }
    suspend fun setAutoExecute(auto: Boolean) { context.dataStore.edit { it[KEY_AUTO_EXECUTE] = auto } }
    suspend fun setSelectedPreset(name: String) { context.dataStore.edit { it[KEY_SELECTED_PRESET] = name } }
    suspend fun setContentType(type: String) { context.dataStore.edit { it[KEY_CONTENT_TYPE] = type } }
    suspend fun setSelectedTvName(name: String) { context.dataStore.edit { it[KEY_SELECTED_TV_NAME] = name } }

    // ── Preset management via SharedPreferences ──────────────────────

    private val presetsPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PRESETS_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns all presets: built-in first, then user-created custom presets.
     */
    fun getAllPresets(): List<Preset> {
        val customPresets = loadCustomPresets()
        return BUILT_IN_PRESETS + customPresets
    }

    /**
     * Save a new custom preset. The name must not collide with built-in presets.
     * Returns true if saved successfully, false if name already exists.
     */
    fun saveCustomPreset(name: String, command: String): Boolean {
        if (name.isBlank()) return false
        if (BUILT_IN_PRESETS.any { it.name.equals(name, ignoreCase = true) }) return false

        val presets = loadCustomPresets().toMutableList()
        val existingIndex = presets.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (existingIndex >= 0) {
            presets[existingIndex] = Preset(name, command)
        } else {
            presets.add(Preset(name, command))
        }

        saveCustomPresets(presets)
        return true
    }

    /**
     * Delete a custom preset by name. Cannot delete built-in presets.
     */
    fun deleteCustomPreset(name: String): Boolean {
        val presets = loadCustomPresets().toMutableList()
        val removed = presets.removeAll { it.name == name }
        if (removed) saveCustomPresets(presets)
        return removed
    }

    /**
     * Get the command template for a given preset name.
     * Returns null if preset not found.
     */
    fun getPresetCommand(presetName: String): String? {
        BUILT_IN_PRESETS.find { it.name == presetName }?.let { return it.command }
        return loadCustomPresets().find { it.name == presetName }?.command
    }

    /**
     * Build an `am start` command from package exploration data.
     * Bare {URL}/{FILE} placeholders — NO surrounding quotes.
     * shellEscape() in AdbManager adds single quotes at runtime.
     */
    fun buildPresetFromPackage(
        presetName: String,
        packageName: String,
        action: String = "android.intent.action.VIEW",
        dataUri: String = "{URL}",
        type: String = "",
        component: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append("am start")
        if (action.isNotBlank()) sb.append(" -a $action")
        if (dataUri.isNotBlank()) sb.append(" -d $dataUri")
        if (type.isNotBlank()) sb.append(" -t $type")
        if (component.isNotBlank()) sb.append(" -n $component")
        else if (packageName.isNotBlank()) sb.append(" $packageName")
        return sb.toString()
    }

    private fun loadCustomPresets(): List<Preset> {
        val json = presetsPrefs.getString(KEY_PRESETS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Preset(obj.getString("name"), obj.getString("command"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustomPresets(presets: List<Preset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("command", p.command)
            arr.put(obj)
        }
        presetsPrefs.edit().putString(KEY_PRESETS_JSON, arr.toString()).apply()
    }

    /**
     * Serialize all custom presets to a JSON string for export/backup.
     * Format: {"presets": [{"name": "...", "command": "..."}]}
     */
    fun exportPresetsJson(): String {
        val presets = loadCustomPresets()
        val arr = JSONArray()
        presets.forEach { p ->
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("command", p.command)
            arr.put(obj)
        }
        val root = JSONObject()
        root.put("presets", arr)
        return root.toString(2)
    }

    /**
     * Parse a JSON string and import presets. Returns the count of presets imported.
     * Format: {"presets": [{"name": "...", "command": "..."}]}
     * Skips presets that conflict with built-in names.
     */
    fun importPresetsJson(json: String): Int {
        return try {
            val root = JSONObject(json)
            val arr = root.getJSONArray("presets")
            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val command = obj.getString("command")
                if (saveCustomPreset(name, command)) {
                    count++
                }
            }
            count
        } catch (e: Exception) {
            -1
        }
    }
}
