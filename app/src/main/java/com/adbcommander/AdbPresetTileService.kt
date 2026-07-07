package com.adbcommander

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * v2.2.1 Quick Settings tile that lets the user lock the auto-execute
 * preset directly from the notification shade — no app launch required.
 *
 * Tap behavior:
 *  - Launches [PresetPickerActivity] as a transparent overlay that
 *    displays ALL custom saved user presets simultaneously in a native
 *    dropdown-style panel.
 *  - Selecting a preset from the list instantly locks it as the primary
 *    auto-execute profile (via [SettingsManager.setSelectedPreset]) and
 *    updates the tile's subtitle dynamically to reflect the active lock.
 *
 * Tile state:
 *  - STATE_ACTIVE when a preset is locked (subtitle shows the preset name)
 *  - STATE_INACTIVE when no preset is locked (subtitle shows "No preset locked")
 *
 * Threading: all DataStore reads happen via [runBlocking] on the binder
 * thread that [onStartListening] / [onClick] runs on — this is safe
 * because TileService callbacks never run on the Main thread. Preset
 * reads go through the global SharedPreferences layer (see
 * [SettingsManager.preload]) which is non-blocking after App.onCreate.
 *
 * See developer-context.md §2.2 (background IO threading) and §2.6
 * (dual activity-alias share targets — this tile is the third share-
 * adjacent surface, alongside ShareReceiverManual and ShareReceiverAuto).
 */
class AdbPresetTileService : TileService() {

    companion object {
        private const val TAG = "AdbPresetTile"
        // v2.2.1: TileService tile label (the bold line shown in the QS panel).
        // The subtitle (the secondary line under the label, API 29+) shows
        // the currently-locked preset name.
        const val TILE_LABEL = "ADB Preset"
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        // AI AGENT NOTE: Launching a transparent Activity (not a Dialog via
        // TileService.showDialog) because Compose-based UIs require a
        // ComponentActivity context — Dialog's content view is a classic
        // Android View hierarchy and would force us to bridge Compose
        // through ComposeView inside a Dialog window, which is fragile.
        // The transparent Activity approach is the standard pattern for
        // QS-tile pickers across the Android ecosystem.
        launchPresetPicker()
    }

    /**
     * Launch [PresetPickerActivity] as a transparent overlay. On Android
     * 14+ we must pass a PendingIntent to [startActivityAndCollapse]; on
     * older versions we pass the raw Intent (deprecated but still works).
     */
    private fun launchPresetPicker() {
        val intent = Intent(applicationContext, PresetPickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    applicationContext,
                    // Request code unique to this tile so it doesn't collide
                    // with AdbTileService's MainActivity launch pending intent.
                    1001,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch PresetPickerActivity from tile", e)
        }
    }

    /**
     * Update the tile's label, subtitle, and state to reflect the
     * currently-locked auto-execute preset.
     *
     * AI AGENT NOTE: tile.subtitle requires API 29+. On older devices the
     * subtitle is silently ignored — the tile just shows the label. Do NOT
     // bake the preset name into tile.label on older devices; the label
     * must stay "ADB Preset" so the user can identify the tile in the
     * QS edit drawer.
     */
    private fun refreshTile() {
        val tile = qsTile ?: return
        val settings = SettingsManager(applicationContext)

        val lockedPreset: String = try {
            runBlocking { settings.getSelectedPreset() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read selected preset", e)
            ""
        }

        tile.label = TILE_LABEL
        if (lockedPreset.isNotBlank()) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = lockedPreset
            }
            tile.contentDescription = "$TILE_LABEL: $lockedPreset"
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_preset_subtitle_none)
            }
            tile.contentDescription = TILE_LABEL
        }
        tile.updateTile()
    }
}
