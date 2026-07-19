# ADB Commander — Developer Context (Architectural Truth)

> **This file is the canonical architectural reference for ADB Commander.**
> Any AI agent or human contributor starting a new session on this project
> MUST read this file end-to-end before proposing changes. The constraints
> documented here are non-negotiable unless explicitly revisited by the
> project owner. Last updated: **v2.7.0**.

---

## 1. Project Identity & Philosophy

ADB Commander is a **lean, premium, zero-bloat background ADB command bridge
for Android TV**. It runs on the user's phone, surfaces inside the system
share sheet for every file and link type, and routes the shared payload to a
user-selected Android TV over wireless ADB — no PC, no cable, no companion TV
app required.

The design philosophy is **manual-connection, on-demand execution**:

- Each command execution opens a fresh ADB connection, runs one shell command,
  and closes the connection. This eliminates stale-socket and auto-reconnect
  bugs entirely.
- A persistent foreground service (`AdbForegroundService`) is *optional* — it
  keeps the process warm so share-sheet launches feel instant, but it is not
  in the critical path for command execution.
- A Quick Settings tile (`AdbTileService`) exposes a one-tap toggle for the
  bridge from anywhere in the OS.
- The UI has two top-level destinations (Connect and Terminal), navigated via a
  bottom NavigationBar that hides on scroll-down and shows on scroll-up. This
  provides a clean, immersive experience. There is no onboarding flow, no
  analytics, no telemetry. Every screen is built to be understood in under five
  seconds by a technical user.

**What this project is NOT**: a file manager, a side-loading utility,
a multi-device orchestrator, or a generic Android utility suite. Pull
requests that drift in any of those directions should be rejected.

---

## 2. Core Constraints (NON-NEGOTIABLE)

These constraints exist because removing or modifying them has historically
caused production regressions. They are the load-bearing walls of the
codebase.

### 2.1 The Local HTTP File Server Must NEVER Be Deleted or Modified

`FileServer.kt` is the in-process HTTP server that streams `content://` URIs
to the TV when a share payload is a local file (image, video, audio,
document) and the chosen preset uses the `{URL}` token rather than `{FILE}`.

- It supports HTTP **Range requests** so the TV's video player can seek.
- It auto-shuts down after 10 minutes of inactivity to release the port.
- It exposes a single endpoint `/file.<ext>` that pipes the resolved MIME
  type and bytes from `ContentResolver.openInputStream(uri)` directly to the
  socket output stream — **the streaming buffers must not be touched**.
- It is referenced by `ShareReceiverActivity` via the static
  `activeFileServer` field, which is cleared in `onDestroy()`.

**Do not** replace this server with a third-party HTTP library, refactor it
to use Ktor/NanoHTTPD, or "modernize" the buffer sizes. The current
implementation has been validated against real Android TV video players
(Cx Player, VLC, MX Player, SmartTube) and the buffer geometry is exactly
what makes seeking work.

### 2.2 Network Operations Must Run on Background IO Threads

The Main thread is reserved exclusively for Compose recomposition and UI
event dispatch. Any of the following on the Main thread is a **P0 bug**:

- `AdbManager.executeShell()` calls
- `AdbManager.testConnection()` calls
- `AdbManager.pushFileSmall()` calls
- `SettingsManager.get*()` DataStore reads (any `flow.first()`)
- `CommandLogStore.getLogs()` JSON parsing
- `TvDiscoveryService.discover()` collection
- `ContentResolver.query()` calls in `ShareReceiverActivity`
- `PowerManager.isIgnoringBatteryOptimizations()` IPC

The launch-latency budget for cold-start to interactive UI is **under 500
milliseconds**. Auto-prompting for battery-optimization exemption in
`MainActivity.onCreate()` is explicitly forbidden — it was the #1 cause of
launch stalls in v1.x and was removed in v2.0.0. The exemption must only be
requested in response to an explicit user tap on the "Grant immunity" button
inside the Settings sheet.

### 2.3 AdbManager.kt and ShareReceiverActivity.kt Backend Contract

`AdbManager` exposes exactly these public methods. New methods are welcome;
**renaming or removing existing ones is forbidden** without a coordinated
update to every call site (ShareReceiverActivity, MainActivity,
TvDiscoveryService, AdbForegroundService, AdbTileService):

| Method | Purpose |
|--------|---------|
| `getManager(context)` | Lazily initializes the AdbConnectionManager singleton. |
| `sanitizeCommand(raw)` | Strips `adb shell` prefixes, trims whitespace. |
| `shellEscape(value)` | Wraps a value in single quotes for shell safety. |
| `stripQuotesAroundToken(template, token)` | Removes accidental quotes around `{URL}`/`{FILE}` before escaping. |
| `prepareCommand(template, url, mime)` | Substitutes `{URL}` and `{MIME}` into a template. |
| `prepareFileCommand(template, remotePath, httpUrl, mime)` | Substitutes `{FILE}` (push path) or `{URL}` (http stream) plus `{MIME}`. |
| `executeShell(context, host, port, rawCommand)` | Opens a fresh ADB connection, runs the command with a 10s read deadline, closes. |
| `testConnection(context, host, port)` | Calls `executeShell` with `echo ok`. |
| `pushFileSmall(context, host, port, fileUri, fileName)` | Base64-encodes a small file and writes it to `/sdcard/` on the TV. |
| `extractUrl(sharedText)` | Pulls the first http/https/magnet URL out of arbitrary shared text. |
| `resolveMimeType(intentMimeType, fileName)` | Maps intent MIME + filename extension to a wildcard category. |
| `getExtensionFromFileName` / `getExtensionFromMimeType` / `getMimeTypeFromExtension` | Self-explanatory helpers. |

### 2.4 The 7-Second Hard Timeout on TvDiscoveryService Must NEVER Be Removed

`TvDiscoveryService.discover()` enforces a strict 7,000-millisecond ceiling
on the entire scan window. When the deadline elapses, the flow closes
itself, which triggers `awaitClose` — that cancels every background
coroutine (mDNS listener, subnet sweep, cache-persist loop, and any
in-flight device-name fetches).

Removing or relaxing this timeout causes the scanner to run **infinitely**
in the background, consuming memory and battery. This was the v2.1.0
regression that v2.2.0 fixed. The constant `HARD_TIMEOUT_MS = 7000L` is
intentional and must not be raised above 10,000ms without explicit owner
sign-off.

### 2.5 Two-Destination UI With Bottom NavigationBar

The app has **two top-level destinations**: the Connection screen and the
Terminal screen. Navigation is via a `NavigationBar` at the bottom of the
screen that hides when the user scrolls down and shows when they scroll up.
The Gear `IconButton` in the `TopAppBar.actions` slot opens a
`ModalBottomSheet` settings overlay. Do not add additional top-level
destinations beyond these two. The Settings content is configuration, not
navigation — it stays in the ModalBottomSheet.

The bottom NavigationBar uses `AnimatedVisibility` with `slideInVertically`/
`slideOutVertically` to provide a smooth hide/show animation. The scroll
direction is detected by comparing consecutive scroll positions with a
threshold of 10 pixels.

### 2.6 Dual Activity-Alias Share Targets

`AndroidManifest.xml` declares two `activity-alias` entries —
`.ShareReceiverManual` and `.ShareReceiverAuto` — both targeting
`.ShareReceiverActivity`. `ShareReceiverActivity.onCreate()` inspects
`intent.component.className` to determine which alias the system used and
forces the corresponding mode (interactive dialog vs auto-fire), overriding
the persisted `autoExecute` setting.

Do not collapse these into a single alias. The whole point is that the user
sees **two distinct entries** in the native Android share menu and can
choose per-share without entering the app.

---

## 3. Token Dictionary

Preset command templates support three dynamic substitution tokens. They are
substituted by `AdbManager.prepareCommand()` (for URL shares) or
`AdbManager.prepareFileCommand()` (for file shares) immediately before shell
execution.

### `{URL}`

- **Substituted with**: A shell-escaped single-quoted URL string.
- **Source**: For text shares, `AdbManager.extractUrl()` pulls the first
  http/https/magnet URL out of `Intent.EXTRA_TEXT`. For file shares, the
  phone's local HTTP server URL (`http://<phone-ip>:<port>/file.<ext>`).
- **Shell safety**: The URL is wrapped in single quotes via
  `shellEscape()` after any accidental surrounding quotes are stripped via
  `stripQuotesAroundToken()`.
- **Example**: `am start -a android.intent.action.VIEW -d {URL}` →
  `am start -a android.intent.action.VIEW -d 'https://youtu.be/abc123'`

### `{MIME}`

- **Substituted with**: A shell-escaped MIME type string (no quotes added
  by the shell because MIME types contain no special characters, but the
  value is still routed through `shellEscape()` for defense in depth).
- **Source**: `AdbManager.resolveMimeType(intentMimeType, fileName)` returns
  one of `image/*`, `video/*`, `audio/*`, or `*/*` based on the incoming
  intent MIME type and (if absent) the file extension parsed from
  `Intent.EXTRA_STREAM`'s display name.
- **Why wildcard**: The TV's intent resolver picks the right handler for
  the wildcard category. Hardcoding `video/mp4` would break sharing a
  YouTube link to SmartTube, for example.
- **Example**: `am start -a android.intent.action.VIEW -d {URL} -t {MIME}`
  → `am start -a android.intent.action.VIEW -d 'https://...' -t video/*`

### `{FILE}`

- **Substituted with**: The absolute remote path on the TV where the file
  was pushed via `AdbManager.pushFileSmall()` (typically
  `/sdcard/adb_commander_share.<ext>`).
- **Only valid in file-share contexts**: If a preset uses `{FILE}` and the
  share is a URL, that preset is disabled in the manual dialog and skipped
  in auto-execute.
- **Size limit**: `pushFileSmall()` base64-encodes the file and writes it
  via `echo <base64> | base64 -d > /sdcard/...`. Practical limit is ~2 MB
  before the ADB shell command exceeds the protocol's command-length cap.
  For larger files, presets should use `{URL}` which streams via the local
  HTTP server.

### Token stripping and escaping order

The substitution pipeline is:
1. `stripQuotesAroundToken(template, "{URL}")` — removes any accidental
   `"{URL}"` or `'{URL}'` left over from user-edited presets.
2. `shellEscape(resolvedUrl)` — wraps the value in single quotes and
   escapes any embedded single quotes.
3. `template.replace("{URL}", escapedValue)` — final substitution.

This order matters. Swapping steps 1 and 2 would double-escape user-entered
quotes. Do not change the order.

---

## 4. Device Discovery Workflow

`TvDiscoveryService.discover()` returns a cold `Flow<List<DiscoveredTv>>`
that performs a dual-tier scan with a hard 7-second ceiling. The flow is
collected inside `ConnectionTab` via `lifecycleOwner.lifecycleScope.launch`
and cancelled on `onDispose`.

### Tier 1 — mDNS (seconds 0–3)

- Uses `NsdManager.discoverServices("_adb-tls-connect._tcp.", PROTOCOL_DNS_SD, listener)`.
- Every Android 11+ TV with wireless debugging enabled broadcasts this
  service type. The mDNS `serviceName` is often a hex string (not the real
  device name), so we treat it as a **placeholder** and immediately trigger
  name enrichment (see below).
- `onServiceFound` → `resolveService` → `onServiceResolved` extracts
  `info.host` and `info.port`. A new `DiscoveredTv` is created with
  `source = "mdns"` and inserted into the `seen` map keyed by host IP.

### Tier 2 — Subnet Sweep (kicks off at second 3 if mDNS found nothing)

- Computes the phone's local IPv4 via `WifiManager.connectionInfo.ipAddress`
  with a `NetworkInterface` enumeration fallback.
- Probes every address in `<subnet>.1..254` on port 5555 with a 500ms TCP
  connect timeout, 50 coroutines in flight at a time via
  `kotlinx.coroutines.sync.Semaphore(50)`.
- Each host that accepts the TCP connection is registered as a
  `DiscoveredTv` with `source = "scan"` and a placeholder name
  `Android TV (<host>)`.

### Name Enrichment (parallel, non-blocking)

For every freshly discovered device (both tiers), `enrichDeviceName()` is
fired on a `SupervisorJob + Dispatchers.IO` scope. It runs:

1. `settings get global device_name` (the user-set name shown in TV
   Settings → Device Preferences → About → Device name). Wrapped in a
   2.5-second `withTimeoutOrNull`.
2. If the primary returns blank or the literal string `"null"`, falls back
   to `getprop ro.product.model` (the marketing model name, e.g. "BRAVIA
   VH2" or "Chromecast").
3. On success, replaces the placeholder in `seen[host]` via `.copy(name = resolved)`
   and invokes the `onResolved` callback, which calls `trySend(emitSorted())`
   on the active channel so the UI updates immediately.

The supervisor scope means a single name-fetch failure does not cancel
sibling fetches. When the hard timeout fires, `awaitClose` cancels the
entire supervisor scope, which cancels every in-flight name fetch — **no
thread leaks**.

### Hard Timeout (second 7)

- `hardTimeoutJob` calls `kotlinx.coroutines.delay(7000L)` then
  `persistCache()` (writes the current `seen` map to
  `discovered_tvs_cache.json` in `context.filesDir`) and `close()` on the
  channel.
- Closing the channel triggers `awaitClose`, which cancels `sweepJob`,
  `persistJob`, `hardTimeoutJob`, `fetchScope` (cancelling all in-flight
  name fetches), and calls `nsdManager.stopServiceDiscovery(listener)`.

### Cache

- On the next `discover()` call, the cache is loaded synchronously and
  emitted instantly so the UI is never empty.
- Cached devices are marked `source = "cached"` and shown with an amber
  status dot in the device list.
- `forgetDevice(host)` removes a single entry; `clearCache()` wipes all.

### Lifecycle

`ConnectionTab` uses a `DisposableEffect(lifecycleOwner)` to start the scan
on `ON_START` and cancel it on `onDispose`. The "Rescan" button cancels
the current job and starts a fresh one. Because the flow self-terminates
after 7 seconds, a forgotten `DisposableEffect` cannot leak — but the
explicit cancellation on dispose is still in place as defense-in-depth.

---

## 5. UI Architecture

### 5.1 MainScreen

```
Scaffold
├── TopAppBar
│   ├── title = "ADB Commander"
│   └── actions = [ Gear IconButton → opens ModalBottomSheet ]
├── content = when(activeTab) { 0 → ConnectionTab(); 1 → TerminalTab() }
└── bottomBar = NavigationBar (hides on scroll-down, shows on scroll-up)
    ├── NavigationBarItem(Connect)
    └── NavigationBarItem(Terminal)
```

The NavigationBar uses `AnimatedVisibility` with slide animations for
smooth hide/show on scroll. When switching tabs, the bar is always visible.

### 5.2 HomeTab (v2.7.0)

The primary operational surface. Scrollable `Column` containing, in order:

1. **Connection Status card** (GlassCard) — always visible at top. Shows
   connected TV name + host:port with a green dot, or "No TV connected"
   with a red dot. Includes a Test button. If the bridge service is already
   running, the connection is trusted without re-testing (faster startup).
2. **TV Discovery** (only when not connected) — scan for TVs on the network.
   Only auto-scans if no TV is configured and bridge is not running.
3. **Quick Remote** (expandable, only when connected) — D-pad and navigation
   buttons for basic TV remote control.
4. **TV Apps Grid** (expandable, only when connected) — scans TV for installed
   third-party packages via `pm list packages -3`, renders a `LazyVerticalGrid`
   (2 columns). Each grid item shows the app's short name with a copy button
   for the full package name. Tapping an app opens a popup Dialog showing:
   - Package name with copy button (for pasting into Quick Command)
   - Preset creation fields (name + command template)
   - Launch and Save buttons
   - Existing presets for that app (with delete option)
5. **Quick Command** (only when connected) — text field for shell commands.
   Run button executes the command. Save Preset button appears after a
   successful run, allowing the command to be saved as a preset.
   Package names can be copied from the grid above and pasted here.
6. **Custom Presets overview** (compact, only if custom presets exist) —
   shows user-created presets grouped by app package.

**Key v2.7.0 changes**:
- Discovery scan ONLY runs if no TV is configured. If the bridge is running
  and a TV host is saved, no scan is triggered on app launch or tab switch.
- Connection test is skipped if `AdbForegroundService.isRunning()` — the
  running bridge is trusted, eliminating the 10-second test on cold start.
- TV name is persisted via `SettingsManager.setSelectedTvName()` when a TV
  is selected from discovery, and loaded immediately on app launch.
- All presets (built-in + custom) are shown in the Quick Settings preset
  picker, not just custom presets.
- The `GlassCard` composable provides a frosted-glass aesthetic using
  semi-transparent surface colors.

### 5.2b TerminalTab (v2.3.0)

A pure ADB shell that runs commands directly on the TV. Contains:

1. **Target status bar** — shows connected TV name:port or "No TV — go to
   Connect tab" with colored indicator dot.
2. **Quick-action chips** — `FilterChip` strip with icons for common TV
   operations: Home, OK, Back, Volume Up/Down, Power, Model, Android
   version, Resolution, Awake check, Apps list, Reboot.
3. **Output scrollback** — dark terminal background (#0D1117) with
   color-coded lines: cyan for commands, light gray for output, soft red
   for errors, dim for system messages. Auto-scrolls to newest output.
4. **Input bar** — monospace text field with "$" prompt, Send button
   (filled icon button), and history navigation (up/down arrows).

### 5.3 SettingsSheet (ModalBottomSheet)

Opened via the Gear IconButton. Contains a scrollable `Column` with a
header row (gear icon + "Settings" title + close X button) followed by:

1. **Manual Connection** (expandable) — IP/port text fields + Test button
   for manual TV entry. Collapsed by default since discovery handles most cases.
2. **Appearance** (expandable) — theme mode selector (System/Light/Dark).
3. **Backup & Restore Presets** — Export copies custom presets JSON to clipboard;
   Import opens a dialog to paste JSON.
4. **About** (expandable) — app version, build number, changelog, GitHub link.

### 5.4 ShareReceiverActivity (Dual Aliases)

Two `activity-alias` entries in `AndroidManifest.xml` route the system
share intent to the same `ShareReceiverActivity`:

- `.ShareReceiverManual` → label "ADB Commander (Manual)" → always shows
  the interactive verification dialog with the preset list.
- `.ShareReceiverAuto` → label "ADB Commander (Auto-Execute)" → skips the
  dialog and immediately fires the saved selected preset.

`ShareReceiverActivity.onCreate()` reads `intent.component.className` and
sets `forcedAutoExecute`:

- ends with `.ShareReceiverAuto` → `true`
- ends with `.ShareReceiverManual` → `false`
- otherwise → `null` (legacy path, falls back to persisted setting)

The `forcedAutoExecute` value is passed to `ShareReceiverDialog`, whose
`LaunchedEffect` uses `forcedAutoExecute ?: settings.getAutoExecute()` to
decide whether to auto-fire.

### 5.5 Theme

Material3, edge-to-edge, dynamic color off (uses the default Material3
baseline). The transparent theme `Theme.ADBCommander.Transparent` is used
by `ShareReceiverActivity` for the floating-dialog effect.

---

## 6. Build & CI

- **Build system**: Gradle (Kotlin DSL), AGP via version catalog
  (`gradle/libs.versions.toml`).
- **Min SDK**: 24 (Android 7.0) — required for Conscrypt TLS 1.3 used in
  ADB pairing.
- **Target SDK**: 35 (Android 15).
- **Java/Kotlin target**: 11.
- **CI**: `.github/workflows/build.yml` triggers on push to `main`/`master`
  and on `workflow_dispatch`. Runs `./gradlew assembleDebug` on
  `ubuntu-latest` with JDK 17 (Temurin). Uploads `app-debug.apk` as a
  build artifact.
- **No local builds**: The project is built exclusively via GitHub Actions.
  Do not attempt to build locally unless explicitly debugging a CI-only
  failure.

---

## 7. File Map

| File | Role |
|------|------|
| `app/src/main/java/com/adbcommander/MainActivity.kt` | Two-destination Compose UI with bottom nav. `MainScreen` (NavigationBar with hide-on-scroll), `ConnectionTab`, `TerminalTab`, `SettingsSheet`, `DiscoveredTvRow`, `PackageRow`, `LogEntryRow`, `SectionHeader`, `TerminalLine`. |
| `app/src/main/java/com/adbcommander/TvDiscoveryService.kt` | Dual-tier TV discovery (mDNS + subnet sweep) with 7s hard timeout and live device-name enrichment via ADB shell. |
| `app/src/main/java/com/adbcommander/AdbManager.kt` | ADB connection management, shell execution, URL/file/mime token substitution, file push, HTTP file server lifecycle. |
| `app/src/main/java/com/adbcommander/ShareReceiverActivity.kt` | Translucent dialog activity launched from the share sheet. Detects alias and forces manual/auto mode. |
| `app/src/main/java/com/adbcommander/SettingsManager.kt` | DataStore-backed settings + SharedPreferences-backed custom presets. JSON export/import. |
| `app/src/main/java/com/adbcommander/AdbForegroundService.kt` | Persistent foreground service keeping the process warm. `connectedDevice` FGS type. `START_STICKY` + `onTaskRemoved` self-restart. |
| `app/src/main/java/com/adbcommander/AdbTileService.kt` | Quick Settings tile for one-tap toggle of the bridge service. |
| `app/src/main/java/com/adbcommander/FileServer.kt` | In-process HTTP server for streaming local `content://` URIs to the TV. **DO NOT MODIFY** — see §2.1. |
| `app/src/main/java/com/adbcommander/CommandLogStore.kt` | JSON file-backed execution log store. |
| `app/src/main/java/com/adbcommander/App.kt` | Application class. Installs Conscrypt TLS provider at position 1 for ADB TLS 1.3 support on Android 7-8. |
| `app/src/main/AndroidManifest.xml` | Declares `MainActivity` (launcher), `ShareReceiverActivity` (target of two activity-aliases), `AdbForegroundService`, `AdbTileService`. |

---

## 8. Change Protocol for Future AI Sessions

Before opening a pull request against this repository, an AI agent MUST:

1. **Read this file end-to-end.** No exceptions, no skimming.
2. **Re-read §2 (Core Constraints).** Any change that touches the local
   HTTP file server, the 7-second hard timeout, the single-screen UI, the
   dual activity-alias structure, or the background-IO-threading rule
   requires explicit owner sign-off in the PR description.
3. **Search for `// AI AGENT NOTE:` comments in the source files.** These
   inline comments mark the specific lines that must not be modified
   without understanding why they exist. Modifying or deleting these
   comments is itself a code-review red flag.
4. **Run the build via GitHub Actions.** Do not claim success based on a
   local build. The CI workflow is the source of truth.
5. **Update this file** if your change adds, removes, or modifies a core
  architectural mechanism. A PR that changes the architecture without
  updating `developer-context.md` is incomplete.

---

**End of architectural reference.**
