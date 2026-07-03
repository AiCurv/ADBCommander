package com.adbcommander

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile that toggles the ADB TV bridge foreground service.
 *
 * Tap behaviour (Step 8, v2.0.0):
 *  - If a TV host is configured in [SettingsManager]: toggle the
 *    [AdbForegroundService] on/off instantly.
 *  - If no TV host is configured (or it is blank): bring [MainActivity]
 *    to the foreground so the user can quickly scan or re-select an
 *    active device.
 *
 * Tile state mirrors the foreground service: STATE_ACTIVE when the
 * bridge is running, STATE_INACTIVE otherwise.
 *
 * Reading the saved TV host from DataStore is a suspend operation; we
 * use [runBlocking] because [TileService.onClick] runs on a binder
 * thread, never the UI thread, so blocking it briefly is safe.
 */
class AdbTileService : TileService() {

    companion object {
        private const val TAG = "AdbTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val context = applicationContext
        val settings = SettingsManager(context)

        val host: String = try {
            runBlocking { settings.getTvHost() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read TV host from settings", e)
            ""
        }

        if (host.isNullOrBlank()) {
            // No TV configured — bring MainActivity to the foreground
            launchMainActivity()
        } else {
            // TV is configured — toggle the foreground service
            if (AdbForegroundService.isRunning()) {
                AdbForegroundService.stop(context)
            } else {
                AdbForegroundService.start(context)
            }
        }
        refreshTile()
    }

    /**
     * Launch MainActivity. On Android 14+ we must pass a PendingIntent
     * to [startActivityAndCollapse]; on older versions we pass the raw
     * Intent (deprecated but still works).
     */
    private fun launchMainActivity() {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    applicationContext, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch MainActivity from tile", e)
        }
    }

    /**
     * Update the tile's icon, label, and state to reflect whether the
     * bridge service is currently running.
     */
    private fun refreshTile() {
        val tile = qsTile ?: return
        if (AdbForegroundService.isRunning()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.tile_label_active)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label_inactive)
        }
        tile.contentDescription = tile.label
        tile.updateTile()
    }
}
