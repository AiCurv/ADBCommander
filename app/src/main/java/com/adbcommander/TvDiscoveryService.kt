package com.adbcommander

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Friendly-name enrichment (v2.2.0)
 *   Both tiers initially register devices with a placeholder name (the mDNS
 *   service name or an `Android TV (host)` string). For every freshly-discovered
 *   device we instantly kick off a non-blocking background ADB shell that runs
 *   `settings get global device_name` (falling back to `getprop ro.product.model`)
 *   and replaces the placeholder with the real friendly name when it returns.
 *
 * Caching
 *   Every successfully discovered device is persisted to a JSON file in the app's
 *   internal storage directory. On the next [discover] call, cached devices are
 *   emitted immediately so the UI can populate instantly while a fresh scan runs.
 *
 * Hard timeout (v2.2.0)
 *   [discover] enforces a strict [HARD_TIMEOUT_MS] ceiling on the entire scan.
 *   When the deadline elapses, the flow closes itself, which triggers [awaitClose]
 *   — that stops mDNS discovery, cancels every sweep coroutine, cancels the
 *   cache-persist loop, and cancels any in-flight device-name fetches. No thread
 *   leaks, no battery drain after the scan window ends.
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

        // v2.2.0: Hard ceiling on the entire discovery session. After this many
        // milliseconds the flow closes itself, which triggers awaitClose and
        // tears down every background coroutine (mDNS listener, subnet sweep,
        // cache-persist loop, and any pending device-name fetches).
        //
        // AI AGENT NOTE: DO NOT remove or raise this timeout above 10,000ms
        // without explicit owner sign-off. The 7-second ceiling is what
        // prevents the scanner from running infinitely in the background and
        // draining battery — this was the v2.1.0 production regression that
        // v2.2.0 fixed. See developer-context.md §2.4.
        private const val HARD_TIMEOUT_MS = 7000L

        // Per-device name-fetch ceiling. If the ADB shell doesn't respond within
        // this window we leave the placeholder name in place rather than
        // blocking the scan pipeline.
        private const val NAME_FETCH_TIMEOUT_MS = 2500L
    }

    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val seen = ConcurrentHashMap<String, DiscoveredTv>()

    /**
     * Start a discovery session. Emits the merged list of (cached + freshly
     * discovered) TVs every time a new device is found or a friendly name is
     * resolved. The flow automatically terminates after [HARD_TIMEOUT_MS],
     * tearing down mDNS + every sweep coroutine + every name-fetch coroutine.
     */
    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        // Supervisor scope so a single name-fetch failure doesn't cancel siblings.
        //
        // AI AGENT NOTE: This supervisor scope MUST be cancelled in awaitClose
        // below. Forgetting to cancel it here will leak every in-flight
        // device-name ADB shell coroutine when the scan terminates. The
        // SupervisorJob is intentional — a regular Job would cancel all
        // sibling name fetches the moment one device's ADB shell timed out.
        val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                            val placeholderName = info.serviceName?.takeIf { it.isNotBlank() }
                                ?: "Android TV ($host)"
                            val tv = DiscoveredTv(
                                name = placeholderName,
                                host = host,
                                port = if (info.port > 0) info.port else DEFAULT_ADB_PORT,
                                source = "mdns",
                                lastSeen = System.currentTimeMillis()
                            )
                            seen[tv.identity] = tv
                            trySend(emitSorted())

                            // v2.2.0: instantly fire a non-blocking ADB shell
                            // to fetch the true friendly name. The placeholder
                            // is replaced if/when the shell returns a value.
                            enrichDeviceName(fetchScope, host, tv.port, placeholderName) {
                                trySend(emitSorted())
                            }
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
                val sweepResults = subnetSweep(subnet, fetchScope) {
                    trySend(emitSorted())
                }
                sweepResults.forEach { seen[it.identity] = it }
                trySend(emitSorted())
                persistCache()
            }
        }

        // 4. Periodically persist the cache while the scan window is open.
        // Cancelled automatically by awaitClose when the hard timeout fires.
        val persistJob = launch {
            while (isActive) {
                kotlinx.coroutines.delay(2000)
                persistCache()
            }
        }

        // 5. v2.2.0: Hard timeout — close the channel after HARD_TIMEOUT_MS.
        // Closing the channel triggers awaitClose below, which performs the
        // full teardown (stop mDNS, cancel sweep, cancel persist loop, cancel
        // fetchScope, persist final cache). No background coroutine survives.
        //
        // AI AGENT NOTE: This hardTimeoutJob is the load-bearing safety net.
        // If you remove it, the persistJob's `while (isActive)` loop and the
        // mDNS listener will keep running forever — that is exactly the v2.1.0
        // infinite-scan bug. Do NOT replace close() with channel.cancel()
        // either; close() triggers awaitClose, cancel() does not.
        val hardTimeoutJob = launch {
            kotlinx.coroutines.delay(HARD_TIMEOUT_MS)
            Log.d(TAG, "Hard timeout (${HARD_TIMEOUT_MS}ms) reached — terminating scan")
            persistCache()
            close()
        }

        // AI AGENT NOTE: This awaitClose block is the single teardown point
        // for the entire scan. Every background coroutine launched above MUST
        // be cancelled here. If you add a new coroutine to discover(), add
        // its cancellation here too — a leaked coroutine will outlive the
        // scan window and drain battery silently.
        awaitClose {
            sweepJob.cancel()
            persistJob.cancel()
            hardTimeoutJob.cancel()
            // Cancel any in-flight device-name fetches and tear down the
            // supervisor scope so no background coroutines outlive the scan.
            fetchScope.cancel()
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {}
            persistCache()
        }
    }

    /**
     * Probe every address in `subnet.1..254` on the default ADB port, with
     * bounded concurrency. Returns the list of hosts that accepted a TCP
     * connection within [SUBNET_PROBE_TIMEOUT_MS]. Each live host is also
     * queued for friendly-name enrichment via [enrichDeviceName]; the
     * [onResolved] callback is invoked after each successful name fetch so
     * the caller can re-emit the updated list to the channel.
     */
    private suspend fun CoroutineScope.subnetSweep(
        subnet: String,
        fetchScope: CoroutineScope,
        onResolved: () -> Unit
    ): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, DiscoveredTv>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(SUBNET_CONCURRENCY)
        val jobs = (1..254).map { i ->
            launch {
                semaphore.acquire()
                try {
                    val host = "$subnet.$i"
                    if (probeHost(host, DEFAULT_ADB_PORT)) {
                        val placeholder = "Android TV ($host)"
                        val tv = DiscoveredTv(
                            name = placeholder,
                            host = host,
                            port = DEFAULT_ADB_PORT,
                            source = "scan",
                            lastSeen = System.currentTimeMillis()
                        )
                        // v2.2.0 bugfix: original v2.1.0 code had a corrupted
                        // LHS here (looked like `results<corrupted> = tv`)
                        // that prevented the entire project from compiling.
                        // The intended form is `results[host] = tv` — keyed
                        // by host IP so the ConcurrentHashMap deduplicates.
                        //
                        // AI AGENT NOTE: Do NOT collapse this into a direct
                        // trySend() — the sweep results must be collected
                        // into the `results` map first so subnetSweep() can
                        // return a deduplicated List<DiscoveredTv> to the
                        // caller, which then merges them into `seen` and
                        // emits. Skipping the map causes duplicate entries
                        // if two probe coroutines ever hit the same host.
                        results[host] = tv
                        // Fire-and-forget name enrichment on the supervisor scope
                        // so it doesn't block the sweep pipeline.
                        enrichDeviceName(fetchScope, host, DEFAULT_ADB_PORT, placeholder, onResolved)
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
        jobs.forEach { it.join() }
        results.values.toList()
    }

    /**
     * v2.2.0: Fetch the true friendly name of a discovered device via a fast,
     * non-blocking ADB shell. Tries `settings get global device_name` first
     * (returns the user-set name shown in TV settings), and falls back to
     * `getprop ro.product.model` (returns the marketing model name) if the
     * first command returns empty/error.
     *
     * Runs on the supervisor scope [fetchScope] so:
     *  • a single failure doesn't cancel sibling fetches
     *  • if the scan terminates before the fetch returns, the supervisor is
     *    cancelled by awaitClose and this coroutine is torn down with it
     *  • the parent scan pipeline never blocks waiting for the result
     *
     * On success, the device's entry in [seen] is updated with the new name
     * and [onResolved] is invoked so the caller can push a fresh emission to
     * the active channel.
     */
    private fun enrichDeviceName(
        fetchScope: CoroutineScope,
        host: String,
        port: Int,
        placeholder: String,
        onResolved: () -> Unit
    ) {
        fetchScope.launch {
            try {
                // AI AGENT NOTE: This is the primary device-name lookup — the
                // user-set name shown in TV Settings → Device Preferences →
                // About → Device name. The literal command string
                // "settings get global device_name" must NOT be changed; it is
                // the only Android system property that returns the user's
                // custom name (e.g. "Living Room TV"). Substituting any other
                // settings key or prop name will return generic model strings
                // instead of the user-friendly label. See developer-context.md §4.
                val primary = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(NAME_FETCH_TIMEOUT_MS) {
                        val r = AdbManager.executeShell(context, host, port, "settings get global device_name")
                        r.getOrDefault("").trim()
                    }
                }
                val resolved = when {
                    !primary.isNullOrBlank() && !primary.equals("null", ignoreCase = true) -> primary
                    else -> {
                        // AI AGENT NOTE: Fallback to marketing model name. Some
                        // TV ROMs (notably older Chromecast firmware) return
                        // the literal string "null" instead of an empty string
                        // when device_name is unset — the case-insensitive
                        // "null" check above catches that. Do not remove the
                        // null check or you will start showing "null" as the
                        // device name in the UI.
                        val fallback = withContext(Dispatchers.IO) {
                            kotlinx.coroutines.withTimeoutOrNull(NAME_FETCH_TIMEOUT_MS) {
                                val r = AdbManager.executeShell(context, host, port, "getprop ro.product.model")
                                r.getOrDefault("").trim()
                            }
                        }
                        fallback?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    }
                }
                if (resolved != null && resolved.isNotBlank()) {
                    val existing = seen[host] ?: return@launch
                    // Don't overwrite a real name with a placeholder again.
                    if (existing.name != placeholder && existing.name != "Android TV ($host)") return@launch
                    seen[host] = existing.copy(name = resolved, lastSeen = System.currentTimeMillis())
                    Log.d(TAG, "Resolved device name for $host → $resolved")
                    onResolved()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Name fetch failed for $host: ${e.message}")
            }
        }
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

    /**
     * v2.2.0: Public helper for one-shot, non-blocking device-name resolution.
     * Used by [MainActivity] when the user taps a device to immediately look up
     * its friendly name (without waiting for the next scan window).
     *
     * Returns the resolved name or null on timeout/failure. Safe to call from
     * any coroutine scope — never blocks the calling thread.
     */
    suspend fun resolveDeviceName(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        try {
            val primary = kotlinx.coroutines.withTimeoutOrNull(NAME_FETCH_TIMEOUT_MS) {
                AdbManager.executeShell(context, host, port, "settings get global device_name")
                    .getOrDefault("").trim()
            }
            when {
                !primary.isNullOrBlank() && !primary.equals("null", ignoreCase = true) -> primary
                else -> {
                    val fallback = kotlinx.coroutines.withTimeoutOrNull(NAME_FETCH_TIMEOUT_MS) {
                        AdbManager.executeShell(context, host, port, "getprop ro.product.model")
                            .getOrDefault("").trim()
                    }
                    fallback?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
