package com.adbcommander

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ADBCommanderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var defaultCommand by remember { mutableStateOf(SettingsManager.DEFAULT_COMMAND) }
    var isTesting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        tvHost = settings.getTvHost()
        tvPort = settings.getTvPort()
        defaultCommand = settings.getDefaultCommand()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ADB Commander") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── TV Connection ──────────────────────────────
            SectionHeader("TV Connection", Icons.Filled.Link)

            OutlinedTextField(
                value = tvHost,
                onValueChange = { tvHost = it; scope.launch { settings.setTvHost(it) } },
                label = { Text("TV IP Address") },
                placeholder = { Text("192.168.1.123") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tvPort.toString(),
                onValueChange = { it.toIntOrNull()?.let { p -> tvPort = p; scope.launch { settings.setTvPort(p) } } },
                label = { Text("Connection Port") },
                placeholder = { Text("5555") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (tvHost.isBlank()) { connectionStatus = "Please enter the TV IP address"; return@Button }
                    isTesting = true; connectionStatus = null
                    scope.launch {
                        val result = AdbManager.testConnection(context, tvHost, tvPort)
                        isTesting = false
                        connectionStatus = if (result.isSuccess) "Connected! TV responded: ${result.getOrDefault("")}"
                        else "Connection failed: ${result.exceptionOrNull()?.message}"
                    }
                },
                enabled = !isTesting && tvHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isTesting) "Testing..." else "Test Connection")
            }

            connectionStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }

            // ── Pairing Info ────────────────────────────────
            HorizontalDivider()
            SectionHeader("Android 11+ Pairing", Icons.Filled.Terminal)

            Text(
                text = "If your TV runs Android 11+ and shows \"Wireless debugging\"," +
                        " you must pair it first using a computer or terminal app:\n\n" +
                        "1. On TV: Settings → Developer Options → Wireless debugging → Pair device\n" +
                        "2. On computer: adb pair <TV_IP>:<pairing_port>\n" +
                        "3. Enter the 6-digit code shown on TV\n" +
                        "4. Then use the connection port (shown under \"IP address & port\") in this app\n\n" +
                        "For Android 10 and below, just use port 5555 — no pairing needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── Default Command ────────────────────────────
            HorizontalDivider()
            SectionHeader("Default Command", Icons.Filled.Terminal)

            Text(
                text = "Use {URL} as placeholder — it gets replaced with the shared link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = defaultCommand,
                onValueChange = { defaultCommand = it; scope.launch { settings.setDefaultCommand(it) } },
                label = { Text("Shell Command Template") },
                placeholder = { Text("am start -a android.intent.action.VIEW -d \"{URL}\"") },
                minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
