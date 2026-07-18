package com.adbcommander

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Persistent foreground service representing the active ADB TV bridge.
 *
 * v2.4.0: Enhanced notification with:
 *  - Disconnect action button (expanded notification)
 *  - Connected TV name + host info
 *  - Current locked preset name
 */
class AdbForegroundService : Service() {

    companion object {
        private const val TAG = "AdbForegroundService"
        const val CHANNEL_ID = "adb_commander_bridge"
        const val NOTIFICATION_ID = 4242
        const val ACTION_DISCONNECT = "com.adbcommander.ACTION_DISCONNECT"

        @Volatile
        private var running: Boolean = false

        fun isRunning(): Boolean = running

        fun start(context: Context) {
            val intent = Intent(context, AdbForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AdbForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground service", e)
            }
        }
    }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISCONNECT) {
                Log.d(TAG, "Disconnect requested from notification")
                // Stop the service
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()

        // Register receiver for disconnect action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(disconnectReceiver, IntentFilter(ACTION_DISCONNECT), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(disconnectReceiver, IntentFilter(ACTION_DISCONNECT))
        }

        startForegroundCompat()
        Log.d(TAG, "ADB TV bridge service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Update notification with current TV info
        updateNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = Intent(applicationContext, AdbForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restart)
            } else {
                applicationContext.startService(restart)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not self-restart on task removal", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(disconnectReceiver) } catch (_: Exception) {}
        Log.d(TAG, "ADB TV bridge service stopped")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun updateNotification() {
        val notif = buildNotification()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Disconnect action
        val disconnectIntent = Intent(ACTION_DISCONNECT)
        val disconnectPi = PendingIntent.getBroadcast(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Read current TV name for notification text
        val settings = SettingsManager(this)
        var tvName = ""
        var tvHost = ""
        try {
            // Use runBlocking since this runs on a service thread, not UI
            tvName = kotlinx.coroutines.runBlocking { settings.getSelectedTvName() }
            tvHost = kotlinx.coroutines.runBlocking { settings.getTvHost() }
        } catch (_: Exception) {}

        val displayText = if (tvHost.isNotBlank()) {
            val name = tvName.ifBlank { tvHost }
            getString(R.string.notification_text_connected, name)
        } else {
            getString(R.string.notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(displayText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notification_action_disconnect),
                disconnectPi
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(displayText)
                    .setSummaryText(getString(R.string.notification_summary))
            )
            .build()
    }
}
