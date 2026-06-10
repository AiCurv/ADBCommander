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
    fun prepareCommand(template: String, sharedUrl: String, mimeType: String = "video/*"): String {
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
    fun prepareFileCommand(template: String, remoteFilePath: String, httpUrl: String, mimeType: String = "video/*"): String {
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

                val connected = manager.connect(host, port)
                if (!connected) {
                    return@withContext Result.failure(
                        IOException("Failed to connect to $host:$port — ensure TV is paired and on same WiFi")
                    )
                }

                val stream: AdbStream = manager.openStream("shell:$command")

                val outputStream = ByteArrayOutputStream()
                val inputStream = stream.openInputStream()
                val buffer = ByteArray(4096)

                var totalRead = 0
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
