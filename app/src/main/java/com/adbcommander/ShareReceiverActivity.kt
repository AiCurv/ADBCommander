package com.adbcommander

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.IOException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive translucent dialog activity that appears when content is shared
 * to ADB Commander from any app. Supports all content types: URL text, images,
 * videos, audio, documents, and any other files.
 *
 * ContentResolver / URI resolution runs on Dispatchers.IO so the UI thread
 * is never blocked and the share-sheet transition feels instant.
 */
class ShareReceiverActivity : ComponentActivity() {

    private var sharedContentType: String = "url"
    private var sharedUrl: String? = null
    private var sharedFileUri: Uri? = null
    private var sharedFileMimeType: String? = null
    private var sharedFileName: String? = null
    private var isResolving by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show the UI immediately with a loading indicator while we resolve
        // the shared content on a background thread. This prevents the
        // share-sheet-to-app transition from freezing the phone.
        setContent {
            ADBCommanderTheme {
                if (isResolving) {
                    // Lightweight loading screen — no ContentResolver work on main thread
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Resolving shared content...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (sharedUrl == null && sharedFileUri == null) {
                    Toast.makeText(this, "No shareable content found", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    ShareReceiverDialog(
                        contentType = sharedContentType,
                        sharedUrl = sharedUrl,
                        sharedFileUri = sharedFileUri,
                        sharedFileMimeType = sharedFileMimeType,
                        sharedFileName = sharedFileName,
                        onDismiss = { finish() }
                    )
                }
            }
        }

        // Resolve the shared intent off the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            parseSharedContent()

            withContext(Dispatchers.Main) {
                isResolving = false
            }
        }
    }

    /**
     * Parse the incoming share intent. Runs on Dispatchers.IO so that
     * ContentResolver queries, stream reads, and file-name extraction
     * never block the main UI thread.
     */
    private fun parseSharedContent() {
        val action = intent?.action
        val type = intent?.type

        Log.d(TAG, "Share received — action=$action, type=$type")

        when {
            // File sharing (images, video, audio, documents, any non-text MIME)
            type != null && !type.startsWith("text/") -> {
                sharedContentType = "file"
                sharedFileMimeType = type
                sharedFileUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                // Resolve file name via ContentResolver on IO thread (may query provider)
                sharedFileName = sharedFileUri?.let { uri ->
                    resolveFileName(uri)
                } ?: "shared_file"
                Log.d(TAG, "File share — uri=$sharedFileUri, mime=$type, name=$sharedFileName")
            }
            // Text sharing (URLs, magnet links)
            type != null && type.startsWith("text/") -> {
                val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                sharedUrl = AdbManager.extractUrl(sharedText) ?: sharedText.ifBlank { null }
                sharedContentType = "url"
                Log.d(TAG, "Text share — url=$sharedUrl")
            }
            // Fallback: try to extract URL from text
            else -> {
                val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                sharedUrl = AdbManager.extractUrl(sharedText) ?: sharedText.ifBlank { null }
                sharedContentType = if (sharedUrl != null) "url" else "file"
                Log.d(TAG, "Fallback share — url=$sharedUrl")
            }
        }
    }

    /**
     * Resolve the display name of a content URI using ContentResolver.
     * This can involve a database query, so it MUST be called from
     * Dispatchers.IO to avoid blocking the main thread.
     */
    private fun resolveFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve file name for $uri", e)
            uri.lastPathSegment
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeFileServer?.stop()
        activeFileServer = null
    }

    companion object {
        private const val TAG = "ShareReceiver"
        var activeFileServer: FileServer? = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareReceiverDialog(
    contentType: String,
    sharedUrl: String?,
    sharedFileUri: Uri?,
    sharedFileMimeType: String?,
    sharedFileName: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val presets = remember { settings.getAllPresets() }

    var isExecuting by remember { mutableStateOf(false) }
    var executingPreset by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val autoExecute = settings.getAutoExecute()
        if (autoExecute && presets.isNotEmpty()) {
            val selectedPresetName = settings.getSelectedPreset()
            val preset = presets.find { it.name == selectedPresetName } ?: presets.first()
            isExecuting = true
            executingPreset = preset.name
            statusMessage = "Auto-executing: ${preset.name}"

            val result = executePresetSuspend(
                context = context,
                settings = settings,
                preset = preset,
                contentType = contentType,
                sharedUrl = sharedUrl,
                sharedFileUri = sharedFileUri,
                sharedFileMimeType = sharedFileMimeType,
                sharedFileName = sharedFileName
            )

            isExecuting = false
            executingPreset = null
            if (result.isSuccess) {
                Toast.makeText(context, "Command sent!", Toast.LENGTH_SHORT).show()
                delay(500)
                onDismiss()
            } else {
                statusMessage = "Failed: ${result.exceptionOrNull()?.message}"
                isError = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExecuting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send to TV")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Shared item info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (contentType == "file") Icons.Filled.VideoFile else Icons.Filled.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (contentType == "file") "Local File" else "URL",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (contentType) {
                                "file" -> sharedFileName ?: "Unknown file"
                                else -> sharedUrl ?: "Unknown URL"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Status
                statusMessage?.let { msg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isExecuting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(msg, style = MaterialTheme.typography.bodySmall,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }

                HorizontalDivider()

                Text("Select a preset to execute:", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Preset buttons
                presets.forEach { preset ->
                    val isCompatible = when (contentType) {
                        "file" -> true
                        else -> preset.usesUrl || !preset.usesFile
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isExecuting = true
                                executingPreset = preset.name
                                statusMessage = "Executing: ${preset.name}"
                                isError = false

                                val result = executePresetSuspend(
                                    context = context,
                                    settings = settings,
                                    preset = preset,
                                    contentType = contentType,
                                    sharedUrl = sharedUrl,
                                    sharedFileUri = sharedFileUri,
                                    sharedFileMimeType = sharedFileMimeType,
                                    sharedFileName = sharedFileName
                                )

                                isExecuting = false
                                executingPreset = null
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Command sent!", Toast.LENGTH_SHORT).show()
                                    delay(500)
                                    onDismiss()
                                } else {
                                    statusMessage = "Failed: ${result.exceptionOrNull()?.message}"
                                    isError = true
                                }
                            }
                        },
                        enabled = !isExecuting && isCompatible,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (executingPreset == preset.name)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when {
                                        preset.usesFile -> Icons.Filled.FolderOpen
                                        else -> Icons.AutoMirrored.Filled.Send
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (executingPreset == preset.name) "Running..." else preset.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            if (preset.command.isNotBlank()) {
                                Text(
                                    preset.command.take(70) + if (preset.command.length > 70) "..." else "",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (contentType == "url") {
                    val fileOnlyPresets = presets.filter { it.usesFile && !it.usesUrl }
                    if (fileOnlyPresets.isNotEmpty()) {
                        Text("Some presets require a local file and are disabled for URL sharing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isExecuting) { Text("Cancel") }
        }
    )
}

/**
 * Execute a preset command — suspend function that can be called from a coroutine.
 */
private suspend fun executePresetSuspend(
    context: android.content.Context,
    settings: SettingsManager,
    preset: SettingsManager.Preset,
    contentType: String,
    sharedUrl: String?,
    sharedFileUri: Uri?,
    sharedFileMimeType: String?,
    sharedFileName: String?
): Result<String> {
    val host = settings.getTvHost()
    val port = settings.getTvPort()

    if (host.isBlank()) {
        return Result.failure(IOException("TV IP not set! Open app settings first."))
    }

    return try {
        // Resolve the true MIME type from the intent + filename.
        // Never falls back to "video/*" — images get image/*, etc.
        val resolvedMime = AdbManager.resolveMimeType(sharedFileMimeType, sharedFileName)
        Log.d("ShareReceiver", "Resolved MIME: intent=$sharedFileMimeType, fileName=$sharedFileName → $resolvedMime")

        val finalCommand = when (contentType) {
            "file" -> {
                val fileUri = sharedFileUri
                    ?: return Result.failure(IOException("No file to share"))

                if (preset.usesFile && !preset.usesUrl) {
                    // Preset uses {FILE} — push small file to TV
                    val ext = AdbManager.getExtensionFromFileName(sharedFileName)
                        ?: AdbManager.getExtensionFromMimeType(resolvedMime)
                    val fileName = "adb_commander_share.$ext"
                    val pushResult = AdbManager.pushFileSmall(context, host, port, fileUri, fileName)
                    if (pushResult.isFailure) {
                        return Result.failure(pushResult.exceptionOrNull() ?: IOException("Push failed"))
                    }
                    val remotePath = pushResult.getOrDefault("")
                    AdbManager.prepareFileCommand(preset.command, remotePath, "", resolvedMime)
                } else {
                    // Preset uses {URL} — start HTTP server for streaming
                    val server = FileServer(fileUri, resolvedMime, context.contentResolver)
                    ShareReceiverActivity.activeFileServer = server
                    val serverPort = server.start()
                    val phoneIp = server.getLocalIpAddress()

                    if (phoneIp == null) {
                        server.stop()
                        return Result.failure(IOException("Cannot get phone IP. Are you on WiFi?"))
                    }

                    val ext = AdbManager.getExtensionFromFileName(sharedFileName)
                        ?: AdbManager.getExtensionFromMimeType(resolvedMime)
                    val httpUrl = "http://$phoneIp:$serverPort/file.$ext"
                    Log.d("ShareReceiver", "HTTP streaming URL: $httpUrl")
                    AdbManager.prepareFileCommand(preset.command, "", httpUrl, resolvedMime)
                }
            }
            else -> {
                val url = sharedUrl
                    ?: return Result.failure(IOException("No URL to send"))
                AdbManager.prepareCommand(preset.command, url, resolvedMime)
            }
        }

        if (finalCommand.isBlank()) {
            return Result.failure(IOException("Command is empty after substitution"))
        }

        Log.d("ShareReceiver", "Final command: $finalCommand")
        val result = AdbManager.executeShell(context, host, port, finalCommand)
        // Log the execution to persistent history
        val logStore = CommandLogStore(context)
        logStore.addLog(finalCommand, result.isSuccess)
        result
    } catch (e: Exception) {
        Result.failure(IOException("Execution error: ${e.message}", e))
    }
}
