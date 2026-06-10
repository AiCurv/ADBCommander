package com.adbcommander

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdbManager.initCert(applicationContext)
        enableEdgeToEdge()

        setContent {
            ADBCommanderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Tab definitions
// ──────────────────────────────────────────────────────────────────────────

enum class MainTab(val label: String, val icon: @Composable () -> Unit) {
    Connection(
        label = "Connection",
        icon = { Icon(Icons.Filled.Link, contentDescription = null) }
    ),
    Settings(
        label = "Settings",
        icon = { Icon(Icons.Filled.Settings, contentDescription = null) }
    )
}

// ──────────────────────────────────────────────────────────────────────────
// Main screen with bottom navigation
// ──────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(MainTab.Connection) }

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
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = tab.icon,
                        label = { Text(tab.label) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            MainTab.Connection -> ConnectionTab(Modifier.padding(innerPadding))
            MainTab.Settings -> SettingsTab(Modifier.padding(innerPadding))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Tab 1: Connection — TV IP, Port, Test, Pairing
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun ConnectionTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var pairingPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_PAIRING_PORT) }

    var pairingCode by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var pairingStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        tvHost = settings.getTvHost()
        tvPort = settings.getTvPort()
        pairingPort = settings.getPairingPort()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Section: TV Connection ───────────────────────────────────
        SectionHeader("TV Connection", Icons.Filled.Link)

        OutlinedTextField(
            value = tvHost,
            onValueChange = {
                tvHost = it
                scope.launch { settings.setTvHost(it) }
            },
            label = { Text("TV IP Address") },
            placeholder = { Text("192.168.1.123") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = tvPort.toString(),
            onValueChange = {
                it.toIntOrNull()?.let { port ->
                    tvPort = port
                    scope.launch { settings.setTvPort(port) }
                }
            },
            label = { Text("Connection Port") },
            placeholder = { Text("5555") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (tvHost.isBlank()) {
                    connectionStatus = "Please enter the TV IP address"
                    return@Button
                }
                isTesting = true
                connectionStatus = null
                scope.launch {
                    val result = AdbManager.testConnection(tvHost, tvPort)
                    isTesting = false
                    connectionStatus = if (result.isSuccess) {
                        "Connected! TV responded: ${result.getOrDefault("")}"
                    } else {
                        "Connection failed: ${result.exceptionOrNull()?.message}"
                    }
                }
            },
            enabled = !isTesting && tvHost.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isTesting) "Testing..." else "Test Connection")
        }

        connectionStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Connected"))
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider()

        // ── Section: Pairing (Android 11+) ──────────────────────────
        SectionHeader("Wireless Pairing (Android 11+)", Icons.Filled.SettingsRemote)

        Text(
            text = "If your TV runs Android 11 or later, pair it first before connecting. " +
                    "On the TV: Settings → Developer Options → Wireless debugging → " +
                    "Pair device with pairing code. Enter the pairing port and code below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = pairingPort.toString(),
            onValueChange = {
                it.toIntOrNull()?.let { port ->
                    pairingPort = port
                    scope.launch { settings.setPairingPort(port) }
                }
            },
            label = { Text("Pairing Port") },
            placeholder = { Text("37155") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("Pairing Code") },
            placeholder = { Text("6-digit code from TV") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (tvHost.isBlank()) {
                    pairingStatus = "Please enter the TV IP address first"
                    return@Button
                }
                if (pairingCode.isBlank()) {
                    pairingStatus = "Please enter the pairing code"
                    return@Button
                }
                isPairing = true
                pairingStatus = null
                scope.launch {
                    val result = AdbManager.pair(tvHost, pairingPort, pairingCode)
                    isPairing = false
                    pairingStatus = if (result.isSuccess) {
                        "Pairing successful! You can now connect."
                    } else {
                        "Pairing failed: ${result.exceptionOrNull()?.message}"
                    }
                }
            },
            enabled = !isPairing && tvHost.isNotBlank() && pairingCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Cast, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isPairing) "Pairing..." else "Pair with TV")
        }

        pairingStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Pairing successful"))
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Tab 2: Settings — Package Manager Layout + Execution Logs & History
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val logStore = remember { CommandLogStore(context) }
    val scope = rememberCoroutineScope()

    var defaultCommand by remember { mutableStateOf(SettingsManager.DEFAULT_COMMAND) }
    var intentAction by remember { mutableStateOf(SettingsManager.DEFAULT_INTENT_ACTION) }
    var targetPackage by remember { mutableStateOf(SettingsManager.DEFAULT_TARGET_PACKAGE) }
    var targetActivity by remember { mutableStateOf(SettingsManager.DEFAULT_TARGET_ACTIVITY) }

    var isPkgExpanded by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(logStore.getLogs()) }

    // Load saved settings on first composition
    LaunchedEffect(Unit) {
        defaultCommand = settings.getDefaultCommand()
        intentAction = settings.getIntentAction()
        targetPackage = settings.getTargetPackage()
        targetActivity = settings.getTargetActivity()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Section: Package Manager Layout Configuration (expandable) ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column {
                // Expandable header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isPkgExpanded = !isPkgExpanded }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Package Manager Layout Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        if (isPkgExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isPkgExpanded) "Collapse" else "Expand"
                    )
                }

                // Expandable content
                AnimatedVisibility(visible = isPkgExpanded) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Configure how the ADB shell command is structured. " +
                                    "Changes to Intent Action, Target Package, or Target Activity " +
                                    "will auto-regenerate the command template below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = intentAction,
                            onValueChange = {
                                intentAction = it
                                scope.launch {
                                    settings.setIntentAction(it)
                                    defaultCommand = settings.rebuildCommandFromLayout()
                                    settings.setDefaultCommand(defaultCommand)
                                }
                            },
                            label = { Text("Intent Action") },
                            placeholder = { Text("android.intent.action.VIEW") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = targetPackage,
                            onValueChange = {
                                targetPackage = it
                                scope.launch {
                                    settings.setTargetPackage(it)
                                    defaultCommand = settings.rebuildCommandFromLayout()
                                    settings.setDefaultCommand(defaultCommand)
                                }
                            },
                            label = { Text("Target Package (optional)") },
                            placeholder = { Text("com.android.tv") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = targetActivity,
                            onValueChange = {
                                targetActivity = it
                                scope.launch {
                                    settings.setTargetActivity(it)
                                    defaultCommand = settings.rebuildCommandFromLayout()
                                    settings.setDefaultCommand(defaultCommand)
                                }
                            },
                            label = { Text("Target Activity (optional)") },
                            placeholder = { Text("MainActivity") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider()

                        Text(
                            text = "Command template — tokens {URL} and {MIME} are replaced " +
                                    "at execution time and shell-escaped automatically. " +
                                    "Do NOT add quotes around tokens; escaping is handled by " +
                                    "shellEscape() as the single source of truth.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = defaultCommand,
                            onValueChange = {
                                defaultCommand = it
                                scope.launch { settings.setDefaultCommand(it) }
                            },
                            label = { Text("Shell Command Template") },
                            placeholder = { Text("am start -a android.intent.action.VIEW -d {URL} -t {MIME}") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }

        // ── Section: Execution Logs & History ─────────────────────────
        SectionHeader("Execution Logs & History", Icons.Filled.History)

        // Refresh button + Clear button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { logs = logStore.getLogs() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refresh")
            }
            OutlinedButton(
                onClick = {
                    logStore.clearLogs()
                    logs = emptyList()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear All")
            }
        }

        if (logs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "No commands executed yet. Share content to your TV " +
                            "and the full command string will appear here.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = "${logs.size} command(s) — tap any entry to copy the full command",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Log entries — each one is tappable to copy
        logs.forEach { entry ->
            LogEntryCard(entry)
        }

        // Bottom spacing
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun LogEntryCard(entry: CommandLogStore.LogEntry) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val statusColor = if (entry.isSuccess)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.error

    val statusIcon = if (entry.isSuccess)
        Icons.Filled.CheckCircle
    else
        Icons.Filled.Error

    val statusText = if (entry.isSuccess) "Success" else "Failed"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboardManager.setText(AnnotatedString(entry.command))
                android.widget.Toast.makeText(
                    context,
                    "Command copied to clipboard",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Top row: status + timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        statusIcon,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor
                    )
                }
                Text(
                    text = CommandLogStore.formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Command string (monospace, max 4 lines)
            Text(
                text = entry.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4
            )

            // Tap hint
            Text(
                text = "Tap to copy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Shared composable: section header
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
