package com.adbcommander

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.adbcommander.ui.theme.AccentChoice
import com.adbcommander.ui.theme.AppearanceConfig
import com.adbcommander.ui.theme.BlurChoice
import com.adbcommander.ui.theme.LocalAppearance
import com.adbcommander.ui.theme.TextSizeChoice
import com.adbcommander.ui.theme.ThemeMode
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════════
//  v2.3.0 — Premium Glassmorphism UI Overhaul
//  ═══════════════════════════════════════════════════════════════════════
//  Single workspace with three runtime frames plus a settings overlay:
//
//    1. InterconnectFrame  (Screenshot 1)  — shown on launch. Lists
//       discovered TVs, lets user pick one or change devices.
//    2. ConnectingOverlay  (transient)    — brand-new "tab" opened the
//       moment user taps a TV. Closes itself the instant the test
//       connection process finishes (success OR failure).
//    3. DashboardScreen    (Screenshot 2) — single persistent home tab
//       with the app-icon grid + Quick Command surface. The gear
//       IconButton in the header opens the Settings sheet.
//    +  SettingsSheet      (Screenshot 3) — ModalBottomSheet overlay
//       opened from the header gear. Never its own top-level tab.
//
//  Spring physics:
//    Every transition between InterconnectFrame ↔ ConnectingOverlay ↔
//    DashboardScreen uses native Compose spring physics with
//    stiffness = Spring.StiffnessLow and dampingRatio =
//    Spring.DampingRatioLowBouncy — exactly as specified in the
//    v2.3.0 build brief ("organic-snap into the main dashboard").
//
//  Glassmorphism visual tokens (per build brief):
//    • 24dp backdrop blur via Modifier.cloudy (radius = 24)
//    • White alpha tint 0.35f (light) / Black alpha tint 0.45f (dark)
//    • Sharp 1.2dp outer border stroke on every card
//  All encapsulated in [GlassCard] so the visual language is enforced
//  in exactly one place.
//
//  AI AGENT NOTE: developer-context.md §2.5 still holds — there is
//  exactly ONE persistent home tab (the Dashboard). The
//  InterconnectFrame is a launch-time transient that closes on tap,
//  NOT a second tab. The Settings sheet remains a ModalBottomSheet
//  launched from the header gear, never its own destination.
// ═══════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AI AGENT NOTE (carried from v2.2.0): Battery-optimization
        // exemption is intentionally NOT auto-prompted here. Auto-prompting
        // on every cold start blocks the Main thread with a system Intent
        // and was the #1 cause of launch latency. The user triggers it on
        // demand from the "Background Service & Battery" card in the
        // Settings sheet — see developer-context.md §2.2.
        enableEdgeToEdge()
        setContent {
            // v2.3.0: Appearance config is loaded once from DataStore and
            // threaded through CompositionLocal so every composable can
            // read the live theme/accent/text-size/blur settings. The
            // SettingsSheet mutates this state via the [updateAppearance]
            // callback which persists changes back to DataStore.
            val context = LocalContext.current
            val settings = remember { SettingsManager(context) }
            var appearance by remember { mutableStateOf(AppearanceConfig()) }

            LaunchedEffect(Unit) {
                val mode = settings.getThemeMode()
                val accent = settings.getAccentChoice()
                val textSize = settings.getTextSize()
                val blur = settings.getBlurIntensity()
                appearance = AppearanceConfig(
                    themeMode = runCatching { ThemeMode.valueOf(mode) }.getOrDefault(ThemeMode.System),
                    accent = runCatching { AccentChoice.valueOf(accent) }.getOrDefault(AccentChoice.Teal),
                    textSize = runCatching { TextSizeChoice.valueOf(textSize) }.getOrDefault(TextSizeChoice.Medium),
                    blur = runCatching { BlurChoice.valueOf(blur) }.getOrDefault(BlurChoice.Normal)
                )
            }

            val updateAppearance: (AppearanceConfig) -> Unit = { newCfg ->
                appearance = newCfg
                // Persist off the Main thread — DataStore edits dispatch IO internally
                // but the .edit{} lambda itself can block briefly on the calling scope.
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    settings.setThemeMode(newCfg.themeMode.name)
                    settings.setAccentChoice(newCfg.accent.name)
                    settings.setTextSize(newCfg.textSize.name)
                    settings.setBlurIntensity(newCfg.blur.name)
                }
            }

            ADBCommanderTheme(appearance = appearance) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(appearance = appearance, onAppearanceChange = updateAppearance)
                }
            }
        }
    }
}

/**
 * Top-level state machine for the three frames + settings sheet.
 *
 * The [uiState] field is the only piece of state that controls which
 * frame is visible. [AnimatedContent] watches it and applies the
 * spring physics exit/enter transitions. All other UI state (TV host,
 * selected app, command text, etc.) lives inside the leaf composables
 * so the parent doesn't recompose when the user types a character.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appearance: AppearanceConfig,
    onAppearanceChange: (AppearanceConfig) -> Unit
) {
    var uiState by remember { mutableStateOf<UiFrame>(UiFrame.Interconnect) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Connection signal payload — passed from InterconnectFrame up to
    // MainScreen so the Dashboard knows which TV just connected.
    var connectedTv by remember { mutableStateOf<ConnectedTv?>(null) }

    AnimatedContent(
        targetState = uiState,
        transitionSpec = {
            // v2.3.0: native Compose spring physics per the build brief —
            // stiffness = Spring.StiffnessLow, dampingRatio =
            // Spring.DampingRatioLowBouncy. The "organic snap" feel comes
            // from the low stiffness (slow acceleration) combined with the
            // low bouncy damping (one small overshoot before settling).
            val enterSpring = spring<IntOffset>(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
            val exitSpring = spring<IntOffset>(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
            val fadeSpring = spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
            // Direction depends on which way we're moving:
            //   Interconnect → Connecting → Dashboard  = forward (slide left + fade)
            //   Dashboard → Interconnect               = backward (slide right + fade)
            val forward = (initialState.ordinal() < targetState.ordinal())
            if (forward) {
                (slideInHorizontally(enterSpring) { w -> w } + fadeIn(fadeSpring)) togetherWith
                    (slideOutHorizontally(exitSpring) { w -> -w } + fadeOut(fadeSpring))
            } else {
                (slideInHorizontally(enterSpring) { w -> -w } + fadeIn(fadeSpring)) togetherWith
                    (slideOutHorizontally(exitSpring) { w -> w } + fadeOut(fadeSpring))
            }
        },
        contentAlignment = Alignment.Center,
        label = "frame-transition"
    ) { frame ->
        when (frame) {
            UiFrame.Interconnect -> InterconnectFrame(
                connectedTv = connectedTv,
                onConnectRequested = { tv ->
                    // Tapping the main connector OR a device row lands here.
                    // Hand off to the transient ConnectingOverlay; the
                    // overlay closes itself when the test connection
                    // finishes and the Dashboard takes over.
                    connectedTv = tv
                    uiState = UiFrame.Connecting
                }
            )
            UiFrame.Connecting -> ConnectingOverlay(
                connectedTv = connectedTv,
                onFinished = { success ->
                    if (success) {
                        uiState = UiFrame.Dashboard
                    } else {
                        // Connection failed — bounce back to the
                        // Interconnect frame so the user can retry.
                        uiState = UiFrame.Interconnect
                    }
                }
            )
            UiFrame.Dashboard -> DashboardScreen(
                connectedTv = connectedTv,
                onOpenSettings = { showSettingsSheet = true },
                onDisconnect = {
                    // "Disconnect or Change Device" — sends the user
                    // back to the Interconnect frame with spring exit.
                    uiState = UiFrame.Interconnect
                }
            )
        }
    }

    // ── Settings ModalBottomSheet ───────────────────────────────────────
    // AI AGENT NOTE (carried from v2.2.0): This MUST remain a
    // ModalBottomSheet — not a separate Activity, not a Fragment, not
    // a navigation destination. The sheet pattern preserves the user's
    // Dashboard scroll state and is dismissable with a single swipe.
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            SettingsSheet(
                appearance = appearance,
                onAppearanceChange = onAppearanceChange,
                onClose = { showSettingsSheet = false }
            )
        }
    }
}

/** The three runtime frames orchestrated by [MainScreen]. */
private sealed interface UiFrame {
    /** Launch-time TV picker. Screenshot 1. */
    data object Interconnect : UiFrame
    /** Transient "Connecting…" overlay that auto-closes when the
     *  test-connection process finishes. */
    data object Connecting : UiFrame
    /** Single persistent home tab. Screenshot 2. */
    data object Dashboard : UiFrame

    /** Stable ordering so [AnimatedContent]'s transitionSpec can decide
     *  forward vs backward direction. Mirrors enum.ordinal without
     *  forcing these to be a single enum (each frame carries different
     *  state, so sealed data objects are cleaner). */
    fun ordinal(): Int = when (this) {
        Interconnect -> 0
        Connecting -> 1
        Dashboard -> 2
    }
}

/**
 * Lightweight bundle of the TV that just connected. Passed from the
 * InterconnectFrame into the Dashboard so the dashboard can render
 * the device-name header without re-reading DataStore.
 */
data class ConnectedTv(
    val name: String,
    val host: String,
    val port: Int,
    val type: TvType = TvType.Android
)

/** Coarse TV-type enum used by the InterconnectFrame's filter tabs. */
enum class TvType(val label: String) {
    Android("Android TV"),
    Fire("Fire TV"),
    All("All"),
    Saved("Saved")
}

// ═══════════════════════════════════════════════════════════════════════
//  GLASS CARD — single source of truth for the glassmorphism visual
//  language (24dp backdrop blur + white/black alpha tint + 1.2dp stroke).
// ═══════════════════════════════════════════════════════════════════════

/**
 * Reusable frosted-glass card. Every premium surface in the new UI is
 * a [GlassCard] — the visual tokens below are non-negotiable per the
 * v2.3.0 build brief:
 *
 *   • 24dp backdrop blur via Modifier.cloudy
 *     (radius is taken from [LocalAppearance.blur], default 24)
 *   • White alpha tint 0.35f (light) / Black alpha tint 0.45f (dark)
 *   • Sharp 1.2dp outer border stroke
 *
 * v2.3.0 implementation note: The build brief specifies
 * `com.github.skydoves:cloudy:0.6.1` for the backdrop blur. That
 * version was compiled with Kotlin 2.3.0 metadata (incompatible with
 * this project's Kotlin 2.1.0) and transitively requires compileSdk 36
 * + AGP 8.9.1 (this project is on 35 / 8.7.3). Rather than bump the
 * entire toolchain for one modifier, we implement the same visual
 * effect using Android's native `RenderEffect.createBlurEffect`
 * (API 31+) with a translucent-overlay fallback for API 24–30. This
 * is functionally identical to what cloudy does internally.
 *
 * AI AGENT NOTE: The alpha tint is applied via Surface.color (NOT a
 * Box overlay) so Material3 elevation overlays still work on top of
 * the frosted layer. The border stroke is applied via Modifier.border
 * with a clip so the corners stay rounded.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tint = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.35f)
    val strokeColor = if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.10f)
    val blurRadius = LocalAppearance.current.blur.radiusDp
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .then(blurModifier(blurRadius))
            .background(tint, shape)
            .border(width = 1.2.dp, color = strokeColor, shape = shape)
    ) {
        Column(
            modifier = contentModifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * Native backdrop-blur modifier. On API 31+ (Android 12) it uses
 * Compose's `BlurEffect` (which wraps `RenderEffect.createBlurEffect`)
 * to blur the composable's own translucent layer, producing a
 * frosted-glass look. On API 24–30 where RenderEffect is unavailable,
 * it returns `Modifier` (no-op) and the [GlassCard]'s alpha tint
 * layer alone provides the glassy feel. This is the same
 * graceful-degradation approach cloudy uses.
 */
@Composable
private fun blurModifier(radiusDp: Int): Modifier {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val radiusPx = with(density) { radiusDp.dp.toPx() }
        Modifier.graphicsLayer {
            renderEffect = androidx.compose.ui.graphics.BlurEffect(
                radiusPx, radiusPx,
                androidx.compose.ui.graphics.TileMode.Clamp
            )
        }
    } else {
        Modifier
    }
}

/** Cheap luminance estimate (no need to pull in androidx.palette for one check). */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

// ═══════════════════════════════════════════════════════════════════════
//  FRAME 1 — INTERCONNECT FRAME (Screenshot 1)
//  Shown on launch. Tapping the main connector card OR a device row
//  triggers a spring-physics exit transition into the ConnectingOverlay,
//  which then hands off to the Dashboard once the test-connection
//  process finishes.
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun InterconnectFrame(
    connectedTv: ConnectedTv?,
    onConnectRequested: (ConnectedTv) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    // ── Discovery state ────────────────────────────────────────────────
    // Reuses the existing TvDiscoveryService. The 7-second hard timeout
    // (developer-context.md §2.4) is preserved — we do NOT touch it.
    val discovery = remember { TvDiscoveryService(context) }
    var discoveredTvs by remember { mutableStateOf<List<TvDiscoveryService.DiscoveredTv>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    // Persisted connection state — loaded async so the first frame
    // renders instantly (developer-context.md §2.2 launch budget).
    var tvHost by remember { mutableStateOf("") }
    var tvPort by remember { mutableIntStateOf(SettingsManager.DEFAULT_TV_PORT) }
    var tvName by remember { mutableStateOf("") }

    // Active filter tab on the device list. Default "Android TV" matches
    // the screenshot. The filter is purely cosmetic — every discovered
    // TV is a candidate; the tab just visually buckets them.
    var activeTab by remember { mutableStateOf(TvType.Android) }

    // Manual IP entry accordion (kept from v2.2.0 for power users).
    var manualExpanded by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("5555") }

    LaunchedEffect(Unit) {
        tvHost = settings.getTvHost()
        tvPort = settings.getTvPort()
        tvName = settings.getSelectedTvName()
        manualHost = tvHost
        manualPort = tvPort.toString()
    }

    // ── Lifecycle-tied discovery scan ──────────────────────────────────
    // AI AGENT NOTE (carried from v2.2.0): This lifecycle binding is
    // the ONLY thing preventing the mDNS listener + 50-coroutine subnet
    // sweep from leaking. The flow self-terminates after 7 seconds per
    // developer-context.md §2.4, but the explicit onDispose cancel is
    // defense-in-depth and must stay.
    val lifecycleOwner = LocalLifecycleOwner.current
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

    DisposableEffect(lifecycleOwner) {
        startScan()
        onDispose {
            scanJob?.cancel()
            isScanning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Title ───────────────────────────────────────────────────────
        Text(
            text = "Your TV Connection",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )

        // ── Main Connection Card ────────────────────────────────────────
        // This is the "main connector button" the user taps to enter
        // the dashboard. The card shows the currently selected TV
        // (or a "no TV selected" hint) plus a "Disconnect or Change
        // Device" button.
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Top row: TV-type icon + name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Filled.CastConnected,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tvName.ifBlank { "Android TV" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (tvHost.isNotBlank()) {
                            Text(
                                text = "IP: $tvHost",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Status badge — green "Connected" if we have a TV host,
                // amber "Not connected" otherwise.
                val isConnected = tvHost.isNotBlank()
                StatusBadge(
                    connected = isConnected,
                    modifier = Modifier.align(Alignment.Start)
                )

                // The connector button — tapping this is what triggers
                // the spring physics transition into the Dashboard per
                // the v2.3.0 build brief. If no TV is selected yet, we
                // fall through to the discovery list (the user must
                // pick one first).
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            val target = if (tvHost.isNotBlank()) {
                                ConnectedTv(
                                    name = tvName.ifBlank { tvHost },
                                    host = tvHost,
                                    port = tvPort,
                                    type = TvType.Android
                                )
                            } else {
                                // No active TV — scroll the user's attention
                                // down to the device list by temporarily
                                // switching to the "All" tab.
                                activeTab = TvType.All
                                null
                            }
                            if (target != null) onConnectRequested(target)
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isConnected) "Disconnect or Change Device" else "Tap a TV below to connect",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // ── Tab row: Android TV | Fire TV | All | Saved ─────────────────
        AppTabRow(
            tabs = TvType.entries,
            activeTab = activeTab,
            onTabSelected = { activeTab = it }
        )

        // ── Discovery status row ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Filled.WifiFind,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    isScanning && discoveredTvs.isEmpty() -> "Scanning for TVs…"
                    isScanning && discoveredTvs.isNotEmpty() -> "Still scanning… ${discoveredTvs.size} found"
                    discoveredTvs.isEmpty() -> "No TVs found yet"
                    else -> "${discoveredTvs.size} TV(s) found"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                discoveredTvs = emptyList()
                startScan()
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
            }
        }

        // ── Device list ─────────────────────────────────────────────────
        // Filter the discovered list by the active tab. The filter is
        // cosmetic — we don't actually know if a discovered TV is
        // "Fire TV" vs "Android TV" without further probing, so we
        // apply a simple heuristic: device names containing "Fire"
        // or "Stick" → Fire TV; names containing "Shield"/"Bravia"/
        // "Chromecast"/"Android" → Android TV; "All" shows everything.
        val filteredTvs = remember(discoveredTvs, activeTab) {
            when (activeTab) {
                TvType.Android -> discoveredTvs.filter {
                    val n = it.name.lowercase()
                    n.contains("android") || n.contains("shield") || n.contains("bravia") ||
                        n.contains("chromecast") || n.contains("google") || n.contains("tv") &&
                        !n.contains("fire") && !n.contains("stick")
                }.ifEmpty { discoveredTvs } // fall back so user always sees something
                TvType.Fire -> discoveredTvs.filter {
                    val n = it.name.lowercase()
                    n.contains("fire") || n.contains("stick") || n.contains("amazon")
                }
                TvType.Saved -> discoveredTvs.filter { it.source == "cached" }
                TvType.All -> discoveredTvs
            }
        }

        filteredTvs.forEach { tv ->
            DeviceRow(
                tv = tv,
                isSelected = tv.host == tvHost && tvHost.isNotBlank(),
                onTap = {
                    // Selecting a TV in the list ALSO triggers the
                    // connector → Dashboard spring transition (per the
                    // user's voice instructions: "When user taps on its
                    // TV, okay, or already connected a TV, it should
                    // open a brand new tab").
                    val target = ConnectedTv(
                        name = tv.name,
                        host = tv.host,
                        port = tv.port,
                        type = TvType.Android
                    )
                    scope.launch {
                        settings.setTvHost(tv.host)
                        settings.setTvPort(tv.port)
                        settings.setSelectedTvName(tv.name)
                    }
                    onConnectRequested(target)
                }
            )
        }

        if (filteredTvs.isEmpty() && !isScanning) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No devices match this filter. Try the All tab or rescan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── Advanced Manual Entry accordion ─────────────────────────────
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { manualExpanded = !manualExpanded }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Advanced Manual Entry",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Enter IP and port manually if auto-discovery misses your TV.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        if (manualExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = manualExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HorizontalDivider()
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text("TV IP Address") },
                            placeholder = { Text("192.168.1.123") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Connection Port") },
                            placeholder = { Text("5555") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val host = manualHost.trim()
                                val port = manualPort.toIntOrNull() ?: 5555
                                if (host.isBlank()) {
                                    Toast.makeText(context, "Enter a TV IP first", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    settings.setTvHost(host)
                                    settings.setTvPort(port)
                                    settings.setSelectedTvName(host)
                                }
                                onConnectRequested(
                                    ConnectedTv(name = host, host = host, port = port, type = TvType.Android)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Login, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Green/amber status badge with a small dot, matching the screenshot's
 * "Connected" pill in the main connection card.
 */
@Composable
fun StatusBadge(connected: Boolean, modifier: Modifier = Modifier) {
    val bg = if (connected) Color(0xFFE6F4EA) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
    val dot = if (connected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(modifier = Modifier.size(8.dp).clip(CircleShape), color = dot) {}
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (connected) "Connected" else "Not connected",
                style = MaterialTheme.typography.labelMedium,
                color = fg
            )
        }
    }
}

/**
 * Segmented tab row matching the screenshot's "Android TV | Fire TV | All | Saved"
 * pattern. The active tab gets a tinted background + primary-color text;
 * inactive tabs are plain on-surface-variant text.
 */
@Composable
fun <T> AppTabRow(
    tabs: List<T>,
    activeTab: T,
    onTabSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() }
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { tab ->
                val active = tab == activeTab
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onTabSelected(tab) },
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
                ) {
                    Text(
                        text = label(tab),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * One device row in the InterconnectFrame's device list. Tapping the
 * row selects the device AND fires the connect-request callback so
 * the spring transition can hand off to the ConnectingOverlay.
 */
@Composable
fun DeviceRow(tv: TvDiscoveryService.DiscoveredTv, isSelected: Boolean, onTap: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
        cornerRadius = 14.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // TV-type icon. Heuristic: "Fire"/"Stick" → fire icon,
            // everything else → TV icon.
            val isFire = tv.name.contains("fire", ignoreCase = true) ||
                tv.name.contains("stick", ignoreCase = true)
            Surface(
                modifier = Modifier.size(36.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (isFire) Icons.Filled.LocalFireDepartment else Icons.Filled.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tv.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "IP: ${tv.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Status dot — green for selected/cached, gray otherwise
            Surface(
                modifier = Modifier.size(8.dp).clip(CircleShape),
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    tv.source == "cached" -> MaterialTheme.colorScheme.tertiary
                    else -> Color(0xFF4CAF50)
                }
            ) {}
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  FRAME 2 — CONNECTING OVERLAY (transient "brand new tab")
//  Opens the instant the user taps a TV. Closes itself the moment the
//  test-connection process finishes — success snaps to Dashboard,
//  failure bounces back to InterconnectFrame.
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ConnectingOverlay(
    connectedTv: ConnectedTv?,
    onFinished: (success: Boolean) -> Unit
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Opening ADB connection…") }
    var pulse by remember { mutableStateOf(false) }

    // Trigger the test connection exactly once when this overlay enters
    // composition. The LaunchedEffect's key is the connected TV's host
    // so re-tapping the same TV (after a failure) re-runs the test.
    LaunchedEffect(connectedTv?.host) {
        val tv = connectedTv
        if (tv == null) {
            onFinished(false)
            return@LaunchedEffect
        }
        pulse = true
        statusText = "Connecting to ${tv.name}…"
        // Brief artificial delay so the user actually perceives the
        // overlay (per the user's voice note: "this tab will close as
        // soon this process finishes" — the process needs to be visible
        // long enough to register as a discrete step).
        kotlinx.coroutines.delay(450)
        statusText = "Testing ADB shell…"
        val result = AdbManager.testConnection(context, tv.host, tv.port)
        pulse = false
        statusText = if (result.isSuccess) "Connected!" else "Connection failed"
        // Hold the success/failure message briefly before handing off
        // so the user sees the outcome rather than a flash.
        kotlinx.coroutines.delay(350)
        onFinished(result.isSuccess)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            cornerRadius = 28.dp
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Pulsing connector icon
                Surface(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .then(
                            if (pulse) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                            else Modifier
                        ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Filled.CastConnected,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (pulse) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  FRAME 3 — DASHBOARD SCREEN (Screenshot 2)
//  The single persistent home tab. Contains:
//    • Header card with the connected TV's name + green dot + gear IconButton
//    • "Select App" section with tabbed icon grid
//    • "Quick Command" section with selected app indicator + command field
//    • RUN + SAVE AS PRESET buttons + status message
//  No bottom nav, no drawer — per developer-context.md §2.5.
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    connectedTv: ConnectedTv?,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val iconCache = remember { TvIconCache(context) }

    // ── Preset + command state ────────────────────────────────────────
    var presets by remember { mutableStateOf(SettingsManager.BUILT_IN_PRESETS) }
    var selectedPresetName by remember { mutableStateOf(SettingsManager.DEFAULT_PRESET_NAME) }
    var customCommand by remember { mutableStateOf(SettingsManager.DEFAULT_COMMAND) }
    var presetExpanded by remember { mutableStateOf(false) }

    // ── App-grid state ────────────────────────────────────────────────
    var appTab by remember { mutableStateOf(AppTab.AllApps) }
    var tvPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanningApps by remember { mutableStateOf(false) }
    var appScanError by remember { mutableStateOf<String?>(null) }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var iconFetchProgress by remember { mutableStateOf(0 to 0) }
    var sortExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // ── Run state ─────────────────────────────────────────────────────
    var isRunning by remember { mutableStateOf(false) }
    var runOutput by remember { mutableStateOf<String?>(null) }

    // ── Dialog state ──────────────────────────────────────────────────
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var showBuildDialog by remember { mutableStateOf(false) }
    var buildPresetPackage by remember { mutableStateOf("") }
    var buildPresetName by remember { mutableStateOf("") }
    var buildAction by remember { mutableStateOf("android.intent.action.VIEW") }
    var buildDataUri by remember { mutableStateOf("{URL}") }
    var buildType by remember { mutableStateOf("{MIME}") }
    var buildComponent by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Load persisted settings off Main thread (developer-context.md §2.2).
        val cmd = settings.getDefaultCommand()
        val sel = settings.getSelectedPreset()
        val all = withContext(Dispatchers.IO) { settings.getAllPresets() }
        customCommand = cmd
        selectedPresetName = sel
        presets = all
    }

    // ── Auto-scan the TV's app list the first time the dashboard mounts
    //    AND we have a non-null connectedTv. Subsequent re-mounts (e.g.
    //    when the user comes back from settings) keep the cached list.
    LaunchedEffect(connectedTv?.host) {
        val tv = connectedTv ?: return@LaunchedEffect
        if (tvPackages.isNotEmpty()) return@LaunchedEffect
        isScanningApps = true
        appScanError = null
        val result = AdbManager.listTvPackages(context, tv.host, tv.port, includeSystem = false)
        isScanningApps = false
        if (result.isSuccess) {
            tvPackages = result.getOrDefault(emptyList())
            if (tvPackages.isEmpty()) appScanError = "No third-party packages found on TV"
        } else {
            appScanError = "Scan failed: ${result.exceptionOrNull()?.message}"
        }

        // Kick off background icon fetch for the discovered packages.
        // We don't block on this — icons populate progressively as they
        // arrive via the cache's bulkFetch onProgress callback.
        if (tvPackages.isNotEmpty()) {
            iconFetchProgress = 0 to tvPackages.size
            iconCache.bulkFetch(
                host = tv.host,
                port = tv.port,
                packages = tvPackages,
                onProgress = { _, _ ->
                    iconFetchProgress = iconFetchProgress.first + 1 to iconFetchProgress.second
                }
            )
        }
    }

    // ── Save Preset Dialog ─────────────────────────────────────────────
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false; newPresetName = "" },
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
                    showSavePresetDialog = false; newPresetName = ""
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false; newPresetName = "" }) { Text("Cancel") }
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
                        value = buildAction, onValueChange = { buildAction = it },
                        label = { Text("Action") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildDataUri, onValueChange = { buildDataUri = it },
                        label = { Text("Data URI (use {URL} or {FILE})") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildType, onValueChange = { buildType = it },
                        label = { Text("MIME Type (use {MIME} for dynamic)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildComponent, onValueChange = { buildComponent = it },
                        label = { Text("Component (pkg/activity)") },
                        placeholder = { Text(buildPresetPackage) }, singleLine = true,
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
                        Toast.makeText(context, "Preset \"$name\" saved! Select it above.", Toast.LENGTH_SHORT).show()
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ══════ HEADER CARD ═════════════════════════════════════════════
        // Per Screenshot 2: device name + green dot + teal gear icon
        // (top-right). Tapping the gear opens the Settings sheet.
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connectedTv?.name ?: "No TV",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = connectedTv?.let { "${it.host}:${it.port}" } ?: "Tap to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Green status dot — always green in the dashboard because
                // we only reach this frame after a successful test connection.
                Surface(modifier = Modifier.size(10.dp).clip(CircleShape), color = Color(0xFF4CAF50)) {}
                Spacer(Modifier.width(12.dp))
                // v2.3.0 gear IconButton — the ONLY entry point to the
                // Settings ModalBottomSheet. AI AGENT NOTE (carried from
                // v2.2.0): do NOT move, hide, or duplicate this.
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ══════ SELECT APP SECTION ═════════════════════════════════════
        Text(
            "Select App",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // App-tab row: ALL APPS | SYSTEM APPS | THIRD-PARTY | sort icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AppTabRow(
                modifier = Modifier.weight(1f),
                tabs = AppTab.entries,
                activeTab = appTab,
                onTabSelected = { newTab ->
                    appTab = newTab
                    // Re-fetch with the new includeSystem flag.
                    val tv = connectedTv ?: return@AppTabRow
                    scope.launch {
                        isScanningApps = true
                        appScanError = null
                        val includeSystem = when (newTab) {
                            AppTab.AllApps -> true
                            AppTab.SystemApps -> true   // System tab shows system apps only — we filter client-side
                            AppTab.ThirdParty -> false
                        }
                        val result = AdbManager.listTvPackages(context, tv.host, tv.port, includeSystem)
                        isScanningApps = false
                        if (result.isSuccess) {
                            val all = result.getOrDefault(emptyList())
                            tvPackages = when (newTab) {
                                AppTab.SystemApps -> all.filter { pkg ->
                                    // Heuristic: system packages tend to start with com.android /
                                    // com.google.android or have no dot in the second segment.
                                    pkg.startsWith("com.android.") ||
                                        pkg.startsWith("com.google.android.") ||
                                        pkg.startsWith("android") ||
                                        pkg.startsWith("com.mediatek.") ||
                                        pkg.startsWith("com.realtek.")
                                }
                                else -> all
                            }
                            if (tvPackages.isEmpty()) appScanError = "No packages match this filter"
                        } else {
                            appScanError = "Scan failed: ${result.exceptionOrNull()?.message}"
                        }
                    }
                },
                label = { it.label }
            )
            // Sort icon — toggles A-Z / Z-A
            ExposedDropdownMenuBox(expanded = sortExpanded, onExpandedChange = { sortExpanded = !sortExpanded }) {
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .menuAnchor()
                        .clickable { sortExpanded = true },
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Sort, contentDescription = "Sort", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    DropdownMenuItem(text = { Text("A-Z") }, onClick = { tvPackages = tvPackages.sorted(); sortExpanded = false })
                    DropdownMenuItem(text = { Text("Z-A") }, onClick = { tvPackages = tvPackages.sortedDescending(); sortExpanded = false })
                }
            }
        }

        // App-grid state — show spinner / error / grid
        when {
            isScanningApps && tvPackages.isEmpty() -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("Scanning TV apps…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            appScanError != null && tvPackages.isEmpty() -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text(appScanError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            }
            tvPackages.isEmpty() -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No apps to show. Switch tabs or rescan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                // Icon fetch progress bar
                if (iconFetchProgress.first < iconFetchProgress.second && iconFetchProgress.second > 0) {
                    val pct = iconFetchProgress.first.toFloat() / iconFetchProgress.second.toFloat()
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Fetching icons: ${iconFetchProgress.first}/${iconFetchProgress.second}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 4-column grid (screenshot shows 4 cols × 2 rows = 8 apps visible)
                val filteredApps = remember(tvPackages, searchQuery) {
                    if (searchQuery.isBlank()) tvPackages
                    else tvPackages.filter { it.contains(searchQuery, ignoreCase = true) }
                }
                // Use a non-scrolling grid constrained to a max height —
                // the outer Column already scrolls, so an inner scrolling
                // grid would create nested-scroll conflicts.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filteredApps.take(8).chunked(4).forEach { rowPkgs ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPkgs.forEach { pkg ->
                                AppIconTile(
                                    packageName = pkg,
                                    iconFile = iconCache.iconFileFor(pkg),
                                    isSelected = pkg == selectedApp,
                                    modifier = Modifier.weight(1f),
                                    onTap = {
                                        selectedApp = pkg
                                        // Auto-build a force-stop command for the selected
                                        // app — matches the screenshot's prefilled field
                                        // "adb shell am force-stop com.google.android.youtube.tv".
                                        // We omit "adb shell" since AdbManager.sanitizeCommand
                                        // strips it anyway and the visible field is cleaner.
                                        customCommand = "am force-stop $pkg"
                                        scope.launch { settings.setDefaultCommand(customCommand) }
                                    }
                                )
                            }
                            // Pad the row if fewer than 4 items
                            repeat(4 - rowPkgs.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (filteredApps.size > 8) {
                        Text(
                            "+ ${filteredApps.size - 8} more — use the sort/search in Settings → Package Manager",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                }
            }
        }

        // ══════ QUICK COMMAND SECTION ═══════════════════════════════════
        Text(
            "Quick Command",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Selected app indicator card
        if (selectedApp != null) {
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIconTile(
                        packageName = selectedApp!!,
                        iconFile = iconCache.iconFileFor(selectedApp!!),
                        isSelected = false,
                        modifier = Modifier.size(40.dp),
                        showLabel = false,
                        onTap = {}
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "Selected App:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            selectedApp!!.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Command input field
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
            OutlinedTextField(
                value = customCommand,
                onValueChange = {
                    customCommand = it
                    val matching = presets.find { p -> p.command == it }
                    selectedPresetName = matching?.name ?: "Custom"
                    scope.launch { settings.setDefaultCommand(it) }
                },
                label = { Text("Type ADB command…") },
                placeholder = { Text("am start -a android.intent.action.VIEW -d {URL}") },
                minLines = 2, maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            )
        }

        // Preset dropdown — kept from v2.2.0 so users can quickly switch
        // between saved presets. Hidden inside a collapsible to keep the
        // dashboard clean (matches the screenshot's focused command surface).
        ExposedDropdownMenuBox(
            expanded = presetExpanded,
            onExpandedChange = { presetExpanded = !presetExpanded }
        ) {
            OutlinedTextField(
                value = selectedPresetName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Preset") },
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
                                    Text(
                                        preset.command.take(60) + if (preset.command.length > 60) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
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

        // RUN + SAVE AS PRESET buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val tv = connectedTv
                    if (customCommand.isBlank()) { runOutput = "Command cannot be empty"; return@Button }
                    if (tv == null) { runOutput = "No TV connected"; return@Button }
                    isRunning = true; runOutput = null
                    // AI AGENT NOTE (carried): AdbManager.executeShell MUST
                    // be called from a coroutine scope, NOT directly from
                    // the Main thread. See developer-context.md §2.2.
                    scope.launch {
                        val result = AdbManager.executeShell(context, tv.host, tv.port, customCommand)
                        isRunning = false
                        if (result.isSuccess) {
                            val output = result.getOrDefault("")
                            runOutput = "Success: Command executed. $output"
                            val logStore = CommandLogStore(context)
                            logStore.addLog(customCommand, true)
                            Toast.makeText(context, output, Toast.LENGTH_SHORT).show()
                        } else {
                            val err = result.exceptionOrNull()?.message ?: "Unknown error"
                            runOutput = "Failed: $err"
                            val logStore = CommandLogStore(context)
                            logStore.addLog(customCommand, false)
                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !isRunning && connectedTv != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isRunning) "Running…" else "RUN", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = { showSavePresetDialog = true },
                enabled = customCommand.isNotBlank(),
                modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("SAVE AS PRESET")
            }
        }

        // Status message
        runOutput?.let {
            val isSuccess = it.startsWith("Success")
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Disconnect button at the bottom — sends user back to InterconnectFrame
        TextButton(
            onClick = onDisconnect,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Disconnect or Change Device")
        }
    }
}

/** App-grid tab labels matching the screenshot. */
enum class AppTab(val label: String) {
    AllApps("ALL APPS"),
    SystemApps("SYSTEM APPS"),
    ThirdParty("THIRD-PARTY")
}

/**
 * One tile in the Select App icon grid. Loads the cached PNG icon from
 * [TvIconCache.iconFileFor] (saved there by the ADB icon pipeline) and
 * falls back to a generic Android icon when the cache misses or the
 * decode fails. The fallback ensures the grid never shows empty boxes
 * even for system packages whose icons are tricky to extract.
 */
@Composable
fun AppIconTile(
    packageName: String,
    iconFile: java.io.File,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    onTap: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val strokeColor = if (isSelected) MaterialTheme.colorScheme.primary
        else if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)

    // Decode the cached PNG to an ImageBitmap. We do this on the Main
    // thread because BitmapFactory.decodeFile is fast for the small
    // icon sizes (≤192×192) we cache, and threading it would just add
    // complexity. If the file doesn't exist or decode fails, we render
    // the fallback.
    val bitmap = remember(iconFile.absolutePath, iconFile.lastModified()) {
        if (iconFile.exists()) {
            try { BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap() } catch (_: Exception) { null }
        } else null
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = strokeColor,
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .clickable { onTap() }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = packageName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(
                    Icons.Filled.Android,
                    contentDescription = packageName,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        if (showLabel) {
            Text(
                text = packageName.substringAfterLast('.').take(12),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  SETTINGS SHEET (Screenshot 3 + existing functionality)
//  ModalBottomSheet overlay opened from the Dashboard's gear IconButton.
//  Per developer-context.md §2.5: NEVER its own top-level tab.
//
//  Sections (in order, matching Screenshot 3 layout):
//    1. Header — gear icon + "Settings" title + close X
//    2. Appearance — Theme toggle, Accent picker, Text size slider, Blur slider
//    3. Device Management — connected TVs with Edit/Delete/Make Default + Refresh Icons
//    4. Background Service & Battery (kept from v2.2.0)
//    5. Preset Builder / Package Manager (kept from v2.2.0, with icon grid)
//    6. Export Presets (matches Screenshot 3)
//    7. Backup & Restore Presets (kept from v2.2.0)
//    8. Execution Logs & History (kept from v2.2.0)
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    appearance: AppearanceConfig,
    onAppearanceChange: (AppearanceConfig) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val logStore = remember { CommandLogStore(context) }
    val iconCache = remember { TvIconCache(context) }

    // ── Appearance state (lifted from [appearance] param) ──────────────
    // The sheet reads from the parent's [appearance] state and writes back
    // via [onAppearanceChange]. The parent persists to DataStore on the
    // IO dispatcher; this composable only mutates the in-memory state.

    // ── Package Manager state ──────────────────────────────────────────
    var pmExpanded by remember { mutableStateOf(false) }
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf("az") }
    var includeSystemApps by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }

    var showBuildDialog by remember { mutableStateOf(false) }
    var buildPresetPackage by remember { mutableStateOf("") }
    var buildPresetName by remember { mutableStateOf("") }
    var buildAction by remember { mutableStateOf("android.intent.action.VIEW") }
    var buildDataUri by remember { mutableStateOf("{URL}") }
    var buildType by remember { mutableStateOf("{MIME}") }
    var buildComponent by remember { mutableStateOf("") }

    // ── Logs state ────────────────────────────────────────────────────
    var logs by remember { mutableStateOf<List<CommandLogStore.LogEntry>>(emptyList()) }
    var showLogDialog by remember { mutableStateOf(false) }
    var selectedLogCommand by remember { mutableStateOf("") }

    // ── Backup & Restore state ────────────────────────────────────────
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    // ── Background service & battery state ────────────────────────────
    var serviceRunning by remember { mutableStateOf(AdbForegroundService.isRunning()) }
    var batteryImmune by remember { mutableStateOf(false) }
    var batteryChecked by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) { logStore.getLogs() }
    }
    LaunchedEffect(batteryChecked) {
        batteryImmune = withContext(Dispatchers.IO) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        serviceRunning = AdbForegroundService.isRunning()
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
                        value = buildPresetName, onValueChange = { buildPresetName = it },
                        label = { Text("Preset Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = buildPresetPackage,
                        onValueChange = {
                            buildPresetPackage = it
                            if (buildComponent.isBlank()) buildComponent = it
                        },
                        label = { Text("Package") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildAction, onValueChange = { buildAction = it },
                        label = { Text("Action") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildDataUri, onValueChange = { buildDataUri = it },
                        label = { Text("Data URI (use {URL} or {FILE})") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildType, onValueChange = { buildType = it },
                        label = { Text("MIME Type (use {MIME} for dynamic)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = buildComponent, onValueChange = { buildComponent = it },
                        label = { Text("Component (pkg/activity)") },
                        placeholder = { Text(buildPresetPackage) }, singleLine = true,
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
                        Toast.makeText(context, "Preset \"$name\" saved!", Toast.LENGTH_SHORT).show()
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
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Sheet header ──────────────────────────────────────────────
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
        //  1. APPEARANCE — Theme toggle + Accent picker + Text size + Blur
        // ═══════════════════════════════════════════════════════════════
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Theme, accent, text size, blur", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Theme toggle — System | Light | Dark
                Text("Theme Toggle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppTabRow(
                    tabs = ThemeMode.entries,
                    activeTab = appearance.themeMode,
                    onTabSelected = { onAppearanceChange(appearance.copy(themeMode = it)) },
                    label = { it.label }
                )

                // Accent color picker
                Text("Accent Color Picker", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    AccentChoice.entries.forEach { choice ->
                        AccentSwatch(
                            choice = choice,
                            isSelected = appearance.accent == choice,
                            modifier = Modifier.weight(1f),
                            onClick = { onAppearanceChange(appearance.copy(accent = choice)) }
                        )
                    }
                }

                // Text size slider
                Text("Text Size Slider", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SliderWithLabels(
                    options = TextSizeChoice.entries,
                    selected = appearance.textSize,
                    onSelect = { onAppearanceChange(appearance.copy(textSize = it)) }
                )

                // Blur intensity slider
                Text("Blur Intensity Slider", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SliderWithLabels(
                    options = BlurChoice.entries,
                    selected = appearance.blur,
                    onSelect = { onAppearanceChange(appearance.copy(blur = it)) }
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  2. DEVICE MANAGEMENT — list of saved TVs + Refresh Icons button
        // ═══════════════════════════════════════════════════════════════
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Device Management", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Manage saved TVs and refresh icons", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                val discovery = remember { TvDiscoveryService(context) }
                // v2.3.0: All Settings DataStore + cache reads run on
                // Dispatchers.IO via LaunchedEffect — runBlocking on Main
                // is forbidden per developer-context.md §2.2. The initial
                // empty state renders instantly; state updates as the
                // async reads complete.
                var cachedTvs by remember { mutableStateOf<List<TvDiscoveryService.DiscoveredTv>>(emptyList()) }
                var currentHost by remember { mutableStateOf("") }
                var currentName by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    currentHost = settings.getTvHost()
                    currentName = settings.getSelectedTvName()
                    cachedTvs = withContext(Dispatchers.IO) { discovery.getCachedDevices() }
                }

                if (cachedTvs.isEmpty() && currentHost.isBlank()) {
                    Text("No saved devices yet. Discover one from the home screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Active TV first (if any), then cached devices
                    val allDevices = mutableListOf<TvDiscoveryService.DiscoveredTv>()
                    if (currentHost.isNotBlank()) {
                        allDevices.add(TvDiscoveryService.DiscoveredTv(name = currentName.ifBlank { currentHost }, host = currentHost, port = 5555, source = "active", lastSeen = System.currentTimeMillis()))
                    }
                    allDevices.addAll(cachedTvs.filter { it.host != currentHost })

                    allDevices.forEach { tv ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                            color = if (tv.host == currentHost) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Tv, contentDescription = null, modifier = Modifier.size(20.dp),
                                    tint = if (tv.host == currentHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tv.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("${tv.host}:${tv.port}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(modifier = Modifier.size(8.dp).clip(CircleShape),
                                    color = if (tv.host == currentHost) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline) {}
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            // Wipe the icon cache so the next dashboard mount re-pulls
                            // every icon from the TV. Useful after installing new apps.
                            iconCache.clearAll()
                            Toast.makeText(context, "Icon cache cleared. Reconnect to re-fetch.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh Icons")
                    }
                    OutlinedButton(
                        onClick = {
                            discovery.clearCache()
                            Toast.makeText(context, "Device cache cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear Devices")
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  3. BACKGROUND SERVICE & BATTERY (kept from v2.2.0)
        // ═══════════════════════════════════════════════════════════════
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Background Service & Battery", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Persistent TV bridge + Quick Settings tile", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (batteryImmune) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe,
                        contentDescription = null,
                        tint = if (batteryImmune) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (batteryImmune) "Battery immunity granted" else "Battery optimization is active",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (batteryImmune) "OS will not sleep the background ADB thread loop"
                            else "OS may forcefully sleep background ADB threads",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        try {
                            val intent = Intent().apply {
                                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Cannot request battery exemption on this device", Toast.LENGTH_SHORT).show()
                        }
                        batteryChecked++
                    }) {
                        Text(if (batteryImmune) "Re-check" else "Grant immunity")
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (serviceRunning) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                        contentDescription = null,
                        tint = if (serviceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (serviceRunning) "TV bridge running" else "TV bridge stopped",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Survives swipe-away. Add the Quick Settings tile for one-tap toggle.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = {
                        if (AdbForegroundService.isRunning()) AdbForegroundService.stop(context)
                        else AdbForegroundService.start(context)
                        serviceRunning = AdbForegroundService.isRunning()
                    }) {
                        Icon(
                            if (serviceRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (serviceRunning) "Stop" else "Start")
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  4. PACKAGE MANAGER — preset builder (kept from v2.2.0, with icons)
        // ═══════════════════════════════════════════════════════════════
        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                        Text("Preset Builder", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Scan TV apps & build command templates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    if (host.isBlank()) { scanError = "Connect a TV first"; return@launch }
                                    isScanning = true; scanError = null; packages = emptyList()
                                    val result = AdbManager.listTvPackages(context, host, port, includeSystemApps)
                                    isScanning = false
                                    if (result.isSuccess) {
                                        packages = result.getOrDefault(emptyList())
                                        if (packages.isEmpty()) scanError = "No packages found"
                                        // Kick off icon fetch in background
                                        if (packages.isNotEmpty()) {
                                            iconCache.bulkFetch(host, port, packages) { _, _ -> }
                                        }
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
                                        iconFile = iconCache.iconFileFor(pkg),
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
        //  5. EXPORT PRESETS — matches Screenshot 3 layout
        // ═══════════════════════════════════════════════════════════════
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Export Presets", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Keep backups of your preset library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val json = settings.exportPresetsJson()
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("adb_commander_presets", json))
                            Toast.makeText(context, "All presets exported to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export All")
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true; importJsonText = "" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import")
                    }
                }
                Text(
                    "Format: JSON. Auto-save location: system clipboard.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        //  6. EXECUTION LOGS & HISTORY (kept from v2.2.0)
        // ═══════════════════════════════════════════════════════════════
        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                    Text("No execution logs yet. Run a command from the dashboard to start logging.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(8.dp))
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
//  REUSABLE COMPONENTS — Appearance helpers + legacy components kept
//  for compatibility with the Preset Builder / Logs sections.
// ═══════════════════════════════════════════════════════════════════════

/**
 * Single accent-color swatch in the Appearance → Accent Color Picker.
 * Renders a filled circle in the accent's light-mode color; when
 * selected, an inner ring is added.
 */
@Composable
fun AccentSwatch(choice: AccentChoice, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .then(
                    if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    else Modifier
                ),
            color = choice.light
        ) {}
        Spacer(Modifier.height(4.dp))
        Text(
            text = choice.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Three-position slider used by Text Size and Blur Intensity. Renders
 * the slider plus three evenly-spaced labels underneath.
 */
@Composable
fun <T> SliderWithLabels(options: List<T>, selected: T, onSelect: (T) -> Unit) where T : Enum<T> {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    Column {
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { idx ->
                val i = idx.toInt().coerceIn(0, options.size - 1)
                onSelect(options[i])
            },
            valueRange = 0f..(options.size - 1).toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            options.forEach { opt ->
                val label = when (opt) {
                    is TextSizeChoice -> opt.label
                    is BlurChoice -> opt.label
                    else -> opt.name
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (opt == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (opt == selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * One package row in the Preset Builder list. Uses the cached TV icon
 * (saved by [TvIconCache] via the ADB pipeline) — falls back to a
 * generic Android icon when the cache misses. This replaces the old
 * v2.2.0 implementation that called the PHONE's PackageManager, which
 * only returned icons for apps also installed on the phone.
 */
@Composable
fun PackageRow(
    packageName: String,
    iconFile: java.io.File,
    onCopy: () -> Unit,
    onBuildPreset: () -> Unit
) {
    val bitmap = remember(iconFile.absolutePath, iconFile.lastModified()) {
        if (iconFile.exists()) {
            try { BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap() } catch (_: Exception) { null }
        } else null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(32.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                if (bitmap != null) {
                    Image(
                        painter = BitmapPainter(bitmap),
                        contentDescription = packageName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Fit
                    )
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

/**
 * Legacy helper kept for ShareReceiverActivity and other code paths
 * that still pass a phone-side Drawable. Renders any Drawable inside
 * a square box.
 */
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
