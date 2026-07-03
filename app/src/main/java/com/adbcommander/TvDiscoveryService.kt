package com.adbcommander

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Discovers Android TVs on the local network using a dual-tier strategy.
 *
 * Tier 1 — mDNS via [NsdManager]
 *   Listens for the `_adb-tls-connect._tcp` service type that every Android 11+
 *   wireless-debugging-enabled TV broadcasts. Returns friendly device names plus
 *   host+port. Fast (typically resolves within 1-3 seconds) and zero-permission
 *   beyond INTERNET + ACCESS_WIFI_STATE which the app already holds.
 *
 * Tier 2 — Subnet sweep (fallback)
 *   If mDNS finds nothing within [MDNS_GRACE_PERIOD_MS] milliseconds, kicks off a
 *   concurrent coroutine sweep of the local /24 subnet on port 5555. Catches
 *   older Android TVs that don't broadcast mDNS but still listen on the legacy
 *   ADB port. Each address is TCP-probed with a 500ms timeout, 50 coroutines in
 *   flight at a time, so the whole sweep completes in roughly 5-10 seconds.
 *
 * Caching
 *   Every successfully discovered device is persisted to a JSON file in the app's
 *   internal storage directory. On the next [discover] call, cached devices are
 *   emitted immediately so the UI can populate instantly while a fresh scan runs.
 *
 * Concurrency
 *   [discover] returns a cold [Flow]. Collecting it starts both mDNS and (if
 *   needed) subnet sweep. Cancelling the collection stops mDNS discovery and
 *   every sweep coroutine — safe to call from `lifecycleScope.launch` and cancel
 *   on `onDispose`.
 */
class TvDiscoveryService(private val context: Context) {

    data class DiscoveredTv(
        val name: String,
        val host: String,
        val port: Int,
        val source: String,        // "mdns" | "scan" | "cached"
        val lastSeen: Long
    ) {
        /** Stable identity for deduplication — keyed by host. */
        val identity: String get() = host
    }

    companion object {
        private const val TAG = "TvDiscoveryService"
        private const val ADB_MDNS_TYPE = "_adb-tls-connect._tcp."
        private const val MDNS_GRACE_PERIOD_MS = 3000L
        private const val SUBNET_PROBE_TIMEOUT_MS = 500
        private const val SUBNET_CONCURRENCY = 50
        private const val DEFAULT_ADB_PORT = 5555
        private const val CACHE_FILE_NAME = "discovered_tvs_cache.json"
    }

    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val seen = ConcurrentHashMap<String, DiscoveredTv>()

    /**
     * Start a discovery session. Emits the merged list of (cached + freshly
     * discovered) TVs every time a new device is found. The flow stays active
     * (continues listening for mDNS broadcasts) until the collector cancels it.
     */
    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        // 1. Emit cached devices instantly so the UI is never empty.
        val cached = loadCache()
        cached.forEach { seen[it.identity] = it }
        trySend(emitSorted())

        // 2. Kick off mDNS discovery.
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val mdnsHits = AtomicInteger(0)

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "mDNS start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "mDNS stop failed: $errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            mdnsHits.incrementAndGet()
                            val tv = DiscoveredTv(
                                name = info.serviceName ?: "Android TV ($host)",
                                host = host,
                                port = if (info.port > 0) info.port else DEFAULT_ADB_PORT,
                                source = "mdns",
                                lastSeen = System.currentTimeMillis()
                            )
                            seen[tv.identity] = tv
                            trySend(emitSorted())
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "mDNS resolve failed for ${info.serviceName}: $errorCode")
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "mDNS resolve threw", e)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // Don't remove from seen — the device may still be reachable
                // via the cached entry, and the user may want to retry it.
            }
        }

        try {
            nsdManager.discoverServices(ADB_MDNS_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discoverServices threw", e)
        }

        // 3. After the grace period, if mDNS found nothing, kick off subnet sweep.
        val sweepJob = launch {
            kotlinx.coroutines.delay(MDNS_GRACE_PERIOD_MS)
            if (mdnsHits.get() == 0 && !isClosedForSend) {
                val localIp = getLocalIpv4() ?: return@launch
                val subnet = localIp.substringBeforeLast('.')
                Log.d(TAG, "mDNS empty after ${MDNS_GRACE_PERIOD_MS}ms — sweeping $subnet.0/24")
                val sweepResults = subnetSweep(subnet)
                sweepResults.forEach { seen[it.identity] = it }
                trySend(emitSorted())
                persistCache()
            }
        }

        // 4. Periodically persist the cache as mDNS keeps discovering.
        val persistJob = launch {
            while (isActive) {
                kotlinx.coroutines.delay(5000)
                persistCache()
            }
        }

        awaitClose {
            sweepJob.cancel()
            persistJob.cancel()
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {}
            persistCache()
        }
    }

    /**
     * Probe every address in `subnet.1..254` on the default ADB port, with
     * bounded concurrency. Returns the list of hosts that accepted a TCP
     * connection within [SUBNET_PROBE_TIMEOUT_MS].
     */
    private suspend fun subnetSweep(subnet: String): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, DiscoveredTv>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(SUBNET_CONCURRENCY)
        val jobs = (1..254).map { i ->
            launch {
                semaphore.acquire()
                try {
                    val host = "$subnet.$i"
                    if (probeHost(host, DEFAULT_ADB_PORT)) {
                        results[host] = DiscoveredTv(
                            name = "Android TV ($host)",
                            host = host,
                            port = DEFAULT_ADB_PORT,
                            source = "scan",
                            lastSeen = System.currentTimeMillis()
                        )
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
        jobs.forEach { it.join() }
        results.values.toList()
    }

    private suspend fun probeHost(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), SUBNET_PROBE_TIMEOUT_MS)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Get the device's primary IPv4 address on the active Wi-Fi (or Ethernet)
     * interface. Returns null if no suitable interface is up.
     */
    private fun getLocalIpv4(): String? {
        // Try WifiManager first — works on all API levels for Wi-Fi.
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    (ipInt shr 8) and 0xff,
                    (ipInt shr 16) and 0xff,
                    (ipInt shr 24) and 0xff
                )
                if (!ip.startsWith("0.")) return ip
            }
        } catch (_: Exception) {}

        // Fallback: enumerate network interfaces.
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it != null && !it.startsWith("127.") }
        } catch (_: Exception) {
            null
        }
    }

    private fun emitSorted(): List<DiscoveredTv> {
        return seen.values
            .sortedWith(compareByDescending<DiscoveredTv> { it.source == "mdns" }.thenBy { it.name })
            .toList()
    }

    // ── Cache persistence ──────────────────────────────────────────────

    private fun loadCache(): List<DiscoveredTv> {
        if (!cacheFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(cacheFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    DiscoveredTv(
                        name = obj.getString("name"),
                        host = obj.getString("host"),
                        port = obj.optInt("port", DEFAULT_ADB_PORT),
                        source = "cached",
                        lastSeen = obj.optLong("lastSeen", 0)
                    )
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cache", e)
            emptyList()
        }
    }

    private fun persistCache() {
        try {
            val arr = JSONArray()
            seen.values.toList().forEach { tv ->
                arr.put(JSONObject().apply {
                    put("name", tv.name)
                    put("host", tv.host)
                    put("port", tv.port)
                    put("lastSeen", tv.lastSeen)
                })
            }
            cacheFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist cache", e)
        }
    }

    /**
     * Forget a previously discovered device by host. Removes from the in-memory
     * map and persists the updated cache.
     */
    fun forgetDevice(host: String) {
        seen.remove(host)
        persistCache()
    }

    /**
     * Clear all cached devices. Useful for a "reset" button.
     */
    fun clearCache() {
        seen.clear()
        persistCache()
    }
}
