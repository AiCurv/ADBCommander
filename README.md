# ADB Commander

Android app (Kotlin + Jetpack Compose) that appears in Android's Share menu for **all file types** — photos, videos, audio, text, documents, and any other files. Users define custom ADB shell commands with `{URL}`, `{MIME}`, and `{FILE}` placeholders, connect to Android TV over Wireless ADB, and execute commands directly from the phone — no PC needed.

## Architecture Overview

ADB Commander uses a clean manual-connection model. There is no persistent background service, no auto-connect on startup, and noForegroundService that could drain battery or create connection loops. The user enters the TV's IP address, taps "Test Connection" to verify, and then all subsequent command executions use fresh ADB connections that open, run, and close automatically. This simple approach eliminates the stale-socket and auto-reconnect bugs that plagued earlier builds.

### Core Files

| File | Purpose |
|------|---------|
| `AdbManager.kt` | ADB connection management, shell execution, URL extraction, file push. Uses `libadb-android` (`AbsAdbConnectionManager`). Key methods: `executeShell()`, `sanitizeCommand()`, `prepareCommand()`, `prepareFileCommand()`, `shellEscape()`, `stripQuotesAroundToken()`, `extractUrl()` |
| `MainActivity.kt` | 2-tab bottom navigation UI: Connection tab (TV connection, preset selector, command editor, RUN COMMAND, Save Preset) + Settings tab (Package Manager Template Configurator, Execution Logs & History) |
| `ShareReceiverActivity.kt` | Transparent dialog activity launched from Android's Share sheet. Parses shared content on a background coroutine (`Dispatchers.IO`) so the UI appears instantly. Supports auto-execute and manual preset selection |
| `SettingsManager.kt` | Persists settings via DataStore (host, port, command, auto-execute, selected preset). Built-in presets ("Universal Default", "SmartTube") + user custom presets via SharedPreferences JSON |
| `FileServer.kt` | Embedded HTTP server for streaming local `content://` URIs to TV. Supports Range requests for video seeking. Auto-timeout after 10 minutes |
| `CommandLogStore.kt` | Persistent execution log storage. Records every command with timestamp and success/failure status. Powers the Execution Logs card in the Settings tab |
| `App.kt` | Installs Conscrypt TLS provider at position 1 for ADB TLS 1.3 support |

### Key Libraries

- **libadb-android** (`com.github.MuntashirAkon:libadb-android:3.1.1`) — ADB protocol. `AbsAdbConnectionManager.connect()` returns boolean, `openStream("shell:$command")` returns `AdbStream`
- **Conscrypt** (`org.conscrypt:conscrypt-android:2.5.3`) — TLS 1.3 for ADB pairing on Android 7-8
- **BouncyCastle** (`org.bouncycastle:bcprov-jdk15to18:1.81`) — X509V3CertificateGenerator for ADB key management
- **Jetpack Compose** + Material3 for UI
- **DataStore Preferences** for settings persistence
- **Kotlin Coroutines** (`kotlinx.coroutines.android`) for async ADB operations and background content resolution

### Build Config

- `compileSdk = 35`, `targetSdk = 35`, `minSdk = 24`
- AGP 8.7.3, Kotlin 2.1.0
- GitHub Actions CI: `.github/workflows/build.yml` builds debug APK on push

## How the Token System Works

ADB Commander uses three placeholder tokens that get replaced at command execution time. The system is designed so that preset templates use **bare, unquoted** placeholders, and `shellEscape()` in `AdbManager.kt` adds proper single-quote escaping at runtime. This prevents the double-quoting bug (`''URL''`) that earlier builds suffered from.

### Bare `{URL}` Token

The `{URL}` placeholder is replaced with the shared URL, wrapped in single quotes via `shellEscape()`. For example, if the user shares `https://site.com/video?id=123&type=mp4`, the shell command becomes:

```
am start -a android.intent.action.VIEW -d 'https://site.com/video?id=123&type=mp4' -t 'video/*' com.cxinventor.file.explorer
```

The single quotes prevent `&`, `?`, `=`, and other shell metacharacters from fragmenting the command. The `stripQuotesAroundToken()` function strips any accidental surrounding quotes from the template before substitution, so even if a user manually adds quotes around `{URL}` in the preset, the system corrects it automatically.

### Dynamic `{MIME}` Token

The `{MIME}` placeholder is replaced with the MIME type of the shared content, also shell-escaped. When sharing a video, `{MIME}` becomes `'video/*'`; when sharing an image, it becomes `'image/*'`; for audio, `'audio/*'`. This means a single preset template works across all media types without hardcoding a specific MIME value. The Universal Default preset uses `{MIME}` specifically for this flexibility — the same command can open a video in a file explorer or an image in an image viewer, because the MIME type adapts to whatever was actually shared.

### `{FILE}` Token

The `{FILE}` placeholder is used for local file sharing. Small files (under 2 MB) are base64-pushed to the TV's `/sdcard/Download/` directory, and `{FILE}` is replaced with the `file://` URI of the remote path. Larger files use the embedded HTTP server, where `{URL}` gets the streaming URL instead. This dual approach ensures both small and large files can be handled efficiently.

## Share Sheet — Universal Intent Filters

The app registers a single `<intent-filter>` with `<data android:mimeType="*/*" />` inside `ShareReceiverActivity`. This means ADB Commander appears in the Android share sheet for **every content type**: gallery photos, images, videos, audio files, PDFs, text snippets, APK files, and any other shareable content. Earlier builds used separate filters for `text/plain`, `video/*`, `audio/*`, and `application/*`, which excluded images and other common file types. The universal `*/*` filter ensures the app is always available when the user wants to send something to their TV.

## Background Thread Content Resolution

When the user shares content to ADB Commander from another app, the `ShareReceiverActivity` is launched by Android. The previous implementation ran `ContentResolver.query()`, stream reading, and file name extraction directly on the main UI thread inside `parseSharedContent()`. This caused a noticeable freeze — the phone would hang for a moment during the share-sheet-to-app transition, especially when resolving large content URIs or querying media providers.

The current implementation fixes this by running all content resolution on a background coroutine via `lifecycleScope.launch(Dispatchers.IO)`. The `onCreate()` method immediately calls `setContent {}` with a lightweight loading indicator (a `CircularProgressIndicator` and "Resolving shared content..." text), then launches the parsing coroutine. Once the background work completes, the `isResolving` state variable is set to `false` on the main thread via `withContext(Dispatchers.Main)`, and the full ShareReceiverDialog is rendered. This approach ensures the UI is responsive from the first frame, and the user sees a smooth transition from the share sheet into the app.

The `resolveFileName()` method specifically queries `ContentResolver` for the `OpenableColumns.DISPLAY_NAME` of the shared URI, which can involve a database query to the media provider. By running this on `Dispatchers.IO`, we avoid any risk of an ANR (Application Not Responding) dialog.

## TV Connection Model

- Connects via Wireless ADB on port 5555 (default, configurable)
- TV must be on the same WiFi network and already paired (ADB pairing done via Android TV settings)
- `AdbManager.testConnection()` runs `echo ok` to verify connectivity
- Connection is NOT persistent — each `executeShell()` call does a fresh `connect()` + `openStream()`
- No foreground service, no auto-connect, no background connection management — simple and reliable

## Share Flow

1. User Shares any content from any app (photo, video, link, file) → Android launches `ShareReceiverActivity`
2. UI appears instantly with a loading spinner while content is resolved on `Dispatchers.IO`
3. Intent is parsed: `text/*` = URL/text, everything else = file
4. File names are resolved via `ContentResolver.query()` on the background thread
5. If auto-execute is ON → runs the selected preset immediately
6. If auto-execute is OFF → shows interactive dialog with preset buttons
7. For files: small files (<2 MB) are base64-pushed to TV `/sdcard/Download/`, larger files use HTTP streaming via `FileServer`

## Built-in Presets

| Preset | Command | Description |
|--------|---------|-------------|
| Universal Default | `am start -a android.intent.action.VIEW -d {URL} -t {MIME} com.cxinventor.file.explorer` | Opens any shared content in CX File Explorer. Uses `{MIME}` so it works with videos, images, and audio |
| SmartTube | `am start -a android.intent.action.VIEW -d {URL} -n org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity` | Opens YouTube URLs directly in SmartTube Next on the TV |

Users can create additional custom presets via the "Save Preset" button in the Connection tab or the Package Manager Template Configurator in the Settings tab.

## Version History

| Version | Status | Notes |
|---------|--------|-------|
| v1.6.1 (build 22) | Current | Restored preset selector UI, 2-tab layout, Package Manager + Logs in Settings |
| v1.6.0 (build 21) | Superseded | 2-tab UI restructure, `stripQuotesAroundToken()`, removed legacy presets |
| v1.5.0 (build 15) | Legacy | Last build with old 3-tab layout and 6 hardcoded presets |

## GitHub & CI

- **Repo**: `AiCurv/ADBCommander`
- **CI**: `.github/workflows/build.yml` — runs `assembleDebug` on ubuntu-latest + JDK 17
- **Artifacts**: Debug APK uploaded as `app-debug` artifact, downloadable from Actions tab
- **Branch**: `main` only
