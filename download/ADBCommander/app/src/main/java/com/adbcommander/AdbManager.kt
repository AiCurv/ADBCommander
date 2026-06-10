package com.adbcommander

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.store.OkioFilePrivateKeyStore
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

    /** Name of the private key file stored in app-private storage. */
    private const val KEY_FILE_NAME = "adb_private_key.pem"

    /**
     * One-time initialisation of KadbCert. Call this early (e.g. from the
     * Application class or the first Activity's onCreate). It is safe to call
     * multiple times — subsequent calls are no-ops once configured.
     */
    fun initCert(context: Context) {
        try {
            val keyPath = context.filesDir.resolve(KEY_FILE_NAME).toPath()
            val store = OkioFilePrivateKeyStore(privateKeyPath = keyPath)
            KadbCert.configure(
                store = store,
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()
            Log.d(TAG, "KadbCert initialised — private key at $keyPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise KadbCert", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Pairing (Android 11+ wireless debugging)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Pair with a device that has Android 11+ wireless debugging enabled.
     *
     * The pairing port is shown on the TV under:
     *   Settings → Developer Options → Wireless debugging → Pair device with pairing code
     *
     * @param host     TV IP address (e.g. "192.168.1.123")
     * @param port     Pairing port shown on the TV (NOT the connection port, e.g. 37155)
     * @param code     6-digit pairing code shown on the TV
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

    // ──────────────────────────────────────────────────────────────────────
    // Connection & Shell
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Execute a shell command on the TV over wireless ADB.
     *
     * @param host    TV IP address
     * @param port    ADB connection port (typically 5555 for non-11+, or the port
     *                shown under "Wireless debugging" → "IP address & port" for 11+)
     * @param command Full shell command string
     * @return        Result containing the command output, or a failure with details
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
                    Result.failure(
                        IOException("Shell command failed (exit $exitCode): $output")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shell execution failed", e)
                Result.failure(e)
            }
        }

    /**
     * Test connectivity by connecting and running `echo ok`.
     */
    suspend fun testConnection(host: String, port: Int): Result<String> =
        executeShell(host, port, "echo ok")

    // ──────────────────────────────────────────────────────────────────────
    // URL extraction helper
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Extract the first URL from shared text. Handles common URL formats
     * including those with query parameters and fragments.
     */
    fun extractUrl(sharedText: String): String? {
        // Regex matches http/https URLs, including those with query params
        val urlRegex = Regex(
            """https?://[^\s<>"{}|\\^`\[\]]+""",
            RegexOption.IGNORE_CASE
        )
        return urlRegex.find(sharedText)?.value
    }

    // ──────────────────────────────────────────────────────────────────────
    // MIME type resolution & command preparation
    // ──────────────────────────────────────────────────────────────────────

    /** Image file extensions that map to "image/*". */
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "tiff", "tif")

    /** Video file extensions that map to "video/*". */
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts")

    /** Audio file extensions that map to "audio/*". */
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus", "mid", "midi")

    /**
     * Resolve a broad MIME type category from a file extension and/or an
     * incoming MIME type supplied by the sending app.
     *
     * Resolution order:
     * 1. If [incomingMimeType] is non-null and not "application/octet-stream"
     *    or "*/*", derive the category from it (e.g. "image/png" → "image/*").
     * 2. Otherwise inspect [fileExtension]. Known image/video/audio extensions
     *    are mapped to their respective wildcard MIME; everything else falls
     *    back to "*/*".
     *
     * @param incomingMimeType  The MIME type from the incoming Intent (may be null).
     * @param fileExtension     The lower-case file extension without the dot (may be empty).
     * @return                  A wildcard MIME type: "image/*", "video/*", "audio/*", or "*/*".
     */
    fun resolveMimeType(incomingMimeType: String?, fileExtension: String): String {
        // ── Try deriving from the incoming MIME type first ──
        if (!incomingMimeType.isNullOrBlank() &&
            incomingMimeType != "application/octet-stream" &&
            incomingMimeType != "*/*"
        ) {
            return when {
                incomingMimeType.startsWith("image/", ignoreCase = true) -> "image/*"
                incomingMimeType.startsWith("video/", ignoreCase = true) -> "video/*"
                incomingMimeType.startsWith("audio/", ignoreCase = true) -> "audio/*"
                else -> {
                    // The incoming type is specific but not a media category
                    // (e.g. "text/plain", "application/pdf") — fall through
                    // to extension-based check.
                }
            }
        }

        // ── Fall back to file-extension heuristics ──
        val ext = fileExtension.lowercase()
        return when {
            ext in IMAGE_EXTENSIONS -> "image/*"
            ext in VIDEO_EXTENSIONS -> "video/*"
            ext in AUDIO_EXTENSIONS -> "audio/*"
            else -> "*/*"
        }
    }

    /**
     * Prepare a command for ADB execution by replacing template tokens.
     *
     * Supported tokens:
     * - `{URL}`  — replaced with the shared URL/text.
     * - `{MIME}` — replaced with the resolved wildcard MIME type
     *              (e.g. "video/*", "image/*", "audio/*", "*/*").
     *
     * @param template  The raw command template from settings.
     * @param url       The shared URL or text to substitute for {URL}.
     * @param mimeType  The resolved MIME category to substitute for {MIME}.
     * @return          The final command string ready for ADB execution.
     */
    fun prepareCommand(template: String, url: String, mimeType: String): String {
        return template
            .replace("{URL}", url)
            .replace("{MIME}", mimeType)
    }
}
