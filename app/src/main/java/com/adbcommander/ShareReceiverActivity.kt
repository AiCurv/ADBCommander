package com.adbcommander

import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ShareReceiver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val sharedUrl = AdbManager.extractUrl(sharedText) ?: sharedText.ifBlank { null }

        Log.d(TAG, "Share received — raw text: $sharedText")
        Log.d(TAG, "Extracted URL: $sharedUrl")

        if (sharedUrl == null) {
            Toast.makeText(this, "No URL found in shared text", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            ADBCommanderTheme {
                val context = LocalContext.current
                val settings = remember { SettingsManager(context) }
                var autoExecute by remember { mutableStateOf(false) }
                var command by remember { mutableStateOf("") }
                var initialized by remember { mutableStateOf(false) }

                LaunchedEffect(sharedUrl) {
                    autoExecute = settings.getAutoExecute()
                    val template = settings.getDefaultCommand()
                    // Use prepareCommand which replaces BOTH {URL} and YOUR_VIDEO_URL
                    // then strips any "adb shell" / "adb" prefixes
                    command = AdbManager.prepareCommand(template, sharedUrl)
                    Log.d(TAG, "Template: $template")
                    Log.d(TAG, "Final command: $command")
                    initialized = true
                }

                if (initialized) {
                    if (autoExecute) {
                        AutoExecuteScreen(command = command, sharedUrl = sharedUrl, onDone = { finish() })
                    } else {
                        ShareDialog(sharedUrl = sharedUrl, command = command, onDismiss = { finish() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun AutoExecuteScreen(command: String, sharedUrl: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    var status by remember { mutableStateOf("Connecting to TV...") }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val host = settings.getTvHost()
        val port = settings.getTvPort()

        if (host.isBlank()) {
            status = "TV IP not set! Open app settings first."
            isError = true
            delay(2000)
            onDone()
            return@LaunchedEffect
        }

        if (command.isBlank()) {
            status = "Command is empty after URL substitution!"
            isError = true
            delay(2000)
            onDone()
            return@LaunchedEffect
        }

        // Command is already prepared (URL replaced, adb shell stripped) by ShareReceiverActivity
        val result = AdbManager.executeShell(context, host, port, command)
        if (result.isSuccess) {
            status = "Command sent to TV!"
            isError = false
            Toast.makeText(context, "Sent! ${result.getOrDefault("")}", Toast.LENGTH_SHORT).show()
            delay(800)
            onDone()
        } else {
            status = "Failed: ${result.exceptionOrNull()?.message}"
            isError = true
            Toast.makeText(context, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            delay(2000)
            onDone()
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("ADB Commander") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Text(
                    "URL: $sharedUrl",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
fun ShareDialog(sharedUrl: String, command: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    // Command is already prepared with URL replaced and adb shell stripped
    var editableCommand by remember { mutableStateOf(command) }
    var isExecuting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultIsError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to TV via ADB") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Shared URL:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    sharedUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2
                )
                HorizontalDivider()
                Text("Shell command:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = editableCommand,
                    onValueChange = { editableCommand = it },
                    placeholder = { Text("am start -a android.intent.action.VIEW -d \"{URL}\"") },
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
                resultMessage?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (editableCommand.isBlank()) {
                        resultMessage = "Command cannot be empty"; resultIsError = true; return@Button
                    }
                    isExecuting = true; resultMessage = null
                    scope.launch {
                        val host = settings.getTvHost()
                        val port = settings.getTvPort()
                        if (host.isBlank()) {
                            isExecuting = false
                            resultMessage = "TV IP not set. Open app settings first."
                            resultIsError = true
                            return@launch
                        }
                        // editableCommand already has the URL replaced.
                        // sanitizeCommand will strip any "adb shell" / "adb" prefixes just in case.
                        val finalCommand = AdbManager.sanitizeCommand(editableCommand)
                        val result = AdbManager.executeShell(context, host, port, finalCommand)
                        isExecuting = false
                        if (result.isSuccess) {
                            resultMessage = "OK: ${result.getOrDefault("")}"
                            resultIsError = false
                            Toast.makeText(context, "Sent!", Toast.LENGTH_SHORT).show()
                            delay(800)
                            onDismiss()
                        } else {
                            resultMessage = "Failed: ${result.exceptionOrNull()?.message}"
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
