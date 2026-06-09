package com.adbcommander

import android.content.Context
import android.content.SharedPreferences
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
 * Handles RSA key pair generation, ADB authentication, and shell execution.
 */
object AdbManager {

    private const val TAG = "ADBCommander"
    private const val PREFS_NAME = "adb_keys"
    private const val KEY_PRIVATE = "private_key_b64"
    private const val KEY_CERT = "certificate_b64"

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
        // Remove "adb shell " prefix (case-insensitive, handles double spacing too)
        val adbShellPattern = Regex("""^adb\s+shell\s+""", RegexOption.IGNORE_CASE)
        clean = adbShellPattern.replaceFirst(clean, "")
        // Remove bare "adb " prefix if that's all that's left
        val adbPattern = Regex("""^adb\s+""", RegexOption.IGNORE_CASE)
        clean = adbPattern.replaceFirst(clean, "")
        return clean.trim()
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
                Result.failure(IOException("Connection failed: ${e.message}", e))
            }
        }

    suspend fun testConnection(context: Context, host: String, port: Int): Result<String> =
        executeShell(context, host, port, "echo ok")

    fun extractUrl(sharedText: String): String? {
        val urlRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
        return urlRegex.find(sharedText)?.value
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
