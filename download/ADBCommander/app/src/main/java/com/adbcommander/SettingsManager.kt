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
 * Persists app settings (TV IP, ports, default command, package manager
 * layout) using DataStore Preferences.
 */
class SettingsManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "adb_commander_settings"
        )

        // ── Keys ────────────────────────────────────────────────────────
        val KEY_TV_HOST = stringPreferencesKey("tv_host")
        val KEY_TV_PORT = intPreferencesKey("tv_port")
        val KEY_PAIRING_PORT = intPreferencesKey("pairing_port")
        val KEY_DEFAULT_COMMAND = stringPreferencesKey("default_command")
        val KEY_INTENT_ACTION = stringPreferencesKey("intent_action")
        val KEY_TARGET_PACKAGE = stringPreferencesKey("target_package")
        val KEY_TARGET_ACTIVITY = stringPreferencesKey("target_activity")

        // ── Defaults ───────────────────────────────────────────────────
        const val DEFAULT_TV_HOST = ""
        const val DEFAULT_TV_PORT = 5555
        const val DEFAULT_PAIRING_PORT = 37155

        /**
         * Default command template. Tokens {URL} and {MIME} are bare —
         * they must NOT be wrapped in quotes here because [AdbManager.shellEscape]
         * is the single source of truth for parameter escaping.
         */
        const val DEFAULT_COMMAND =
            """am start -a android.intent.action.VIEW -d {URL} -t {MIME}"""

        const val DEFAULT_INTENT_ACTION = "android.intent.action.VIEW"
        const val DEFAULT_TARGET_PACKAGE = ""
        const val DEFAULT_TARGET_ACTIVITY = ""
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

    val intentAction: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_INTENT_ACTION] ?: DEFAULT_INTENT_ACTION
    }

    val targetPackage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_PACKAGE] ?: DEFAULT_TARGET_PACKAGE
    }

    val targetActivity: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TARGET_ACTIVITY] ?: DEFAULT_TARGET_ACTIVITY
    }

    // ── Suspend getters (for one-shot reads) ────────────────────────────

    suspend fun getTvHost(): String = tvHost.first()
    suspend fun getTvPort(): Int = tvPort.first()
    suspend fun getPairingPort(): Int = pairingPort.first()
    suspend fun getDefaultCommand(): String = defaultCommand.first()
    suspend fun getIntentAction(): String = intentAction.first()
    suspend fun getTargetPackage(): String = targetPackage.first()
    suspend fun getTargetActivity(): String = targetActivity.first()

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

    suspend fun setIntentAction(action: String) {
        context.dataStore.edit { prefs -> prefs[KEY_INTENT_ACTION] = action }
    }

    suspend fun setTargetPackage(pkg: String) {
        context.dataStore.edit { prefs -> prefs[KEY_TARGET_PACKAGE] = pkg }
    }

    suspend fun setTargetActivity(activity: String) {
        context.dataStore.edit { prefs -> prefs[KEY_TARGET_ACTIVITY] = activity }
    }

    /**
     * Rebuild the command template from the current package manager layout
     * configuration (intent action, target package, target activity).
     *
     * The generated template always includes `-d {URL} -t {MIME}`.
     * If a target package is specified, `-n {pkg}/.{activity}` is appended.
     */
    suspend fun rebuildCommandFromLayout(): String {
        val action = getIntentAction().ifBlank { DEFAULT_INTENT_ACTION }
        val pkg = getTargetPackage()
        val activity = getTargetActivity()

        val sb = StringBuilder()
        sb.append("am start -a $action")

        if (pkg.isNotBlank() && activity.isNotBlank()) {
            sb.append(" -n $pkg/.$activity")
        } else if (pkg.isNotBlank()) {
            sb.append(" -n $pkg/.$DEFAULT_TARGET_ACTIVITY")
        }

        sb.append(" -d {URL} -t {MIME}")
        return sb.toString()
    }
}
