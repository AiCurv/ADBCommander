package com.adbcommander

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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
        setContent {
            ADBCommanderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  MAIN SCREEN — Bottom navigation with hide-on-scroll behavior
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var activeTab by remember { mutableIntStateOf(0) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val connectScrollState = rememberScrollState()
    val terminalListState = rememberLazyListState()

    var connectPrevScroll by remember { mutableIntStateOf(0) }
    var terminalPrevScroll by remember { mutableIntStateOf(0) }
    var isBarVisible by remember { mutableStateOf(true) }

    LaunchedEffect(connectScrollState.value) {
        val delta = connectScrollState.value - connectPrevScroll
        if (delta > 10) isBarVisible = false
        else if (delta < -10) isBarVisible = true
        connectPrevScroll = connectScrollState.value
    }

    LaunchedEffect(terminalListState.firstVisibleItemIndex, terminalListState.firstVisibleItemScrollOffset) {
        if (activeTab != 1) return@LaunchedEffect
        val offset = terminalListState.firstVisibleItemIndex * 1000 + terminalListState.firstVisibleItemScrollOffset
        val delta = offset - terminalPrevScroll
        if (delta > 10) isBarVisible = false
        else if (delta < -10) isBarVisible = true
        terminalPrevScroll = offset
    }

    LaunchedEffect(activeTab) {
        isBarVisible = true
    }

    // Battery optimization first-install prompt (shown once only)
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    var showBatteryPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prompted = settings.isFirstInstallPrompted()
        if (!prompted) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            val isImmune = pm.isIgnoringBatteryOptimizations(context.packageName)
            if (!isImmune) {
                showBatteryPrompt = true
            } else {
                settings.setFirstInstallPrompted(true)
            }
        }
    }

    if (showBatteryPrompt) {
        AlertDialog(
            onDismissRequest = {
                showBatteryPrompt = false
                kotlinx.coroutines.runBlocking { settings.setFirstInstallPrompted(true) }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.battery_prompt_title))
                }
            },
            text = {
                Text(context.getString(R.string.battery_prompt_message))
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                    showBatteryPrompt = false
                    kotlinx.coroutines.runBlocking { settings.setFirstInstallPrompted(true) }
                }) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.battery_prompt_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatteryPrompt = false
                    kotlinx.coroutines.runBlocking { settings.setFirstInstallPrompted(true) }
                }) { Text(context.getString(R.string.battery_prompt_skip)) }
            }
        )
    }

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
            AnimatedVisibility(
                visible = isBarVisible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            when (activeTab) {
                0 -> HomeTab(scrollState = connectScrollState, bottomPadding = innerPadding.calculateBottomPadding())
                1 -> TerminalTab(listState = terminalListState, bottomPadding = innerPadding.calculateBottomPadding())
            }
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            SettingsSheet(
                onClose = { showSettingsSheet = false }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  HOME TAB — Connection + Presets + Quick Command with URL input
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    scrollState: ScrollState = rememberScrollState(),
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var tvName by remember { mutableStateOf("") }
    var tvConnected by remember { mutableStateOf(false) }

    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // Connection verification state: null=not checked, true=verified, false=failed
    var connectionVerified by remember { mutableStateOf<Boolean?>(null) }

    // Discovery state
    val discovery = remember { TvDiscoveryService(context) }
    var discoveredTvs by remember { mutableStateOf<List<TvDiscoveryService.DiscoveredTv>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var expandedRowHost by remember { mutableStateOf<String?>(null) }

    // Presets state
    var presets by remember { mutableStateOf(SettingsManager.BUILT_IN_PRESETS) }
    var selectedPresetName by remember { mutableStateOf(SettingsManager.DEFAULT_PRESET_NAME) }
    var presetRefreshKey by remember { mutableIntStateOf(0) }

    // Quick command state
    var customCommand by remember { mutableStateOf(SettingsManager.DEFAULT_COMMAND) }
    var inputUrl by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var runOutput by remember { mutableStateOf<String?>(null) }

    // Save preset dialog
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    // Delete preset dialog
    var showDeleteDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<String?>(null) }

    // Load settings and presets, then verify connection in background
    LaunchedEffect(Unit) {
        val h = settings.getTvHost()
        val p = settings.getTvPort()
        val cmd = settings.getDefaultCommand()
        val sel = settings.getSelectedPreset()
        val selName = settings.getSelectedTvName()
        tvHost = h
        tvPort = p
        tvName = selName
        customCommand = cmd
        selectedPresetName = sel
        if (h.isNotBlank()) {
            tvConnected = true // provisional — will verify below
            // Verify connection in background so user sees real state
            val result = AdbManager.testConnection(context, h, p)
            connectionVerified = result.isSuccess
            if (!result.isSuccess) {
                // Connection failed — but keep host saved, just update verified state
                connectionStatus = "Connection lost: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    // Refresh presets when returning to the tab
    LaunchedEffect(presetRefreshKey) {
        presets = withContext(Dispatchers.IO) { settings.getAllPresets() }
    }

    // Also refresh when the composable regains focus
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                presetRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Lifecycle-tied discovery scan
    var scanJob by remember { mutableStateOf<Job?>(null) }

    val startScan: () -> Unit = {
        scanJob?.cancel()
        isScanning = true
        scanJob = lifecycleOwner.lifecycleScope.launch {
            try {
                discovery.discover().collect { tvs ->
                    discoveredTvs = tvs
                }
            } finally {
                isScanning = false
                tvConnected = tvHost.isNotBlank()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        startScan()
        onDispose {
            scanJob?.cancel()
            isScanning = false
        }
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(
                        "Will save the current command:",
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
                            presetRefreshKey++
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
                    presetRefreshKey++
                    if (selectedPresetName == presetToDelete) {
                        selectedPresetName = SettingsManager.DEFAULT_PRESET_NAME
                        customCommand = settings.getPresetCommand(SettingsManager.DEFAULT_PRESET_NAME) ?: SettingsManager.DEFAULT_COMMAND
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
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ═══ Connection Status Card ═══════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    connectionVerified == true -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    connectionVerified == false -> Color(0x40FFA000) // amber for "lost"
                    tvHost.isNotBlank() -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) // checking
                    else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when {
                        connectionVerified == true -> Icons.Filled.CastConnected
                        connectionVerified == false -> Icons.Filled.CastConnected
                        tvHost.isNotBlank() -> Icons.Filled.CastConnected // checking
                        else -> Icons.Filled.Cast
                    },
                    contentDescription = null,
                    tint = when {
                        connectionVerified == true -> MaterialTheme.colorScheme.primary
                        connectionVerified == false -> Color(0xFFFFA000) // amber
                        tvHost.isNotBlank() -> MaterialTheme.colorScheme.outline // checking
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            connectionVerified == true && tvName.isNotBlank() -> tvName
                            connectionVerified == true -> tvHost
                            connectionVerified == false -> "${tvName.ifBlank { tvHost }} (lost)"
                            tvHost.isNotBlank() -> "Verifying..."
                            else -> "No TV connected"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            connectionVerified == true -> MaterialTheme.colorScheme.onPrimaryContainer
                            connectionVerified == false -> Color(0xFFE65100)
                            tvHost.isNotBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    if (tvHost.isNotBlank()) {
                        Text(
                            "$tvHost:$tvPort",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    } else {
                        Text(
                            "Tap scan below to find your TV",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
                }
                if (tvHost.isNotBlank()) {
                    FilledTonalIconButton(
                        onClick = {
                            isTesting = true; connectionStatus = null
                            scope.launch {
                                val result = AdbManager.testConnection(context, tvHost, tvPort)
                                isTesting = false
                                connectionVerified = result.isSuccess
                                connectionStatus = if (result.isSuccess) {
                                    tvConnected = true
                                    "Connected! TV responded: ${result.getOrDefault("")}"
                                } else {
                                    tvConnected = false
                                    "Failed: ${result.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        enabled = !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = "Test", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // Connection test result (transient)
        AnimatedVisibility(
            visible = connectionStatus != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            connectionStatus?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (it.startsWith("Connected"))
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(it, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        IconButton(onClick = { connectionStatus = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // ═══ TV Scan (only shows when not connected) ══════════════════
        if (!tvConnected || isScanning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(20.dp),
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
                        if (!isScanning && discoveredTvs.isEmpty()) {
                            Text(
                                context.getString(R.string.tv_scan_tap_to_retry),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = {
                        discoveredTvs = emptyList()
                        startScan()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                    }
                }
            }

            // Discovered TV list
            if (discoveredTvs.isNotEmpty()) {
                discoveredTvs.forEach { tv ->
                    DiscoveredTvRow(
                        tv = tv,
                        isSelected = tv.host == tvHost && tvHost.isNotBlank(),
                        isExpanded = expandedRowHost == tv.host,
                        onTap = {
                            tvHost = tv.host
                            tvPort = tv.port
                            tvName = tv.name
                            tvConnected = true
                            connectionVerified = null // will verify
                            connectionStatus = null
                            scope.launch {
                                settings.setTvHost(tv.host)
                                settings.setTvPort(tv.port)
                                settings.setSelectedTvName(tv.name)
                                // Verify this connection actually works
                                val result = AdbManager.testConnection(context, tv.host, tv.port)
                                connectionVerified = result.isSuccess
                                if (result.isSuccess) {
                                    connectionStatus = "Connected! ${tv.name} responded: ${result.getOrDefault("")}"
                                } else {
                                    connectionStatus = "Connected but ADB failed: ${result.exceptionOrNull()?.message}"
                                }
                            }
                            Toast.makeText(context, "Selected: ${tv.name}", Toast.LENGTH_SHORT).show()
                        },
                        onExpandToggle = {
                            expandedRowHost = if (expandedRowHost == tv.host) null else tv.host
                        },
                        onTest = {
                            isTesting = true; connectionStatus = null
                            scope.launch {
                                val result = AdbManager.testConnection(context, tv.host, tv.port)
                                isTesting = false
                                connectionVerified = if (tv.host == tvHost) result.isSuccess else connectionVerified
                                connectionStatus = if (result.isSuccess)
                                    "Connected! ${tv.name} responded: ${result.getOrDefault("")}"
                                else
                                    "Failed: ${result.exceptionOrNull()?.message}"
                            }
                        },
                        onForget = {
                            discovery.forgetDevice(tv.host)
                            if (tv.host == tvHost) {
                                tvHost = ""
                                tvName = ""
                                tvConnected = false
                                connectionVerified = null
                                scope.launch {
                                    settings.setTvHost("")
                                    settings.setSelectedTvName("")
                                }
                            }
                            Toast.makeText(context, "Forgot ${tv.name}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        } else {
            // TV is connected — compact rescan row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${discoveredTvs.size} device(s) found",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    tvConnected = false
                    discoveredTvs = emptyList()
                    startScan()
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rescan", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // ═══ Presets Section ══════════════════════════════════════════
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        SectionHeader(context.getString(R.string.home_presets_section), Icons.Filled.PlaylistPlay)

        // Preset chips — horizontal scrollable
        LazyRow(
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(presets) { preset ->
                val isSelected = preset.name == selectedPresetName
                val isBuiltIn = SettingsManager.BUILT_IN_PRESETS.any { it.name == preset.name }

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedPresetName = preset.name
                        customCommand = preset.command
                        scope.launch {
                            settings.setSelectedPreset(preset.name)
                            settings.setDefaultCommand(preset.command)
                        }
                    },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(
                            when {
                                preset.name == "SmartTube" -> Icons.Filled.SmartDisplay
                                preset.name == "Open Link" -> Icons.Filled.OpenInNew
                                preset.name == "Video Player" -> Icons.Filled.PlayCircle
                                preset.usesFile -> Icons.Filled.FolderOpen
                                else -> Icons.Filled.Terminal
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        if (!isBuiltIn) {
                            IconButton(
                                onClick = { presetToDelete = preset.name; showDeleteDialog = true },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Delete",
                                    modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        // ═══ Quick Command Section ════════════════════════════════════
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        SectionHeader(context.getString(R.string.home_quick_command), Icons.Filled.Terminal)

        // URL input field — this is the KEY addition that makes presets work from home tab
        OutlinedTextField(
            value = inputUrl,
            onValueChange = { inputUrl = it },
            label = { Text("URL / Link to send") },
            placeholder = { Text("https://... or magnet:?...") },
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            )
        )

        Text(
            "Paste a URL above, select a preset, and hit RUN. Tokens {URL} and {MIME} are auto-replaced. Without a URL, the command runs as-is.",
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
            placeholder = { Text("""am start -a android.intent.action.VIEW -d "{URL}"""") },
            minLines = 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (customCommand.isBlank()) { runOutput = "Command cannot be empty"; return@Button }
                    if (tvHost.isBlank()) { runOutput = "No TV connected — scan or set IP in settings"; return@Button }

                    // Build the final command: substitute tokens if URL is provided
                    val finalCommand = if (inputUrl.isNotBlank()) {
                        val mime = AdbManager.resolveMimeType(null, inputUrl)
                        AdbManager.prepareCommand(customCommand, inputUrl, mime)
                    } else {
                        customCommand
                    }

                    isRunning = true; runOutput = null
                    scope.launch {
                        val result = AdbManager.executeShell(context, tvHost, tvPort, finalCommand)
                        isRunning = false
                        if (result.isSuccess) {
                            val output = result.getOrDefault("")
                            runOutput = "OK: $output"
                            connectionVerified = true // connection works
                            val logStore = CommandLogStore(context)
                            logStore.addLog(finalCommand, true)
                            Toast.makeText(context, output, Toast.LENGTH_SHORT).show()
                        } else {
                            val err = result.exceptionOrNull()?.message ?: "Unknown error"
                            runOutput = "FAIL: $err"
                            val logStore = CommandLogStore(context)
                            logStore.addLog(finalCommand, false)
                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isRunning) "Running..." else "RUN", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedButton(
                onClick = { showSaveDialog = true },
                enabled = customCommand.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(context.getString(R.string.home_save_preset))
            }
        }

        // Run output
        AnimatedVisibility(
            visible = runOutput != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            runOutput?.let {
                Card(modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (it.startsWith("OK")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(it, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (it.startsWith("OK")) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = { runOutput = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
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
    listState: LazyListState = rememberLazyListState(),
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

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
                val logStore = CommandLogStore(context)
                logStore.addLog(cmd, true)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                append(TerminalLine("err", err))
                val logStore = CommandLogStore(context)
                logStore.addLog(cmd, false)
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
                "Awake" to "dumpsys power | grep 'mWakefulness'",
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
//  SETTINGS SHEET — Clean: Manual Connection + Backup/Restore only
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    // Manual Connection state
    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var tvName by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        tvHost = settings.getTvHost()
        tvPort = settings.getTvPort()
        tvName = settings.getSelectedTvName()
    }

    // Import presets dialog
    var showImportDialog by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf("") }

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
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = settings.importPresetsJson(importJson)
                    if (count > 0) {
                        Toast.makeText(context, "Imported $count preset(s)!", Toast.LENGTH_SHORT).show()
                    } else if (count == 0) {
                        Toast.makeText(context, "No new presets to import (all names exist)", Toast.LENGTH_SHORT).show()
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Sheet header ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close settings")
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  MANUAL CONNECTION
        // ═══════════════════════════════════════════════════════════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Edit, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            context.getString(R.string.tv_advanced),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            context.getString(R.string.tv_advanced_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
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

                // Connection test result
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
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  BACKUP & RESTORE PRESETS
        // ═══════════════════════════════════════════════════════════════
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
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("presets", json))
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
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  SHARED COMPOSABLES
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
