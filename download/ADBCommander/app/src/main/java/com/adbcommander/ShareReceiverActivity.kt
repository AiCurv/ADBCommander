package com.adbcommander

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.launch

/**
 * Activity that receives ACTION_SEND intents with ANY MIME type (*/*).
 *
 * Handles two kinds of shares:
 * - **Text shares** (text/plain, text/x-uri, etc.) — extracts the URL from
 *   [Intent.EXTRA_TEXT].
 * - **File/binary shares** (image/*, video/*, audio/*, application/*, etc.) —
 *   reads the [Intent.EXTRA_STREAM] URI and uses the full path/URI to resolve
 *   a wildcard MIME category via [AdbManager.resolveMimeType].
 *
 * The resolved MIME is passed into the command template as `{MIME}`.
 * **No manual quoting is applied** — [AdbManager.shellEscape] is the single
 * source of truth for parameter escaping, applied inside [AdbManager.prepareCommand].
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdbManager.initCert(applicationContext)

        val action = intent?.action
        if (action != Intent.ACTION_SEND) {
            Toast.makeText(this, "Unsupported intent action", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // ── 1. Determine the shared content ─────────────────────────────
        val incomingMimeType = intent?.type
        var sharedUrl: String? = null
        var resolvedMimeType = "*/*"     // default fallback

        // Try text first — the classic URL-sharing path
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: ""

        if (sharedText.isNotEmpty()) {
            // Text share — try to extract a URL, otherwise use raw text
            sharedUrl = AdbManager.extractUrl(sharedText) ?: sharedText

            // For text shares, use the full text/URL for MIME resolution.
            // This allows URLs like "https://example.com/video.mp4" to be
            // correctly identified as video/* via .contains() matching.
            resolvedMimeType = AdbManager.resolveMimeType(incomingMimeType, sharedUrl)
        }

        // If no text was found, try EXTRA_STREAM (binary/file share)
        if (sharedUrl == null) {
            val streamUri: Uri? = intent?.getParcelableExtra(Intent.EXTRA_STREAM)
            if (streamUri != null) {
                // Use the URI string as the "URL" payload
                sharedUrl = streamUri.toString()

                // Build a combined path for MIME resolution: include both
                // the URI string and the display name (if available) to
                // maximise the chance of matching an extension.
                val displayName = extractDisplayName(streamUri)
                val combinedPath = "$streamUri $displayName"

                Log.d(TAG, "Stream URI: $streamUri, displayName=$displayName, intentType=$incomingMimeType")

                // Resolve the wildcard MIME category using the full path
                resolvedMimeType = AdbManager.resolveMimeType(incomingMimeType, combinedPath)
            }
        }

        if (sharedUrl == null) {
            Toast.makeText(this, "No shareable content found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.d(TAG, "sharedUrl=$sharedUrl, resolvedMimeType=$resolvedMimeType")

        val finalUrl = sharedUrl
        val finalMime = resolvedMimeType

        setContent {
            ADBCommanderTheme {
                ShareDialog(
                    sharedUrl = finalUrl,
                    resolvedMimeType = finalMime,
                    onDismiss = { finish() }
                )
            }
        }
    }

    /**
     * Extract the display name from a content URI.
     * Queries the ContentResolver for [OpenableColumns.DISPLAY_NAME].
     * Returns an empty string if the name cannot be determined.
     */
    private fun extractDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query display name for $uri", e)
            }
        }

        // Fall back to the URI path
        return uri.path ?: ""
    }

    // Prevent re-handling intent on configuration changes
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun ShareDialog(
    sharedUrl: String,
    resolvedMimeType: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val logStore = remember { CommandLogStore(context) }
    val scope = rememberCoroutineScope()

    var command by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultIsError by remember { mutableStateOf(false) }

    // Load the default command template and replace {URL} & {MIME}.
    // prepareCommand() applies shellEscape() internally — no manual quoting needed.
    LaunchedEffect(sharedUrl, resolvedMimeType) {
        val template = settings.getDefaultCommand()
        command = AdbManager.prepareCommand(template, sharedUrl, resolvedMimeType)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Send to TV via ADB")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Show the shared URL
                Text(
                    text = "Shared content:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = sharedUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2
                )

                // Show resolved MIME type
                Text(
                    text = "MIME type: $resolvedMimeType",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Command editor
                Text(
                    text = "Shell command:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = { Text("am start -a android.intent.action.VIEW -d {URL} -t {MIME}") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )

                // Result feedback
                resultMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resultIsError)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (command.isBlank()) {
                        resultMessage = "Command cannot be empty"
                        resultIsError = true
                        return@Button
                    }

                    isExecuting = true
                    resultMessage = null

                    scope.launch {
                        val host = settings.getTvHost()
                        val port = settings.getTvPort()

                        if (host.isBlank()) {
                            isExecuting = false
                            resultMessage = "TV IP not set. Open app settings first."
                            resultIsError = true
                            return@launch
                        }

                        // Replace any remaining {URL} and {MIME} placeholders
                        // in case the user typed them manually after the initial auto-fill.
                        // shellEscape() is applied by prepareCommand() — single source of truth.
                        val finalCommand = AdbManager.prepareCommand(command, sharedUrl, resolvedMimeType)

                        val result = AdbManager.executeShell(host, port, finalCommand)
                        isExecuting = false

                        val isSuccess = result.isSuccess
                        // Record the command in the execution log
                        logStore.addLog(finalCommand, isSuccess)

                        if (isSuccess) {
                            resultMessage = "Command sent to TV!"
                            resultIsError = false
                            Toast.makeText(context, "Command sent to TV", Toast.LENGTH_SHORT).show()
                            // Small delay so the user sees the success message
                            kotlinx.coroutines.delay(800)
                            onDismiss()
                        } else {
                            val error = result.exceptionOrNull()?.message ?: "Unknown error"
                            resultMessage = "Failed: $error"
                            resultIsError = true
                        }
                    }
                },
                enabled = !isExecuting
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (isExecuting) "Sending..." else "Execute")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
