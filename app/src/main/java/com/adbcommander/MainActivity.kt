package com.adbcommander

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBatteryExemption()
        enableEdgeToEdge()
        setContent {
            ADBCommanderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent()
                    intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Connection", "Settings")

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
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.Filled.Link
                                    else -> Icons.Filled.Settings
                                },
                                contentDescription = null
                            )
                        },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ConnectionTab()
                1 -> SettingsTab()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  CONNECTION TAB — TV Connection + Presets + Command + Run
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionTab() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var customCommand by remember { mutableStateOf(SettingsManager.DEFAULT_COMMAND) }
    var autoExecute by remember { mutableStateOf(false) }

    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var runOutput by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    // Preset state
    var presets by remember { mutableStateOf(settings.getAllPresets()) }
    var selectedPresetName by remember { mutableStateOf("Universal Default") }
    var presetExpanded by remember { mutableStateOf(false) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        tvHost = settings.getTvHost()
        tvPort = settings.getTvPort()
        customCommand = settings.getDefaultCommand()
        autoExecute = settings.getAutoExecute()
        selectedPresetName = settings.getSelectedPreset()
    }

    // ── Save Preset Dialog ───────────────────────────────────────────
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false; newPresetName = "" },
            title = { Text("Save as Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("Preset name") },
                        placeholder = { Text("My Custom Command") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Will save the current shell command:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        customCommand.take(100) + if (customCommand.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPresetName.isNotBlank() && customCommand.isNotBlank()) {
                        val saved = settings.saveCustomPreset(newPresetName.trim(), customCommand)
                        if (saved) {
                            presets = settings.getAllPresets()
                            selectedPresetName = newPresetName.trim()
                            scope.launch { settings.setSelectedPreset(selectedPresetName) }
                            Toast.makeText(context, "Preset saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Name conflicts with built-in preset", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showSaveDialog = false; newPresetName = ""
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false; newPresetName = "" }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteDialog && presetToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; presetToDelete = null },
            title = { Text("Delete Preset") },
            text = { Text("Delete preset \"${presetToDelete}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    settings.deleteCustomPreset(presetToDelete!!)
                    presets = settings.getAllPresets()
                    if (selectedPresetName == presetToDelete) {
                        selectedPresetName = "Universal Default"
                        customCommand = settings.getPresetCommand("Universal Default") ?: SettingsManager.DEFAULT_COMMAND
                        scope.launch {
                            settings.setSelectedPreset(selectedPresetName)
                            settings.setDefaultCommand(customCommand)
                        }
                    }
                    showDeleteDialog = false; presetToDelete = null
                    Toast.makeText(context, "Preset deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; presetToDelete = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══ TV Connection ═════════════════════════════════════════════
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
            onValueChange = {
                it.toIntOrNull()?.let { p -> tvPort = p; scope.launch { settings.setTvPort(p) } }
            },
            label = { Text("Connection Port") },
            placeholder = { Text("5555") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (tvHost.isBlank()) { connectionStatus = "Enter TV IP first"; return@Button }
                isTesting = true; connectionStatus = null
                scope.launch {
                    val result = AdbManager.testConnection(context, tvHost, tvPort)
                    isTesting = false
                    connectionStatus = if (result.isSuccess)
                        "Connected! TV responded: ${result.getOrDefault("")}"
                    else
                        "Failed: ${result.exceptionOrNull()?.message}"
                }
            },
            enabled = !isTesting && tvHost.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isTesting) "Testing..." else "Test Connection")
        }

        connectionStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }

        // ═══ Command Presets ═══════════════════════════════════════════
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        SectionHeader("Command Presets", Icons.Filled.List)

        ExposedDropdownMenuBox(
            expanded = presetExpanded,
            onExpandedChange = { presetExpanded = !presetExpanded }
        ) {
            OutlinedTextField(
                value = selectedPresetName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Select Preset") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )

            ExposedDropdownMenu(
                expanded = presetExpanded,
                onDismissRequest = { presetExpanded = false }
            ) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                                    if (preset.command.isNotBlank()) {
                                        Text(
                                            preset.command.take(60) + if (preset.command.length > 60) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                    } else {
                                        Text("Blank template", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                }
                                val isBuiltIn = SettingsManager.BUILT_IN_PRESETS.any { it.name == preset.name }
                                if (!isBuiltIn) {
                                    IconButton(onClick = { presetToDelete = preset.name; showDeleteDialog = true }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        },
                        onClick = {
                            selectedPresetName = preset.name
                            customCommand = preset.command
                            scope.launch { settings.setSelectedPreset(preset.name); settings.setDefaultCommand(preset.command) }
                            presetExpanded = false
                        }
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { showSaveDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = customCommand.isNotBlank()
        ) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("SAVE CURRENT AS PRESET")
        }

        // ═══ Shell Command ════════════════════════════════════════════
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        SectionHeader("Shell Command", Icons.Filled.Terminal)

        Text(
            "Use bare {URL} for shared links, {FILE} for local files, {MIME} for content type. \"adb shell\" prefix stripped. URLs are shell-escaped automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = customCommand,
            onValueChange = {
                customCommand = it
                val matchingPreset = presets.find { p -> p.command == it }
                if (matchingPreset == null) selectedPresetName = "Custom"
                else selectedPresetName = matchingPreset.name
                scope.launch { settings.setDefaultCommand(it) }
            },
            label = { Text("Shell Command") },
            placeholder = { Text("am start -a android.intent.action.VIEW -d {URL}") },
            minLines = 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )

        Button(
            onClick = {
                if (customCommand.isBlank()) { runOutput = "Command cannot be empty"; return@Button }
                if (tvHost.isBlank()) { runOutput = "Set TV IP first"; return@Button }
                isRunning = true; runOutput = null
                scope.launch {
                    val result = AdbManager.executeShell(context, tvHost, tvPort, customCommand)
                    isRunning = false
                    if (result.isSuccess) {
                        val output = result.getOrDefault("")
                        runOutput = "OK: $output"
                        val logStore = CommandLogStore(context)
                        logStore.addLog(customCommand, true)
                        Toast.makeText(context, output, Toast.LENGTH_SHORT).show()
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Unknown error"
                        runOutput = "FAIL: $err"
                        val logStore = CommandLogStore(context)
                        logStore.addLog(customCommand, false)
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (isRunning) "Running..." else "RUN COMMAND", style = MaterialTheme.typography.titleMedium)
        }

        runOutput?.let {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (it.startsWith("OK")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(it, modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (it.startsWith("OK")) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // ═══ Share Behavior ════════════════════════════════════════════
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        SectionHeader("Share Behavior", Icons.Filled.FastForward)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Switch(
                checked = autoExecute,
                onCheckedChange = { autoExecute = it; scope.launch { settings.setAutoExecute(it) } }
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Auto-Execute", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (autoExecute) "Share a link and command runs immediately" else "Share a link and pick preset first",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  SETTINGS TAB — Package Manager + Backup & Restore + Execution Logs
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val logStore = remember { CommandLogStore(context) }

    // ── Package Manager state ──────────────────────────────────────────
    var pmExpanded by remember { mutableStateOf(false) }
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf("az") }
    var includeSystemApps by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }

    // Build preset dialog
    var showBuildDialog by remember { mutableStateOf(false) }
    var buildPresetPackage by remember { mutableStateOf("") }
    var buildPresetName by remember { mutableStateOf("") }
    var buildAction by remember { mutableStateOf("android.intent.action.VIEW") }
    var buildDataUri by remember { mutableStateOf("{URL}") }
    var buildType by remember { mutableStateOf("{MIME}") }
    var buildComponent by remember { mutableStateOf("") }

    // ── Logs state ─────────────────────────────────────────────────────
    var logs by remember { mutableStateOf(logStore.getLogs()) }
    var showLogDialog by remember { mutableStateOf(false) }
    var selectedLogCommand by remember { mutableStateOf("") }

    // ── Backup & Restore state ─────────────────────────────────────────
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        logs = logStore.getLogs()
    }

    // ── Log Detail Dialog ──────────────────────────────────────────────
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Command Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        selectedLogCommand,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 300.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("command", selectedLogCommand))
                    Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("Close") }
            }
        )
    }

    // ── Import Presets Dialog ──────────────────────────────────────────
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false; importJsonText = "" },
            title = { Text("Import Presets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste the JSON backup below. Format: {\"presets\": [{\"name\": \"...\", \"command\": \"...\"}]}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        label = { Text("JSON") },
                        placeholder = { Text("{\"presets\": [...]}") },
                        minLines = 4, maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = settings.importPresetsJson(importJsonText)
                    if (count >= 0) {
                        Toast.makeText(context, "Imported $count preset(s)!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
                    }
                    showImportDialog = false; importJsonText = ""
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false; importJsonText = "" }) { Text("Cancel") }
            }
        )
    }

    // ── Build Preset Dialog ─────────────────────────────────────────
    if (showBuildDialog) {
        AlertDialog(
            onDismissRequest = { showBuildDialog = false },
            title = { Text("Build Preset for Package") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = buildPresetName,
                        onValueChange = { buildPresetName = it },
                        label = { Text("Preset Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = buildPresetPackage,
                        onValueChange = {
                            buildPresetPackage = it
                            if (buildComponent.isBlank()) buildComponent = it
                        },
                        label = { Text("Package") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildAction,
                        onValueChange = { buildAction = it },
                        label = { Text("Action") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildDataUri,
                        onValueChange = { buildDataUri = it },
                        label = { Text("Data URI (use {URL} or {FILE})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildType,
                        onValueChange = { buildType = it },
                        label = { Text("MIME Type (use {MIME} for dynamic)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildComponent,
                        onValueChange = { buildComponent = it },
                        label = { Text("Component (pkg/activity)") },
                        placeholder = { Text(buildPresetPackage) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )

                    HorizontalDivider()
                    Text("Generated command:", style = MaterialTheme.typography.labelSmall)
                    val generated = settings.buildPresetFromPackage(
                        buildPresetPackage, buildPresetPackage, buildAction, buildDataUri, buildType, buildComponent
                    )
                    Text(
                        generated,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cmd = settings.buildPresetFromPackage(
                        buildPresetPackage, buildPresetPackage, buildAction, buildDataUri, buildType, buildComponent
                    )
                    val name = buildPresetName.ifBlank { buildPresetPackage.substringAfterLast(".") }
                    val saved = settings.saveCustomPreset(name, cmd)
                    if (saved) {
                        Toast.makeText(context, "Preset \"$name\" saved! Select it in the Connection tab.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to save (name conflict?)", Toast.LENGTH_SHORT).show()
                    }
                    showBuildDialog = false
                }) { Text("Save Preset") }
            },
            dismissButton = {
                TextButton(onClick = { showBuildDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════
        //  PACKAGE MANAGER — Expandable Card
        // ═══════════════════════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pmExpanded = !pmExpanded }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Package Manager Template Configurator", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Scan TV packages & build command templates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (pmExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }

                AnimatedVisibility(visible = pmExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()

                        Text("Scan your TV for installed apps, then build command templates. Requires TV connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            FilterChip(
                                selected = includeSystemApps,
                                onClick = { includeSystemApps = !includeSystemApps },
                                label = { Text("System Apps") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (includeSystemApps) "Showing all packages" else "3rd-party only",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    val host = settings.getTvHost()
                                    val port = settings.getTvPort()
                                    if (host.isBlank()) { scanError = "Set TV IP in Connection tab first"; return@launch }
                                    isScanning = true; scanError = null; packages = emptyList()
                                    val cmd = if (includeSystemApps) "pm list packages" else "pm list packages -3"
                                    val result = AdbManager.executeShell(context, host, port, cmd)
                                    isScanning = false
                                    if (result.isSuccess) {
                                        val output = result.getOrDefault("")
                                        packages = output.lines()
                                            .map { it.trim() }
                                            .filter { it.startsWith("package:") }
                                            .map { it.removePrefix("package:") }
                                        if (packages.isEmpty()) scanError = "No packages found"
                                    } else {
                                        scanError = "Scan failed: ${result.exceptionOrNull()?.message}"
                                    }
                                }
                            },
                            enabled = !isScanning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isScanning) "Scanning..." else "Scan TV Packages")
                        }

                        scanError?.let {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Text(it, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }

                        if (packages.isNotEmpty()) {
                            Text("${packages.size} packages found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

                            // Search + Sort row
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("Filter") },
                                    placeholder = { Text("Search...") },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(4.dp))
                                ExposedDropdownMenuBox(expanded = sortExpanded, onExpandedChange = { sortExpanded = !sortExpanded }) {
                                    IconButton(onClick = { sortExpanded = true }, modifier = Modifier.menuAnchor()) {
                                        Icon(Icons.Filled.Sort, contentDescription = "Sort")
                                    }
                                    ExposedDropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                        DropdownMenuItem(text = { Text("A-Z") }, onClick = { sortOrder = "az"; sortExpanded = false })
                                        DropdownMenuItem(text = { Text("Z-A") }, onClick = { sortOrder = "za"; sortExpanded = false })
                                    }
                                }
                            }

                            // Sorted and filtered package list
                            val sortedPackages = remember(packages, sortOrder, searchQuery) {
                                val filtered = packages.filter { searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true) }
                                if (sortOrder == "az") filtered.sorted() else filtered.sortedDescending()
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                sortedPackages.forEach { pkg ->
                                    PackageRow(
                                        packageName = pkg,
                                        onCopy = {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("package", pkg))
                                            Toast.makeText(context, "Copied: $pkg", Toast.LENGTH_SHORT).show()
                                        },
                                        onBuildPreset = {
                                            buildPresetPackage = pkg
                                            buildPresetName = pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                                            buildAction = "android.intent.action.VIEW"
                                            buildDataUri = "{URL}"
                                            buildType = "{MIME}"
                                            buildComponent = ""
                                            showBuildDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  BACKUP & RESTORE PRESETS — Card
        // ═══════════════════════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Backup & Restore Presets", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Export or import your custom presets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val json = settings.exportPresetsJson()
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("adb_commander_presets", json))
                            Toast.makeText(context, "Presets JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true; importJsonText = "" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import")
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  EXECUTION LOGS & HISTORY — Card
        // ═══════════════════════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Execution Logs & History", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Tap any entry to view details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = { logStore.clearLogs(); logs = emptyList(); Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show() }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear Logs", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                if (logs.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("No execution logs yet. Run a command from the Connection tab to start logging.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(8.dp))
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        logs.forEach { entry ->
                            LogEntryRow(
                                entry = entry,
                                onTap = {
                                    selectedLogCommand = entry.command
                                    showLogDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  REUSABLE COMPONENTS
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun PackageRow(packageName: String, onCopy: () -> Unit, onBuildPreset: () -> Unit) {
    val context = LocalContext.current
    val appIcon by remember(packageName) {
        mutableStateOf<Drawable?>(
            try { context.packageManager.getApplicationIcon(packageName) } catch (_: Exception) { null }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon or fallback
            Surface(
                modifier = Modifier.size(32.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                if (appIcon != null) {
                    AndroidAppIcon(icon = appIcon!!, modifier = Modifier.size(32.dp).clip(CircleShape))
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                packageName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onBuildPreset, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Build, contentDescription = "Build Preset", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun AndroidAppIcon(icon: Drawable, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                setImageDrawable(icon)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
        }
    )
}

@Composable
fun LogEntryRow(entry: CommandLogStore.LogEntry, onTap: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onTap() },
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isSuccess) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (entry.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (entry.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.command.take(80) + if (entry.command.length > 80) "..." else "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(CommandLogStore.formatTimestamp(entry.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
