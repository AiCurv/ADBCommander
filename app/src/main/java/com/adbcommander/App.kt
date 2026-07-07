package com.adbcommander

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Application class that installs Conscrypt security provider.
 *
 * Conscrypt provides TLS 1.3 support which is required for
 * Android 11+ wireless ADB pairing. Without it, pairing would
 * fail on devices running Android 7-8 (which lack built-in TLS 1.3).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Install Conscrypt as the first security provider
        // This enables TLS 1.3 for ADB pairing on all Android versions
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        // v2.2.1: Bind the global preset SharedPreferences from the
        // application context so that background intent processors
        // (ShareReceiverActivity cold-started from the share sheet,
        // AdbPresetTileService) see the same custom presets that
        // MainActivity saved — no per-Activity lazy init, no stale reads.
        // See developer-context.md §2.2 and SettingsManager.preload().
        SettingsManager.preload(this)
    }
}
