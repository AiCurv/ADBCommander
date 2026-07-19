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
        val KEY_FIRST_INSTALL = booleanPreferencesKey("first_install_prompted")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        const val DEFAULT_TV_HOST = ""
        const val DEFAULT_TV_PORT = 5555
        const val DEFAULT_COMMAND = """am start -a android.intent.action.VIEW -d "{URL}" -t "{MIME}""""
        const val DEFAULT_AUTO_EXECUTE = false
        const val CONTENT_TYPE_URL = "url"
        const val CONTENT_TYPE_FILE = "file"
        const val DEFAULT_PRESET_NAME = "Open Link"

        // Theme mode constants
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // ── Preset constants ─────────────────────────────────────────
        private const val PRESETS_PREFS_NAME = "adb_commander_presets"
        private const val KEY_PRESETS_JSON = "presets_json"

        @Volatile
        private var globalPresetsPrefs: SharedPreferences? = null

        fun preload(context: Context) {
            if (globalPresetsPrefs == null) {
                synchronized(Companion) {
                    if (globalPresetsPrefs == null) {
                        globalPresetsPrefs = context.applicationContext
                            .getSharedPreferences(PRESETS_PREFS_NAME, Context.MODE_PRIVATE)
                    }
                }
            }
        }

        // ── v2.7.0: Synchronous warm cache for startup speed ──────────
        // DataStore's first read can take 50-200ms (file open + parse).
        // We warm these values into a volatile cache from App.onCreate()
        // so HomeTab can render instantly on first frame.
        @Volatile
        private var cachedTvHost: String? = null
        @Volatile
        private var cachedTvPort: Int? = null
        @Volatile
        private var cachedTvName: String? = null
        @Volatile
        private var cacheWarmed = false

        /**
         * Warm the connection-settings cache by reading DataStore once
         * from App.onCreate(). This is called on the main thread during
         * Application startup, but DataStore preferencesDataStore delegate
         * creates the file lazily — the first .data.map.first() call
         * triggers the file open, and subsequent reads are near-instant.
         *
         * Must be called from App.onCreate only.
         */
        fun warmCache(context: Context) {
            if (cacheWarmed) return
            synchronized(Companion) {
                if (cacheWarmed) return
                val appContext = context.applicationContext
                // Trigger DataStore file creation by accessing the delegate.
                // We use a simple kotlinx.coroutines.runBlocking to read
                // the three most latency-sensitive values synchronously.
                // This is safe in Application.onCreate (no ANR risk —
                // the Activity hasn't started yet).
                try {
                    val ds = appContext.dataStore
                    // Access the DataStore to trigger file init
                    kotlinx.coroutines.runBlocking {
                        val prefs = ds.data.first()
                        cachedTvHost = prefs[KEY_TV_HOST] ?: DEFAULT_TV_HOST
                        cachedTvPort = prefs[KEY_TV_PORT] ?: DEFAULT_TV_PORT
                        cachedTvName = prefs[KEY_SELECTED_TV_NAME] ?: ""
                    }
                } catch (e: Exception) {
                    // Non-fatal — the UI will fall back to the async path
                    cachedTvHost = null
                    cachedTvPort = null
                    cachedTvName = null
                }
                cacheWarmed = true
            }
        }

        /** Read cached TV host instantly (null if cache not warmed) */
        fun getCachedTvHost(): String? = cachedTvHost
        /** Read cached TV port instantly (null if cache not warmed) */
        fun getCachedTvPort(): Int? = cachedTvPort
        /** Read cached TV name instantly (null if cache not warmed) */
        fun getCachedTvName(): String? = cachedTvName

        private fun presetsPrefs(context: Context): SharedPreferences {
            return globalPresetsPrefs ?: synchronized(Companion) {
                globalPresetsPrefs ?: context.applicationContext
                    .getSharedPreferences(PRESETS_PREFS_NAME, Context.MODE_PRIVATE)
                    .also { globalPresetsPrefs = it }
            }
        }

        // Built-in presets — generic, not app-specific.
        // Users create their own app-specific presets via the App Selector.
        val BUILT_IN_PRESETS = listOf(
            Preset(
                "Open Link",
                """am start -a android.intent.action.VIEW -d "{URL}""""
            ),
            Preset(
                "Video Player",
                """am start -a android.intent.action.VIEW -d "{URL}" -t "{MIME}""""
            )
        )
    }

    data class Preset(
        val name: String,
        val command: String,
        val appPackage: String = ""
    ) {
        val usesFile: Boolean get() = command.contains("{FILE}")
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
    val firstInstallPrompted = context.dataStore.data.map { it[KEY_FIRST_INSTALL] ?: false }
    val themeMode = context.dataStore.data.map { it[KEY_THEME_MODE] ?: THEME_SYSTEM }

    suspend fun getTvHost(): String = tvHost.first()
    suspend fun getTvPort(): Int = tvPort.first()
    suspend fun getDefaultCommand(): String = defaultCommand.first()
    suspend fun getAutoExecute(): Boolean = autoExecute.first()
    suspend fun getSelectedPreset(): String = selectedPreset.first()
    suspend fun getContentType(): String = contentType.first()
    suspend fun getSelectedTvName(): String = selectedTvName.first()
    suspend fun isFirstInstallPrompted(): Boolean = firstInstallPrompted.first()
    suspend fun getThemeMode(): String = themeMode.first()

    suspend fun setTvHost(host: String) { context.dataStore.edit { it[KEY_TV_HOST] = host } }
    suspend fun setTvPort(port: Int) { context.dataStore.edit { it[KEY_TV_PORT] = port } }
    suspend fun setDefaultCommand(command: String) { context.dataStore.edit { it[KEY_DEFAULT_COMMAND] = command } }
    suspend fun setAutoExecute(auto: Boolean) { context.dataStore.edit { it[KEY_AUTO_EXECUTE] = auto } }
    suspend fun setSelectedPreset(name: String) { context.dataStore.edit { it[KEY_SELECTED_PRESET] = name } }
    suspend fun setContentType(type: String) { context.dataStore.edit { it[KEY_CONTENT_TYPE] = type } }
    suspend fun setSelectedTvName(name: String) { context.dataStore.edit { it[KEY_SELECTED_TV_NAME] = name } }
    suspend fun setFirstInstallPrompted(prompted: Boolean) { context.dataStore.edit { it[KEY_FIRST_INSTALL] = prompted } }
    suspend fun setThemeMode(mode: String) { context.dataStore.edit { it[KEY_THEME_MODE] = mode } }


    // ── Preset management via SharedPreferences ──────────────────────

    fun getAllPresets(): List<Preset> {
        val customPresets = loadCustomPresets()
        return BUILT_IN_PRESETS + customPresets
    }

    fun getPresetsForApp(packageName: String): List<Preset> {
        if (packageName.isBlank()) return emptyList()
        return loadCustomPresets().filter { it.appPackage == packageName }
    }

    fun saveCustomPreset(name: String, command: String, appPackage: String = ""): Boolean {
        if (name.isBlank()) return false
        if (BUILT_IN_PRESETS.any { it.name.equals(name, ignoreCase = true) }) return false

        val presets = loadCustomPresets().toMutableList()
        val existingIndex = presets.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (existingIndex >= 0) {
            presets[existingIndex] = Preset(name, command, appPackage)
        } else {
            presets.add(Preset(name, command, appPackage))
        }

        saveCustomPresets(presets)
        return true
    }

    fun deleteCustomPreset(name: String): Boolean {
        val presets = loadCustomPresets().toMutableList()
        val removed = presets.removeAll { it.name == name }
        if (removed) saveCustomPresets(presets)
        return removed
    }

    fun getPresetCommand(presetName: String): String? {
        BUILT_IN_PRESETS.find { it.name == presetName }?.let { return it.command }
        return loadCustomPresets().find { it.name == presetName }?.command
    }

    /**
     * Build an `am start` command from package exploration data.
     * Templates use double-quoted tokens ("{URL}", "{MIME}") for maximum
     * compatibility with magnet links and URLs containing special characters.
     */
    fun buildPresetFromPackage(
        presetName: String,
        packageName: String,
        action: String = "android.intent.action.VIEW",
        dataUri: String = """{URL}""",
        type: String = """{MIME}""",
        component: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append("am start")
        if (action.isNotBlank()) sb.append(" -a $action")
        if (dataUri.isNotBlank()) sb.append(" -d \"$dataUri\"")
        if (type.isNotBlank()) sb.append(" -t \"$type\"")
        if (component.isNotBlank()) {
            sb.append(" -n $component")
        } else if (packageName.isNotBlank()) {
            sb.append(" -n $packageName/.MainActivity")
        }
        return sb.toString()
    }

    private fun loadCustomPresets(): List<Preset> {
        val json = presetsPrefs(context).getString(KEY_PRESETS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Preset(
                    name = obj.getString("name"),
                    command = obj.getString("command"),
                    appPackage = obj.optString("appPackage", "")
                )
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
            obj.put("appPackage", p.appPackage)
            arr.put(obj)
        }
        presetsPrefs(context).edit().putString(KEY_PRESETS_JSON, arr.toString()).commit()
    }

    fun exportPresetsJson(): String {
        val presets = loadCustomPresets()
        val arr = JSONArray()
        presets.forEach { p ->
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("command", p.command)
            obj.put("appPackage", p.appPackage)
            arr.put(obj)
        }
        val root = JSONObject()
        root.put("presets", arr)
        return root.toString(2)
    }

    fun importPresetsJson(json: String): Int {
        return try {
            val root = JSONObject(json)
            val arr = root.getJSONArray("presets")
            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val command = obj.getString("command")
                val appPackage = obj.optString("appPackage", "")
                if (saveCustomPreset(name, command, appPackage)) {
                    count++
                }
            }
            count
        } catch (e: Exception) {
            -1
        }
    }
}
