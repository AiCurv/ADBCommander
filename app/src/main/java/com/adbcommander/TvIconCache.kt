package com.adbcommander

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2.3.0 — On-disk cache for TV-side app launcher icons.
 *
 * Lives at `<app filesDir>/tv_icons/<packageName>.png`. Every icon is
 * fetched exactly once per package-name and reused on subsequent
 * preset-builder sessions. Call [invalidate] when the user wants to
 * re-pull (e.g. after uninstalling/reinstalling an app on the TV).
 *
 * Per the user's voice instructions:
 *   "you're going to download all TV icons and save that in some TV
 *    folder, okay, where you're going to access that via ADB and show
 *    that icons, okay, when the user tries to build presets. Okay,
 *    that's how we're going to show that. Save those icons with the name."
 *
 * All network I/O is funneled through [AdbManager.fetchTvAppIconBytes]
 * which itself runs on Dispatchers.IO — so this class is safe to call
 * from any coroutine scope including the UI scope (developer-context.md
 * §2.2 background-IO-threading rule).
 *
 * The folder is also exposed via [iconFileFor] so the UI can load the
 * PNG straight from disk via Coil-less `BitmapFactory.decodeFile` (we
 * don't ship an image-loading library to keep the APK lean, per the
 * "zero-bloat" philosophy in developer-context.md §1).
 */
class TvIconCache(private val context: Context) {

    private val tag = "ADBCommander"

    /** Root directory: `<filesDir>/tv_icons/`. Created lazily. */
    val iconDir: File by lazy {
        File(context.filesDir, "tv_icons").apply { if (!exists()) mkdirs() }
    }

    /**
     * The cache file for a given package name. Always returns a File
     * even if the icon has not been fetched yet — callers should check
     * [File.exists] before trying to decode.
     */
    fun iconFileFor(packageName: String): File =
        File(iconDir, "${packageName.replace('/', '_')}.png")

    /**
     * Returns true if the icon for [packageName] is already cached on
     * disk. Cheap file-exists check — safe to call on the Main thread.
     */
    fun isCached(packageName: String): Boolean = iconFileFor(packageName).exists()

    /**
     * Fetch and persist a single icon. Skips the network round-trip if
     * the file is already cached (so calling this for every package in
     * a 100-app list does only as much I/O as needed).
     *
     * Returns the cached File on success, null on failure or when the
     * TV has no extractable launcher icon for this package.
     */
    suspend fun fetchAndCache(host: String, port: Int, packageName: String): File? =
        withContext(Dispatchers.IO) {
            val cached = iconFileFor(packageName)
            if (cached.exists() && cached.length() > 0) return@withContext cached

            val result = AdbManager.fetchTvAppIconBytes(context, host, port, packageName)
            if (result.isFailure) {
                Log.w(tag, "Icon fetch failed for $packageName: ${result.exceptionOrNull()?.message}")
                return@withContext null
            }
            val bytes = result.getOrDefault(ByteArray(0))
            if (bytes.isEmpty()) return@withContext null

            try {
                cached.parentFile?.mkdirs()
                cached.writeBytes(bytes)
                Log.d(tag, "Cached TV icon: $packageName (${bytes.size} bytes)")
                cached
            } catch (e: Exception) {
                Log.e(tag, "Failed to write icon for $packageName", e)
                null
            }
        }

    /**
     * Bulk-fetch icons for a list of packages, invoking [onProgress]
     * after each one completes (success OR failure) so the UI can
     * progressively reveal icons as they arrive.
     *
     * Concurrency is bounded by [maxParallel] using a Semaphore so we
     * don't open more ADB sockets than the TV can handle at once —
     * the TV's adbd has a hard connection ceiling (~5 simultaneous
     * shells on most Android TV builds) and exceeding it triggers
     * transient "device offline" errors that pollute the cache.
     */
    suspend fun bulkFetch(
        host: String,
        port: Int,
        packages: List<String>,
        maxParallel: Int = 4,
        onProgress: (packageName: String, succeeded: Boolean) -> Unit
    ) = coroutineScope {
        val sem = Semaphore(maxParallel)
        packages.forEach { pkg ->
            launch {
                sem.withPermit {
                    val file = fetchAndCache(host, port, pkg)
                    onProgress(pkg, file != null)
                }
            }
        }
    }

    /**
     * Wipe the entire icon cache. Used by the Settings → Device
     * Management → "Refresh icons" action and by [invalidate(packageName)].
     */
    fun invalidate(packageName: String) {
        iconFileFor(packageName).delete()
    }

    fun clearAll() {
        iconDir.listFiles()?.forEach { it.delete() }
    }
}
