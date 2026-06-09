# ADB Commander

Android app (Kotlin + Jetpack Compose) that appears in Android's Share menu, lets users define custom ADB shell commands with `{URL}`/`{FILE}` placeholders, connects to Android TV over Wireless ADB, and executes commands directly from the phone — no PC needed.

## Current Working Build

**v1.5.0 (versionCode 15)** — This is the last known *working* build. All builds after this (16, 17, 18) introduced regressions and were rolled back. **Start from this codebase.**

## Architecture

### Core Files

| File | Purpose |
|------|---------|
| `AdbManager.kt` | ADB connection management, shell execution, URL extraction, file push. Uses `libadb-android` (`AbsAdbConnectionManager`). Key methods: `executeShell()`, `sanitizeCommand()`, `prepareCommand()`, `extractUrl()` |
| `MainActivity.kt` | 2-tab UI: Commander tab (TV connection, presets, command editor, RUN COMMAND) + Package Manager tab (scan TV packages, build presets) |
| `ShareReceiverActivity.kt` | Interactive share dialog — appears when user Shares from another app. Shows preset buttons, auto-execute mode, handles both URL and file sharing |
| `SettingsManager.kt` | Persists settings via DataStore (host, port, presets, etc.). Has 6 built-in presets + user custom presets via SharedPreferences |
| `FileServer.kt` | Embedded HTTP server for streaming local `content://` URIs to TV. Supports Range requests for video seeking. Auto-timeout after 10 min |
| `App.kt` | Installs Conscrypt TLS provider at position 1 |

### Key Libraries

- **libadb-android** (`com.github.MuntashirAkon:libadb-android:3.1.1`) — ADB protocol. `AbsAdbConnectionManager.connect()` returns boolean, `openStream("shell:$command")` returns `AdbStream`
- **Conscrypt** (`org.conscrypt:conscrypt-android:2.5.3`) — TLS 1.3 for ADB pairing
- **BouncyCastle** (`org.bouncycastle:bcprov-jdk15to18:1.81`) — X509V3CertificateGenerator for ADB key management
- **Jetpack Compose** + Material3 for UI
- **DataStore Preferences** for settings persistence

### Build Config

- `compileSdk = 35`, `targetSdk = 35`, `minSdk = 24`
- AGP 8.7.3, Kotlin 2.1.0
- GitHub Actions CI: `.github/workflows/build.yml` builds debug APK on push

### TV Connection

- Connects via Wireless ADB on port 5555 (default)
- TV must be on same WiFi network and already paired (ADB pairing done via Android TV settings)
- `AdbManager.testConnection()` runs `echo ok` to verify connectivity
- Connection is NOT persistent — each `executeShell()` call does a fresh `connect()` + `openStream()`

### Share Flow

1. User Shares a URL or file from any app → Android launches `ShareReceiverActivity`
2. Intent is parsed: text/plain = URL, video/audio/application = file
3. If auto-execute is ON → runs selected preset immediately
4. If auto-execute is OFF → shows interactive dialog with preset buttons
5. For files: small files (<2MB) are base64-pushed to TV `/sdcard/Download/`, larger files use HTTP streaming via `FileServer`

## Known Issues (What Needs Fixing)

These are the bugs/limitations that Builds 16-18 tried to fix but introduced regressions:

### 1. Shell Argument Escaping — URLs with `&`, `?`, `=` get fragmented

**Problem**: When a URL like `https://site.com/video?id=123&type=mp4` is inserted into a shell command, the `&` acts as a shell operator, fragmenting the command. The `prepareCommand()` method just does a simple string replacement without escaping.

**What was tried (Build 16)**: Added `shellEscape()` that wraps `{URL}` values in single quotes. BUT the preset templates also had `'{URL}'` with single quotes, creating DOUBLE quoting (`''url''`) which shell interprets as empty string + unquoted URL + empty string — even worse.

**Correct fix**: Add `shellEscape()` that wraps in single quotes, but make sure preset templates use bare `{URL}` (no surrounding quotes). The `shellEscape()` function should be the ONLY source of quoting.

### 2. Background ADB Connection Dies When App is Closed

**Problem**: When user Shares a link and the app is in background, the ADB socket can be killed by Android. The share then fails.

**What was tried (Build 16)**: Added `AdbForegroundService` with a sticky notification. BUT this introduced the auto-connect bug (see #3).

**Correct fix**: Foreground service is the right approach, but it should ONLY be started after a successful manual connection (user taps Test Connection). Do NOT auto-start it.

### 3. Auto-Connect Causes "Ensure TV is Paired" Loop

**Problem**: Build 16 added auto-connect on app startup. When navigating between tabs (Commander ↔ Package Manager), the `LaunchedEffect(Unit)` would re-fire and try to connect again, often failing and showing error messages.

**What was tried (Build 17)**: Added `isConnecting` guard and `isConnectedTo()` check. Still had issues because the underlying socket management in libadb-android doesn't cleanly support "check if connected."

**Correct approach**: Do NOT auto-connect. Keep Build 15's manual-only connection (user taps "Test Connection"). This is simple and reliable.

### 4. URL Extraction Only Supports http/https/magnet

**Problem**: `extractUrl()` only matches `https?://` and `magnet:` schemes. APK download links, FTP URLs, `content://` URIs, etc. are dropped.

**Fix**: Use a universal URI regex that matches any `[a-zA-Z][a-zA-Z0-9+.-]*://` scheme, and fall back to returning the full shared text if no URI is found.

### 5. Hardcoded Presets Bloat

**Problem**: Build 15 has 6 hardcoded presets (Default Video Player, VLC, SmartTube, Stremio, Local File Player, Custom Template). These are specific to apps the user may not have.

**What was tried (Build 16)**: Removed all hardcoded presets, kept only "Vimu Player" as fallback. This was correct.

**Fix**: Keep the dynamic SharedPreferences preset system but reduce hardcoded presets to just the Vimu Player fallback.

## File-by-File Notes for Future AI

### AdbManager.kt
- `sanitizeCommand()` strips "adb shell" / "adb" prefix — users paste commands with this prefix and `openStream("shell:adb shell ...")` double-prefixes
- `prepareCommand()` replaces `{URL}` and `YOUR_VIDEO_URL` — both placeholder formats supported for backward compat
- `AdbConnectionManager` inner class extends `AbsAdbConnectionManager` — handles RSA key generation, self-signed cert via BouncyCastle, key persistence in SharedPreferences
- The `connect()` call happens INSIDE `executeShell()` — there's no separate connect step. Every command execution does a fresh connect.

### MainActivity.kt
- `CommanderTab()` composable has all the TV connection UI, preset dropdown, command editor
- `PackageManagerTab()` composable scans TV packages via `pm list packages -3`
- Settings loaded via `LaunchedEffect(Unit)` — this is where auto-connect was added in Build 16 (DON'T add it back)
- `requestBatteryExemption()` prompts user to whitelist app from battery optimization

### ShareReceiverActivity.kt
- `parseSharedContent()` detects URL vs file from the share intent
- `ShareReceiverDialog()` composable shows preset buttons
- `executePresetSuspend()` is the actual execution path — prepares command and calls `AdbManager.executeShell()`
- Uses `FileServer` for HTTP streaming of local files
- `activeFileServer` is a static reference to clean up on destroy

### SettingsManager.kt
- Uses both DataStore (for host, port, command, etc.) and SharedPreferences (for presets JSON)
- `getAllPresets()` returns built-in presets + custom presets merged
- `buildPresetFromPackage()` generates an `am start` command from package info
- Presets are stored as JSON array in SharedPreferences under key `presets_json`

### FileServer.kt
- Tiny HTTP server using `ServerSocket` — no dependencies
- Serves a single `content://` URI with Range request support
- `getLocalIpAddress()` finds the phone's WiFi IP for constructing the TV-accessible URL
- Auto-stops after 10 minutes of inactivity

## GitHub & CI

- **Repo**: `AiCurv/ADBCommander`
- **GitHub token**: Check `.env` file or ask user
- **CI**: `.github/workflows/build.yml` — runs `assembleDebug` on ubuntu-latest + JDK 17
- **Artifacts**: Debug APK uploaded as `app-debug` artifact, downloadable from Actions tab
- **Branch**: `main` only

## Version History

| Version | Status | Notes |
|---------|--------|-------|
| v1.5.0 (build 15) | ✅ WORKING | Current codebase. Interactive share dialog, dual content, package manager |
| v1.6.0 (build 16) | ❌ Broken | Added shell escaping (double-quote bug), foreground service, auto-connect (loop bug) |
| v1.7.0 (build 17) | ❌ Broken | Fixed auto-connect collision, interactive notification, still had double-quote bug |
| v1.8.0 (build 18) | ❌ Broken | Removed auto-connect, fixed double-quote, but too many changes from working base |

## Recommended Next Steps

1. **Fix shell escaping properly**: Add `shellEscape()` to AdbManager, remove quotes from preset templates so there's no double-quoting
2. **Add foreground service carefully**: Start it ONLY after manual "Test Connection" succeeds. Keep it simple.
3. **Do NOT add auto-connect**: Build 15's manual connection is reliable. Auto-connect caused more problems than it solved.
4. **Universal URL extraction**: Expand `extractUrl()` to support any URI scheme
5. **Reduce hardcoded presets**: Keep Vimu Player as fallback, let users add their own
6. **Test incrementally**: One fix per build, test on real TV before stacking changes
