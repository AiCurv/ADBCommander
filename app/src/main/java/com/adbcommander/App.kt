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
    }
}
