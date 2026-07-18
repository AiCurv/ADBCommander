package com.adbcommander

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.adbcommander.ui.theme.ADBCommanderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.2.1 Transparent overlay activity launched from [AdbPresetTileService].
 *
 * Displays ALL custom saved user presets simultaneously in a native
 * dropdown-style panel anchored to the top of the screen. Tapping any
 * preset instantly:
 *  1. Locks it as the primary auto-execute profile by calling
 *     [SettingsManager.setSelectedPreset] (which persists via DataStore).
 *  2. Also persists its command template as the default command via
 *     [SettingsManager.setDefaultCommand] so that ShareReceiverActivity's
 *     auto-execute path picks it up immediately (the auto path uses
 *     settings.getSelectedPreset() then presets.find{ name == selectedPresetName }).
 *  3. Shows a brief confirmation Toast and finishes the activity so the
 *     notification shade can collapse and reveal the updated tile subtitle.
 *
 * The activity is themed translucent so the QS panel underneath remains
 * partially visible — the dropdown-overlay UX matches the user's mental
 * model of "tap tile → pick from list → tile now reflects the choice".
 *
 * Built-in presets (currently just "SmartTube") are intentionally NOT
 * shown in this picker — the user instruction specifies "all custom saved
 * user presets". Built-ins are always available as fallbacks in the main
 * app's preset dropdown; this tile is specifically for switching between
 * the user's own saved configurations.
 *
 * Threading: all SettingsManager suspend calls run on Dispatchers.IO via
 * lifecycleScope. The initial preset list load uses LaunchedEffect so the
 * UI renders instantly with a loading state, then populates from the
 * global SharedPreferences layer (which is non-blocking after App.onCreate).
 *
 * See developer-context.md §2.2 (background IO threading).
 */
class PresetPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ADBCommanderTheme {
                PresetPickerOverlay(
                    onPresetSelected = { presetName ->
                        // Lock the preset and finish — the tile's
                        // onStartListening will fire when the QS panel
                        // regains focus, refreshing the subtitle.
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                val settings = SettingsManager(this@PresetPickerActivity)
                                settings.setSelectedPreset(presetName)
                                // Also sync the default command so the
                                // ShareReceiverActivity auto-execute path
                                // picks up the new preset's command template
                                // immediately, with no extra indirection.
                                settings.getPresetCommand(presetName)?.let { cmd ->
                                    settings.setDefaultCommand(cmd)
                                }
                            }
                            Toast.makeText(
                                this@PresetPickerActivity,
                                "Locked: $presetName",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPickerOverlay(
    onPresetSelected: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }

    // Load custom presets async — the global SharedPreferences layer is
    // non-blocking after App.onCreate, but JSON parsing still belongs off
    // the Main thread per developer-context.md §2.2.
    var customPresets by remember { mutableStateOf<List<SettingsManager.Preset>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedPresetName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // AI AGENT NOTE: This is the loadCustomPresets() path that v2.2.1
            // fixed — it now routes through the companion-level
            // presetsPrefs(context) helper which binds to a single
            // process-wide SharedPreferences instance. The cold-start from
            // the QS tile will see the same presets that MainActivity saved.
            customPresets = settings.getAllPresets()
                .filter { preset ->
                    // Exclude built-ins — this picker shows only user-saved
                    // presets, per the user instruction.
                    SettingsManager.BUILT_IN_PRESETS.none { it.name == preset.name }
                }
            selectedPresetName = settings.getSelectedPreset()
        }
        isLoading = false
    }

    // Dropdown-style overlay: a surface anchored to the top of the screen
    // with a translucent scrim behind it. Tapping the scrim dismisses.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Tapping the scrim = cancel
            .clickable { onCancel() }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 48.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(id = R.string.preset_picker_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Loading presets…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    customPresets.isEmpty() -> {
                        Text(
                            androidx.compose.ui.res.stringResource(id = R.string.preset_picker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                    else -> {
                        // Scrollable list of all custom presets — each row
                        // is a clickable card that fires onPresetSelected.
                        // The currently-locked preset is highlighted with a
                        // primary-container background and a check icon.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            customPresets.forEach { preset ->
                                val isSelected = preset.name == selectedPresetName
                                PresetPickerRow(
                                    preset = preset,
                                    isSelected = isSelected,
                                    onTap = { onPresetSelected(preset.name) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetPickerRow(
    preset: SettingsManager.Preset,
    isSelected: Boolean,
    onTap: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator: check-circle if locked, empty circle otherwise
            Icon(
                if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (preset.command.isNotBlank()) {
                    Text(
                        preset.command.take(80) + if (preset.command.length > 80) "…" else "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Token badges — show {URL} / {FILE} / {MIME} indicators
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (preset.usesUrl) {
                    TokenBadge("URL")
                }
                if (preset.usesFile) {
                    TokenBadge("FILE")
                }
            }
        }
    }
}

@Composable
private fun TokenBadge(label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
