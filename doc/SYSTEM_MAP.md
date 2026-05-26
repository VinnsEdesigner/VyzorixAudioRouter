# SYSTEM_MAP.md — Architecture Reference

## Document Purpose

The reference for the VyzorixAudioRouter service. It maps every component's role, lifecycle, dependencies, data flows, and failure boundaries. Use this document to understand how the daemon operates from APK install to steady-state, and how it survives crashes, soft reboots, and system interruptions.

---

## 1. Module Dependency Graph

```
┌─────────────────────────────────────────────────────────────────┐
│                         app/ (APK Module)                        │
│  - VyzorixApplication.kt                                         │
│  - VyzorixAppInitializer.kt                                      │
│  - BootstrapActivity.kt, ProjectionPermissionActivity.kt         │
│  - AppExitDispatcher.kt                                          │
│  - AndroidManifest.xml (Permissions, Receivers, Providers)       │
│  - Resources (Layouts, Drawables, XML configs)                   │
└──────────────────────────────┬──────────────────────────────────┘
                               │ depends on
           ┌───────────────────┼───────────────────┐
           ▼                   ▼                   ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ core/services/   │ │    core/ui/      │ │  core/common/    │
│ (Orchestration)  │ │ (Trampoline UI)  │ │   (Utilities)    │
│                  │ │                  │ │                  │
│ - accessibility/ │ │ - Activities     │ │ - constants/     │
│ - audio/         │ │ - Layouts        │ │ - enums/         │
│ - bootstrap/     │ │ - Themes         │ │ - extensions/    │
│ - capture/       │ │                  │ │ - model/         │
│ - foreground/    │ └────────┬─────────┘ │ - logging/       │
│ - diagnostics/   │          │           │ - concurrency/   │
│ - managers/      │          │           │ - audio/         │
│ - monitoring/    │          │           │ - device/        │
│ - playback/      │          │           │ - utils/         │
│ - updates/       │          │           └────────┬─────────┘
│ - voip/          │          │                    │
│ - scheduler/     │          │          ┌─────────┴─────────┐
│ - resilience/    │          │          ▼                   ▼
│ - stability/     │          │ ┌──────────────┐ ┌──────────────┐
│ - state/         │          │ │  core/data/  │ │core/audioengine│
│ - storage/       │          │ │ (Persistence)│ │  (Native)    │
│ - testing/       │          │ │              │ │              │
│ - security/      │          │ │ - database/  │ │ - cpp/       │
│ - compat/        │          │ │ - dao/       │ │ - include/   │
│ - provider/      │          │ │ - entity/    │ │ - JNI Bridge │
│ - receivers/     │          │ │ - converters/│ │ - Pipeline   │
│ - fallback/      │          │ │ - repository/│ │ - Safety     │
│ - headless/      │          │ └──────────────┘ └──────────────┘
│ - ipc/           │          │
│ - metrics/       │          │
│ - oem/           │          │
│ - permissions/
| -fcm/
| -websocket/      │          │
└──────────────────┘          │
```

### Dependency Rules

1. `core/common` has **zero dependencies** on other modules. It is the foundation.

2. `core/data` depends only on `core/common` (for models, constants, extensions).

3. `core/services` depends on `common`, `data`, and `audioengine`. It orchestrates them.

4. `core/audioengine` depends on `common` for constants and models. It is isolated from Room.

5. `app` is the **aggregation module** — it declares all dependencies and packs the final APK.

6. `core/ui` depends on `services` and `common` for permission flows and exit logic.

7. `core/services/updates` depends on `common/network`, `data/repository`, and `services/foreground` for notification updates.

---

## 2. Complete Startup Sequence (Corrected — Accessibility-First, No Icon Tap)

```
T+0s    ┌─────────────────────────────────────────────────────┐
        │  USER ACTION: Install APK via file manager/APK       │
        │  SYSTEM ACTION: App registers on launcher            │
        │  IMPORTANT: User NEVER taps launcher icon            │
        │  (Tapping icon triggers soft reboot on Nokia C22)    │
        │                                                      │
        │  USER ACTION: Opens Settings -> Accessibility        │
        │  - Sees "VyzorixAudioRouter" in the services list    │
        │  - Taps it -> sees two toggles:                      │
        │    1. "Enable VyzorixAudioRouter" (top)              │
        │    2. "Create overlay shortcut" (bottom)             │
        │  USER: Taps "Enable" -> Grants Accessibility         │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+1s    ┌─────────────────────────────────────────────────────┐
        │  SYSTEM ACTION: RouterAccessibilityService bound     │
        │  - onServiceConnected() fires                        │
        │  - AccessibilityStateTracker.markEnabled()           │
        │  - LauncherIconHider.nukeLauncherIcon()              │
        │    - Calls PackageManager.setComponentEnabledSetting │
        │    - Disables BootstrapActivity permanently          │
        │    - Launcher icon disappears from user's view       │
        │  - AppInfoConfig.hideOpenButton()                    │
        │    - Removes CATEGORY_LAUNCHER intent filter         │
        │    - Settings -> Apps now shows only:                │
        │      [Uninstall] [Disable] (no "Open" button)       │
        │  - Triggers VyzorixAppInitializer.execute()          │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+2s    ┌─────────────────────────────────────────────────────┐
        │  INITIALIZATION: VyzorixAppInitializer               │
        │  1. NotificationChannelManager.createChannels()      │
        │     - Creates "daemon_primary" (IMPORTANCE_LOW)      │
        │     - Creates "diagnostics" (IMPORTANCE_MIN)         │
        │     - Creates "updates" (IMPORTANCE_DEFAULT)         │
        │  2. DaemonDatabase.getInstance() + Migrations        │
        │  3. KeystoreManager.initialize()                     │
        │  4. AppConfig.load()                                 │
        │  5. PermissionAutoGranter.requestAll()               │
        │     - POST_NOTIFICATIONS (auto-granted on enable)    │
        │     - SYSTEM_ALERT_WINDOW (overlay, if user enabled) │
        │     - REQUEST_INSTALL_PACKAGES (for future updates)  │
        │     - Verifies manifest-granted:                     │
        │       MODIFY_AUDIO_SETTINGS, RECEIVE_BOOT_COMPLETED, │
        │       FOREGROUND_SERVICE, INTERNET, ACCESS_NETWORK   │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+3s    ┌─────────────────────────────────────────────────────┐
        │  BOOTSTRAP: TrampolineService starts                 │
        │  - BootstrapCoordinator.begin()                      │
        │  - PermissionStateMachine.initState(ACCESS_GRANTED)  │
        │  - Checks: MediaProjection token cached?             │
        │    - YES: Jump to T+6s                               │
        │    - NO: Proceed to T+4s                             │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+4s    ┌─────────────────────────────────────────────────────┐
        │  PERMISSION RE-GRANT BY AUTOMATION DEEMON            │
        │  - ProjectionPermissionActivity.launch()             │
        │  - DialogRecognitionEngine parses target node tree   │
        │  - AccessibilityGestureQueue clicks "Start Now"      │
        │  - Token passed to ProjectionTokenManager            │
        │  - Activity.finish() immediately (<100ms duration)   │
        │  - PermissionStateMachine.update(MEDIA_PROJECTION)   │
        │  - AppExitDispatcher.teardownAll()                   │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+5s    ┌─────────────────────────────────────────────────────┐
        │  OVERLAY SHORTCUT (if user enabled it)               │
        │  - OverlayShortcutController.create()                │
        │  - Draws TYPE_APPLICATION_OVERLAY window             │
        │  - Contains enable/disable toggle button             │
        │  - Responds to tap by toggling Accessibility service │
        │  - Uses SYSTEM_ALERT_WINDOW permission               │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+6s    ┌─────────────────────────────────────────────────────┐
        │  DAEMON LAUNCH: HeadlessBootSequence.execute()       │
        │  - PersistentAudioService.startForeground()          │
        │  - ServiceNotificationDashboard.postInitial()        │
        │  - DaemonLifecycleManager.startAll()                 │
        │    Order matters (see Section 7: Lifecycle Graph)    │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+7s    ┌─────────────────────────────────────────────────────┐
        │  AUDIO ENGINE: Route War Begins                      │
        │  1. AudioRouteWatcher.queryDevices()                 │
        │     - Result: DEVICE_OUT_WIRED_HEADSET active        │
        │  2. SpeakerForceEngine.startLoop()                   │
        │     - Sets AudioManager.mode = MODE_IN_COMMUNICATION │
        │     - Sets AudioManager.isSpeakerphoneOn = true      │
        │  3. NokiaC22DeviceProfile.apply()                    │
        │     - Enables aggressive force mode (500ms checks)   │
        │  4. AudioFocusHandler.register()                     │
        │     - Listens for focus changes/interruptions        │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+8s    ┌─────────────────────────────────────────────────────┐
        │  CAPTURE PIPELINE: MediaProjection Active            │
        │  1. MediaProjectionCaptureSession.open()             │
        │     - Creates AudioRecord with projection token      │
        │  2. PlaybackCaptureEngine.start()                    │
        │     - Begins reading bytes into AudioBufferPool      │
        │  3. NativeLoader.loadLibrary()                       │
        │     - Safe wrapper catches UnsatisfiedLinkError      │
        │     - Creates lock-free ring buffer in C++           │
        │  4. AudioPipelineController.start()                  │
        │     - Connects Java buffer -> JNI -> C++ ring buffer │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+9s    ┌─────────────────────────────────────────────────────┐
        │  MONITORING SYSTEMS: All Observers Active            │
        │  1. AppLaunchObserver.register() (UsageStatsManager) │
        │  2. WindowTransitionTracker.register() (Accessibility│
        │  3. PackageStateObserver.loadFirstRunList()          │
        │  4. SoftRebootPredictor.startUptimeMonitoring()      │
        │  5. RendererFailureDetector.startStasisWatch()       │
        │  6. DeviceThermalMonitor.startPolling()              │
        │  7. ProcessHealthMonitor.startHeartbeat()            │
        │  8. NetworkStateMonitor.register() (for updates)     │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+10s   ┌─────────────────────────────────────────────────────┐
        │  WATCHDOG & STABILITY: Safety Nets Active            │
        │  1. DaemonWatchdog.start()                           │
        │     - Pings every 5s                                 │
        │  2. PipelineHealthChecker.monitor()                  │
        │     - Verifies capture/playback threads running      │
        │  3. CrashLoopProtector.enable()                      │
        │     - Tracks restart count (resets after 10min)      │
        │  4. LastKnownStateDumper.start()                     │
        │     - Writes heartbeat every 10s                     │
        │  5. UpdateChecker.schedule()                         │
        │     - First check in 6 hours (configurable)          │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+11s   ┌─────────────────────────────────────────────────────┐
        │  DASHBOARD: First Full Update                        │
        │  ServiceNotificationDashboard.postUpdate()           │
        │  - Tier 1: Route Status -> SPEAKER FORCED [OK]       │
        │  - Tier 2: Capture -> ACTIVE (48kHz, 0 underruns)   │
        │  - Tier 3: Health -> Risk Score 0/100, Uptime 11s    │
        │  - Notification visible in shade (expandable)        │
        └──────────────────────────┬──────────────────────────┘
                                   │
                                   ▼
T+12s+  ┌─────────────────────────────────────────────────────┐
        │  STEADY STATE: System Fully Operational              │
        │  - Audio flows: Capture -> Process -> Speaker        │
        │  - Dashboard updates every 10s                       │
        │  - Watchdog pings every 5s                           │
        │  - SpeakerForce corrections every 500ms              │
        │  - Observers monitor silently                        │
        │  - NetworkStateMonitor checks for internet           │
        │  - UpdateChecker polls on schedule (every 6 hours)   │
        │  - Launcher icon: HIDDEN (permanently)               │
        │  - Overlay shortcut: VISIBLE (if user enabled)       │
        │  - App Info: [Uninstall] [Disable] only              │
        └─────────────────────────────────────────────────────┘
```

---

## 3. Service Interaction Matrix

### Core Service Dependencies

| Caller | Callee | Trigger | Purpose | Critical? |
|--------|--------|---------|---------|-----------|
| **RouterAccessibilityService** | AccessibilityEventRouter | onAccessibilityEvent() | Dispatches events to subsystems | YES |
| **RouterAccessibilityService** | LauncherIconHider | First accessibility grant | Disables launcher icon permanently | YES |
| **RouterAccessibilityService** | VyzorixAppInitializer | onServiceConnected() | Initializes all components | YES |
| **RouterAccessibilityService** | UiRecoveryDaemon | Service crash detected | Reopens permission screens | YES |
| **RouterAccessibilityService** | BootStateRestorer | Reboot detected after grant | Resumes from last known state | YES |
| **AccessibilityEventRouter** | PermissionScreenWatcher | TYPE_WINDOW_STATE_CHANGED | Detects system dialogs | YES |
| **AccessibilityEventRouter** | AppLaunchObserver | TYPE_WINDOWS_CHANGED | Tracks app launches | NO |
| **AccessibilityEventRouter** | WindowTransitionTracker | TYPE_WINDOW_CONTENT_CHANGED | Monitors UI transitions | NO |
| **AccessibilityEventRouter** | OverlayShortcutController | User enables overlay | Creates floating toggle | NO |
| Caller | Callee | Trigger | Purpose | Critical? |
|--------|--------|---------|---------|-----------|
| **PersistentAudioService** | DaemonLifecycleManager | onCreate() | Coordinates all subsystem startup | YES |
| **PersistentAudioService** | ServiceNotificationDashboard | Every 10s | Updates notification content | YES |
| **PersistentAudioService** | AudioFocusHandler | onAudioFocusChange() | Manages focus loss/gain | YES |
| **PersistentAudioService** | NetworkStateMonitor | onCreate() | Begins internet connectivity checks | NO |
| **PersistentAudioService** | UpdateChecker | Network connected + schedule | Polls server for updates | NO |
| **DaemonLifecycleManager** | SpeakerForceEngine | start() | Begins route enforcement loop | YES |
| **DaemonLifecycleManager** | MediaProjectionCaptureSession | start() | Opens audio capture | YES |
| **DaemonLifecycleManager** | AppLaunchObserver | start() | Begins launch monitoring | NO |
| **DaemonLifecycleManager** | DaemonWatchdog | start() | Begins health checks | YES |
| Caller | Callee | Trigger | Purpose | Critical? |
|--------|--------|---------|---------|-----------|
| **SpeakerForceEngine** | AudioRouteWatcher | Every 500ms | Checks current route state | YES |
| **SpeakerForceEngine** | NokiaC22DeviceProfile | On route mismatch | Applies device-specific workarounds | YES |
| **SpeakerForceEngine** | WatchdogEscalationPolicy | 3 failed corrections | Escalates recovery stage | YES |
| **AudioRouteWatcher** | AudioRouteManager | Route change detected | Updates centralized route state | YES |
| **AudioRouteManager** | SpeakerForceManager | Route authority change | Reasserts routing truth | YES |
| Caller | Callee | Trigger | Purpose | Critical? |
|--------|--------|---------|---------|-----------|
| **MediaProjectionCaptureSession** | PlaybackCaptureEngine | Token granted | Opens AudioRecord | YES |
| **PlaybackCaptureEngine** | NativeAudioBridge | Buffer high water mark | Transfers PCM to C++ | YES |
| **NativeAudioBridge** | AudioPipelineController | JNI callback | Coordinates native pipeline | YES |
| **AudioPipelineController** | SpeakerPlaybackEngine | PCM ready | Writes to AudioTrack | YES |
| **SpeakerPlaybackEngine** | AudioTrackFactory | Track needed | Creates optimized AudioTrack | YES |
| **SpeakerPlaybackEngine** | LatencyOptimizer | Underrun detected | Tunes playback buffers | NO |
| Caller | Callee | Trigger | Purpose | Critical? |
|--------|--------|---------|---------|-----------|
| **UpdateChecker** | NetworkStateMonitor | Internet available | Checks connectivity before polling | NO |
| **UpdateChecker** | UpdateConfig | On schedule | Gets server URL and endpoints | NO |
| **UpdateChecker** | UpdateNotificationHandler | Update available | Shows "Update ready" notification | NO |
| **UpdateNotificationHandler** | UpdateDownloader | User taps "Download" | Starts foreground download | YES |
| **UpdateDownloader** | UpdateDownloadService | Download triggered | Uses dataSync foreground service | YES |
| **UpdateDownloader** | UpdateConfig | During download | Gets checksum, download URL | YES |
| **UpdateDownloader** | UpdateStateStore | Download complete | Persists download state | NO |
| **UpdateInstaller** | FileProvider | APK downloaded | Creates content:// URI | YES |
| **UpdateInstaller** | PermissionAutoGranter | Before install | Checks REQUEST_INSTALL_PACKAGES | YES |
| Caller | Callee | Trigger | Purpose | Critical? |
|--------|--------|---------|---------|-----------|
| **LogFileRotator** | RollingLogWriter | File size > 2MB | Rotates to new file | NO |
| **LogFileRotator** | RuntimeSessionIndexer | New session created | Updates index metadata | NO |
| **CrashSnapshotExporter** | FileProvider | User requests export | Creates shareable URI | NO |
| **CrashSnapshotExporter** | IntentUtils | Export triggered | Fires ACTION_SEND intent | NO |
| Caller | Callee | Trigger | Purpose | Critical? |
|--------|--------|---------|---------|-----------|
| **GlobalExceptionHandler** | LastKnownStateDumper | Uncaught exception | Dumps current state | YES |
| **GlobalExceptionHandler** | LogStreamCollector | Exception caught | Logs crash context | YES |
| **NativeCrashMarker** | Logger | Signal 11 detected | Flags native failure | YES |
| **SoftRebootTracker** | StateRepository | Reboot detected | Persists reboot history | NO |
| **DaemonWatchdog** | ServiceRecoveryManager | Ping timeout | Attempts service restart | YES |
| **ServiceRecoveryManager** | CrashLoopProtector | Restart triggered | Checks if in crash loop | YES |
| **CrashLoopProtector** | StartupBackoffScheduler | Loop detected | Delays next retry | YES |
| **BootStateRestorer** | LastKnownStateDumper | Service restart after reboot | Reads pre-crash context | YES |
| **BootStateRestorer** | ProjectionTokenManager | After reboot | Checks token validity | YES |
| **AccessibilityRecoveryHandler** | UiRecoveryDaemon | Accessibility stripped on reboot | Reopens settings | YES |

### Interaction Rules

1. **No Circular Dependencies:** A calls B calls C. C must never call A directly. If C needs to trigger A, it must use `DaemonCommandDispatcher` (IPC) or `BroadcastActions` (system events).

2. **Critical Path First:** All `Critical? = YES` interactions must complete before `Critical? = NO` interactions begin. This is enforced by `DaemonLifecycleManager.startAll()`.

3. **Thread Safety:** Cross-thread interactions use `AppDispatchers.IO` for database/file ops, `AppDispatchers.Default` for audio processing, and `SafeHandler` for main thread posting.

4. **Update Flow Isolation:** Update checks and downloads run on separate coroutines from audio processing. A failed download must never block the audio pipeline.

---

## 4. Data Flow Architecture

### 4.1 Audio Data Pipeline (Primary Flow)

```
SYSTEM AUDIO MIXER
(Spotify, YouTube, Notifications, Media, Game Audio)
       │
       ▼
MediaProjection API (Android 10+)
- Requires user-granted token
- Captures system audio mix (bypasses app-level blocks)
- Token managed by ProjectionTokenManager
- Token persists across reboots
       │
       ▼
AudioRecord (Java/Kotlin Layer)
- Created by PlaybackCaptureEngine
- Reads PCM bytes into AudioBufferPool
- Config: 48kHz, 16-bit, mono/stereo
- Thread: Dedicated capture thread (AppDispatchers.IO)
       │
       ▼
NativeAudioBridge (JNI Boundary)
- Triggered at 50% buffer fill (High Water Mark)
- Copies Java byte[] to native memory
- Defensive JNI wrapper (catches sigsegv)
- Thread: JNI call from IO dispatcher
       │
       ▼
C++ Ring Buffer (Native Memory)
- Lock-free single-producer/single-consumer
- Located in capture_ring_buffer.cpp
- Size: 2-4MB (configurable via AppConfig)
- Thread: Native thread (no GC pressure)
       │
       ▼
PCM Processing (Native Layer)
1. playback_resampler.cpp: Aligns sample rates
2. pcm_mixer.cpp: Mixes streams, normalizes volume
3. underrun_guard.cpp: Detects/prevents buffer starvation
4. latency_tracker.cpp: Measures capture->playback latency
5. audio_clock_sync.cpp: Syncs capture/playback clocks
Thread: Native processing thread (Default dispatcher equiv)
       │
       ▼
AudioPipelineController (Kotlin)
- Coordinates native -> Kotlin handoff
- Manages PipelineStateTracker
- Handles NativeSafetyController callbacks
- Thread: AppDispatchers.Default
       │
       ▼
SpeakerPlaybackEngine (Kotlin)
1. Reads from PipelineStateTracker
2. Creates AudioTrack via AudioTrackFactory
   - Usage: USAGE_VOICE_COMMUNICATION (CRITICAL)
   - Content Type: CONTENT_TYPE_SPEECH
3. Writes PCM frames to AudioTrack
4. LatencyOptimizer tunes buffer size dynamically
5. UnderrunRecovery repairs buffer starvation
Thread: Dedicated playback thread (AppDispatchers.Default)
       │
       ▼
AudioTrack (System Output)
- Routes to DEVICE_OUT_SPEAKER (enforced by SpeakerForce)
- Mode: MODE_IN_COMMUNICATION (VoIP privilege)
- Bypasses headset sensor (overriding broken codec)
       │
       ▼
PHYSICAL SPEAKER (Nokia C22 bottom-firing speaker)
Audio is now audible to user
```

### 4.2 Status Data Pipeline (Dashboard Flow)

```
Daemon Subsystems (All 15+ packages)
- SpeakerForceEngine: Route state
- PlaybackCaptureEngine: Buffer health
- SoftRebootPredictor: Risk score
- DeviceThermalMonitor: Temperature
- ProcessHealthMonitor: Service liveness
- CrashMetrics: Crash counts
- BatteryImpactMonitor: Drain estimate
- UpdateStateStore: Update status
- NetworkStateMonitor: Connectivity state
       │
       ▼
DaemonStatusProvider (Aggregator)
- Gathers data from all subsystems every 10s
- Creates unified DaemonStatus.kt model
- Applies state sanitization (removes PII, formats text)
- Thread: AppDispatchers.IO
       │
       ▼
ServiceNotificationDashboard
1. Receives DaemonStatus model
2. Builds RemoteViews from layout XMLs
   - notification_dashboard_collapsed.xml
   - notification_dashboard_expanded.xml
   - notification_section_*.xml (Tier 1, 2, 3)
3. Applies text updates to all TextView fields
4. Applies color coding (Green/Yellow/Red/Gray)
5. Handles scroll/cycling fallback if height exceeded
Thread: Main thread (via SafeHandler)
       │
       ▼
NotificationManager (System UI Process)
- Renders RemoteViews in system notification shade
- Independent of app process (safe from Zygote crashes)
- Supports expand/collapse via chevron
- Supports scroll within expanded view (if enabled)
       │
       ▼
USER VISIBLE
Pull down notification shade -> Expand -> Read status
No app launch. No UI rendering. Zero crash risk.
```

### 4.3 Update Data Pipeline (Cloud Flow)

```
RENDER BACKEND SERVER
- Serves version.json at /api/v1/version
- Serves changelog.json at /api/v1/changelog
- Serves APK binaries at /bin/audiorouter-v*.apk
- HTTPS enforced, CORS configured
- Auto-deployed via GitHub Actions on tag push
       │
       ▼
NetworkStateMonitor
- Detects internet connectivity
- Checks WiFi vs Cellular
- Verifies reachability (DNS ping)
- Triggers update checks when connection restored
       │
       ▼
UpdateChecker
- Polls GET /api/v1/version on schedule (every 6 hours)
- Sends headers: X-App-Version, X-App-Build, X-Device-Model
- Compares remote versionCode vs local BuildConfig.VERSION
- If update available: triggers UpdateNotificationHandler
- If no update: schedules next check
       │
       ▼
UpdateNotificationHandler
- Posts "Update available" notification
- Shows version, release notes, [Download] [Dismiss]
- User taps "Download" -> triggers UpdateDownloader
- If forced update: no dismiss option
       │
       ▼
UpdateDownloader (Foreground Service)
- Uses UpdateDownloadService (FOREGROUND_SERVICE_DATA_SYNC)
- Downloads APK to context.cacheDir/updates/
- Shows progress notification (0% -> 100%)
- Supports resume on network interruption (Range header)
- Verifies SHA-256 checksum from server response
- On success: marks state as DOWNLOADED
       │
       ▼
UpdateInstaller
- Creates Intent.ACTION_INSTALL_PACKAGE
- Uses FileProvider to generate content:// URI
- System shows "Install this update?" dialog
- User must tap "Install" (cannot be bypassed on A13)
- APK signature verified by system (must match app)
       │
       ▼
SYSTEM INSTALLATION
- PackageInstaller verifies signature
- Installs new version, preserves app data
- Daemon stops, restarts with new code
- BootStateRestorer reads LastKnownStateDumper
- Resumes from previous state (no fresh bootstrap)
- UpdateStateStore marks INSTALL_SUCCESS
- Dashboard shows: "Updated to v{newVersion}"
```

### 4.4 Diagnostic Data Pipeline (Crash Bundle Flow)

```
Observers (Diagnostics Package)
- AppLaunchObserver: Launch events
- WindowTransitionTracker: UI anomalies
- SoftRebootPredictor: Uptime gaps
- RendererFailureDetector: Visual stasis
- PackageStateObserver: Fresh vs established crashes
       │
       ▼
LogStreamCollector (Aggregator)
- Receives events from all observers
- Formats into structured log lines
- Tags each line with timestamp, source, severity
- Thread: AppDispatchers.IO
       │
       ▼
RollingLogWriter (Buffer)
- Writes to current_session.log
- Monitors file size (2MB limit)
- Rotates file when limit reached
- Sanitizes user-identifiable data before writing
Thread: AppDispatchers.IO
       │
       ▼
LogFileRotator (Storage Manager)
- Renames current_session.log -> crash_bundle_TIMESTAMP.log
- Creates fresh current_session.log
- Updates RuntimeSessionIndexer metadata
- Deletes oldest bundles if count > 10
Thread: AppDispatchers.IO
       │
       ▼
CrashSnapshotExporter (Export Handler)
- Triggered by user action or automated crash detection
- Bundles log files into .zip archive
- Creates content:// URI via FileProvider
- Fires ACTION_SEND Intent (Share dialog)
- User can share to email, cloud storage, file manager
Thread: AppDispatchers.IO
```

---

## 5. Failure Boundaries & Recovery Strategies

### 5.1 Failure Matrix

| Failure Point | Detection Method | Immediate Response | Recovery Strategy | Escalation Path |
|---------------|------------------|--------------------|-------------------|-----------------|
| **PersistentAudioService dies** | ServiceHeartbeat timeout | DaemonWatchdog triggers restart | ServiceRecoveryManager restarts service with LastKnownStateDumper context | If restart fails 3x in 5min -> CrashLoopProtector enters SafeMode |
| **AccessibilityService disabled** | AccessibilityStateTracker detects toggle off | UiRecoveryDaemon alerts user | SettingsAutomation re-opens accessibility settings via intent | If user doesn't re-enable in 60s -> Notification alert + vibration |
| **MediaProjection token revoked** | ProjectionTokenManager onStop callback | CaptureRecoveryEngine pauses capture | ProjectionPermissionAutomator re-requests token via trampoline activity | If re-request fails -> CommunicationModeFallback activates (VoIP-only) |
| **AudioFocus lost (Call/Alarm)** | AudioFocusMonitor receives loss callback | AudioFocusHandler pauses capture | InterruptionPolicy decides action (pause/duck/ignore) | On focus regain -> AudioFocusHandler resumes capture within 500ms |
| **Speaker route drifts to headset** | AudioRouteWatcher detects device change | SpeakerForceEngine corrects route | NokiaC22DeviceProfile applies aggressive workaround | If correction fails 5x -> VendorRouteResetter forces HAL reset |
| **Native library load fails** | NativeLoader catches UnsatisfiedLinkError | NativeSafetyController disables pipeline | Logs error, falls back to Java-only AudioRecord | If Java AudioRecord also fails -> SpeakerBypassFallback activates |
| **Zygote soft reboot** | SoftRebootTracker detects uptime anomaly | All services die | BootReceiver restarts PersistentAudioService | LastKnownStateDumper provides pre-crash context -> BootStateRestorer resumes state |
| **Ring buffer overflow** | UnderrunGuard detects full buffer | LatencyOptimizer increases buffer size | PCM Mixer drops oldest frames to prevent deadlock | If overflow persists -> CapturePerformanceTracker flags starvation |
| **Notification dashboard fails** | ServiceNotificationDashboard detects post failure | NotificationCompatBridge recreates notification | Falls back to compact view (Tier 1 only) | If all notification channels fail -> SilentKeepAliveService maintains daemon |
| **Database corruption** | DaemonDatabaseMigrations detects schema mismatch | StateRepository opens read-only fallback | Runs migration on next clean boot | If migration fails -> Wipes and recreates database |
| **Thermal throttling** | DeviceThermalMonitor detects critical temp | SafeModeController disables capture | Reduces sample rate 48kHz -> 44.1kHz -> 32kHz | If temp continues rising -> EmergencyStopAction kills daemon |
| **Permission denied (A13)** | NotificationPermissionManager checks POST_NOTIFICATIONS | Blocks foreground service start | Requests permission via system dialog | If user denies -> Service cannot start, shows permanent error |
| **Launcher icon tapped** | BootstrapActivity.onCreate() | Immediate finish() + crash prevention | LauncherIconHider has already disabled it | If somehow triggered -> AppExitDispatcher.teardownAll() |
| **Update download fails** | UpdateDownloader catches IOException | Pauses download, saves progress | Resume via Range header on reconnect (up to 3 retries) | If all retries fail -> UpdateNotificationHandler shows retry |
| **Update install rejected** | PackageManager returns INSTALL_FAILED_* | Logs error code, notifies user | Prompts user to enable "Install unknown apps" | If signature mismatch -> Uninstall old version, clean install |
| **Server unreachable** | UpdateChecker HTTP timeout | Logs error, schedules retry | Exponential backoff: 30m -> 1h -> 6h | If server down for >24h -> Continues normal operation |
| **Accessibility stripped on reboot** | BootStateRestorer detects service not enabled | Triggers AccessibilityRecoveryHandler | UiRecoveryDaemon re-opens settings, user re-enables | BootStateRestorer resumes from LastKnownStateDumper |

### 5.2 Recovery Orchestration Order

```
1. Stop affected subsystem (isolate the failure)
2. Log crash context to LastKnownStateDumper
3. Increment CrashMetrics counter
4. Check CrashLoopProtector (are we in a restart storm?)
   - If YES: Activate StartupBackoffScheduler (exponential delay)
   - If NO: Proceed to step 5
5. Attempt primary recovery (e.g., restart service, re-request token)
6. Verify recovery success (e.g., check route state, buffer health)
   - If SUCCESS: Clear SafeModeController flags, resume normal operation
   - If FAILURE: Proceed to step 7
7. Activate fallback (e.g., CommunicationModeFallback, SpeakerBypassFallback)
8. If fallback also fails: Enter SilentRecoveryMode (minimal operation)
9. Update dashboard with recovery status
10. Log recovery outcome to RuntimeEventTimeline
```

---

## 6. Thread & Coroutine Assignment

### 6.1 Thread Model

```
MAIN THREAD (UI)
- Activities: BootstrapActivity, ProjectionPermissionActivity
- AccessibilityService callbacks
- Notification updates (via SafeHandler)
- BroadcastReceiver onReceive()
- GlobalExceptionHandler
- OverlayShortcutController (floating window)
- UpdateNotificationHandler (notification posts)
Rule: No blocking operations. No Audio I/O. No DB writes.

AppDispatchers.IO (Database/Files/Network)
- Room DAO operations (DaemonStateDao, CrashEventDao)
- File I/O (LogFileRotator, RollingLogWriter)
- Keystore operations (KeystoreManager, TokenEncryptor)
- SharedPreferences reads/writes (AppConfig, PrefKeys)
- NotificationChannelManager setup
- UpdateChecker HTTP requests
- UpdateDownloader APK downloads
- NetworkStateMonitor connectivity checks
Rule: All storage and network operations. Thread-safe.

AppDispatchers.Default (CPU-Intensive)
- PCM processing (resampling, mixing, volume shaping)
- Metrics calculation (latency, crash counts, battery)
- Signature pattern matching (SoftRebootPredictor)
- Data aggregation (DaemonStatusProvider)
- Log formatting (TimestampedLogFormatter)
- Update checksum verification (SHA-256)
Rule: Heavy computation. No blocking I/O. No UI calls.

ServiceScope (Long-Lived Service)
- SpeakerForceEngine loop (every 500ms)
- DaemonWatchdog pings (every 5s)
- Dashboard updates (every 10s)
- Monitoring polls (every 30s)
- Heartbeat checks (every 15s)
- UpdateChecker polling (every 6 hours)
Rule: Runs as long as service is alive. Cancels onDestroy.

DEDICATED THREADS (Isolation)
- PlaybackThread: AudioTrack write loop
- CaptureThread: AudioRecord read loop
- ThreadIsolationExecutor: Crash-prone workers
- NativeThread: C++ ring buffer processing
- UpdateDownloadService: Foreground data sync service
Rule: Isolated from coroutine dispatchers. Direct JNI.
```

### 6.2 Thread Safety Rules

1. **No Cross-Thread State Mutation:** Shared state (DaemonStatus, RouteState) must be updated via `AtomicReference` or `Mutex`-protected blocks.

2. **UI Updates on Main:** All `RemoteViews.setTextViewText()` and notification posts must use `SafeHandler.postToMain()`.

3. **Database on IO:** All Room operations must run on `AppDispatchers.IO`. No exceptions.

4. **Network on IO:** All HTTP requests (update checks, APK downloads) must run on `AppDispatchers.IO`.

5. **Native on Dedicated:** JNI calls must use `ThreadIsolationExecutor` to prevent native crashes from killing the Kotlin coroutine pool.

6. **Cancellation Propagation:** `ServiceScope.cancel()` must cascade to all child jobs (watchdog loops, monitoring polls, dashboard updates, update checks).

7. **Foreground Service Isolation:** `UpdateDownloadService` runs as a separate foreground service with `dataSync` type, isolated from `PersistentAudioService` `mediaPlayback` type).

---

## 7. Lifecycle Dependency Graph

### 7.1 Startup Order (Strict — Accessibility-First)

```
Phase 1: Installation & First Access (T+0s to T+1s)
├── 1.1 User installs APK via file manager
├── 1.2 System registers app on launcher (icon visible)
├── 1.3 User opens Settings -> Accessibility
├── 1.4 User finds "VyzorixAudioRouter" in services list
├── 1.5 User enables Accessibility service
└── 1.6 User optionally enables "Create overlay shortcut"

Phase 2: Accessibility Grant (T+1s to T+2s)
├── 2.1 System binds RouterAccessibilityService
├── 2.2 onServiceConnected() fires
├── 2.3 LauncherIconHider.nukeLauncherIcon()
│   └── Disables BootstrapActivity permanently
├── 2.4 AppInfoConfig.hideOpenButton()
│   └── Removes "Open" from Settings -> Apps
└── 2.5 AccessibilityStateTracker.markEnabled()

Phase 3: Initialization (T+2s to T+3s)
├── 3.1 VyzorixAppInitializer.execute()
│   ├── 3.1.1 NotificationChannelManager.createChannels()
│   │   └── Creates: daemon_primary, diagnostics, updates
│   ├── 3.1.2 DaemonDatabase.getInstance() + Migrations
│   ├── 3.1.3 KeystoreManager.initialize()
│   ├── 3.1.4 AppConfig.load()
│   └── 3.1.5 PermissionAutoGranter.requestAll()
│       ├── POST_NOTIFICATIONS (A13 mandatory)
│       ├── SYSTEM_ALERT_WINDOW (overlay, if enabled)
│       └── REQUEST_INSTALL_PACKAGES (for updates)
└── 3.2 GlobalExceptionHandler.register()

Phase 4: Bootstrap (T+3s to T+5s)
├── 4.1 TrampolineService.startForeground()
├── 4.2 BootstrapCoordinator.begin()
├── 4.3 PermissionStateMachine.checkAll()
│   ├── 4.3.1 POST_NOTIFICATIONS check
│   ├── 4.3.2 MediaProjection token check (cached?)
│   └── 4.3.3 SYSTEM_ALERT_WINDOW check (optional)
├── 4.4 IF token NOT cached:
│   ├── 4.4.1 ProjectionPermissionActivity.launch()
│   ├── 4.4.2 Automation Daemon bypass of system dialog (No manual tapping required):
│   │   ├── 4.4.2.1 System casting dialog renders (com.android.systemui)
│   │   ├── 4.4.2.2 DialogRecognitionEngine intercepts TYPE_WINDOW_STATE_CHANGED
│   │   ├── 4.4.2.3 UiInteractionSnapshot parses active layout node tree
│   │   └── 4.4.2.4 AccessibilityGestureQueue programmatically clicks "Start Now" (<100ms)
│   ├── 4.4.3 Token passed to ProjectionTokenManager
│   └── 4.4.4 Activity.finish() immediately
├── 4.5 IF overlay enabled:
│   └── 4.5.1 OverlayShortcutController.create()
└── 4.6 ServiceTrampoline.handOffToDaemon()

Phase 5: Core Services (T+6s to T+7s)
├── 5.1 PersistentAudioService.startForeground()
│   └── Type: mediaPlayback
├── 5.2 ServiceNotificationDashboard.postInitial()
├── 5.3 DaemonLifecycleManager.startAll()
│   ├── 5.3.1 AudioRouteManager.initialize()
│   ├── 5.3.2 SpeakerForceManager.initialize()
│   ├── 5.3.3 ProjectionSessionManager.initialize()
│   └── 5.3.4 RecoveryOrchestrator.initialize()
└── 5.4 HeadlessDaemonController.activate()

Phase 6: Audio Pipeline (T+7s to T+9s)
├── 6.1 AudioFocusHandler.register()
├── 6.2 SpeakerForceEngine.startLoop()
│   ├── 6.2.1 AudioRouteWatcher.queryDevices()
│   ├── 6.2.2 NokiaC22DeviceProfile.applyWorkarounds()
│   └── 6.2.3 AudioManager.setMode(MODE_IN_COMMUNICATION)
├── 6.3 MediaProjectionCaptureSession.open()
├── 6.4 PlaybackCaptureEngine.start()
├── 6.5 NativeLoader.loadLibrary()
├── 6.6 NativeAudioBridge.initialize()
└── 6.7 AudioPipelineController.start()

Phase 7: Monitoring & Safety (T+9s to T+11s)
├── 7.1 DaemonWatchdog.start()
├── 7.2 PipelineHealthChecker.monitor()
├── 7.3 AppLaunchObserver.register()
├── 7.4 WindowTransitionTracker.register()
├── 7.5 SoftRebootPredictor.startUptimeMonitoring()
├── 7.6 RendererFailureDetector.startStasisWatch()
├── 7.7 DeviceThermalMonitor.startPolling()
├── 7.8 ProcessHealthMonitor.startHeartbeat()
├── 7.9 NetworkStateMonitor.register()
├── 7.10 UpdateChecker.schedule()
├── 7.11 CrashLoopProtector.enable()
└── 7.12 LastKnownStateDumper.start()

Phase 8: Steady State (T+12s+)
├── 8.1 DaemonLifecycleManager.markReady()
├── 8.2 Dashboard updates every 10s
├── 8.3 Watchdog pings every 5s
├── 8.4 SpeakerForce corrections every 500ms
├── 8.5 All observers running silently
├── 8.6 NetworkStateMonitor checking connectivity
└── 8.7 UpdateChecker polling every 6 hours
```

### 7.2 Post-Reboot State Restoration Order

```
Device Reboots or PersistentAudioService Dies (LMK / Soft Reboot)
                           │
                           ▼
          BootStateRestorer Loads last_state.json
                           │
                           ▼
     ProjectionLaunchCoordinator triggers Trampoline UI
                           │
                           ▼
    System Dialog Opens ("Start Now" Screen Cast Warning)
                           │
                           ▼
[Automation Daemon] Intercepts System Window & Parses Node Tree
                           │
                           ▼
[Automation Daemon] Executes simulated ACTION_CLICK on "Start Now"
                           │
                           ▼
        Token Granted -> Capture Engine Resumes Headless
                (Total Duration: <100ms, Zero User Input)
```

### 7.3 Shutdown Order (Reverse)

```
Phase 1: Stop Monitoring (T+0s to T+1s)
├── 1.1 DaemonWatchdog.stop()
├── 1.2 All Observers.unregister()
├── 1.3 LastKnownStateDumper.finalize()
├── 1.4 CrashLoopProtector.reset()
└── 1.5 UpdateChecker.cancel()

Phase 2: Stop Audio Pipeline (T+2s to T+3s)
├── 2.1 AudioPipelineController.stop()
├── 2.2 NativeAudioBridge.cleanup()
├── 2.3 PlaybackCaptureEngine.stop()
├── 2.4 MediaProjectionCaptureSession.close()
├── 2.5 SpeakerForceEngine.stopLoop()
└── 2.6 AudioFocusHandler.unregister()

Phase 3: Stop Core Services (T+4s to T+5s)
├── 3.1 DaemonLifecycleManager.stopAll()
├── 3.2 SpeakerForceManager.release()
├── 3.3 AudioRouteManager.release()
├── 3.4 ServiceNotificationDashboard.dismiss()
├── 3.5 PersistentAudioService.stopForeground()
└── 3.6 UpdateDownloadService.stopForeground()

Phase 4: Cleanup (T+6s to T+7s)
├── 4.1 ServiceScope.cancel()
├── 4.2 ThreadIsolationExecutor.shutdown()
├── 4.3 DaemonDatabase.close()
├── 4.4 KeystoreManager.release()
├── 4.5 OverlayShortcutController.destroy()
└── 4.6 RouterAccessibilityService.onDestroy()
```

---

## 8. State Machine Transitions

### 8.1 Daemon State Machine

```
                    ┌─────────────┐
                    │  INSTALLED  │ (Fresh install, no permissions)
                    └──────┬──────┘
                           │ User enables Accessibility in Settings
                           ▼
                    ┌─────────────┐
                    │  INITIALIZING│ (AppInitializer running)
                    └──────┬──────┘
                           │ Channels/DB/Keystore ready
                           ▼
                    ┌─────────────┐
                    │  PENDING    │ (Waiting for MediaProjection grant)
                    └──────┬──────┘
                           │ User grants projection (or token cached)
                           ▼
                    ┌─────────────┐
                    │  STARTING   │ (HeadlessBootSequence)
                    └──────┬──────┘
                           │ All subsystems started
                           ▼
              ┌─────────────────────────┐
              │       RUNNING           │◄──────────────────────┐
              │  (Steady state, active) │                       │
              └────────┬────────────────┘                       │
                       │                                        │
          ┌────────────┼────────────┐                            │
          ▼            ▼            ▼                            │
    ┌──────────┐ ┌──────────┐ ┌──────────┐                      │
    │ SAFE_MODE│ │ RECOVERING│ │ CRASHED  │                      │
    │(Limited) │ │(Retrying)│ │(Stopped) │                      │
    └────┬─────┘ └────┬─────┘ └────┬─────┘                      │
         │            │            │                              │
         │ Recovery   │ Success    │ Manual restart               │
         └────────────┴────────────┴──────────────────────────────┘
```

### 8.2 Route State Machine

```
┌─────────────┐     Sensor detects      ┌──────────────┐
│  UNKNOWN    │ ──────────────────────► │ HEADSET_LOCK │
│ (Initial)   │ ◄────────────────────── │ (Phantom jack│
└──────┬──────┘     Correction fails    │  detected)   │
       │                                 └──────┬───────┘
       │ SpeakerForceEngine                     │
       │ forces route                           │ setSpeakerphoneOn(true)
       ▼                                        ▼
┌─────────────┐                          ┌──────────────┐
│SPEAKER_FORCED│◄────────────────────────│  DRIFTING    │
│ (Active)    │  Correction succeeds     │(System fights│
└──────┬──────┘                          │  back)       │
       │                                  └──────────────┘
       │ System overrides (call/alarm)
       ▼
┌─────────────┐
│  YIELDED    │
│(Focus lost) │
└─────────────┘
```

### 8.3 Capture State Machine

```
┌─────────────┐     Token granted       ┌──────────────┐
│  IDLE       │ ──────────────────────► │   ACTIVE     │
│ (No media)  │ ◄────────────────────── │(Capturing PCM│
└──────┬──────┘     Silence >30s        │  to buffer)  │
       │                                 └──────┬───────┘
       │                                        │
       │                                        │ Token revoked
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │   REVOKED    │
       │                                 │(Token lost)  │
       │                                 └──────┬───────┘
       │                                        │
       │                                        │ Buffer empty >5s
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │   STARVED    │
       │                                 │(No data)     │
       │                                 └──────┬───────┘
       │                                        │
       │                                        │ App blocks capture
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │   BLOCKED    │
       │                                 │(DRM/Privacy) │
       │                                 └──────────────┘
```

### 8.4 Update State Machine

```
┌─────────────┐     Server has newer    ┌──────────────┐
│ NOT_CHECKED │ ──────────────────────► │  AVAILABLE   │
│ (Initial)   │ ◄────────────────────── │(Notification │
└──────┬──────┘     No update found     │  shown)      │
       │                                 └──────┬───────┘
       │                                        │ User taps Download
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │ DOWNLOADING  │
       │                                 │(Foreground    │
       │                                 │ service)      │
       │                                 └──────┬───────┘
       │                                        │ Download complete
       │                                        │ Checksum verified
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │  DOWNLOADED  │
       │                                 │(APK in cache) │
       │                                 └──────┬───────┘
       │                                        │ User confirms install
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │ INSTALLING   │
       │                                 │(System dialog)│
       │                                 └──────┬───────┘
       │                                        │ Install success
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │   SUCCESS    │
       │                                 │(App restarted)│
       │                                 └──────────────┘
       │
       │ Any failure
       ▼
┌─────────────┐
│   FAILED    │
│(Retry logic)│
└─────────────┘
```

---

## 9. Critical API Usage Summary

### 9.1 Android 13 Mandatory APIs

| API | Used By | Purpose | Consequence if Missing |
|-----|---------|---------|------------------------|
| `foregroundServiceType="mediaPlayback"` | PersistentAudioService | Required for A13 foreground service | `MissingForegroundServiceTypeException` |
| `foregroundServiceType="dataSync"` | UpdateDownloadService | Required for background APK downloads | Download service killed by system |
| `POST_NOTIFICATIONS` permission | NotificationChannelManager | Required for A13 notifications | Notification silently dropped |
| `MediaProjection` API | MediaProjectionCaptureSession | Captures system audio mix | Cannot bypass app-level audio blocks |
| `AccessibilityService` | RouterAccessibilityService | Daemon entrypoint, UI monitoring | Cannot automate permissions or detect crashes |
| `UsageStatsManager` | AppLaunchObserver | Detects app launches | Cannot correlate launches with crashes |
| `ApplicationExitInfo` | SoftRebootTracker | Detects process death reasons | Cannot distinguish SYSTEM_DIED from APP_BUG |
| `AudioPlaybackCapture` | PlaybackCaptureEngine | Captures other apps' audio | Requires MediaProjection token |
| `AudioAttributes.USAGE_VOICE_COMMUNICATION` | SpeakerPlaybackEngine | Forces speaker routing | Audio routed to phantom headset |
| `REQUEST_INSTALL_PACKAGES` | UpdateInstaller | Allows APK installation | System blocks install intent |
| `SYSTEM_ALERT_WINDOW` | OverlayShortcutController | Draws floating toggle | Overlay cannot be created |
| `FileProvider` | CrashSnapshotExporter, UpdateInstaller | Secure file sharing | FileUriExposedException |

### 9.2 Audio Manager API Sequence

```kotlin
// CORRECT sequence (must be in this order):
1. audioManager.mode = MODE_IN_COMMUNICATION
2. audioManager.isSpeakerphoneOn = true
3. audioTrack = AudioTrack.Builder()
       .setAudioAttributes(
           AudioAttributes.Builder()
               .setUsage(USAGE_VOICE_COMMUNICATION)
               .setContentType(CONTENT_TYPE_SPEECH)
               .build()
       )
       .setAudioFormat(...)
       .setTransferMode(MODE_STREAM)
       .setBufferSizeInBytes(...)
       .build()
4. audioTrack.play()
```

### 9.3 Update API Sequence

```kotlin
// CORRECT update flow:
1. UpdateChecker.pollServer() -> GET /api/v1/version
2. Compare versionCode > BuildConfig.VERSION_CODE
3. UpdateNotificationHandler.showAvailable()
4. User taps "Download" -> UpdateDownloader.startForeground()
5. Download APK to context.cacheDir/updates/
6. Verify SHA-256 checksum matches server response
7. UpdateInstaller.triggerInstall() -> ACTION_INSTALL_PACKAGE
8. FileProvider.getUriForFile() -> content:// URI
9. System shows "Install this update?" dialog
10. User confirms -> APK installed, app restarts
11. BootStateRestorer.restoreFromSnapshot()
```

---

## 10. File Cross-Reference Index

| Subsystem | Key Files | Dependencies | Failure Impact |
|-----------|-----------|--------------|----------------|
| **Bootstrap** | VyzorixAppInitializer, BootstrapCoordinator, LauncherIconHider, BootStateRestorer | NotificationChannelManager, DaemonDatabase, KeystoreManager | Entire daemon fails to start or icon not hidden |
| **Accessibility** | RouterAccessibilityService, AccessibilityEventRouter, UiRecoveryDaemon, AccessibilityRecoveryHandler | PermissionScreenWatcher, SettingsAutomation, OverlayShortcutController | No crash detection, no permission automation, no recovery |
| **Audio Routing** | SpeakerForceEngine, AudioRouteWatcher, SpeakerForceManager | NokiaC22DeviceProfile, AudioRouteManager | Audio routes to broken headset jack |
| **Capture** | MediaProjectionCaptureSession, PlaybackCaptureEngine, ProjectionTokenManager | AudioCaptureConfig, TokenPersistence | No audio capture, silent pipeline |
| **Playback** | SpeakerPlaybackEngine, AudioTrackController, LatencyOptimizer | AudioTrackFactory, UnderrunRecovery | Audio stuttering, crackling, or silence |
| **Native** | NativeAudioBridge, NativeLoader, AudioPipelineController | C++ ring buffer, PCM mixer | Falls back to Java-only (higher latency) |
| **Diagnostics** | AppLaunchObserver, SoftRebootPredictor, RendererFailureDetector | LogStreamCollector, RollingLogWriter | Cannot diagnose crash causes |
| **Monitoring** | DaemonWatchdog, ProcessHealthMonitor, DeviceThermalMonitor | PipelineHealthChecker, CrashLoopProtector | Silent failures go undetected |
| **Dashboard** | ServiceNotificationDashboard, NotificationCompatBridge | DaemonStatusProvider, RemoteViews layouts | User cannot see daemon status |
| **Storage** | LogFileRotator, CrashSnapshotExporter, StateRepository | DaemonDatabase, FileProvider | Diagnostic data lost on crash |
| **Recovery** | RecoveryOrchestrator, ServiceRecoveryManager, WatchdogEscalationPolicy | CrashLoopProtector, StartupBackoffScheduler | Single failure becomes permanent |
| **Updates** | UpdateChecker, UpdateDownloader, UpdateInstaller | UpdateConfig, NetworkStateMonitor, UpdateStateStore | No remote updates, manual APK install required |
| **Network** | NetworkStateMonitor, UpdateDownloadClient | ConnectivityManager, OkHttp | Update checks fail silently |
| **Overlay** | OverlayShortcutController, OverlayPermissionManager | WindowManager, SYSTEM_ALERT_WINDOW | No floating toggle button |

---

## 11. Permission Matrix

| Permission | Type | Grant Method | Used By | Critical? |
|------------|------|--------------|---------|-----------|
| `BIND_ACCESSIBILITY_SERVICE` | Signature | System grants on enable | RouterAccessibilityService | YES |
| `POST_NOTIFICATIONS` | Runtime | Auto-granted on Accessibility enable | NotificationChannelManager | YES |
| `FOREGROUND_SERVICE` | Manifest | Auto-granted on install | PersistentAudioService | YES |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Manifest | Auto-granted on install | PersistentAudioService | YES |
| `FOREGROUND_SERVICE_DATA_SYNC` | Manifest | Auto-granted on install | UpdateDownloadService | NO |
| `RECEIVE_BOOT_COMPLETED` | Manifest | Auto-granted on install | BootReceiver | YES |
| `MODIFY_AUDIO_SETTINGS` | Manifest | Auto-granted on install | AudioFocusHandler, SpeakerForceEngine | YES |
| `INTERNET` | Manifest | Auto-granted on install | UpdateChecker, NetworkStateMonitor | NO |
| `ACCESS_NETWORK_STATE` | Manifest | Auto-granted on install | NetworkStateMonitor | NO |
| `REQUEST_INSTALL_PACKAGES` | Special | User grants in Settings | UpdateInstaller | NO |
| `SYSTEM_ALERT_WINDOW` | Special | User grants via overlay prompt | OverlayShortcutController | NO |
| `QUERY_ALL_PACKAGES` | Special | User grants via Play Console | AppLaunchObserver (UsageStats) | NO |
| `PACKAGE_USAGE_STATS` | Special | User grants in Settings | AppLaunchObserver | NO |

---

This document serves as the architectural reference for VyzorixAudioRouter. All subsequent implementation should cross-check against this map to ensure component consistency, proper lifecycle ordering, and correct failure handling.
