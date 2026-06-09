package com.adbcommander

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.InMemoryPrivateKeyStore
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Manages ADB connections, pairing, and shell command execution.
 *
 * Uses the Kadb library (pure Kotlin) for all ADB operations, including
 * Android 11+ wireless debugging pairing. No native adb binary is needed.
 */
object AdbManager {

    private const val TAG = "ADBCommander"
    private const val PREFS_NAME = "adb_commander_prefs"
    private const val KEY_PRIVATE_KEY = "adb_private_key_pem"

    /**
     * One-time initialisation of KadbCert. Uses InMemoryPrivateKeyStore
     * and persists the key to SharedPreferences for survival across restarts.
     */
    fun initCert(context: Context) {
        try {
            val store = InMemoryPrivateKeyStore()

            // Restore previously saved private key
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedKey = prefs.getString(KEY_PRIVATE_KEY, null)
            if (savedKey != null) {
                store.writePrivateKeyPemAtomic(savedKey.toByteArray(Charsets.UTF_8))
                Log.d(TAG, "Restored saved ADB private key")
            }

            KadbCert.configure(
                store = store,
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()

            // Persist the generated key for next launch
            val keyPem = KadbCert.exportPrivateKeyOrNull()
            if (keyPem != null) {
                prefs.edit().putString(KEY_PRIVATE_KEY, String(keyPem, Charsets.UTF_8)).apply()
            }
            Log.d(TAG, "KadbCert initialised successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise KadbCert", e)
        }
    }

    // ── Pairing (Android 11+ wireless debugging) ──────────────────────

    /**
     * Pair with a device running Android 11+.
     *
     * On the TV: Settings → Developer Options → Wireless debugging →
     * Pair device with pairing code. Enter the pairing port and code.
     */
    suspend fun pair(host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Pairing with $host:$port code=$code")
                Kadb.pair(host, port, code)
                Log.d(TAG, "Pairing succeeded with $host:$port")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                Result.failure(e)
            }
        }

    // ── Connection & Shell ────────────────────────────────────────────

    /**
     * Execute a shell command on the TV over wireless ADB.
     */
    suspend fun executeShell(host: String, port: Int, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                Log.d(TAG, "Executing shell: $command")
                val response = Kadb.create(host, port).use { it.shell(command) }
                val output = response.output.trim()
                val exitCode = response.exitCode
                Log.d(TAG, "Shell result exitCode=$exitCode output=$output")
                if (exitCode == 0) {
                    Result.success(output)
                } else {
                    Result.failure(IOException("Shell command failed (exit $exitCode): $output"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shell execution failed", e)
                Result.failure(e)
            }
        }

    suspend fun testConnection(host: String, port: Int): Result<String> =
        executeShell(host, port, "echo ok")

    // ── URL extraction ────────────────────────────────────────────────

    fun extractUrl(sharedText: String): String? {
        val urlRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
        return urlRegex.find(sharedText)?.value
    }
}
