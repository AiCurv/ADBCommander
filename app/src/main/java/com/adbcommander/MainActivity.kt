package com.adbcommander

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
                } catch (e: Exception) {
                    // Some devices may not support this intent
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

    // ── State ────────────────────────────────────────────────────────
    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var customCommand by remember { mutableStateOf(SettingsManager.DEFAULT_COMMAND) }
    var autoExecute by remember { mutableStateOf(false) }

    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var runOutput by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    // ── Preset state ─────────────────────────────────────────────────
    var presets by remember { mutableStateOf(settings.getAllPresets()) }
    var selectedPresetName by remember { mutableStateOf("Default Video Player") }
    var presetExpanded by remember { mutableStateOf(false) }

    // ── Save preset dialog state ─────────────────────────────────────
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    // ── Delete preset dialog state ───────────────────────────────────
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
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    label = { Text("Preset name") },
                    placeholder = { Text("My Custom Command") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPresetName.isNotBlank() && customCommand.isNotBlank()) {
                            val saved = settings.saveCustomPreset(newPresetName.trim(), customCommand)
                            if (saved) {
                                presets = settings.getAllPresets()
                                selectedPresetName = newPresetName.trim()
                                scope.launch { settings.setSelectedPreset(selectedPresetName) }
                                Toast.makeText(context, "Preset \"$selectedPresetName\" saved!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Name conflicts with built-in preset", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showSaveDialog = false
                        newPresetName = ""
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false; newPresetName = "" }) { Text("Cancel") }
            }
        )
    }

    // ── Delete Preset Confirmation Dialog ────────────────────────────
    if (showDeleteDialog && presetToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; presetToDelete = null },
            title = { Text("Delete Preset") },
            text = { Text("Delete preset \"${presetToDelete}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.deleteCustomPreset(presetToDelete!!)
                        presets = settings.getAllPresets()
                        if (selectedPresetName == presetToDelete) {
                            selectedPresetName = "Default Video Player"
                            customCommand = settings.getPresetCommand("Default Video Player") ?: SettingsManager.DEFAULT_COMMAND
                            scope.launch {
                                settings.setSelectedPreset(selectedPresetName)
                                settings.setDefaultCommand(customCommand)
                            }
                        }
                        showDeleteDialog = false
                        presetToDelete = null
                        Toast.makeText(context, "Preset deleted", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; presetToDelete = null }) { Text("Cancel") }
            }
        )
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ═══════════════════════════════════════════════════════════
            //  TOP — TV Connection
            // ═══════════════════════════════════════════════════════════
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
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Connected"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            // ═══════════════════════════════════════════════════════════
            //  MIDDLE — Command Presets + Custom Command + Run
            // ═══════════════════════════════════════════════════════════
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionHeader("Command Presets", Icons.Filled.List)

            // ── Dropdown for preset selection ─────────────────────────
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = presetExpanded,
                    onDismissRequest = { presetExpanded = false }
                ) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            preset.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        if (preset.command.isNotBlank()) {
                                            Text(
                                                preset.command.take(60) + if (preset.command.length > 60) "..." else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1
                                            )
                                        } else {
                                            Text(
                                                "Blank template — type your own",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    // Show delete icon for custom presets (not built-in)
                                    val isBuiltIn = SettingsManager.BUILT_IN_PRESETS.any { it.name == preset.name }
                                    if (!isBuiltIn) {
                                        IconButton(
                                            onClick = {
                                                presetToDelete = preset.name
                                                showDeleteDialog = true
                                            }
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Delete preset",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                selectedPresetName = preset.name
                                customCommand = preset.command
                                scope.launch {
                                    settings.setSelectedPreset(preset.name)
                                    settings.setDefaultCommand(preset.command)
                                }
                                presetExpanded = false
                            }
                        )
                    }
                }
            }

            // ── Save Current as Preset button ────────────────────────
            OutlinedButton(
                onClick = { showSaveDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = customCommand.isNotBlank()
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("SAVE CURRENT AS PRESET")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // ── Command text area ────────────────────────────────────
            SectionHeader("Shell Command", Icons.Filled.Terminal)

            Text(
                "Use {URL} as placeholder — it auto-replaces with shared links. \"adb shell\" prefix is stripped automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = customCommand,
                onValueChange = {
                    customCommand = it
                    // Mark as custom if it doesn't match any preset
                    val matchingPreset = presets.find { p -> p.command == it }
                    if (matchingPreset == null) {
                        selectedPresetName = "Custom Template"
                    } else {
                        selectedPresetName = matchingPreset.name
                    }
                    scope.launch { settings.setDefaultCommand(it) }
                },
                label = { Text("Shell Command") },
                placeholder = { Text("am start -a android.intent.action.VIEW -d \"{URL}\" -t \"video/*\"") },
                minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            )

            // ── RUN COMMAND button ───────────────────────────────────
            Button(
                onClick = {
                    if (customCommand.isBlank()) {
                        runOutput = "Command cannot be empty"; return@Button
                    }
                    if (tvHost.isBlank()) {
                        runOutput = "Set TV IP first"; return@Button
                    }
                    isRunning = true; runOutput = null
                    scope.launch {
                        val result = AdbManager.executeShell(context, tvHost, tvPort, customCommand)
                        isRunning = false
                        if (result.isSuccess) {
                            val output = result.getOrDefault("")
                            runOutput = "OK: $output"
                            Toast.makeText(context, output, Toast.LENGTH_SHORT).show()
                        } else {
                            val err = result.exceptionOrNull()?.message ?: "Unknown error"
                            runOutput = "FAIL: $err"
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
                Text(
                    if (isRunning) "Running..." else "▶ RUN COMMAND",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            runOutput?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (it.startsWith("OK"))
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (it.startsWith("OK"))
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════
            //  BOTTOM — Share Behavior
            // ═══════════════════════════════════════════════════════════
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionHeader("Share Behavior", Icons.Filled.FastForward)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    checked = autoExecute,
                    onCheckedChange = { autoExecute = it; scope.launch { settings.setAutoExecute(it) } }
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Auto-Execute", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (autoExecute) "Share a link → command runs immediately"
                        else "Share a link → review/edit command first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
