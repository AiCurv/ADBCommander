package com.adbcommander

import android.content.Intent
import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val sharedUrl = AdbManager.extractUrl(sharedText) ?: sharedText.ifBlank { null }

        if (sharedUrl == null) {
            Toast.makeText(this, "No URL found in shared text", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            ADBCommanderTheme {
                // Check auto-execute setting
                val context = LocalContext.current
                val settings = remember { SettingsManager(context) }
                var autoExecute by remember { mutableStateOf(false) }
                var command by remember { mutableStateOf("") }
                var initialized by remember { mutableStateOf(false) }

                LaunchedEffect(sharedUrl) {
                    autoExecute = settings.getAutoExecute()
                    command = settings.getDefaultCommand().replace("{URL}", sharedUrl)
                    initialized = true
                }

                if (initialized) {
                    if (autoExecute) {
                        // Auto-execute mode: just show a brief status and execute
                        AutoExecuteScreen(sharedUrl = sharedUrl, command = command, onDone = { finish() })
                    } else {
                        // Manual mode: show dialog to review/edit command
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
fun AutoExecuteScreen(sharedUrl: String, command: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
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

        val result = AdbManager.executeShell(context, host, port, command)
        if (result.isSuccess) {
            status = "Command sent to TV!"
            isError = false
            Toast.makeText(context, "Command sent to TV!", Toast.LENGTH_SHORT).show()
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
                        val finalCommand = editableCommand.replace("{URL}", sharedUrl)
                        val result = AdbManager.executeShell(context, host, port, finalCommand)
                        isExecuting = false
                        if (result.isSuccess) {
                            resultMessage = "Command sent to TV!"
                            resultIsError = false
                            Toast.makeText(context, "Command sent to TV!", Toast.LENGTH_SHORT).show()
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
