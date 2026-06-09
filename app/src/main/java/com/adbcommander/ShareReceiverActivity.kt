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
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdbManager.initCert(applicationContext)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val sharedUrl = AdbManager.extractUrl(sharedText) ?: sharedText.ifBlank { null }

        if (sharedUrl == null) {
            Toast.makeText(this, "No URL found in shared text", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            ADBCommanderTheme {
                ShareDialog(sharedUrl = sharedUrl, onDismiss = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun ShareDialog(sharedUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var command by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultIsError by remember { mutableStateOf(false) }

    LaunchedEffect(sharedUrl) {
        val template = settings.getDefaultCommand()
        command = template.replace("{URL}", sharedUrl)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to TV via ADB") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Shared URL:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sharedUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary, maxLines = 2)
                HorizontalDivider()
                Text("Shell command:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = { Text("am start -a android.intent.action.VIEW -d \"{URL}\"") },
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
                resultMessage?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (command.isBlank()) { resultMessage = "Command cannot be empty"; resultIsError = true; return@Button }
                    isExecuting = true; resultMessage = null
                    scope.launch {
                        val host = settings.getTvHost()
                        val port = settings.getTvPort()
                        if (host.isBlank()) {
                            isExecuting = false; resultMessage = "TV IP not set. Open app settings first."; resultIsError = true; return@launch
                        }
                        val finalCommand = command.replace("{URL}", sharedUrl)
                        val result = AdbManager.executeShell(host, port, finalCommand)
                        isExecuting = false
                        if (result.isSuccess) {
                            resultMessage = "Command sent to TV!"; resultIsError = false
                            Toast.makeText(context, "Command sent to TV", Toast.LENGTH_SHORT).show()
                            kotlinx.coroutines.delay(800)
                            onDismiss()
                        } else {
                            resultMessage = "Failed: ${result.exceptionOrNull()?.message}"; resultIsError = true
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
