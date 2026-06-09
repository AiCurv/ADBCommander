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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    // Connection settings
    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var pairingPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_PAIRING_PORT) }
    var pairingCode by remember { mutableStateOf("") }

    // Command settings
    var selectedPreset by remember { mutableIntStateOf(0) }
    var customCommand by remember { mutableStateOf(SettingsManager.DEFAULT_COMMAND) }
    var autoExecute by remember { mutableStateOf(false) }

    // Status
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var pairingStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }

    // Current tab
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        tvHost = settings.getTvHost()
        tvPort = settings.getTvPort()
        pairingPort = settings.getPairingPort()
        customCommand = settings.getDefaultCommand()
        autoExecute = settings.getAutoExecute()
        selectedPreset = settings.getSelectedPreset()
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
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    label = { Text("Connect") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                    label = { Text("Commands") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                0 -> ConnectionTab(
                    tvHost = tvHost,
                    tvPort = tvPort,
                    pairingPort = pairingPort,
                    pairingCode = pairingCode,
                    connectionStatus = connectionStatus,
                    pairingStatus = pairingStatus,
                    isTesting = isTesting,
                    isPairing = isPairing,
                    onTvHostChange = { tvHost = it; scope.launch { settings.setTvHost(it) } },
                    onTvPortChange = { it.toIntOrNull()?.let { p -> tvPort = p; scope.launch { settings.setTvPort(p) } } },
                    onPairingPortChange = { it.toIntOrNull()?.let { p -> pairingPort = p; scope.launch { settings.setPairingPort(p) } } },
                    onPairingCodeChange = { pairingCode = it },
                    onTestConnection = {
                        if (tvHost.isBlank()) { connectionStatus = "Enter TV IP first"; return@ConnectionTab }
                        isTesting = true; connectionStatus = null
                        scope.launch {
                            val result = AdbManager.testConnection(context, tvHost, tvPort)
                            isTesting = false
                            connectionStatus = if (result.isSuccess) "Connected! TV responded: ${result.getOrDefault("")}"
                            else "Failed: ${result.exceptionOrNull()?.message}"
                        }
                    },
                    onPair = {
                        if (tvHost.isBlank()) { pairingStatus = "Enter TV IP first"; return@ConnectionTab }
                        if (pairingCode.isBlank()) { pairingStatus = "Enter pairing code"; return@ConnectionTab }
                        if (pairingPort == 0) { pairingStatus = "Enter pairing port"; return@ConnectionTab }
                        isPairing = true; pairingStatus = null
                        scope.launch {
                            val result = AdbManager.pair(context, tvHost, pairingPort, pairingCode)
                            isPairing = false
                            if (result.isSuccess) {
                                pairingStatus = "Paired successfully! Now use Test Connection."
                                pairingCode = ""
                            } else {
                                pairingStatus = "Pairing failed: ${result.exceptionOrNull()?.message}"
                            }
                        }
                    }
                )
                1 -> CommandsTab(
                    selectedPreset = selectedPreset,
                    customCommand = customCommand,
                    autoExecute = autoExecute,
                    onPresetChange = { id ->
                        selectedPreset = id
                        scope.launch { settings.setSelectedPreset(id) }
                        val preset = SettingsManager.COMMAND_PRESETS.find { it.id == id }
                        if (preset != null && preset.command.isNotEmpty()) {
                            customCommand = preset.command
                            scope.launch { settings.setDefaultCommand(preset.command) }
                        }
                    },
                    onCommandChange = { customCommand = it; scope.launch { settings.setDefaultCommand(it) } },
                    onAutoExecuteChange = { autoExecute = it; scope.launch { settings.setAutoExecute(it) } }
                )
            }
        }
    }
}

@Composable
fun ConnectionTab(
    tvHost: String,
    tvPort: Int,
    pairingPort: Int,
    pairingCode: String,
    connectionStatus: String?,
    pairingStatus: String?,
    isTesting: Boolean,
    isPairing: Boolean,
    onTvHostChange: (String) -> Unit,
    onTvPortChange: (String) -> Unit,
    onPairingPortChange: (String) -> Unit,
    onPairingCodeChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onPair: () -> Unit
) {
    // ── TV Connection ──────────────────────────────
    SectionHeader("TV Connection", Icons.Filled.Link)

    OutlinedTextField(
        value = tvHost,
        onValueChange = onTvHostChange,
        label = { Text("TV IP Address") },
        placeholder = { Text("192.168.1.123") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = tvPort.toString(),
        onValueChange = onTvPortChange,
        label = { Text("Connection Port") },
        placeholder = { Text("5555") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )

    Button(
        onClick = onTestConnection,
        enabled = !isTesting && tvHost.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (isTesting) "Testing..." else "Test Connection")
    }

    connectionStatus?.let {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (it.startsWith("Connected"))
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                it,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Connected"))
                    MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    // ── Pairing Section ──────────────────────────────
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    SectionHeader("Wireless Pairing", Icons.Filled.Phonelink)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Text(
            "To pair your TV (Android 11+):\n" +
            "1. TV: Settings → Developer Options → Wireless Debugging\n" +
            "2. Tap \"Pair device with pairing code\"\n" +
            "3. Enter the IP, pairing port & code below\n" +
            "4. After pairing, use Test Connection above",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }

    OutlinedTextField(
        value = if (pairingPort == 0) "" else pairingPort.toString(),
        onValueChange = onPairingPortChange,
        label = { Text("Pairing Port") },
        placeholder = { Text("37421") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = pairingCode,
        onValueChange = onPairingCodeChange,
        label = { Text("Pairing Code") },
        placeholder = { Text("123456") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )

    Button(
        onClick = onPair,
        enabled = !isPairing && tvHost.isNotBlank() && pairingCode.isNotBlank() && pairingPort != 0,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
    ) {
        Icon(Icons.Filled.Phonelink, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (isPairing) "Pairing..." else "Pair with TV")
    }

    pairingStatus?.let {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (it.startsWith("Paired"))
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                it,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Paired"))
                    MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun CommandsTab(
    selectedPreset: Int,
    customCommand: String,
    autoExecute: Boolean,
    onPresetChange: (Int) -> Unit,
    onCommandChange: (String) -> Unit,
    onAutoExecuteChange: (Boolean) -> Unit
) {
    // ── Command Presets ──────────────────────────────
    SectionHeader("Command Presets", Icons.Filled.Terminal)

    Text(
        "Select a preset or choose Custom to write your own command. " +
        "Use {URL} as placeholder — it gets replaced with the shared link.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SettingsManager.COMMAND_PRESETS.forEach { preset ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(
                selected = selectedPreset == preset.id,
                onClick = { onPresetChange(preset.id) }
            )
            Text(
                preset.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    // ── Command Editor ──────────────────────────────
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    SectionHeader("Command Template", Icons.Filled.Edit)

    OutlinedTextField(
        value = customCommand,
        onValueChange = onCommandChange,
        label = { Text("Shell Command Template") },
        placeholder = { Text("am start -a android.intent.action.VIEW -d \"{URL}\"") },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth(),
        enabled = selectedPreset == SettingsManager.COMMAND_PRESETS.last().id // Only editable for Custom
    )

    if (selectedPreset != SettingsManager.COMMAND_PRESETS.last().id) {
        Text(
            "Select \"Custom Command\" to edit the command template.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ── Auto Execute Toggle ─────────────────────────
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    SectionHeader("Share Behavior", Icons.Filled.FastForward)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Switch(
            checked = autoExecute,
            onCheckedChange = onAutoExecuteChange
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Auto-Execute", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (autoExecute) "When you share a link, command runs immediately"
                else "When you share a link, you can edit the command first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
