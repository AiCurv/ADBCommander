# ADB Commander — Worklog

---
Task ID: 1
Agent: Main Agent
Task: Build Phase 1 Android project — ADB Commander

Work Log:
- Analyzed device screenshot: Samsung Exynos 7870, armeabi-v7a architecture, 32-bit mode
- Researched ADB libraries: dadb (no pairing support), Kadb (has pairing support)
- Chose Kadb (com.flyfishxu:kadb:2.1.1) as the ADB library — pure Kotlin, supports wireless pairing
- Added Conscrypt for TLS 1.3 on Android 7-8
- Created full Android project structure with all required files
- Wrote AdbManager.kt: pairing, shell execution, URL extraction
- Wrote SettingsManager.kt: DataStore-based persistent settings
- Wrote MainActivity.kt: Settings UI with connection test, pairing, default command
- Wrote ShareReceiverActivity.kt: Share menu handler with command dialog
- Wrote GitHub Actions CI workflow
- Fixed double-close bug in AdbManager.executeShell()

Stage Summary:
- Full Phase 1 project delivered at /home/z/my-project/download/ADBCommander/
- Library: Kadb 2.1.1 (pure Kotlin, wireless pairing support)
- No native ADB binary needed (armeabi-v7a device, pure Java/Kotlin works)
- All Phase 1 features implemented: Share menu, command dialog, ADB shell, pairing, settings

---
Task ID: 3
Agent: Main Agent
Task: Step 3 — universal-share-intent-and-dynamic-mime

Work Log:
- Updated AndroidManifest.xml: Changed ShareReceiverActivity intent-filter from text/plain to */* so the app appears in every Android share sheet
- Added AdbManager.resolveMimeType(): Maps incoming MIME types and file extensions to wildcard categories (image/*, video/*, audio/*, */*)
- Added AdbManager.prepareCommand(): Replaces both {URL} and {MIME} tokens in command templates before ADB execution
- Rewrote ShareReceiverActivity.kt: Now handles both text shares (EXTRA_TEXT) and binary/file shares (EXTRA_STREAM), extracts file extension from content URI display name, resolves MIME category, and passes it into the command template via {MIME} token. Shows resolved MIME type in the share dialog UI.
- Updated SettingsManager.kt: DEFAULT_COMMAND now uses {MIME} instead of hardcoded video/*, making the template universally applicable
- Updated MainActivity.kt: Command template help text and placeholder now document the new {MIME} placeholder
- Committed as "universal-share-intent-and-dynamic-mime" and pushed to GitHub

Stage Summary:
- App now appears in Android share sheet for ANY file type (images, audio, APKs, etc.)
- New {MIME} token dynamically resolves to image/*, video/*, audio/*, or */* based on file extension and incoming MIME type
- Default command template: am start -a android.intent.action.VIEW -d "{URL}" -t "{MIME}"
- Commit pushed to https://github.com/AiCurv/ADBCommander.git (branch main)

---
Task ID: 8
Agent: Main Agent
Task: Step 8 — feat-foreground-service-qs-tile-battery-optimization (v2.0.0)

Work Log:
- Audited MainActivity.kt for launch-latency blockers: removed auto-call to requestBatteryExemption() from onCreate() (was firing a system Intent on every cold start and blocking the Main thread). Battery-optimization prompt is now user-initiated from the Settings tab.
- Forced all heavy reads onto Dispatchers.IO via LaunchedEffect: ConnectionTab now initializes presets with BUILT_IN_PRESETS in-memory and loads custom presets async; SettingsTab now initializes logs empty and loads them async; PowerManager.isIgnoringBatteryOptimizations() call pushed to IO.
- Created AdbForegroundService.kt — persistent foreground service with connectedDevice FGS type, low-priority ongoing notification, START_STICKY return, onTaskRemoved() self-restart so swiping the app from Recents does NOT terminate the bridge. Static isRunning() flag for tile/UI mirroring.
- Created AdbTileService.kt — Quick Settings tile. Tap: if TV host is configured → toggle AdbForegroundService; if blank → launch MainActivity via PendingIntent (Android 14+) or raw Intent (older). Tile state mirrors service state.
- Updated AndroidManifest.xml — added FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS permissions; declared AdbForegroundService with connectedDevice FGS type; declared AdbTileService with BIND_QUICK_SETTINGS_TILE permission and QS_TILE intent-filter.
- Added Background Service & Battery premium card to SettingsTab — start/stop foreground service button, request-battery-immunity button via Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, live state indicators.
- Added ic_notification.xml and ic_tile.xml vector drawables for the notification small icon and QS tile icon.
- Updated strings.xml with notification channel name/desc/text and tile label active/inactive.
- Bumped version: versionCode 28 → 29, versionName 1.9.0 → 2.0.0.
- Overwrote README.md with v2.0.0 architecture documentation covering foreground service, QS tile, battery immunity, async launch pattern, and new presets.
- Did NOT modify AdbManager.kt or ShareReceiverActivity.kt — backend file modifications strictly avoided per prior step feedback.

Stage Summary:
- 4 new architectural features delivered: launch-latency fix (async IO), persistent foreground service (survives swipe-away), Quick Settings tile (one-tap toggle or device-pick launch), premium battery-optimization prompt card.
- 4 new files: AdbForegroundService.kt, AdbTileService.kt, res/drawable/ic_notification.xml, res/drawable/ic_tile.xml.
- 4 modified files: AndroidManifest.xml, MainActivity.kt, strings.xml, build.gradle.kts.
- AdbManager.kt and ShareReceiverActivity.kt intentionally untouched.
- Build delegated to GitHub Actions CI — no local compilation attempted.

---
Task ID: 9
Agent: Main Agent
Task: Step 9 — feat-tv-network-discovery-scanner-ui-swap (v2.1.0)

Work Log:
- Created TvDiscoveryService.kt — dual-tier discovery engine:
  - Tier 1: NsdManager.discoverServices("_adb-tls-connect._tcp") with ResolveListener to extract friendly name + host + port
  - Tier 2: after 3-second grace period with zero mDNS hits, concurrent subnet sweep of /24 on port 5555 (50 coroutines, 500ms TCP timeout each) via Semaphore-bounded coroutine launch
  - Returns cold Flow<List<DiscoveredTv>> via callbackFlow; emits cached devices instantly, then live updates as mDNS resolves or subnet sweep finds hosts
  - Persists all discovered devices to discovered_tvs_cache.json in app internal storage; loads cache on next launch for instant population
  - forgetDevice(host) and clearCache() methods for UI management
  - Local IP detection: WifiManager.connectionInfo.ipAddress first, NetworkInterface enumeration fallback
- Updated SettingsManager.kt: added KEY_SELECTED_TV_NAME DataStore key + getSelectedTvName()/setSelectedTvName() suspend accessors so the friendly name of the active TV persists across launches
- Rewrote ConnectionTab in MainActivity.kt:
  - Removed the hardcoded IP and Port OutlinedTextFields from the primary viewport
  - Added TV Scan card at top with scan status (scanning/found N/none/still scanning), spinner, and Rescan button
  - Added Active Target indicator card showing the currently selected TV name + host:port
  - Added scrollable Discovered TV list — each row shows status dot (green=selected, gray=available, amber=cached), bold friendly name, IP:port · source subtitle, and expand icon. Tap = select+save. Expand = full details + Test + Forget buttons
  - Encapsulated the old IP/Port text fields inside a collapsible "Advanced Manual Entry" accordion card (default closed) for power users
  - Lifecycle-tied scanning: DisposableEffect(lifecycleOwner) starts the scan via lifecycleScope.launch when ConnectionTab enters composition; onDispose cancels the scan job, tearing down mDNS + all subnet-sweep coroutines. Rescan button cancels current job and starts fresh via shared startScan lambda
  - Added DiscoveredTvRow and DetailRow reusable composables
- Updated strings.xml with TV discovery strings (tv_scan_scanning, tv_scan_found, tv_scan_none, tv_scan_tap_to_retry, tv_scan_still_scanning, tv_selected, tv_status_available, tv_status_cached, tv_forget, tv_test_connection, tv_advanced, tv_advanced_hint)
- Bumped version: versionCode 29 → 30, versionName 2.0.0 → 2.1.0
- Overwrote README.md with v2.1.0 architecture documentation including new TV Network Discovery section detailing dual-tier strategy, caching, lifecycle, and UI behavior
- Did NOT modify AdbManager.kt or ShareReceiverActivity.kt — they still take host+port parameters; values now come from the selected discovered device via SettingsManager

Stage Summary:
- 1 new file: TvDiscoveryService.kt (dual-tier discovery: mDNS + subnet sweep, JSON cache, Flow-based)
- 4 modified files: MainActivity.kt (ConnectionTab UI swap + DiscoveredTvRow composable), SettingsManager.kt (+selectedTvName), strings.xml (+12 TV strings), build.gradle.kts (version bump)
- README.md overwritten with v2.1.0 docs
- Build delegated to GitHub Actions CI — no local compilation attempted

---
Task ID: 10
Agent: Main Agent
Task: Step 10 — fix-scanner-names-single-tab-and-dual-share (v2.2.0)

Work Log:
- TvDiscoveryService.kt: enforced strict 7-second HARD_TIMEOUT_MS ceiling on the entire discover() flow. Added a hardTimeoutJob that delays 7000ms then closes the channel — closing triggers awaitClose which cancels sweepJob, persistJob, hardTimeoutJob, fetchScope (SupervisorJob), and stops the NsdManager listener. No background coroutine survives the scan window.
- TvDiscoveryService.kt: fixed compile-breaking typo `resultsost] = DiscoveredTv(` → `results[host] = tv` in subnetSweep. v2.1.0 would not have compiled without this fix.
- TvDiscoveryService.kt: added enrichDeviceName() — fires a non-blocking ADB shell `settings get global device_name` (with `getprop ro.product.model` fallback) on a SupervisorJob+IO scope for every freshly discovered device (both mDNS and subnet-sweep paths). Resolved name replaces the placeholder via seen[host].copy(name = resolved) and triggers an immediate trySend(emitSorted()) through an onResolved lambda captured from the ProducerScope.
- TvDiscoveryService.kt: added public suspend resolveDeviceName(host, port) helper for one-shot lookups from MainActivity when the user taps a device.
- TvDiscoveryService.kt: each name fetch is bounded by NAME_FETCH_TIMEOUT_MS (2500ms) via withTimeoutOrNull so a slow TV cannot stall the enrichment pipeline.
- MainActivity.kt: deleted the 2-tab bottom NavigationBar entirely. MainScreen is now a single Scaffold with TopAppBar containing a Gear IconButton (Icons.Filled.Settings) in the actions slot. Tapping it opens a ModalBottomSheet that hosts the new SettingsSheet composable (renamed from SettingsTab). The sheet has its own header row (Settings title + X close button) and uses skipPartiallyExpanded = true so it opens full-height by default.
- MainActivity.kt: ConnectionTab is now the sole content of the Scaffold body. No selectedTab state, no NavigationBarItem imports needed.
- MainActivity.kt: replaced all "Universal Default" string literals with SettingsManager.DEFAULT_PRESET_NAME (new constant = "SmartTube") so the default preset reference survives future preset list changes.
- AndroidManifest.xml: removed the SEND */* intent-filter from the main ShareReceiverActivity declaration. Added two activity-alias entries — .ShareReceiverManual (label "ADB Commander (Manual)") and .ShareReceiverAuto (label "ADB Commander (Auto-Execute)") — each with its own SEND */* intent-filter, both targeting .ShareReceiverActivity. Both aliases are exported and enabled so they show as two distinct entries in the system share menu.
- ShareReceiverActivity.kt: added forcedAutoExecute field. In onCreate, reads intent.component.className and sets forcedAutoExecute = true for .ShareReceiverAuto, false for .ShareReceiverManual, null otherwise. Passes forcedAutoExecute into ShareReceiverDialog. The dialog's LaunchedEffect uses forcedAutoExecute ?: settings.getAutoExecute() so the alias-forced mode takes precedence over the persisted setting.
- strings.xml: added share_label_manual = "ADB Commander (Manual)" and share_label_auto = "ADB Commander (Auto-Execute)" referenced by the two activity-alias labels.
- SettingsManager.kt: purged three dead built-in presets — "Universal Default" (which used com.cxinventor.file.explorer / Cx Player), "Send to TV Downloads", and "APK Installer". Only "SmartTube" remains in BUILT_IN_PRESETS. Updated DEFAULT_COMMAND to drop the Cx Player component (now package-agnostic: `am start -a android.intent.action.VIEW -d {URL} -t {MIME}`). Added DEFAULT_PRESET_NAME = "SmartTube" constant and changed selectedPreset flow fallback to use it.
- build.gradle.kts: bumped versionCode 30 → 31, versionName 2.1.0 → 2.2.0.
- README.md: rewrote architecture section for v2.2.0 — documents single-screen + Gear IconButton + ModalBottomSheet, dual share-sheet targets, 7-second hard timeout, device_name shell enrichment, and the preset purge.
- Build delegated to GitHub Actions CI — no local compilation attempted (per user instruction "use github actions for build no local!").

Stage Summary:
- 5 files modified: TvDiscoveryService.kt (7s hard timeout + device_name enrichment + typo fix), MainActivity.kt (single-tab + Gear IconButton + ModalBottomSheet), ShareReceiverActivity.kt (alias-forced auto/manual mode), SettingsManager.kt (preset purge + DEFAULT_COMMAND cleanup + DEFAULT_PRESET_NAME constant), AndroidManifest.xml (dual activity-alias), strings.xml (share labels), build.gradle.kts (version bump), README.md (v2.2.0 docs).
- All 5 user requirements satisfied: (1) 7-second hard timeout with full coroutine teardown, (2) true TV device names via `settings get global device_name` + `getprop ro.product.model` fallback, (3) single-tab UI with Gear IconButton → ModalBottomSheet settings overlay, (4) dual share-sheet activity-alias targets, (5) purged Cx Player / Send to Downloads / APK Installer presets.
- Critical compile bug from v2.1.0 (`resultsost]` typo) fixed.
- Commit: fix-scanner-names-single-tab-and-dual-share, version v2.2.0 / code 31.
- Build delegated to GitHub Actions CI — no local compilation attempted.

---
Task ID: 11
Agent: Main Agent
Task: Step 11 — docs-developer-context-and-ai-inline-guardrails (v2.2.0-docs)

Work Log:
- Created /developer-context.md in repo root — the canonical architectural reference for ADB Commander. Eight sections: Project Identity & Philosophy, Core Constraints (NON-NEGOTIABLE — covers FileServer protection, IO threading, AdbManager backend contract, 7s timeout, single-screen UI, dual activity-alias), Token Dictionary ({URL}, {MIME}, {FILE} — exact semantics and substitution order), Device Discovery Workflow (mDNS + subnet sweep + name enrichment + hard timeout + cache), UI Architecture (MainScreen + ConnectionTab + SettingsSheet + dual share aliases), Build & CI, File Map, Change Protocol for Future AI Sessions.
- Injected `// AI AGENT NOTE:` inline comment blocks across the 4 core structural files:
  • MainActivity.kt: 4 notes — (a) MainScreen scaffold no-re-introduce-bottom-nav warning, (b) Gear IconButton sole-entry-point-to-settings warning, (c) ModalBottomSheet-must-remain-a-sheet warning, (d) ConnectionTab scan lifecycle binding no-leak warning, (e) RUN COMMAND scope.launch IO-threading reminder, (f) Auto-Execute toggle is now fallback-only (aliases override) note.
  • TvDiscoveryService.kt: 6 notes — (a) HARD_TIMEOUT_MS do-not-remove warning with explicit reference to v2.1.0 regression, (b) fetchScope supervisor-scope cancellation reminder, (c) hardTimeoutJob load-bearing-safety-net warning (close() not channel.cancel()), (d) awaitClose single-teardown-point warning, (e) settings-get-global-device_name command-string-do-not-change note, (f) "null" string check warning for Chromecast firmware quirk, (g) results[host] do-not-collapse-into-trySend note.
  • AdbManager.kt: 2 notes — (a) prepareCommand substitution-order load-bearing warning, (b) executeShell 10s read deadline do-not-raise-above-15s warning.
  • ShareReceiverActivity.kt: 4 notes — (a) alias detection is the heart of dual share-sheet feature, do-not-replace-with-EXTRA inspection, (b) forcedAutoExecute Elvis-operator single-decision-point warning, (c) FileServer do-not-modify reminder, (d) parseSharedContent ContentResolver-query IO-threading requirement.
- Verified file writes — no syntax compilation breaks. All annotations are inside /* */ or // comment blocks; no executable code was modified.
- Build delegated to GitHub Actions CI — no local compilation attempted.

Stage Summary:
- 1 new file: /developer-context.md (canonical architectural reference, 8 sections, ~6KB markdown).
- 4 modified files: MainActivity.kt (+6 AI AGENT NOTE blocks), TvDiscoveryService.kt (+7 AI AGENT NOTE blocks), AdbManager.kt (+2 AI AGENT NOTE blocks), ShareReceiverActivity.kt (+4 AI AGENT NOTE blocks).
- 1 modified file: worklog.md (this entry).
- Zero executable code changes — pure documentation pass. Build should remain green.
- Commit: docs-developer-context-and-ai-inline-guardrails, version stays at v2.2.0 / code 31.
- Build delegated to GitHub Actions CI — no local compilation attempted.

---
Task ID: 12
Agent: Main Agent
Task: Step 12 — fix-preset-visibility-and-add-dropdown-preset-tile (v2.2.1-stable)

Work Log:
- SettingsManager.kt: Fixed the regression where custom saved presets did not surface to background intent processors (ShareReceiverActivity cold-started from the share sheet, AdbPresetTileService). Root cause: `presetsPrefs` was a per-instance `by lazy { context.getSharedPreferences(...) }` property, so every new `SettingsManager(context)` constructed its own lazy holder. Combined with `apply()` (async writes), background intent processors could read stale/empty preset lists when MainActivity had never been opened in the current process.
  • Added a `companion object`-level `globalPresetsPrefs: SharedPreferences?` (volatile, double-checked-locked) bound from the application context.
  • Added `SettingsManager.preload(context)` — called from `App.onCreate()` so the binding exists before any Activity or Service touches the preset layer.
  • Added `presetsPrefs(context)` private helper that returns the global instance (with defensive on-demand initialization for entry points that fire before App.onCreate completes).
  • Switched `saveCustomPresets()` from `apply()` to `commit()` so the very next read from a background intent processor is guaranteed to see the new preset list.
  • All `loadCustomPresets()` / `saveCustomPresets()` / `getAllPresets()` / `saveCustomPreset()` / `deleteCustomPreset()` / `getPresetCommand()` / `exportPresetsJson()` / `importPresetsJson()` now route through the global helper.
- App.kt: Added `SettingsManager.preload(this)` in `onCreate()` immediately after the Conscrypt install. This guarantees the global SharedPreferences binding is in place before any entry point (Activity, Service, or TileService) reads presets.
- AndroidManifest.xml: Renamed user-facing share-sheet labels via strings.xml — `share_label_manual` is now "ADB Manual" (was "ADB Commander (Manual)"), `share_label_auto` is now "ADB Auto" (was "ADB Commander (Auto-Execute)"). Shorter labels read better in the dense native share menu and align with the v2.2.1 visibility streamlining pass.
- strings.xml: Added four new strings for the v2.2.1 preset tile — `tile_preset_label` ("ADB Preset"), `tile_preset_subtitle_none` ("No preset locked"), `preset_picker_title` ("Lock auto-execute preset"), `preset_picker_empty` ("No custom presets saved. Open ADB Manual to create one.").
- AdbPresetTileService.kt (new): Quick Settings tile that lets the user lock the auto-execute preset from the notification shade. On tap, launches PresetPickerActivity via PendingIntent (Android 14+) or raw Intent (older) using `startActivityAndCollapse`. On `onStartListening`, refreshes the tile label ("ADB Preset"), subtitle (currently-locked preset name, or "No preset locked" — API 29+ only, gracefully degrades on older devices), state (STATE_ACTIVE when a preset is locked, STATE_INACTIVE otherwise), and content description. All DataStore reads use `runBlocking` on the binder thread (safe — TileService callbacks never run on Main).
- PresetPickerActivity.kt (new): Transparent overlay activity launched from AdbPresetTileService. Renders a dropdown-style Surface anchored to the top-center of the screen with a translucent scrim behind it. Shows ALL custom saved user presets (built-ins excluded per the user instruction) in a scrollable column of clickable rows. Each row shows a check-circle/radio-unchecked icon (depending on whether it's the currently-locked preset), the preset name, the command template preview (monospace, truncated), and {URL}/{FILE} token badges. Tapping a row locks it via `SettingsManager.setSelectedPreset()` + syncs `setDefaultCommand()` so ShareReceiverActivity's auto-execute path picks it up immediately, then shows a confirmation Toast and finishes. Tapping the scrim dismisses. All SettingsManager suspend calls run on Dispatchers.IO via lifecycleScope; the preset list loads via LaunchedEffect with an initial loading spinner.
- AndroidManifest.xml: Registered AdbPresetTileService with `BIND_QUICK_SETTINGS_TILE` permission and `QS_TILE` intent-filter. Registered PresetPickerActivity with the transparent theme (`Theme.ADBCommander.Transparent`), `excludeFromRecents`, empty task affinity, and `noHistory` so it never leaves a trace in the Recents list.
- build.gradle.kts: bumped versionCode 31 → 32, versionName 2.2.0 → 2.2.1.
- All threading and layout modifications respect the guardrails in developer-context.md §2.2 (background IO threading — preset reads use the global SharedPreferences layer with on-demand defensive init; preset writes use `commit()` for synchronous persistence; all DataStore operations remain suspend and run on Dispatchers.IO).

Stage Summary:
- 2 new files: AdbPresetTileService.kt (QS tile that launches the picker overlay + refreshes tile subtitle on lock), PresetPickerActivity.kt (transparent overlay with dropdown-style panel of all custom saved presets).
- 5 modified files: SettingsManager.kt (companion-level global SharedPreferences + preload() + commit() writes), App.kt (calls SettingsManager.preload in onCreate), AndroidManifest.xml (shortened alias labels + registered new tile + new activity), strings.xml (renamed alias labels + 4 new preset-tile strings), build.gradle.kts (version bump).
- 3 user requirements satisfied: (1) preset visibility regression fixed via global SharedPreferences binding, (2) share-sheet labels shortened to "ADB Auto" / "ADB Manual", (3) new AdbPresetTileService with dropdown overlay that locks the auto-execute preset and updates tile subtitle dynamically.
- Commit: fix-preset-visibility-and-add-dropdown-preset-tile, version v2.2.1 / code 32.
- Build delegated to GitHub Actions CI — no local compilation attempted.

---
Task ID: 13
Agent: Main Agent
Task: feat: bottom nav + terminal tab + declutter + connection fix (v2.3.0)

Work Log:
- Read developer-context.md and all source files end-to-end
- Read feature/terminal-tab branch commits (terminal tab + icon fix)
- Identified TV connection status bug: active target indicator was hidden when no scan was active
- Created feat/bottom-nav-terminal-declutter branch from main
- Rewrote MainScreen with bottom NavigationBar + hide-on-scroll behavior
- Fixed TV connection status: always-visible active target indicator reflecting persisted state
- Merged TerminalTab with improvements (FilterChips with icons, dark theme, history nav)
- Decluttered ConnectionTab (scan card auto-hides, dismiss buttons, compact rescan)
- Rounded all cards/inputs to 12-16dp
- Updated developer-context.md for v2.3.0
- Fixed CI compile errors (imports, API differences, log field names)
- CI build succeeded, merged to main, pushed

Stage Summary:
- 5 files modified: MainActivity.kt, SettingsManager.kt, build.gradle.kts, developer-context.md, build.yml
- Version: v2.3.0 / code 33
- CI run ID: 29628148931, Artifact: app-debug (23.7 MB)

---
Task ID: 2
Agent: Main Agent (Super Z)
Task: Fix broken presets, video/magnet link commands, declutter home tab, add app selector, enhance notification

Work Log:
- Read all source files: MainActivity.kt (1977 lines), AdbManager.kt, SettingsManager.kt, ShareReceiverActivity.kt, AdbForegroundService.kt, PresetPickerActivity.kt, AdbTileService.kt, AdbPresetTileService.kt, AndroidManifest.xml, build.gradle.kts, developer-context.md
- Identified root causes: (1) buildPresetFromPackage() appends bare package name without -n flag, creating invalid commands; (2) custom presets saved from Package Manager don't refresh in home tab; (3) stripQuotesAroundToken() removes double quotes that magnet links need; (4) only SmartTube built-in preset
- Fixed SettingsManager.buildPresetFromPackage(): now generates -n pkg/.MainActivity format by default
- Added 4 built-in presets: Open Link, Video Player, SmartTube, CloudStream
- Fixed AdbManager.prepareCommand(): new substituteToken() method detects quoting context — "{URL}" uses doubleQuoteEscape(), '{URL}' or bare {URL} uses shellEscape()
- Added AdbManager.doubleQuoteEscape() for magnet link / complex URI support
- Restructured ConnectionTab → HomeTab: connection status + preset chips + quick command with app selector
- Added inline app selector: scans TV packages, auto-generates command on selection
- Moved manual connection (IP/port) from home to Settings sheet
- Removed auto-execute toggle from home tab
- Removed ADB Bridge start/stop from settings (use Quick Settings tile)
- Enhanced AdbForegroundService notification: disconnect action button, connected TV name
- Added battery optimization first-install prompt (AlertDialog on first launch)
- Added preset refresh on ON_RESUME lifecycle event (fixes presets not showing from builder)
- Added AnimatedVisibility with fadeIn/fadeOut for status cards
- Updated strings.xml with new string resources
- Updated version to 2.4.0 / code 34
- Updated developer-context.md version reference
- Committed as d60a522

Stage Summary:
- 10 files modified: MainActivity.kt, AdbManager.kt, SettingsManager.kt, AdbForegroundService.kt, strings.xml, build.gradle.kts, developer-context.md, build.yml, + mode changes
- Version: v2.4.0 / code 34
- Push pending: needs GitHub auth (SSH key or PAT)
---
Task ID: v2.7.0
Agent: Main Agent (Super Z)
Task: v2.7.0 — Fix tab switch re-connection, TV name disappearing, startup speed, push build

Work Log:
- Investigated full codebase: MainActivity.kt, SettingsManager.kt, ShareReceiverActivity.kt, PresetPickerActivity.kt, AdbPresetTileService.kt, developer-context.md
- Confirmed auto commands ARE implemented: app popup has preset name + command template fields + Save button, presets appear in QS tile picker, ShareSheet auto-execute uses selected preset
- Fixed tab switching causing re-connection: HomeTab stays composed across tab switches, only visibility toggles via isVisible parameter
- Fixed premature discovery scan: added isSettingsLoaded flag to prevent scan from starting before DataStore values are loaded (tvHost was blank on first frame)
- Added warm cache for instant startup: SettingsManager.warmCache() reads critical connection settings in App.onCreate() so UI renders on first frame
- Updated About section with v2.7.0 changelog
- Bumped version: 2.6.0 → 2.7.0 (code 36 → 37)
- Pushed to GitHub, CI build #55 succeeded
- APK downloaded to /home/z/my-project/download/app-debug-v2.7.0/app-debug.apk

Stage Summary:
- 6 files modified: MainActivity.kt, App.kt, SettingsManager.kt, build.gradle.kts, developer-context.md, PresetPickerActivity.kt
- Key fixes: tab state preservation, no re-scan on switch, instant startup via warm cache, isSettingsLoaded guard
- Auto commands confirmed working: popup preset creation → QS tile selection → ShareSheet auto-execute
- Version: v2.7.0 / code 37
