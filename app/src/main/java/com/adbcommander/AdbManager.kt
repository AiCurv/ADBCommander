package com.adbcommander

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Manages ADB connections using libadb-android.
 * Handles RSA key pair generation, ADB authentication, shell execution,
 * and file push operations.
 */
object AdbManager {

    private const val TAG = "ADBCommander"
    private const val PREFS_NAME = "adb_keys"
    private const val KEY_PRIVATE = "private_key_b64"
    private const val KEY_CERT = "certificate_b64"
    private const val REMOTE_FILE_DIR = "/sdcard/Download"
    private const val REMOTE_FILE_NAME = "remote_shared_file.bin"

    private var managerInstance: AdbConnectionManager? = null

    fun getManager(context: Context): AdbConnectionManager {
        managerInstance?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manager = AdbConnectionManager(prefs)
        managerInstance = manager
        return manager
    }

    /**
     * Strip any "adb shell " or "adb " prefix that users commonly paste.
     * libadb-android's shell stream is already a shell — sending "adb shell"
     * inside it would be a recursive no-op error.
     */
    fun sanitizeCommand(raw: String): String {
        var clean = raw.trim()
        val adbShellPattern = Regex("""^adb\s+shell\s+""", RegexOption.IGNORE_CASE)
        clean = adbShellPattern.replaceFirst(clean, "")
        val adbPattern = Regex("""^adb\s+""", RegexOption.IGNORE_CASE)
        clean = adbPattern.replaceFirst(clean, "")
        return clean.trim()
    }

    /**
     * Escape a string for safe insertion into a Linux shell command.
     * Wraps the value in single quotes and escapes any embedded single quotes.
     * This prevents &, ?, =, ;, |, and other shell metacharacters from
     * fragmenting the command.
     *
     * Example: https://site.com/file.apk?id=1&type=mp4
     *   → 'https://site.com/file.apk?id=1&type=mp4'
     *
     * IMPORTANT: Preset templates must use bare {URL} / {FILE} placeholders
     * with NO surrounding quotes. shellEscape() adds the quotes at runtime.
     * Double-quoting ('{URL}' + shellEscape) produces ''url'' which is wrong.
     */
    fun shellEscape(value: String): String {
        val escaped = value.replace("'", "'\\''")
        return "'$escaped'"
    }

    /**
     * Strip any surrounding single or double quotes around a placeholder token
     * in the template string. This prevents double-quoting when shellEscape()
     * later wraps the substituted value in single quotes.
     *
     * Example: am start -d '{URL}' → am start -d {URL}
     *          am start -d "{URL}" → am start -d {URL}
     *
     * Uses plain String.replace() instead of Regex because tokens contain
     * curly braces ({URL}, {MIME}, {FILE}) which are regex quantifier
     * metacharacters and would throw PatternSyntaxException if passed
     * to Regex unescaped.
     */
    private fun stripQuotesAroundToken(template: String, token: String): String {
        return template
            .replace("'$token'", token)
            .replace("\"$token\"", token)
    }

    /**
     * Replace URL placeholders in a command template with the actual shared URL,
     * safely shell-escaped so metacharacters like &, ?, = don't fragment the command.
     * Supports both {URL} and the literal YOUR_VIDEO_URL as placeholders.
     *
     * IMPORTANT: Any surrounding quotes around {URL} or {MIME} in the template
     * are stripped BEFORE substitution to prevent double-quoting artifacts
     * like ''url'' being sent to the TV.
     */
    // AI AGENT NOTE: The substitution order is load-bearing — strip quotes
    // BEFORE escaping, never the other way around. Swapping the order would
    // double-escape user-entered quotes and break URLs that legitimately
    // contain single quotes. See developer-context.md §3 "Token stripping
    // and escaping order".
    fun prepareCommand(template: String, sharedUrl: String, mimeType: String): String {
        val escapedUrl = shellEscape(sharedUrl)
        val escapedMime = shellEscape(mimeType)
        // Strip any quotes around placeholders in the template first
        var cleanTemplate = stripQuotesAroundToken(template, "{URL}")
        cleanTemplate = stripQuotesAroundToken(cleanTemplate, "{MIME}")
        cleanTemplate = stripQuotesAroundToken(cleanTemplate, "{FILE}")
        var cmd = cleanTemplate
            .replace("{URL}", escapedUrl)
            .replace("{MIME}", escapedMime)
            .replace("YOUR_VIDEO_URL", escapedUrl)
        return sanitizeCommand(cmd)
    }

    /**
     * Replace file placeholder {FILE} with the remote file path on the TV,
     * and {URL} with the HTTP streaming URL. Both are properly shell-escaped.
     *
     * IMPORTANT: Any surrounding quotes around placeholders in the template
     * are stripped BEFORE substitution to prevent double-quoting artifacts.
     */
    fun prepareFileCommand(template: String, remoteFilePath: String, httpUrl: String, mimeType: String): String {
        // Strip any quotes around placeholders in the template first
        var cleanTemplate = stripQuotesAroundToken(template, "{URL}")
        cleanTemplate = stripQuotesAroundToken(cleanTemplate, "{MIME}")
        cleanTemplate = stripQuotesAroundToken(cleanTemplate, "{FILE}")
        val escapedMime = shellEscape(mimeType)
        var cmd = cleanTemplate
            .replace("{MIME}", escapedMime)
        if (remoteFilePath.isNotBlank()) {
            cmd = cmd.replace("{FILE}", shellEscape("file://$remoteFilePath"))
        }
        if (httpUrl.isNotBlank()) {
            cmd = cmd.replace("{URL}", shellEscape(httpUrl))
                .replace("YOUR_VIDEO_URL", shellEscape(httpUrl))
        }
        return sanitizeCommand(cmd)
    }

    /**
     * Connect to TV and execute a shell command.
     * The command is sanitized before sending to remove any "adb shell" prefix.
     */
    suspend fun executeShell(context: Context, host: String, port: Int, rawCommand: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val command = sanitizeCommand(rawCommand)
                if (command.isBlank()) {
                    return@withContext Result.failure(IOException("Command is empty after sanitization"))
                }

                val manager = getManager(context)
                Log.d(TAG, "Connecting to $host:$port — sanitized command: $command")

                // v2.3.1: Hard 8-second ceiling on the connect() call itself.
                // The library's connect() blocks indefinitely if the TV is
                // showing the "Allow ADB debugging?" auth dialog and the user
                // hasn't responded yet — or if the TV needs pairing first.
                // Without this timeout the ConnectingOverlay spins forever
                // and the user has to force-kill the app. withTimeoutOrNull
                // returns null on timeout so we surface a clear error.
                val connected = withTimeoutOrNull(8000L) { manager.connect(host, port) }
                if (connected != true) {
                    return@withContext Result.failure(
                        IOException(
                            "Could not reach $host:$port within 8s. " +
                            "On the TV: open Settings → Developer options → Wireless debugging, " +
                            "tap 'Pair device with pairing code', and use the Pair button below first. " +
                            "If already paired, ensure the TV is awake and on the same WiFi."
                        )
                    )
                }

                val stream: AdbStream = manager.openStream("shell:$command")

                val outputStream = ByteArrayOutputStream()
                val inputStream = stream.openInputStream()
                val buffer = ByteArray(4096)

                var totalRead = 0
                // AI AGENT NOTE: This 10-second read deadline is the ONLY thing
                // preventing executeShell from hanging forever if the TV accepts
                // the connection but never responds (which happens when the TV
                // is mid-reboot, in standby, or running a long-running command
                // like `pm install`). Do NOT raise this above 15 seconds — the
                // share-sheet UX requires the dialog to either succeed or fail
                // fast so the user can retry. See developer-context.md §2.2.
                val deadline = System.currentTimeMillis() + 10000

                while (System.currentTimeMillis() < deadline) {
                    if (inputStream.available() > 0) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                    } else if (totalRead > 0) {
                        Thread.sleep(300)
                        if (inputStream.available() == 0) break
                    } else {
                        Thread.sleep(100)
                    }
                }

                inputStream.close()
                stream.close()
                manager.disconnect()

                val output = outputStream.toString("UTF-8").trim()
                Log.d(TAG, "Shell output: $output")
                Result.success(output.ifBlank { "Command executed (no output)" })
            } catch (e: Exception) {
                Log.e(TAG, "Shell execution failed", e)
                try { getManager(context).disconnect() } catch (_: Exception) {}
                Result.failure(IOException("Connection failed: ${e.message}", e))
            }
        }

    suspend fun testConnection(context: Context, host: String, port: Int): Result<String> =
        executeShell(context, host, port, "echo ok")

    /**
     * Pair with a TV that has never been connected before.
     *
     * On Android 11+ TVs, wireless debugging requires a one-time pairing
     * handshake (SPAKE2 over TLS) before plain `connect()` on port 5555
     * will succeed. The user initiates pairing on the TV side via:
     *   Settings → Developer options → Wireless debugging →
     *   Pair device with pairing code
     * which displays a random port (NOT 5555) and a 6-digit code.
     *
     * This method wraps the library's `pair()` call with an 8-second
     * timeout so a wrong port/code doesn't hang the UI forever.
     *
     * @param host TV IP address
     * @param pairPort The pairing port shown on the TV (random, NOT 5555)
     * @param code The 6-digit pairing code shown on the TV
     * @return success or failure with a descriptive message
     */
    suspend fun pairDevice(
        context: Context,
        host: String,
        pairPort: Int,
        code: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val manager = getManager(context)
            Log.d(TAG, "Pairing with $host:$pairPort (code=${code.length} digits)")
            val paired = withTimeoutOrNull(8000L) { manager.pair(host, pairPort, code) }
            if (paired != true) {
                Result.failure(IOException("Pairing timed out. Check the IP/port/code and that the TV is still showing the pairing screen."))
            } else {
                Log.d(TAG, "Pairing succeeded for $host")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed", e)
            Result.failure(IOException("Pairing failed: ${e.message}", e))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  v2.3.0 — TV-side package + icon discovery
    //
    //  These helpers are ADDITIVE — they do not modify any existing
    //  method signatures on AdbManager (developer-context.md §2.3 says
    //  renaming or removing existing methods is forbidden; adding new
    //  ones is welcome). All shell I/O goes through the existing
    //  [executeShell] pipeline so it inherits the same 10s read deadline
    //  and Dispatchers.IO threading discipline (developer-context.md §2.2).
    // ─────────────────────────────────────────────────────────────────────

    /**
     * List installed packages on the TV. Mirrors `pm list packages`.
     *
     * @param includeSystem when false, passes `-3` so only third-party
     *   packages are returned (matches the screenshot's "THIRD-PARTY"
     *   tab); when true, returns everything ("ALL APPS" tab).
     */
    suspend fun listTvPackages(
        context: Context,
        host: String,
        port: Int,
        includeSystem: Boolean
    ): Result<List<String>> {
        val cmd = if (includeSystem) "pm list packages" else "pm list packages -3"
        val result = executeShell(context, host, port, cmd)
        if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IOException("Unknown scan error"))
        val pkgs = result.getOrDefault("")
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
        return Result.success(pkgs)
    }

    /**
     * Fetch the launcher icon PNG for a single package on the TV and
     * return its raw bytes. The icon is extracted straight from the
     * package's base APK using `unzip -p`, so no full-APK download is
     * needed — typical payload is 5–40 KB per icon.
     *
     * Pipeline (all executed as one shell invocation on the TV):
     *   1. `pm path <pkg>`   →  package:/data/app/…/base.apk
     *   2. `unzip -l <apk>`  →  list APK entries, grep for launcher PNG
     *   3. `unzip -p <apk> <iconEntry>` →  stream PNG bytes to stdout
     *   4. `base64`          →  ASCII-encode so the bytes survive the
     *                            ADB shell transport (binary would corrupt)
     *
     * The icon-selection regex prefers higher-density mipmap buckets
     * (xxxhdpi → xxhdpi → xhdpi → hdpi → mdpi) and falls back to
     * `ic_launcher_round` if the square variant is absent. If no
     * launcher entry is found, returns a failure so the caller can
     * render the default placeholder.
     *
     * Runs entirely on Dispatchers.IO via [executeShell].
     */
    suspend fun fetchTvAppIconBytes(
        context: Context,
        host: String,
        port: Int,
        packageName: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            // Step 1 — resolve APK path. `pm path` may return multiple
            // lines for split APKs; we use the first (base.apk) which
            // always contains the manifest-declared launcher icon.
            val pathResult = executeShell(context, host, port, "pm path $packageName")
            if (pathResult.isFailure) {
                return@withContext Result.failure(IOException("pm path failed: ${pathResult.exceptionOrNull()?.message}"))
            }
            val apkPath = pathResult.getOrDefault("")
                .lines()
                .firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?.trim()
                ?: return@withContext Result.failure(IOException("No APK path for $packageName"))

            // Step 2 + 3 + 4 — single shell pipeline. Doing it in one
            // shot avoids reconnecting to the TV three times per icon
            // (each executeShell opens a fresh connection per §1 of the
            // developer context). The shell command below is what runs
            // on the TV:
            //
            //   unzip -l <apk> 2>/dev/null \
            //     | grep -oE 'res/[^ ]+ic_launcher[^ ]*\.(png|webp)' \
            //     | sort -r \
            //     | head -1 \
            //     | xargs -I{} unzip -p <apk> {} 2>/dev/null \
            //     | base64
            //
            // The `sort -r` trick ranks mipmap-xhdpi above mipmap-hdpi
            // alphabetically (x > h) which is the density preference we
            // want. If grep finds nothing, the rest of the pipeline
            // produces no output and we return a failure.
            val pipeline = """
                ICON=${'$'}(unzip -l '$apkPath' 2>/dev/null | grep -oE 'res/[^ ]+ic_launcher[^ ]*\.(png|webp)' | sort -r | head -1);
                if [ -n "${'$'}ICON" ]; then unzip -p '$apkPath' "${'$'}ICON" 2>/dev/null | base64; fi
            """.trimIndent()

            val iconResult = executeShell(context, host, port, pipeline)
            if (iconResult.isFailure) {
                return@withContext Result.failure(IOException("Icon pipeline failed: ${iconResult.exceptionOrNull()?.message}"))
            }
            val b64 = iconResult.getOrDefault("").trim()
            if (b64.isBlank()) {
                return@withContext Result.failure(IOException("No launcher icon entry in $apkPath"))
            }
            // The base64 output may contain newlines every 76 chars
            // (standard GNU base64 wrapping). Strip them before decoding.
            val cleaned = b64.replace("\n", "").replace("\r", "").replace(" ", "")
            val bytes = try {
                Base64.decode(cleaned, Base64.NO_WRAP)
            } catch (e: Exception) {
                return@withContext Result.failure(IOException("Base64 decode failed for $packageName: ${e.message}"))
            }
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IOException("Decoded icon is empty for $packageName"))
            }
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(IOException("Icon fetch failed for $packageName: ${e.message}", e))
        }
    }

    /**
     * Push a local file to the TV via ADB shell using base64 encoding.
     * Suitable for small files (under ~2MB). For larger files, use HTTP streaming.
     *
     * @return the remote file path on the TV
     */
    suspend fun pushFileSmall(
        context: Context,
        host: String,
        port: Int,
        fileUri: Uri,
        fileName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileBytes = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IOException("Cannot read file"))

            val fileSizeMB = fileBytes.size / (1024.0 * 1024.0)
            Log.d(TAG, "Pushing file '$fileName' (${String.format("%.1f", fileSizeMB)}MB) to TV")

            if (fileBytes.size > 2 * 1024 * 1024) {
                return@withContext Result.failure(
                    IOException("File too large for direct push (${String.format("%.1f", fileSizeMB)}MB). Use HTTP streaming instead.")
                )
            }

            val remotePath = "$REMOTE_FILE_DIR/$fileName"

            // Truncate/create the remote file first
            val initResult = executeShell(context, host, port, "echo -n '' > $remotePath")
            if (initResult.isFailure) {
                return@withContext Result.failure(IOException("Failed to create remote file: ${initResult.exceptionOrNull()?.message}"))
            }

            // Send file content in base64 chunks
            val base64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
            val chunkSize = 3000 // safe for shell command length
            var offset = 0
            var chunkNum = 0

            while (offset < base64.length) {
                val end = minOf(offset + chunkSize, base64.length)
                val chunk = base64.substring(offset, end)
                val cmd = "echo -n '$chunk' | base64 -d >> $remotePath"
                val result = executeShell(context, host, port, cmd)
                if (result.isFailure) {
                    return@withContext Result.failure(
                        IOException("Failed at chunk $chunkNum: ${result.exceptionOrNull()?.message}")
                    )
                }
                offset = end
                chunkNum++
            }

            // Verify file was written
            val verifyResult = executeShell(context, host, port, "ls -la $remotePath")
            Log.d(TAG, "File push verification: ${verifyResult.getOrDefault("unknown")}")

            Result.success(remotePath)
        } catch (e: Exception) {
            Log.e(TAG, "File push failed", e)
            Result.failure(IOException("File push failed: ${e.message}", e))
        }
    }

    /**
     * Extract a URL/URI from shared text. Universal — supports any scheme:
     * http, https, ftp, content, file, market, .apk download links, magnet, etc.
     * Falls back to the full raw shared text if no URI pattern matches,
     * so nothing the phone sends ever gets silently dropped.
     */
    fun extractUrl(sharedText: String): String? {
        if (sharedText.isBlank()) return null

        // Universal URI: any_scheme://anything (no whitespace)
        val universalRegex = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s<>"{}|\\^`\[\]]+""")
        val universalMatch = universalRegex.find(sharedText)
        if (universalMatch != null) return universalMatch.value

        // Magnet URIs (magnet:? — no // after colon)
        val magnetRegex = Regex("""magnet:\?[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
        val magnetMatch = magnetRegex.find(sharedText)
        if (magnetMatch != null) return magnetMatch.value

        // No URI pattern found — return the full raw text the phone sent
        return sharedText.trim().ifBlank { null }
    }

    /**
     * Get the file extension from a MIME type.
     * Falls back to the generic subtype (e.g. "jpeg" from "image/jpeg")
     * instead of "tmp", so HTTP streaming URLs carry a recognisable extension.
     */
    fun getExtensionFromMimeType(mimeType: String?): String {
        if (mimeType.isNullOrBlank()) return "bin"
        return when {
            mimeType.contains("mp4") -> "mp4"
            mimeType.contains("mkv") -> "mkv"
            mimeType.contains("avi") -> "avi"
            mimeType.contains("webm") -> "webm"
            mimeType.contains("mp3") -> "mp3"
            mimeType.contains("mpeg") || mimeType.contains("mpg") -> "mpeg"
            mimeType.contains("ogg") -> "ogg"
            mimeType.contains("flac") -> "flac"
            mimeType.contains("wav") -> "wav"
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("bmp") -> "bmp"
            mimeType.contains("svg") -> "svg"
            mimeType.contains("pdf") -> "pdf"
            mimeType.contains("audio") -> "mp3"
            mimeType.contains("video") -> "mp4"
            mimeType.contains("image") -> "jpg"
            else -> {
                // Fallback: use the subtype part of "type/subtype" (e.g. "jpeg")
                val subtype = mimeType.substringAfter("/", "").substringBefore(";")
                if (subtype.isNotBlank() && subtype != "*") subtype else "bin"
            }
        }
    }

    /**
     * Extract the file extension from a filename (e.g. "photo.jpg" → "jpg").
     * Returns null if the filename has no extension.
     */
    fun getExtensionFromFileName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val lastDot = fileName.lastIndexOf('.')
        if (lastDot < 0 || lastDot == fileName.length - 1) return null
        val ext = fileName.substring(lastDot + 1).lowercase()
        return if (ext.all { it.isLetterOrDigit() }) ext else null
    }

    /**
     * Derive a MIME type from a file extension.
     * Used to resolve the true content type when the share intent
     * provides a generic MIME (like application/octet-stream) but
     * the filename reveals the actual format.
     */
    fun getMimeTypeFromExtension(extension: String?): String? {
        if (extension.isNullOrBlank()) return null
        return when (extension.lowercase()) {
            // Video
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "m4v" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            // Image
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "heic", "heif" -> "image/heic"
            // Document
            "pdf" -> "application/pdf"
            "html", "htm" -> "text/html"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> null
        }
    }

    /**
     * Resolve the most accurate MIME type from available information.
     * Priority: provided MIME type (if specific) > filename-derived > fallback.
     * Filters out generic MIME types (application/octet-stream, wildcard) that
     * carry no useful type information.
     */
    fun resolveMimeType(intentMimeType: String?, fileName: String?): String {
        // If the intent provided a specific, non-generic MIME type, trust it
        if (!intentMimeType.isNullOrBlank()
            && intentMimeType != "*/*"
            && intentMimeType != "application/octet-stream"
            && intentMimeType != "application/stream"
        ) {
            return intentMimeType
        }
        // Try deriving from the file extension
        val ext = getExtensionFromFileName(fileName)
        if (ext != null) {
            val fromExt = getMimeTypeFromExtension(ext)
            if (fromExt != null) return fromExt
        }
        // Last resort: generic wildcard
        return "*/*"
    }

    // ── AdbConnectionManager implementation ────────────────────────────

    class AdbConnectionManager(private val prefs: SharedPreferences) : AbsAdbConnectionManager() {

        private var privateKey: PrivateKey? = null
        private var certificate: Certificate? = null

        init {
            setApi(Build.VERSION.SDK_INT)
            loadKeys()
        }

        override fun getPrivateKey(): PrivateKey {
            if (privateKey == null) generateKeyPair()
            return privateKey!!
        }

        override fun getCertificate(): Certificate {
            if (certificate == null) generateKeyPair()
            return certificate!!
        }

        override fun getDeviceName(): String = "ADBCommander"

        private fun generateKeyPair() {
            try {
                val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
                keyPairGenerator.initialize(2048, SecureRandom())
                val keyPair = keyPairGenerator.generateKeyPair()

                val start = Date(System.currentTimeMillis() - 86400000L)
                val end = Date(System.currentTimeMillis() + 3650L * 86400000L)

                val cert = createSelfSignedCertificate(keyPair, start, end)

                privateKey = keyPair.private
                certificate = cert
                saveKeys()

                Log.d(TAG, "Generated new RSA key pair and certificate")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate key pair", e)
                throw RuntimeException("Failed to generate ADB key pair", e)
            }
        }

        private fun createSelfSignedCertificate(keyPair: KeyPair, notBefore: Date, notAfter: Date): Certificate {
            val certGen = org.bouncycastle.x509.X509V3CertificateGenerator()
            val issuerName = X500Principal("CN=ADBCommander")
            certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            certGen.setIssuerDN(issuerName)
            certGen.setSubjectDN(issuerName)
            certGen.setNotBefore(notBefore)
            certGen.setNotAfter(notAfter)
            certGen.setPublicKey(keyPair.public)
            certGen.setSignatureAlgorithm("SHA512withRSA")
            return certGen.generate(keyPair.private)
        }

        private fun saveKeys() {
            try {
                val editor = prefs.edit()
                privateKey?.let {
                    editor.putString(KEY_PRIVATE, Base64.encodeToString(it.encoded, Base64.NO_WRAP))
                }
                certificate?.let {
                    editor.putString(KEY_CERT, Base64.encodeToString(it.encoded, Base64.NO_WRAP))
                }
                editor.apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save keys", e)
            }
        }

        private fun loadKeys() {
            try {
                val privateKeyB64 = prefs.getString(KEY_PRIVATE, null)
                val certB64 = prefs.getString(KEY_CERT, null)
                if (privateKeyB64 != null && certB64 != null) {
                    val keyBytes = Base64.decode(privateKeyB64, Base64.NO_WRAP)
                    privateKey = java.security.KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                    val certBytes = Base64.decode(certB64, Base64.NO_WRAP)
                    certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certBytes))
                    Log.d(TAG, "Loaded existing ADB keys")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load keys, will generate new ones", e)
                privateKey = null
                certificate = null
                prefs.edit().clear().apply()
            }
        }
    }
}
