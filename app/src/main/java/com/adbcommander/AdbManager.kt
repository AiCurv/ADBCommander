package com.adbcommander

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory

/**
 * Manages ADB connections using libadb-android with full wireless pairing support.
 *
 * This uses AbsAdbConnectionManager which handles:
 * - RSA key pair generation and ADB authentication
 * - Android 11+ wireless debugging pairing (SPAKE2+)
 * - Shell command execution
 * - Connection management
 *
 * The pairing flow (same as ATV Tools):
 * 1. On TV: Settings → Developer Options → Wireless Debugging → Pair device with pairing code
 * 2. In app: Enter TV IP, pairing port, and the 6-digit code
 * 3. After pairing: Connect using the regular connection port
 */
object AdbManager {

    private const val TAG = "ADBCommander"
    private const val PREFS_NAME = "adb_keys"
    private const val KEY_PRIVATE = "private_key"
    private const val KEY_CERT = "certificate"

    private var managerInstance: AdbConnectionManager? = null

    /**
     * Get or initialize the AdbConnectionManager singleton.
     * Key pair and certificate are persisted in SharedPreferences.
     */
    fun getManager(context: Context): AdbConnectionManager {
        managerInstance?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manager = AdbConnectionManager(prefs)
        managerInstance = manager
        return manager
    }

    // ── Pairing ──────────────────────────────────────────────────────

    /**
     * Pair with an Android 11+ device using wireless debugging.
     * This is the same flow ATV Tools uses - no PC required!
     *
     * Steps for the user:
     * 1. On TV: Settings → Developer Options → Wireless Debugging → Pair device with pairing code
     * 2. Note the IP:pairing_port and the 6-digit code
     * 3. Enter them in this app and tap "Pair"
     *
     * @param host         TV IP address
     * @param pairingPort  Port shown on TV's "Pair device" screen (different from connection port!)
     * @param pairingCode  6-digit code shown on TV
     */
    suspend fun pair(context: Context, host: String, pairingPort: Int, pairingCode: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val manager = getManager(context)
                Log.d(TAG, "Pairing with $host:$pairingPort code=$pairingCode")
                manager.pair(host, pairingPort, pairingCode)
                Log.d(TAG, "Pairing successful!")
                Result.success("Paired successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                Result.failure(IOException("Pairing failed: ${e.message}", e))
            }
        }

    // ── Connection & Shell ────────────────────────────────────────────

    /**
     * Connect to TV and execute a shell command.
     *
     * @param host    TV IP address
     * @param port    Connection port (shown under "IP address & port" in Wireless Debugging settings)
     * @param command Shell command to execute
     */
    suspend fun executeShell(context: Context, host: String, port: Int, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val manager = getManager(context)
                Log.d(TAG, "Connecting to $host:$port")

                val connection: AdbConnection = manager.connect(host, port)
                Log.d(TAG, "Connected! Opening shell: $command")

                val stream: AdbStream = connection.openStream("shell:$command")

                // Read the output from the shell stream
                val outputStream = ByteArrayOutputStream()
                val inputStream = stream.openInputStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int

                // Read with a timeout approach - read available data
                var totalRead = 0
                val readDeadline = System.currentTimeMillis() + 10000 // 10 second timeout

                while (System.currentTimeMillis() < readDeadline) {
                    if (inputStream.available() > 0) {
                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                    } else if (totalRead > 0) {
                        // We've read some data and no more available, give a short grace period
                        Thread.sleep(300)
                        if (inputStream.available() == 0) break
                    } else {
                        Thread.sleep(100)
                    }
                }

                inputStream.close()
                stream.close()
                connection.close()

                val output = outputStream.toString("UTF-8").trim()
                Log.d(TAG, "Shell output: $output")
                Result.success(output.ifBlank { "Command executed (no output)" })
            } catch (e: Exception) {
                Log.e(TAG, "Shell execution failed", e)
                Result.failure(IOException("Connection failed: ${e.message}", e))
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

    // ── AdbConnectionManager implementation ────────────────────────────

    /**
     * Concrete implementation of AbsAdbConnectionManager from libadb-android.
     * Handles RSA key pair generation, certificate creation, and persistence.
     */
    class AdbConnectionManager(private val prefs: SharedPreferences) : AbsAdbConnectionManager() {

        private var privateKey: PrivateKey? = null
        private var certificate: Certificate? = null

        init {
            // Set the API level (used for ADB protocol versioning)
            setApi(Build.VERSION.SDK_INT)

            // Try to load existing keys from SharedPreferences
            loadKeys()
        }

        override fun getPrivateKey(): PrivateKey {
            if (privateKey == null) {
                generateKeyPair()
            }
            return privateKey!!
        }

        override fun getCertificate(): Certificate {
            if (certificate == null) {
                generateKeyPair()
            }
            return certificate!!
        }

        override fun getDeviceName(): String {
            return "ADBCommander"
        }

        /**
         * Generate a new RSA key pair and self-signed certificate.
         * Stores them in SharedPreferences for persistence across app restarts.
         */
        private fun generateKeyPair() {
            try {
                // Generate RSA key pair
                val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
                keyPairGenerator.initialize(2048, SecureRandom())
                val keyPair = keyPairGenerator.generateKeyPair()

                privateKey = keyPair.private

                // Generate self-signed X.509 certificate using BouncyCastle
                val subjectDN = "CN=ADBCommander"
                val validityDays = 3650L // 10 years

                // Use BouncyCastle for certificate generation
                val bcCert = org.bouncycastle.x509.X509V3CertificateGenerator()
                bcCert.setSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()))
                bcCert.setIssuerDN(org.bouncycastle.asn1.x500.X500Name(subjectDN))
                bcCert.setSubjectDN(org.bouncycastle.asn1.x500.X500Name(subjectDN))
                bcCert.setNotBefore(java.util.Date(System.currentTimeMillis() - 86400000))
                bcCert.setNotAfter(java.util.Date(System.currentTimeMillis() + validityDays * 86400000))
                bcCert.setPublicKey(keyPair.public)
                bcCert.setSignatureAlgorithm("SHA512withRSA")

                certificate = bcCert.generate(keyPair.private)

                // Persist keys
                saveKeys()

                Log.d(TAG, "Generated new RSA key pair and certificate")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate key pair", e)
                throw RuntimeException("Failed to generate ADB key pair", e)
            }
        }

        /**
         * Save key pair and certificate to SharedPreferences as Base64.
         */
        private fun saveKeys() {
            try {
                val editor = prefs.edit()

                // Save private key (PKCS8 format)
                privateKey?.let {
                    val keyBytes = it.encoded
                    editor.putString(KEY_PRIVATE, Base64.encodeToString(keyBytes, Base64.NO_WRAP))
                }

                // Save certificate (DER format)
                certificate?.let {
                    val certBytes = it.encoded
                    editor.putString(KEY_CERT, Base64.encodeToString(certBytes, Base64.NO_WRAP))
                }

                editor.apply()
                Log.d(TAG, "Saved ADB keys to SharedPreferences")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save keys", e)
            }
        }

        /**
         * Load key pair and certificate from SharedPreferences.
         */
        private fun loadKeys() {
            try {
                val privateKeyB64 = prefs.getString(KEY_PRIVATE, null)
                val certB64 = prefs.getString(KEY_CERT, null)

                if (privateKeyB64 != null && certB64 != null) {
                    // Restore private key
                    val keyBytes = Base64.decode(privateKeyB64, Base64.NO_WRAP)
                    val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
                    val keyFactory = java.security.KeyFactory.getInstance("RSA")
                    privateKey = keyFactory.generatePrivate(keySpec)

                    // Restore certificate
                    val certBytes = Base64.decode(certB64, Base64.NO_WRAP)
                    val certFactory = CertificateFactory.getInstance("X.509")
                    certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes))

                    Log.d(TAG, "Loaded existing ADB keys from SharedPreferences")
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
