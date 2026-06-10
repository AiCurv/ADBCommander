package com.adbcommander

import android.app.Application
import android.os.Build
import org.conscrypt.Conscrypt
import java.security.Security

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // Install Conscrypt as a security provider for TLS 1.3 support.
        // Android 9+ has built-in TLS 1.3, but Conscrypt ensures consistent
        // behaviour and is required by Kadb for wireless pairing on Android 7-8.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
