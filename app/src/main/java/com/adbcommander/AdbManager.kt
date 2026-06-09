package com.adbcommander

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manages ADB connections and shell command execution using the dadb library.
 *
 * dadb is a pure Java/Kotlin ADB library that connects directly to adbd
 * without needing an adb binary or server. It handles key generation and
 * authentication automatically.
 *
 * Note: dadb does not support Android 11+ wireless pairing. For TVs running
 * Android 11+, you must pair them first using a computer or terminal app:
 *   adb pair <TV_IP>:<pairing_port>
 * Then connect using the regular connection port (shown under "IP address & port").
 */
object AdbManager {

    private const val TAG = "ADBCommander"
    private const val KEY_DIR = "adb_keys"

    /**
     * Get or create the ADB key pair for authentication.
     * Keys are stored in the app's private storage so they persist across launches.
     */
    private fun getKeyPair(context: Context): AdbKeyPair? {
        return try {
            val keyDir = File(context.filesDir, KEY_DIR)
            if (!keyDir.exists()) keyDir.mkdirs()

            val privateKey = File(keyDir, "adbkey")
            val publicKey = File(keyDir, "adbkey.pub")

            if (privateKey.exists() && publicKey.exists()) {
                Log.d(TAG, "Using existing ADB key pair")
                AdbKeyPair.read(privateKey, publicKey)
            } else {
                Log.d(TAG, "Generating new ADB key pair")
                // dadb will auto-generate keys if none exist
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ADB key pair", e)
            null
        }
    }

    // ── Connection & Shell ────────────────────────────────────────────

    /**
     * Execute a shell command on the TV over wireless ADB.
     *
     * @param host    TV IP address
     * @param port    ADB connection port (typically 5555)
     * @param command Full shell command string
     * @return        Result containing the command output, or a failure
     */
    suspend fun executeShell(context: Context, host: String, port: Int, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                val keyPair = getKeyPair(context)
                val dadb = if (keyPair != null) {
                    Dadb.create(host, port, keyPair)
                } else {
                    Dadb.create(host, port)
                }

                Log.d(TAG, "Executing shell: $command")
                val response = dadb.use { it.shell(command) }

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

    /**
     * Test connectivity by connecting and running `echo ok`.
     */
    suspend fun testConnection(context: Context, host: String, port: Int): Result<String> =
        executeShell(context, host, port, "echo ok")

    // ── URL extraction ────────────────────────────────────────────────

    /**
     * Extract the first URL from shared text.
     */
    fun extractUrl(sharedText: String): String? {
        val urlRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
        return urlRegex.find(sharedText)?.value
    }
}
