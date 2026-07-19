package com.adbcommander

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Auto-start ADB bridge if a TV is configured
        val settings = SettingsManager(this)
        lifecycleScope.launch {
            val host = settings.getTvHost()
            if (host.isNotBlank() && !AdbForegroundService.isRunning()) {
                AdbForegroundService.start(this@MainActivity)
            }
        }

        setContent {
            val context = LocalContext.current
            val settingsManager = remember { SettingsManager(context) }
            var themeMode by remember { mutableStateOf(SettingsManager.THEME_SYSTEM) }

            LaunchedEffect(Unit) {
                themeMode = settingsManager.getThemeMode()
            }
            LaunchedEffect(Unit) {
                settingsManager.themeMode.collect { mode -> themeMode = mode }
            }

            val darkTheme = when (themeMode) {
                SettingsManager.THEME_LIGHT -> false
                SettingsManager.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }

            ADBCommanderTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  MAIN SCREEN — Bottom navigation
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var activeTab by remember { mutableIntStateOf(0) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ADB Commander") },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(
                tonalElevation = 8.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Home") },
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                    label = { Text("Terminal") },
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        // Keep both tabs composed to preserve state across switches.
        // Switching tabs only toggles visibility — no recomposition,
        // no re-connection, no re-scanning.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Home tab always exists, just hidden when inactive
            HomeTab(
                bottomPadding = innerPadding.calculateBottomPadding(),
                isVisible = activeTab == 0
            )
            // Terminal tab always exists, just hidden when inactive
            if (activeTab == 1) {
                TerminalTab(bottomPadding = innerPadding.calculateBottomPadding())
            }
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            SettingsSheet(onClose = { showSettingsSheet = false })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  HOME TAB — Connection Status + TV Apps Grid + Quick Command + Presets
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    bottomPadding: Dp = 0.dp,
    isVisible: Boolean = true
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    // ── Connection state ─────────────────────────────────────────────
    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var tvName by remember { mutableStateOf("") }
    var connectionVerified by remember { mutableStateOf<Boolean?>(null) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // ── Discovery state ──────────────────────────────────────────────
    val discovery = remember { TvDiscoveryService(context) }
    var discoveredTvs by remember { mutableStateOf<List<TvDiscoveryService.DiscoveredTv>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var expandedRowHost by remember { mutableStateOf<String?>(null) }

    // ── App grid state ───────────────────────────────────────────────
    var tvApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanningApps by remember { mutableStateOf(false) }
    var showAppPopup by remember { mutableStateOf(false) }
    var popupApp by remember { mutableStateOf("") }

    // ── Quick command state ──────────────────────────────────────────
    var quickCommand by remember { mutableStateOf("") }
    var commandOutput by remember { mutableStateOf<String?>(null) }
    var isRunningCommand by remember { mutableStateOf(false) }
    var commandSucceeded by remember { mutableStateOf(false) }

    // ── Preset dialogs ───────────────────────────────────────────────
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var newPresetCommand by remember { mutableStateOf("") }
    var newPresetApp by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<String?>(null) }

    // ── Presets ──────────────────────────────────────────────────────
    var allPresets by remember { mutableStateOf(SettingsManager.BUILT_IN_PRESETS) }
    var presetRefreshKey by remember { mutableIntStateOf(0) }

    // ── Settings loaded flag ─────────────────────────────────────────
    var isSettingsLoaded by remember { mutableStateOf(false) }

    // ═══ Phase 1: Instant cache read ═════════════════════════════════
    SettingsManager.getCachedTvHost()?.let { tvHost = it }
    SettingsManager.getCachedTvPort()?.let { tvPort = it }
    SettingsManager.getCachedTvName()?.let { tvName = it }

    // ═══ Phase 2: Async DataStore read + verify connection ═══════════
    LaunchedEffect(Unit) {
        val h = settings.getTvHost()
        val p = settings.getTvPort()
        val n = settings.getSelectedTvName()
        tvHost = h
        tvPort = p
        tvName = n
        isSettingsLoaded = true

        if (h.isNotBlank()) {
            if (AdbForegroundService.isRunning()) {
                connectionVerified = true
            } else {
                val result = AdbManager.testConnection(context, h, p)
                connectionVerified = result.isSuccess
                if (result.isSuccess) {
                    AdbForegroundService.start(context)
                }
            }
        }
    }

    // Refresh presets on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                presetRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(presetRefreshKey) {
        allPresets = withContext(Dispatchers.IO) { settings.getAllPresets() }
    }

    // ═══ Discovery scan — ONLY if no TV configured ═══════════════════
    var scanJob by remember { mutableStateOf<Job?>(null) }

    val startScan: () -> Unit = {
        scanJob?.cancel()
        isScanning = true
        scanJob = lifecycleOwner.lifecycleScope.launch {
            try {
                discovery.discover().collect { tvs -> discoveredTvs = tvs }
            } finally {
                isScanning = false
            }
        }
    }

    DisposableEffect(lifecycleOwner, isSettingsLoaded) {
        if (isSettingsLoaded && tvHost.isBlank() && !AdbForegroundService.isRunning()) {
            startScan()
        }
        onDispose {
            scanJob?.cancel()
            isScanning = false
        }
    }

    // ═══ Helper: select a discovered TV ═══════════════════════════════
    val selectTv: (TvDiscoveryService.DiscoveredTv) -> Unit = { tv ->
        scope.launch {
            settings.setTvHost(tv.host)
            settings.setTvPort(tv.port)
            if (tv.name.isNotBlank()) {
                settings.setSelectedTvName(tv.name)
            }
            tvHost = tv.host
            tvPort = tv.port
            tvName = tv.name

            val result = AdbManager.testConnection(context, tv.host, tv.port)
            connectionVerified = result.isSuccess
            if (result.isSuccess) {
                if (!AdbForegroundService.isRunning()) {
                    AdbForegroundService.start(context)
                }
            }
        }
    }

    // ═══ Helper: disconnect from TV ══════════════════════════════════
    val disconnectTv: () -> Unit = {
        scope.launch {
            settings.setTvHost("")
            settings.setSelectedTvName("")
            tvHost = ""
            tvName = ""
            tvApps = emptyList()
            connectionVerified = null
            connectionStatus = null
            commandOutput = null
            commandSucceeded = false
            AdbForegroundService.stop(context)
        }
    }

    // ═══ Helper: scan TV apps ════════════════════════════════════════
    val scanTvApps: () -> Unit = {
        if (tvHost.isBlank()) return
        isScanningApps = true
        scope.launch {
            val result = AdbManager.executeShell(context, tvHost, tvPort, "pm list packages -3")
            isScanningApps = false
            if (result.isSuccess) {
                val output = result.getOrDefault("")
                if (output.isNotBlank() && output != "Command executed (no output)") {
                    tvApps = output.lines()
                        .map { it.removePrefix("package:").trim() }
                        .filter { it.isNotBlank() && it.contains(".") }
                        .sorted()
                    if (tvApps.isEmpty()) {
                        Toast.makeText(context, "No third-party apps found", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Empty response — TV may need ADB authorization", Toast.LENGTH_LONG).show()
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                Toast.makeText(context, "Scan failed: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DIALOGS
    // ═══════════════════════════════════════════════════════════════════

    // ── App Popup Dialog ─────────────────────────────────────────────
    if (showAppPopup && popupApp.isNotBlank()) {
        val appPresets = allPresets.filter { it.appPackage == popupApp }
        var presetCmd by remember(popupApp) { mutableStateOf(settings.buildPresetFromPackage("", popupApp)) }
        var presetName by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { showAppPopup = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header — app short name + copy package
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Android, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(popupApp.substringAfterLast("."),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    // Package name row with copy
                    OutlinedCard(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(popupApp,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("package", popupApp))
                                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy",
                                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    HorizontalDivider()

                    // Create Preset
                    Text("Create Preset", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)

                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Name") },
                        placeholder = { Text("My Preset") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = presetCmd,
                        onValueChange = { presetCmd = it },
                        label = { Text("Command") },
                        placeholder = { Text("""am start -a android.intent.action.VIEW -d "{URL}" -n pkg/.Activity""") },
                        minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )

                    Text(
                        "Use {URL} {MIME} {FILE} as placeholders for ShareSheet auto-execute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val cmd = "monkey -p $popupApp -c android.intent.category.LAUNCHER 1"
                                    val result = AdbManager.executeShell(context, tvHost, tvPort, cmd)
                                    Toast.makeText(context,
                                        if (result.isSuccess) "Launched!" else "Failed: ${result.exceptionOrNull()?.message}",
                                        Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Launch", style = MaterialTheme.typography.labelMedium)
                        }

                        Button(
                            onClick = {
                                if (presetName.isNotBlank() && presetCmd.isNotBlank()) {
                                    val saved = settings.saveCustomPreset(presetName.trim(), presetCmd, popupApp)
                                    if (saved) {
                                        presetRefreshKey++
                                        Toast.makeText(context, "Preset saved!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Name conflicts with built-in", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            enabled = presetName.isNotBlank() && presetCmd.isNotBlank()
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Existing presets for this app
                    if (appPresets.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Saved presets", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        appPresets.forEach { preset ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.PlaylistPlay, contentDescription = null,
                                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.bodySmall)
                                    Text(preset.command.take(50) + if (preset.command.length > 50) "..." else "",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(
                                    onClick = { presetToDelete = preset.name; showDeleteDialog = true },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete",
                                        modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = { showAppPopup = false },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Close") }
                }
            }
        }
    }

    // ── Save Preset Dialog (from Quick Command) ──────────────────────
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false; newPresetName = ""; newPresetCommand = "" },
            title = { Text("Save as Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (newPresetApp.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Apps, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("For: $newPresetApp",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("Name") },
                        placeholder = { Text("My Preset") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = newPresetCommand,
                        onValueChange = { newPresetCommand = it },
                        label = { Text("Command") },
                        minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        readOnly = true
                    )
                    Text(
                        "Use {URL} {MIME} {FILE} for ShareSheet auto-execute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPresetName.isNotBlank() && newPresetCommand.isNotBlank()) {
                        val saved = settings.saveCustomPreset(newPresetName.trim(), newPresetCommand, newPresetApp)
                        if (saved) {
                            presetRefreshKey++
                            Toast.makeText(context, "Preset saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Name conflicts with built-in", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showSaveDialog = false; newPresetName = ""; newPresetCommand = ""; newPresetApp = ""
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false; newPresetName = ""; newPresetCommand = ""; newPresetApp = "" }) { Text("Cancel") }
            }
        )
    }

    // ── Delete Preset Dialog ─────────────────────────────────────────
    if (showDeleteDialog && presetToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; presetToDelete = null },
            title = { Text("Delete Preset") },
            text = { Text("Delete \"${presetToDelete}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    settings.deleteCustomPreset(presetToDelete!!)
                    presetRefreshKey++
                    showDeleteDialog = false; presetToDelete = null
                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; presetToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MAIN CONTENT — compact, no expandables, premium feel
    // ═══════════════════════════════════════════════════════════════════

    if (!isVisible) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ═══ 1. Connection Status — always visible, compact ══════════
        GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp),
                    color = when {
                        connectionVerified == true -> Color(0xFF4CAF50)
                        connectionVerified == false -> Color(0xFFFFA000)
                        tvHost.isNotBlank() -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.error
                    }
                ) {}
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            connectionVerified == true && tvName.isNotBlank() -> tvName
                            connectionVerified == true -> tvHost
                            connectionVerified == false -> "${tvName.ifBlank { tvHost }} (lost)"
                            tvHost.isNotBlank() -> "Verifying..."
                            else -> "No TV connected"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            connectionVerified == true -> MaterialTheme.colorScheme.onSurface
                            connectionVerified == false -> Color(0xFFE65100)
                            tvHost.isNotBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    if (tvHost.isNotBlank()) {
                        Text(
                            "$tvHost:$tvPort",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Action buttons
                if (connectionVerified == true) {
                    // Disconnect button
                    IconButton(onClick = disconnectTv, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.LinkOff, contentDescription = "Disconnect",
                            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                } else if (tvHost.isNotBlank()) {
                    // Test button
                    FilledTonalIconButton(
                        onClick = {
                            isTesting = true; connectionStatus = null
                            scope.launch {
                                val result = AdbManager.testConnection(context, tvHost, tvPort)
                                isTesting = false
                                connectionVerified = result.isSuccess
                                connectionStatus = if (result.isSuccess) "Connected!" else "Failed"
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = "Test", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // ═══ 2. TV Discovery (only when NOT connected) ═══════════════
        if (connectionVerified != true || isScanning) {
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                isScanning && discoveredTvs.isEmpty() -> context.getString(R.string.tv_scan_scanning)
                                isScanning && discoveredTvs.isNotEmpty() -> context.getString(R.string.tv_scan_still_scanning)
                                discoveredTvs.isEmpty() -> context.getString(R.string.tv_scan_none)
                                else -> context.getString(R.string.tv_scan_found).format(discoveredTvs.size)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    IconButton(onClick = { startScan() }, enabled = !isScanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Discovered TVs list
            discoveredTvs.forEach { tv ->
                val isExpanded = expandedRowHost == tv.host
                val isSelected = tv.host == tvHost
                DiscoveredTvRow(
                    tv = tv,
                    isSelected = isSelected,
                    isExpanded = isExpanded,
                    onTap = { selectTv(tv) },
                    onExpandToggle = { expandedRowHost = if (isExpanded) null else tv.host },
                    onTest = {
                        scope.launch {
                            val result = AdbManager.testConnection(context, tv.host, tv.port)
                            connectionVerified = result.isSuccess
                            connectionStatus = if (result.isSuccess) "Connected!" else "Failed"
                        }
                    },
                    onForget = {
                        discovery.forgetDevice(tv.host)
                        discoveredTvs = discoveredTvs.filter { it.host != tv.host }
                        if (tv.host == tvHost) {
                            scope.launch {
                                settings.setTvHost("")
                                settings.setSelectedTvName("")
                                tvHost = ""; tvName = ""; connectionVerified = null
                            }
                        }
                    }
                )
            }
        }

        // ═══ Everything below only when connected ════════════════════
        if (connectionVerified == true) {
            // ═══ 3. TV Apps — scan button + grid, always visible ══════
            GlassCard {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Apps, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("TV Apps", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        if (tvApps.isNotEmpty()) {
                            Text("${tvApps.size}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Scan button
                        IconButton(onClick = scanTvApps, enabled = !isScanningApps, modifier = Modifier.size(28.dp)) {
                            if (isScanningApps) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Search, contentDescription = "Scan",
                                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // App grid
                    if (tvApps.isNotEmpty()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(tvApps) { pkg ->
                                val shortName = pkg.substringAfterLast(".")
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            popupApp = pkg
                                            showAppPopup = true
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Filled.Android, contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(2.dp))
                                        Text(shortName, style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                        Text("Tap app for presets. Copy package name for custom commands.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    } else if (!isScanningApps) {
                        OutlinedButton(
                            onClick = scanTvApps,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan TV Apps", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // ═══ 4. Quick Command — mini terminal ═════════════════════
            GlassCard {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Terminal, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Quick Command", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    }

                    OutlinedTextField(
                        value = quickCommand,
                        onValueChange = {
                            quickCommand = it
                            commandOutput = null
                            commandSucceeded = false
                        },
                        label = { Text("Shell command") },
                        placeholder = { Text("am start -a android.intent.action.VIEW -d \"{URL}\"") },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (quickCommand.isNotBlank() && tvHost.isNotBlank()) {
                                    isRunningCommand = true; commandOutput = null
                                    scope.launch {
                                        val result = AdbManager.executeShell(context, tvHost, tvPort, quickCommand)
                                        isRunningCommand = false
                                        commandSucceeded = result.isSuccess
                                        commandOutput = if (result.isSuccess) {
                                            result.getOrDefault("").ifBlank { "OK" }
                                        } else {
                                            "Error: ${result.exceptionOrNull()?.message}"
                                        }
                                    }
                                }
                            },
                            enabled = quickCommand.isNotBlank() && !isRunningCommand,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isRunningCommand) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("Run", style = MaterialTheme.typography.labelMedium)
                        }

                        if (commandSucceeded) {
                            OutlinedButton(
                                onClick = {
                                    newPresetName = ""
                                    newPresetCommand = quickCommand
                                    newPresetApp = ""
                                    showSaveDialog = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save Preset", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (commandOutput != null) {
                        OutlinedCard(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (commandSucceeded)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(commandOutput!!,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = if (commandSucceeded) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { commandOutput = null }, modifier = Modifier.size(16.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(10.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ═══ 5. Presets — compact list ═════════════════════════════
            if (allPresets.isNotEmpty()) {
                GlassCard {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlaylistPlay, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Presets", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text("${allPresets.size}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        allPresets.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    when {
                                        preset.name == "Open Link" -> Icons.Filled.OpenInNew
                                        preset.name == "Video Player" -> Icons.Filled.PlayCircle
                                        preset.name == "SmartTube" -> Icons.Filled.SmartDisplay
                                        preset.usesFile -> Icons.Filled.FolderOpen
                                        else -> Icons.Filled.Terminal
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.bodySmall)
                                    Text(preset.command.take(50) + if (preset.command.length > 50) "..." else "",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                val isBuiltIn = SettingsManager.BUILT_IN_PRESETS.any { it.name == preset.name }
                                if (!isBuiltIn) {
                                    IconButton(
                                        onClick = { presetToDelete = preset.name; showDeleteDialog = true },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete",
                                            modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        Text("Select in Quick Settings for ShareSheet auto-execute.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

//  GLASS CARD — Blur/frosted-glass style card
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun GlassCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        content()
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  TERMINAL TAB — Pure ADB shell into the TV
// ═══════════════════════════════════════════════════════════════════════

private data class TerminalLine(
    val role: String,
    val text: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalTab(
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var tvName by remember { mutableStateOf("") }

    var lines by remember { mutableStateOf(listOf(TerminalLine("sys", "ADB Terminal — commands run on your TV over Wireless ADB."))) }
    var input by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        val h = withContext(Dispatchers.IO) { settings.getTvHost() }
        val p = withContext(Dispatchers.IO) { settings.getTvPort() }
        val n = withContext(Dispatchers.IO) { settings.getSelectedTvName() }
        tvHost = h
        tvPort = p
        tvName = n
        if (h.isBlank()) {
            lines = lines + TerminalLine("err", "No TV configured. Go to Home tab and select your TV first.")
        } else {
            lines = lines + TerminalLine("sys", "Target: ${n.ifBlank { h }}:$p")
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    fun append(line: TerminalLine) {
        lines = lines + line
    }

    fun runCommand(raw: String) {
        val cmd = AdbManager.sanitizeCommand(raw)
        if (cmd.isBlank()) return
        if (tvHost.isBlank()) {
            append(TerminalLine("err", "No TV connected. Go to Home tab and set your TV first."))
            return
        }
        if (history.isEmpty() || history.last() != cmd) {
            history = history + cmd
        }
        historyIndex = -1
        append(TerminalLine("cmd", cmd))
        isRunning = true
        scope.launch {
            val result = AdbManager.executeShell(context, tvHost, tvPort, cmd)
            isRunning = false
            if (result.isSuccess) {
                val output = result.getOrDefault("").trim()
                if (output.isNotBlank()) {
                    output.split("\n").forEach { append(TerminalLine("out", it)) }
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                append(TerminalLine("err", err))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Target status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                modifier = Modifier.size(8.dp),
                color = if (tvHost.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            ) {}
            Spacer(Modifier.width(8.dp))
            Text(
                if (tvHost.isNotBlank()) "${tvName.ifBlank { tvHost }}:$tvPort" else "No TV — go to Home tab",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (tvHost.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        // Quick-action chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val quickActions = listOf(
                "Home" to "input keyevent KEYCODE_HOME",
                "OK" to "input keyevent KEYCODE_ENTER",
                "Back" to "input keyevent KEYCODE_BACK",
                "Vol+" to "input keyevent KEYCODE_VOLUME_UP",
                "Vol-" to "input keyevent KEYCODE_VOLUME_DOWN",
                "Power" to "input keyevent KEYCODE_POWER",
                "Model" to "getprop ro.product.model",
                "Android" to "getprop ro.build.version.release",
                "Res" to "wm size",
                "Apps" to "pm list packages -3",
                "Reboot" to "reboot"
            )
            items(quickActions) { (label, cmd) ->
                FilterChip(
                    selected = false,
                    onClick = { runCommand(cmd) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        // Output scrollback
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF0D1117))
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(lines) { line ->
                val color = when (line.role) {
                    "cmd" -> Color(0xFF7EE8FA)
                    "out" -> Color(0xFFC9D1D9)
                    "err" -> Color(0xFFFF7B72)
                    else -> Color(0xFF8B949E)
                }
                val prefix = when (line.role) {
                    "cmd" -> "$ "
                    else -> ""
                }
                Text(
                    prefix + line.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color
                )
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp + bottomPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$ ", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("shell command", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) {
                        runCommand(input)
                        input = ""
                    }
                })
            )
            FilledIconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        runCommand(input)
                        input = ""
                    }
                },
                enabled = input.isNotBlank() && !isRunning,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Run", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  SETTINGS SHEET — Expandable sections: Connection, Appearance, Backup, About
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var tvName by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }

    var connectionExpanded by remember { mutableStateOf(false) }
    var appearanceExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }
    var currentTheme by remember { mutableStateOf(SettingsManager.THEME_SYSTEM) }

    var showImportDialog by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        tvHost = settings.getTvHost()
        tvPort = settings.getTvPort()
        tvName = settings.getSelectedTvName()
        currentTheme = settings.getThemeMode()
    }

    LaunchedEffect(Unit) {
        settings.themeMode.collect { mode -> currentTheme = mode }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false; importJson = "" },
            title = { Text("Import Presets") },
            text = {
                OutlinedTextField(
                    value = importJson,
                    onValueChange = { importJson = it },
                    label = { Text("Paste presets JSON") },
                    placeholder = { Text("""{"presets":[...]}""") },
                    minLines = 5, maxLines = 15,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = settings.importPresetsJson(importJson)
                    if (count > 0) {
                        Toast.makeText(context, "Imported $count preset(s)!", Toast.LENGTH_SHORT).show()
                    } else if (count == 0) {
                        Toast.makeText(context, "No new presets to import", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
                    }
                    showImportDialog = false; importJson = ""
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false; importJson = "" }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close settings")
            }
        }

        // ── Manual Connection (expandable) ──────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { connectionExpanded = !connectionExpanded }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(context.getString(R.string.tv_advanced),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(context.getString(R.string.tv_advanced_hint),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (connectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = connectionExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        OutlinedTextField(
                            value = tvHost,
                            onValueChange = {
                                tvHost = it
                                scope.launch {
                                    settings.setTvHost(it)
                                    settings.setSelectedTvName("")
                                }
                            },
                            label = { Text("TV IP Address") },
                            placeholder = { Text("192.168.1.123") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tvPort.toString(),
                            onValueChange = {
                                val p = it.toIntOrNull()
                                if (p != null && p in 1..65535) {
                                    tvPort = p
                                    scope.launch { settings.setTvPort(p) }
                                }
                            },
                            label = { Text("Connection Port") },
                            placeholder = { Text("5555") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (tvHost.isBlank()) { connectionStatus = "Enter TV IP first"; return@Button }
                                    isTesting = true; connectionStatus = null
                                    scope.launch {
                                        val result = AdbManager.testConnection(context, tvHost, tvPort)
                                        isTesting = false
                                        connectionStatus = if (result.isSuccess) {
                                            "Connected! TV responded: ${result.getOrDefault("")}"
                                        } else {
                                            "Failed: ${result.exceptionOrNull()?.message}"
                                        }
                                    }
                                },
                                enabled = !isTesting && tvHost.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (isTesting) "Testing..." else "Test Connection")
                            }
                        }
                        connectionStatus?.let { status ->
                            Spacer(Modifier.height(8.dp))
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (status.startsWith("Connected"))
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(status, style = MaterialTheme.typography.bodySmall,
                                        color = if (status.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f))
                                    IconButton(onClick = { connectionStatus = null }, modifier = Modifier.size(18.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        // ── Appearance (expandable) ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { appearanceExpanded = !appearanceExpanded }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Palette, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Appearance", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Icon(
                        if (appearanceExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = appearanceExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Theme", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeOptionButton(
                                label = "System", icon = Icons.Filled.BrightnessAuto,
                                isSelected = currentTheme == SettingsManager.THEME_SYSTEM,
                                onClick = { scope.launch { settings.setThemeMode(SettingsManager.THEME_SYSTEM) } },
                                modifier = Modifier.weight(1f)
                            )
                            ThemeOptionButton(
                                label = "Light", icon = Icons.Filled.LightMode,
                                isSelected = currentTheme == SettingsManager.THEME_LIGHT,
                                onClick = { scope.launch { settings.setThemeMode(SettingsManager.THEME_LIGHT) } },
                                modifier = Modifier.weight(1f)
                            )
                            ThemeOptionButton(
                                label = "Dark", icon = Icons.Filled.DarkMode,
                                isSelected = currentTheme == SettingsManager.THEME_DARK,
                                onClick = { scope.launch { settings.setThemeMode(SettingsManager.THEME_DARK) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        // ── Backup & Restore ─────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Backup, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Backup & Restore Presets", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val json = settings.exportPresetsJson()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("presets", json))
                            Toast.makeText(context, "Presets JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                }
            }
        }

        // ── About (expandable) ───────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { aboutExpanded = !aboutExpanded }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("About", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Icon(
                        if (aboutExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = aboutExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Smartphone, contentDescription = null,
                                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("ADB Commander", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.height(4.dp))
                        DetailRow("Version", "2.7.1")
                        DetailRow("Build", "38")
                        Spacer(Modifier.height(8.dp))

                        Text("Changelog", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "v2.7.1 — Removed Quick Remote, no expandable sections, compact home layout, SmartTube preset, disconnect button, fixed app scan error handling\n\n" +
                            "v2.7.0 — Tab state preservation, no re-scan on switch, instant startup via warm cache\n\n" +
                            "v2.5.0 — App selector, presets by app, theme settings\n\n" +
                            "v2.4.0 — URL token substitution, preset template system\n\n" +
                            "v2.3.0 — Dual share-sheet targets, preset Quick Settings tile\n\n" +
                            "v2.2.0 — HTTP file streaming, device name resolution\n\n" +
                            "v2.0.0 — Foreground service, quick settings tile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AiCurv/ADBCommander"))
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GitHub")
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  SHARED COMPOSABLES
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ThemeOptionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DiscoveredTvRow(
    tv: TvDiscoveryService.DiscoveredTv,
    isSelected: Boolean,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onExpandToggle: () -> Unit,
    onTest: () -> Unit,
    onForget: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(if (isSelected) 2.dp else 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp),
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        tv.source == "cached" -> Color(0xFFFFA000)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    }
                ) {}
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tv.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${tv.host}:${tv.port}  ·  ${tv.source}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onExpandToggle, modifier = Modifier.size(28.dp)) {
                    Icon(if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    modifier = Modifier.padding(start = 34.dp, end = 14.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider()
                    DetailRow("IP", tv.host)
                    DetailRow("Port", tv.port.toString())
                    DetailRow("Source", tv.source)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onTest, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(onClick = onForget, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Forget", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
    }
}
