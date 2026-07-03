# ADB Commander

Android app (Kotlin + Jetpack Compose) that appears in the system Share menu for **all file types** — photos, videos, audio, text, documents, and any other files. Users define custom ADB shell commands with `{URL}`, `{MIME}`, and `{FILE}` placeholders, connect to Android TV over Wireless ADB, and execute commands directly from the phone. No PC required.

## Architecture (v2.1.0)

ADB Commander uses a manual-connection, on-demand architecture, optionally backed by a **persistent foreground service** (`AdbForegroundService`) that keeps the process warm in the background so share-sheet executions launch with zero cold-start lag. TVs are discovered automatically via a **dual-tier network scanner** (`TvDiscoveryService`) — no IP typing required. The user taps a discovered device name, and all subsequent command executions use fresh ADB connections that open, run, and close automatically. This eliminates stale-socket and auto-reconnect bugs entirely.

A **Quick Settings tile** (`AdbTileService`) lets the user toggle the bridge service from anywhere in the OS with one tap, or — if no TV IP is configured — jump straight to MainActivity to pick a device.

A premium **Background Service & Battery** card in the Settings tab lets the user request `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so the OS never forcefully sleeps the background ADB thread loop.

### 2-Tab Bottom Navigation

The app is organized into exactly two tabs via a Material3 bottom navigation bar:

**Connection Tab** — The primary operational screen. Contains the **TV Scan** card (auto-discovers TVs on the local network via mDNS + subnet sweep, shows a scrollable list of device names — tap one to select it as the active target), an "Active target" indicator showing the currently selected TV, a collapsible **Advanced Manual Entry** accordion (for power users who want to type an IP/port directly), the preset dropdown selector with "Save Current as Preset" button, the shell command text field, "RUN COMMAND" execution button, and the auto-execute toggle for share behavior.

**Settings Tab** — Configuration and history. Contains:
- **Background Service & Battery** card (v2.0.0) — start/stop the persistent TV bridge foreground service, request battery-optimization immunity, see live state.
- **Package Manager Template Configurator** — scan TV packages (3rd-party or all), search and sort, build command templates from any package, with rendered app icons in circular containers.
- **Backup & Restore Presets** — export custom presets to clipboard JSON, import from clipboard JSON.
- **Execution Logs & History** — tap any entry to view the raw command text and copy it.

### Core Files

| File | Purpose |
|------|---------|
| `AdbManager.kt` | ADB connection management, shell execution, URL extraction, file push. Key methods: `executeShell()`, `prepareCommand()`, `prepareFileCommand()`, `shellEscape()`, `stripQuotesAroundToken()`, `resolveMimeType()`, `getExtensionFromFileName()`, `getMimeTypeFromExtension()` |
| `MainActivity.kt` | 2-tab UI with Connection tab and Settings tab. Preset management, Package Manager dialog, Background Service & Battery card, execution logs. All heavy reads (DataStore, SharedPreferences JSON, log file) are pushed to `Dispatchers.IO` so launch is lag-free. |
| `ShareReceiverActivity.kt` | Transparent dialog launched from Android's Share sheet. Parses shared content on `Dispatchers.IO` so the UI appears instantly. Supports auto-execute and manual preset selection. |
| `AdbForegroundService.kt` | Persistent foreground service representing the active TV bridge. Uses `connectedDevice` service type. Survives swipe-away via `START_STICKY` + `onTaskRemoved` self-restart. Displays a low-priority ongoing notification. |
| `AdbTileService.kt` | Quick Settings tile. Tap: if TV host is configured → toggle the foreground service; if not configured → launch MainActivity so user can pick a device. |
| `TvDiscoveryService.kt` | Dual-tier TV discovery. Tier 1: mDNS via `NsdManager` scanning `_adb-tls-connect._tcp` for friendly device names + host + port. Tier 2: if mDNS finds nothing in 3s, concurrent subnet sweep of `/24` on port 5555 (50 coroutines, 500ms timeout each). Persists all discovered devices to a JSON cache file so previously seen TVs appear instantly on next launch. |
| `SettingsManager.kt` | Persists settings via DataStore (host, port, command, auto-execute, selected preset). Built-in presets ("Universal Default", "SmartTube", "Send to TV Downloads", "APK Installer") plus user custom presets via SharedPreferences JSON. Supports JSON export/import. |
| `FileServer.kt` | Embedded HTTP server for streaming local `content://` URIs to the TV. Supports Range requests for video seeking. Auto-timeout after 10 minutes. |
| `CommandLogStore.kt` | Persistent execution log storage with timestamps and success/failure status. |
| `App.kt` | Installs Conscrypt TLS provider at position 1 for ADB TLS 1.3 support. |

### Key Libraries

- **libadb-android** (`com.github.MuntashirAkon:libadb-android:3.1.1`) — ADB protocol
- **Conscrypt** (`org.conscrypt:conscrypt-android:2.5.3`) — TLS 1.3 for ADB pairing on Android 7-8
- **BouncyCastle** (`org.bouncycastle:bcprov-jdk15to18:1.81`) — X509 certificate generation for ADB key management
- **Jetpack Compose** + Material3 for UI
- **DataStore Preferences** for settings persistence
- **Kotlin Coroutines** for async ADB operations and background content resolution

## Dynamic Token Substitution

ADB Commander uses three placeholder tokens that get replaced at command execution time. Preset templates use **bare, unquoted** placeholders, and `shellEscape()` adds proper single-quote escaping at runtime to prevent double-quoting bugs.

### `{URL}` — Shared Link Placeholder

Replaced with the shared URL, wrapped in single quotes via `shellEscape()`. Shell metacharacters like `&`, `?`, `=` are safely escaped so they don't fragment the command. `stripQuotesAroundToken()` strips any accidental surrounding quotes from the template before substitution.

Example: `am start -a android.intent.action.VIEW -d {URL}` becomes `am start -a android.intent.action.VIEW -d 'https://site.com/video?id=123&type=mp4'`

### `{MIME}` — Dynamic Content Type Placeholder

Replaced with the actual MIME type of the shared content, resolved by `AdbManager.resolveMimeType()`. This is a 3-tier resolution pipeline:

1. **Intent MIME** — If the share intent provides a specific, non-generic MIME type (e.g. `image/jpeg`), it's used directly
2. **Filename extension** — If the intent MIME is generic (`*/*`, `application/octet-stream`), the system derives the MIME from the file extension via `getMimeTypeFromExtension()` (e.g. `.jpg` → `image/jpeg`, `.mp4` → `video/mp4`)
3. **Wildcard fallback** — If neither source provides type information, `*/*` is used as the last resort

This means a single preset like the Universal Default (`am start -a android.intent.action.VIEW -d {URL} -t {MIME} com.cxinventor.file.explorer`) works correctly for every content type — videos get `video/mp4`, photos get `image/jpeg`, audio gets `audio/mpeg`. External apps like CX Explorer use the `-t` MIME flag to route to the correct viewer (video player vs. image viewer vs. audio player).

### `{FILE}` — Local File Placeholder

Used for local file sharing. Small files (under 2 MB) are base64-pushed to the TV's `/sdcard/Download/` directory; `{FILE}` is replaced with the `file://` URI of the remote path. Larger files use the embedded HTTP server, where `{URL}` gets the streaming URL instead.

## Background Processing via Dispatchers.IO

When content is shared to ADB Commander from another app, `ShareReceiverActivity` is launched by Android. All `ContentResolver` operations — URI resolution, file name extraction via `OpenableColumns.DISPLAY_NAME` queries, and stream reading — run on `Dispatchers.IO` via `lifecycleScope.launch(Dispatchers.IO)`. The UI renders immediately with a loading spinner, then swaps to the full dialog once resolution completes on the main thread via `withContext(Dispatchers.Main)`. This prevents the share-sheet-to-app transition from freezing the phone.

The same pattern applies to `MainActivity` — all DataStore reads, SharedPreferences JSON parsing, and `CommandLogStore` JSON file parsing run on `Dispatchers.IO` via `LaunchedEffect` so the 2-tab UI renders instantly with built-in presets and empty logs, then swaps in the persisted state when ready.

## Persistent Foreground Service (v2.0.0)

`AdbForegroundService` is a foreground service declared with `android:foregroundServiceType="connectedDevice"` because it represents an active connection to an external device (the TV over ADB). When started, it displays a low-priority, non-vibrating, silent ongoing notification representing the active TV bridge.

Key behaviors:
- **Survives swipe-away**: `onTaskRemoved()` re-delivers itself via `startForegroundService()` so swiping the app from Recents does NOT terminate the bridge.
- **START_STICKY**: if the OS reclaims memory, it restarts the service with a null intent; `onCreate()` rebuilds the notification.
- **Static `isRunning()` flag** so the UI tile and Settings card can mirror service state without binding.
- **Clean notification**: low priority, no vibration, no sound, public visibility, category SERVICE. Tapping the notification brings MainActivity to the foreground.

## Quick Settings Tile (v2.0.0)

`AdbTileService` is a `TileService` registered with `BIND_QUICK_SETTINGS_TILE`. Tap behavior:
- If a TV host is configured in `SettingsManager` → toggle `AdbForegroundService` on/off instantly.
- If no TV host is configured (blank) → bring `MainActivity` to the foreground via `startActivityAndCollapse` (PendingIntent path on Android 14+, raw Intent path on older versions).

The tile's state mirrors `AdbForegroundService.isRunning()`: `STATE_ACTIVE` when the bridge is running, `STATE_INACTIVE` otherwise. Reading the saved TV host from DataStore is a suspend operation; we use `runBlocking` inside `onClick()` because `TileService.onClick` runs on a binder thread, never the UI thread, so blocking it briefly is safe.

## TV Network Discovery (v2.1.0)

The Connection tab no longer requires the user to type a TV IP address. Instead, `TvDiscoveryService` automatically discovers TVs on the local network using a dual-tier strategy:

**Tier 1 — mDNS via `NsdManager`**
- Scans for the `_adb-tls-connect._tcp` service type that every Android 11+ wireless-debugging-enabled TV broadcasts.
- Returns the friendly device name (often the TV model), host IP, and port directly — no manual entry needed.
- Fast: typically resolves within 1-3 seconds.
- Zero extra permissions (INTERNET + ACCESS_WIFI_STATE already held).

**Tier 2 — Subnet sweep (fallback)**
- If mDNS finds nothing within 3 seconds, kicks off a concurrent coroutine sweep of the local `/24` subnet on port 5555.
- 50 coroutines in flight at a time, 500ms TCP-connect timeout per address.
- Catches older Android TVs that don't broadcast mDNS but still listen on the legacy ADB port.
- Whole sweep completes in ~5-10 seconds.

**Caching**
- Every successfully discovered device is persisted to a JSON file (`discovered_tvs_cache.json`) in the app's internal storage.
- On next app launch, cached devices are emitted instantly so the UI is never empty while a fresh scan runs.
- Each device shows a source badge: `mDNS`, `Network scan`, or `Cached`.

**Lifecycle**
- The scan starts the moment the Connection tab enters composition (via `DisposableEffect` + `lifecycleScope.launch`).
- When the user switches to the Settings tab or the activity is destroyed, the scan job is cancelled — mDNS discovery is stopped and every subnet-sweep coroutine is torn down.
- A manual "Rescan" button cancels the current scan and starts a fresh one.

**UI**
- Each discovered TV is a row with: status dot (green=selected, gray=available, amber=cached), bold friendly name, subtitle showing `IP:port · source`, and an expand icon.
- Tapping a row instantly assigns that TV's host+port as the active ADB routing target and persists it to DataStore.
- Expanding a row reveals full details (IP, port, source, last-seen timestamp) plus "Test" and "Forget" buttons.
- The old IP/port text fields are retained inside a collapsible "Advanced Manual Entry" accordion (collapsed by default) for power users on unusual subnets or non-standard ports.

`AdbManager.kt` and `ShareReceiverActivity.kt` are untouched — they still take `host` + `port` parameters. The only difference is that those values now come from the selected discovered device instead of from text fields.

## Battery Optimization Immunity (v2.0.0)

The premium **Background Service & Battery** card in the Settings tab lets the user request `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` via the native system prompt. This is intentionally NOT auto-triggered on app launch (that was the #1 cause of launch latency in v1.x — auto-prompting blocked the Main thread with a system Intent on every cold start). Instead, the user triggers it on demand, and the card shows live state (immune vs. active) so the user always knows whether the OS will leave the background ADB thread loop alone.

## Universal Share Sheet Visibility

The app registers a single `<intent-filter>` with `<data android:mimeType="*/*" />` in `AndroidManifest.xml`. This ensures ADB Commander appears in the Android share sheet for every content type: gallery photos, images, videos, audio, text, documents, APK files, and any other shareable content.

## Built-in Presets

| Preset | Command | Description |
|--------|---------|-------------|
| Universal Default | `am start -a android.intent.action.VIEW -d {URL} -t {MIME} com.cxinventor.file.explorer` | Opens any shared content in CX File Explorer. Uses `{MIME}` so it works with videos, images, and audio |
| SmartTube | `am start -a android.intent.action.VIEW -d {URL} -n org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity` | Opens YouTube URLs directly in SmartTube Next on the TV |
| Send to TV Downloads | `am start -a android.intent.action.VIEW -d {URL} -t application/octet-stream` | Sends the URL to the TV's default download handler |
| APK Installer | `am start -a android.intent.action.VIEW -d {URL} -t application/vnd.android.package-archive com.google.android.packageinstaller` | Installs an APK on the TV via the Package Installer |

Custom presets can be created via the "Save Preset" button in the Connection tab or the Package Manager Template Configurator in the Settings tab. Custom presets can be exported/imported as JSON via the Backup & Restore Presets card.

## HTTP Streaming URL Extensions

The internal HTTP server constructs streaming URLs with the real file extension extracted from the resolved filename (e.g. `http://phone-ip:port/file.jpg` for a JPEG, `file.mp4` for a video). External players use the URL extension to determine the media format, so using the correct extension (instead of `.tmp`) allows CX Explorer and other apps to recognize and open the content properly.

## Build Config

- `compileSdk = 35`, `targetSdk = 35`, `minSdk = 24`
- `versionCode = 30`, `versionName = "2.1.0"`
- GitHub Actions CI: `.github/workflows/build.yml` builds debug APK on push
- **Repo**: `AiCurv/ADBCommander`
