package com.adbcommander

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Persistent foreground service representing the active ADB TV bridge.
 *
 * Design goals (Step 8, v2.0.0):
 *  - Keep the app process alive in the background so subsequent ADB
 *    connections and share-sheet executions launch with zero cold-start lag.
 *  - Survive app swipe-away from the Recents menu — see [onTaskRemoved].
 *  - Display a clean, low-priority ongoing notification that represents
 *    the active TV bridge so the user always knows the bridge is up.
 *  - Use the `connectedDevice` foreground service type because this
 *    service represents an active connection to an external device
 *    (the TV over ADB), as Android 14+ requires for that type.
 *
 * The service does NOT itself maintain a long-lived ADB socket — those
 * are still created on demand by [AdbManager.executeShell]. The service's
 * job is process persistence and visible state, not protocol work.
 */
class AdbForegroundService : Service() {

    companion object {
        private const val TAG = "AdbForegroundService"
        const val CHANNEL_ID = "adb_commander_bridge"
        const val NOTIFICATION_ID = 4242

        @Volatile
        private var running: Boolean = false

        /** True when the foreground service is currently active. */
        fun isRunning(): Boolean = running

        /**
         * Start the persistent bridge service. Safe to call from the UI
         * thread — Android handles the actual start asynchronously.
         */
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

        /**
         * Stop the persistent bridge service.
         */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AdbForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground service", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        startForegroundCompat()
        Log.d(TAG, "ADB TV bridge service started")
    }

    /**
     * Return START_STICKY so the system restarts the service if it has
     * to reclaim memory. The restarted service gets a null intent —
     * [onCreate] handles notification setup, so no intent handling needed.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * App was swiped away from the Recents menu. We must NOT terminate.
     * Re-deliver ourselves so the bridge survives — some OEMs aggressively
     * kill the service process on swipe-away despite START_STICKY.
     */
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

    /**
     * Start in foreground using the connectedDevice service type on
     * Android 14+, or the legacy path on older versions.
     */
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

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .build()
    }
}
