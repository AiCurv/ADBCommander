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
    // Shell escaping — single source of truth
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Escape a value for safe inclusion in a POSIX shell command.
     *
     * Wraps the value in single quotes. Any embedded single quotes are
     * handled by ending the quoted string, adding an escaped quote,
     * and starting a new quoted string: `'` → `'\''`
     *
     * **This is the single source of truth for parameter escaping.**
     * Callers must NOT add their own quoting around values — rely on
     * this function exclusively to avoid double-quoting bugs like `''value''`.
     *
     * Example: `hello world` → `'hello world'`
     * Example: `it's here` → `'it'\''s here'`
     */
    fun shellEscape(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

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

    /** Image file extensions (with leading dot) for case-insensitive .contains() matching. */
    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg", ".tiff", ".tif"
    )

    /** Video file extensions (with leading dot) for case-insensitive .contains() matching. */
    private val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".ts"
    )

    /** Audio file extensions (with leading dot) for case-insensitive .contains() matching. */
    private val AUDIO_EXTENSIONS = listOf(
        ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a", ".wma", ".opus", ".mid", ".midi"
    )

    /**
     * Resolve a broad MIME type category by inspecting the full URL/path
     * string and/or the incoming MIME type from the sending app.
     *
     * Resolution order:
     * 1. If [incomingMimeType] is a specific, known media category
     *    (image/*, video/*, audio/*), derive the wildcard from it.
     * 2. Otherwise, perform a case-insensitive `.contains()` scan on
     *    [pathOrUrl]. If any known image/video/audio extension appears
     *    **anywhere** in the string, return the corresponding wildcard MIME.
     *    For example, a URL containing ".jpeg" or ".png" strictly maps to
     *    "image/*".
     * 3. Fallback to "*/*".
     *
     * @param incomingMimeType  The MIME type from the incoming Intent (may be null).
     * @param pathOrUrl         The full URL or path string to scan for extensions.
     * @return                  A wildcard MIME type: "image/*", "video/*", "audio/*", or "*/*".
     */
    fun resolveMimeType(incomingMimeType: String?, pathOrUrl: String): String {
        // ── Try deriving from the incoming MIME type first ──
        if (!incomingMimeType.isNullOrBlank() &&
            incomingMimeType != "application/octet-stream" &&
            incomingMimeType != "*/*"
        ) {
            when {
                incomingMimeType.startsWith("image/", ignoreCase = true) -> return "image/*"
                incomingMimeType.startsWith("video/", ignoreCase = true) -> return "video/*"
                incomingMimeType.startsWith("audio/", ignoreCase = true) -> return "audio/*"
                // Non-media specific types (text/plain, application/pdf, etc.)
                // fall through to the .contains() scan below.
            }
        }

        // ── Case-insensitive .contains() scan on the full URL/path ──
        val lower = pathOrUrl.lowercase()
        when {
            IMAGE_EXTENSIONS.any { lower.contains(it) } -> return "image/*"
            VIDEO_EXTENSIONS.any { lower.contains(it) } -> return "video/*"
            AUDIO_EXTENSIONS.any { lower.contains(it) } -> return "audio/*"
        }

        return "*/*"
    }

    /**
     * Prepare a command for ADB execution by replacing template tokens.
     *
     * Supported tokens:
     * - `{URL}`  — replaced with the shell-escaped shared URL/text.
     * - `{MIME}` — replaced with the shell-escaped resolved MIME type.
     *
     * Shell escaping is applied via [shellEscape] as the single source of
     * truth. The template must NOT include its own quotes around these
     * tokens — [shellEscape] handles all quoting automatically.
     *
     * Correct template:   `am start -a android.intent.action.VIEW -d {URL} -t {MIME}`
     * Incorrect template: `am start -a android.intent.action.VIEW -d "{URL}" -t "{MIME}"`
     *                    (would produce double-quoting: `''URL''`)
     *
     * @param template  The raw command template from settings.
     * @param url       The shared URL or text to substitute for {URL}.
     * @param mimeType  The resolved MIME category to substitute for {MIME}.
     * @return          The final command string ready for ADB execution.
     */
    fun prepareCommand(template: String, url: String, mimeType: String): String {
        return template
            .replace("{URL}", shellEscape(url))
            .replace("{MIME}", shellEscape(mimeType))
    }
}
