Can you look at the updates by gpt 5.5 ;


```text
VyzorixAudioRouter/
│
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       │
│       │   # Android 13+ critical declarations that MUST exist
│       │   # -------------------------------------------------
│       │   # <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
│       │   # <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
│       │   # <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
│       │   # <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
│       │   # <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
│       │   # <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
│       │   # <uses-permission android:name="android.permission.RECORD_AUDIO"/>
│       │   # <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
│       │   # <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
│       │   # <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"/>
│       │   # <uses-permission android:name="android.permission.WAKE_LOCK"/>
│       │   # <uses-permission android:name="android.permission.INTERNET"/>
│       │   # <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
│       │
│       │   # Accessibility service metadata
│       │   # foregroundServiceType declarations
│       │   # exported=true/false enforcement (A12+ mandatory)
│       │   # queries{} package visibility section
│       │
│       ├── res/
│       │   ├── xml/
│       │   │   ├── backup_rules.xml                    # Android Auto Backup rules
│       │   │   ├── data_extraction_rules.xml           # Android 12+ data extraction policy
│       │   │   ├── provider_paths.xml                  # FileProvider export paths
│       │   │   ├── notification_permission_flow.xml    # Notification rationale flow metadata
│       │   │   └── accessibility_gesture_map.xml       # Accessibility automation action map
│       │   │
│       │   ├── values/
│       │   │   ├── ids.xml                             # Stable IDs for RemoteViews
│       │   │   ├── bools.xml                           # Feature toggles by build type/device
│       │   │   ├── integers.xml                        # Timing defaults / polling intervals
│       │   │   └── config.xml                          # Runtime-safe XML defaults
│       │   │
│       │   └── raw/
│       │       ├── startup_chime.wav                   # Optional debug startup cue
│       │       └── silent_anchor.wav                   # Silent VoIP anchor sample
│       │
│       └── kotlin/com/vyzorix/audiorouter/
│           ├── BuildInfo.kt                            # Runtime build/version/device metadata
│           ├── ProcessEntryGuard.kt                    # Prevents duplicate process initialization
│           ├── StrictModeInitializer.kt                # Debug-only strict mode enforcement
│           └── StartupProfiler.kt                      # Measures cold-start timings
│
├── core/
│   ├── services/
│   │   └── src/main/kotlin/com/vyzorix/audiorouter/services/
│   │
│   │       ├── automation/
│   │       │   ├── AutomationRateLimiter.kt            # Prevents rapid-fire automation loops
│   │       │   ├── HumanPresenceDetector.kt            # Detects screen unlock/user activity
│   │       │   ├── AutomationCooldownPolicy.kt         # Enforces retry cooldown windows
│   │       │   ├── AutomationSafetyGate.kt             # Stops dangerous repetitive actions
│   │       │   ├── DialogRecognitionEngine.kt          # Identifies Android system dialogs
│   │       │   ├── AccessibilityGestureQueue.kt        # Queues gesture actions safely
│   │       │   ├── AutomationDecisionEngine.kt         # Chooses whether automation is safe
│   │       │   └── UiInteractionSnapshot.kt            # Captures current accessibility node tree
│   │       │
│   │       ├── audio/
│   │       │   ├── focus/
│   │       │   │   ├── FocusRecoveryCoordinator.kt     # Reclaims focus after interruptions
│   │       │   │   ├── FocusPriorityPolicy.kt          # Determines focus reclaim strategy
│   │       │   │   ├── FocusConflictResolver.kt        # Resolves competing playback sessions
│   │       │   │   ├── FocusPersistenceEngine.kt       # Keeps communication focus alive
│   │       │   │   ├── FocusEventHistory.kt            # Records focus transitions
│   │       │   │   ├── FocusSuppressionPolicy.kt       # Suppresses unstable reclaim loops
│   │       │   │   └── AudioDuckController.kt          # Handles ducking/restore behavior
│   │       │   │
│   │       │   ├── media/
│   │       │   │   ├── ActiveMediaSessionResolver.kt   # Detects currently dominant media app
│   │       │   │   ├── MediaPriorityPolicy.kt          # Determines playback ownership priority
│   │       │   │   ├── ForegroundPlaybackResolver.kt   # Maps active foreground playback source
│   │       │   │   ├── CaptureOwnershipArbitrator.kt   # Resolves multi-session capture conflicts
│   │       │   │   ├── MediaSessionWatcher.kt          # Watches MediaSessionManager callbacks
│   │       │   │   ├── PlaybackOriginClassifier.kt     # Identifies playback category/source
│   │       │   │   └── SessionEvictionPolicy.kt        # Drops stale/inactive sessions
│   │       │   │
│   │       │   └── route/
│   │       │       ├── RouteAssertionEngine.kt         # Continuously validates speaker route
│   │       │       ├── RouteConflictResolver.kt        # Resolves conflicting route requests
│   │       │       ├── RouteEscalationPolicy.kt        # Escalates failed route corrections
│   │       │       └── RouteFailureJournal.kt          # Persistent route failure history
│   │       │
│   │       ├── memory/
│   │       │   ├── MemoryClassProfiler.kt              # Detects RAM class/device limits
│   │       │   ├── LowRamModeController.kt             # Enables degraded low-memory mode
│   │       │   ├── CacheBudgetManager.kt               # Dynamically shrinks memory allocations
│   │       │   ├── ServiceTrimCoordinator.kt           # Reacts to TRIM_MEMORY callbacks
│   │       │   ├── NativeHeapWatcher.kt                # Watches JNI/native heap growth
│   │       │   ├── AllocationPressureMonitor.kt        # Detects allocation spikes
│   │       │   └── EmergencyMemoryReducer.kt           # Aggressively frees resources in crisis
│   │       │
│   │       ├── projection/
│   │       │   ├── ProjectionLaunchCoordinator.kt      # Safely launches projection permission flow
│   │       │   ├── FullScreenIntentBridge.kt           # Uses full-screen notification trampoline
│   │       │   ├── ProjectionActivityMediator.kt       # Coordinates user-tap mediation
│   │       │   ├── ProjectionLaunchConditions.kt       # Verifies foreground/screen-unlocked state
│   │       │   ├── ProjectionRetryPolicy.kt            # Retries projection requests safely
│   │       │   ├── ProjectionVisibilityGuard.kt        # Prevents illegal background launches
│   │       │   └── ProjectionForegroundEscalator.kt    # Temporarily elevates service priority
│   │       │
│   │       ├── diagnostics/
│   │       │   ├── LogStreamCollector.kt               # Aggregates all runtime events
│   │       │   ├── RuntimeTraceAssembler.kt            # Correlates crash/recovery timelines
│   │       │   ├── DiagnosticCompression.kt            # Compresses exported bundles
│   │       │   ├── EventCorrelationEngine.kt           # Links launch events to instability
│   │       │   └── SystemHealthScorer.kt               # Computes runtime stability score
│   │       │
│   │       ├── performance/
│   │       │   ├── AdaptiveSamplingController.kt       # Dynamically adjusts polling frequency
│   │       │   ├── CpuLoadBalancer.kt                  # Reduces work under CPU stress
│   │       │   ├── FeatureLoadShedding.kt              # Disables noncritical modules
│   │       │   ├── LightweightModeController.kt        # Minimal operational fallback mode
│   │       │   └── ThermalMitigationPolicy.kt          # Reduces processing during overheating
│   │       │
│   │       ├── scheduler/
│   │       │   ├── ForegroundLaunchWindow.kt           # Ensures legal foreground activity launch timing
│   │       │   ├── WakeLockCoordinator.kt              # Central wakelock management
│   │       │   └── AlarmRecoveryBridge.kt              # AlarmManager fallback wakeups
│   │       │
│   │       ├── storage/
│   │       │   ├── RuntimeCheckpointWriter.kt          # Writes daemon recovery checkpoints
│   │       │   ├── PersistentEventQueue.kt             # Survives process death
│   │       │   └── CrashBundleRetentionPolicy.kt       # Controls storage cleanup policy
│   │       │
│   │       └── compat/
│   │           ├── ForegroundStartRestrictionBypass.kt # Handles A13 foreground launch timing legally
│   │           ├── NotificationTrampolineCompat.kt     # Handles Android 12+ notification rules
│   │           └── PendingIntentCompatPolicy.kt        # Immutable/mutable PI enforcement
│   │
│   ├── audioengine/
│   │   └── src/main/
│   │       ├── cpp/
│   │       │   ├── memory_guard.cpp                    # Prevents unsafe native allocations
│   │       │   ├── ringbuffer_pressure.cpp             # Tracks ring-buffer congestion
│   │       │   ├── audio_fallback_bridge.cpp           # Native fallback path
│   │       │   └── thread_priority_guard.cpp           # Safe thread priority management
│   │       │
│   │       └── kotlin/com/vyzorix/audiorouter/audioengine/
│   │           ├── NativeCrashRecovery.kt              # Handles JNI failures gracefully
│   │           ├── PipelineBackpressureController.kt   # Prevents pipeline overload
│   │           └── AudioEngineHealthState.kt           # Native engine runtime state model
│   │
│   └── data/
│       └── src/main/kotlin/com/vyzorix/audiorouter/data/
│           ├── datastore/
│           │   ├── SettingsDataStore.kt                # Proto/DataStore configuration persistence
│           │   ├── RuntimeFlagsStore.kt                # Dynamic feature flags
│           │   └── ProjectionMetadataStore.kt          # Projection metadata persistence only
│           │
│           └── migrations/
│               ├── LegacyPrefsMigration.kt             # SharedPreferences → DataStore migration
│               └── CrashBundleMigration.kt             # Log schema evolution handling
│
└── docs/
    ├── A13_RESTRICTIONS.md                             # Android 13 background/activity restrictions
    ├── ACCESSIBILITY_LIMITATIONS.md                    # Accessibility boundaries and behaviors
    ├── MEDIA_PROJECTION_LIMITATIONS.md                 # Projection token lifecycle realities
    ├── OEM_KILL_POLICIES.md                            # Nokia/Unisoc process-kill behavior
    ├── MEMORY_PRESSURE_STRATEGY.md                     # RAM degradation policies
    ├── AUTOMATION_SAFETY.md                            # Safety rules for automation loops
    └── THREADING_MODEL.md                              # Dispatcher/thread architecture
```

Now the deeper explanation of the new additions:

1. automation/
Why this entire package matters:

Your daemon relies heavily on:
- AccessibilityService
- automated permission navigation
- automated recovery flows

On Android 13, this becomes dangerous if unmanaged.

Example failure:
```text
Projection revoked
↓
daemon launches permission activity
↓
activity blocked
↓
automation retries instantly
↓
loop repeats 100x
↓
SurfaceFlinger instability
↓
soft reboot
```

So these files act as:
“automation governors.”

Key roles:

AutomationRateLimiter.kt
- prevents rapid repeated gestures/intents
- enforces:
  - max actions per minute
  - max retries
  - cooldowns

Without this:
your daemon could DOS the system UI.

HumanPresenceDetector.kt
- checks:
  - screen unlocked?
  - user recently active?
  - keyguard visible?
  - device idle?

Why:
Android increasingly blocks background UI launches while locked.

AutomationCooldownPolicy.kt
- exponential backoff:
```text
1st failure → retry in 5s
2nd failure → 30s
3rd failure → 5min
```

Critical for fragile ROMs.

AutomationSafetyGate.kt
- final “circuit breaker”
- if automation appears unstable:
  - disable automation entirely
  - require manual user interaction

This prevents:
- infinite settings loops
- recursive permission reopening
- catastrophic accessibility spam

--------------------------------------------------

2. audio/focus/
This package is extremely important on Android 13.

Because your entire routing strategy depends on:
```text
MODE_IN_COMMUNICATION
```

which is tightly linked to:
- audio focus ownership
- communication priority

Problem:
many apps fight for focus:
- YouTube
- Spotify
- calls
- alarms
- notifications
- Bluetooth assistants

Without a focus-policy layer:
the system becomes chaotic.

FocusRecoveryCoordinator.kt
- regains focus after interruption
- waits appropriate delay before reclaiming

Example:
```text
alarm sounds
↓
focus lost
↓
wait 2s after alarm ends
↓
reclaim communication focus
```

FocusPriorityPolicy.kt
- decides:
```text
call > alarm > media > daemon
```

or:
```text
daemon aggressively reclaims
```

depending on state.

FocusConflictResolver.kt
- handles simultaneous competing sessions

Example:
```text
Spotify + YouTube + Maps voice nav
```

Who wins?

This file decides.

FocusPersistenceEngine.kt
- continuously reinforces communication-mode ownership

This is what helps:
keep speaker routing dominant.

--------------------------------------------------

3. projection/
This package solves one of the biggest Android 13 realities:

You CANNOT reliably launch Activities silently anymore.

Especially:
- from background
- while screen locked
- after idle
- from dead process recovery

So you need:
“foreground launch mediation.”

ProjectionLaunchCoordinator.kt
- central controller for all projection requests

Instead of random activity launches.

FullScreenIntentBridge.kt
- uses legal:
```text
fullScreenIntent
```
notifications

to bring projection flow forward.

ProjectionActivityMediator.kt
- waits for:
  - user interaction
  - notification tap
  - foreground visibility

before launching projection activity.

ProjectionLaunchConditions.kt
- verifies:
```text
screen unlocked?
app foreground-capable?
notification permission granted?
```

before attempting launch.

ProjectionVisibilityGuard.kt
- prevents illegal background activity launches
- avoids:
```text
Background activity launch blocked
```
errors on Android 13.

This package is essential.

--------------------------------------------------

4. memory/
This package is VERY important for Nokia C22.

Your architecture is now huge.

Without adaptive degradation:
the daemon itself could become the instability source.

MemoryClassProfiler.kt
- detects:
  - RAM class
  - low-RAM device flag
  - heap limits

Then configures:
- buffer sizes
- polling intervals
- cache budgets

LowRamModeController.kt
- disables:
  - diagnostics
  - overlays
  - aggressive observers
  - native DSP

under memory pressure.

CacheBudgetManager.kt
- dynamically shrinks:
  - ring buffers
  - log queues
  - route history caches

ServiceTrimCoordinator.kt
- reacts to:
```kotlin
onTrimMemory()
```

This is one of Android’s most important survival hooks.

Without it:
LMK kills become likely.

--------------------------------------------------

5. media/
This package solves:
“what exactly should be captured?”

Modern Android often has:
- multiple simultaneous media sessions
- stale sessions
- hidden playback owners

Without arbitration:
capture becomes unstable.

ActiveMediaSessionResolver.kt
- identifies currently dominant playback app

MediaPriorityPolicy.kt
- decides:
```text
foreground media wins
navigation prompts override temporarily
```

etc.

ForegroundPlaybackResolver.kt
- correlates:
  - UsageStats
  - MediaSessionManager
  - AudioPlaybackConfiguration

to determine actual active playback source.

CaptureOwnershipArbitrator.kt
- resolves:
```text
multiple apps playing simultaneously
```

This prevents:
- capture confusion
- route flapping
- focus oscillation

--------------------------------------------------

6. Missing Android-13/compiler-required details you still needed
These were genuinely absent before:

- backup_rules.xml
- data_extraction_rules.xml
- provider_paths.xml
- immutable/mutable PendingIntent enforcement
- exported=true/false correctness
- queries{} package visibility declarations
- RECORD_AUDIO permission
- WAKE_LOCK permission
- foreground launch mediation
- DataStore migration handling
- trim-memory handling
- full-screen intent compatibility
- startup process deduplication
- projection foreground launch coordination

Those additions make the structure much closer to:
a real Android 13 production daemon architecture.


👇👇👇

Then look at the mapping documentation it did, but it looks very AI'iish🥱 , so just rewrite the way you did here😄;




# SYSTEM_MAP.md — Architecture Reference (Android 13 Non‑Root Edition)

## Document Purpose

This document defines the operational architecture of VyzorixAudioRouter.

It describes:
- module responsibilities,
- startup order,
- daemon lifecycle,
- recovery orchestration,
- Android 13 limitations,
- MediaProjection handling,
- Accessibility automation boundaries,
- routing strategy,
- update mechanisms,
- and crash survivability behavior.

The project is designed specifically for:
- stock Android 13,
- non-root execution,
- Accessibility-first operation,
- MediaProjection-based playback capture,
- foreground service persistence,
- and Nokia C22 instability mitigation.

This daemon DOES NOT:
- inject into audioserver,
- patch Audio HAL,
- bypass SELinux,
- bypass Android permission consent,
- intercept global Binder traffic,
- or require root privileges.

The daemon operates entirely within:
- Android public APIs,
- AccessibilityService,
- MediaProjection,
- AudioTrack,
- AudioManager,
- foreground services,
- notification infrastructure,
- and user-granted permissions.

---

# 1. Module Dependency Graph

```text
app/
├── Aggregation + packaging module
├── AndroidManifest.xml
├── BootstrapActivity.kt
├── ProjectionPermissionActivity.kt
├── VyzorixApplication.kt
└── VyzorixAppInitializer.kt

Depends on:
├── core/common/
├── core/services/
├── core/data/
├── core/audioengine/
└── core/ui/
```

```text
core/common/
├── constants/
├── enums/
├── extensions/
├── logging/
├── concurrency/
├── audio/
├── device/
└── utils/

Rules:
- ZERO dependencies on other modules
- Foundation layer only
```

```text
core/data/
├── database/
├── dao/
├── entity/
├── converters/
├── repository/
└── datastore/

Rules:
- Depends only on core/common
- No service dependencies
- No Android UI dependencies
```

```text
core/audioengine/
├── cpp/
├── include/
├── NativeAudioBridge.kt
├── NativeLoader.kt
├── AudioPipelineController.kt
└── NativeSafetyController.kt

Rules:
- Depends only on common
- No Room access
- No Accessibility access
- Isolated JNI boundary
```

```text
core/services/
├── accessibility/
├── automation/
├── audio/
├── bootstrap/
├── capture/
├── compat/
├── crash/
├── diagnostics/
├── fallback/
├── foreground/
├── headless/
├── ipc/
├── managers/
├── media/
├── memory/
├── metrics/
├── monitoring/
├── oem/
├── permissions/
├── playback/
├── projection/
├── provider/
├── receivers/
├── resilience/
├── scheduler/
├── security/
├── stability/
├── state/
├── storage/
├── testing/
├── updates/
└── voip/

Rules:
- Main orchestration layer
- Coordinates all subsystems
- Owns daemon lifecycle
```

```text
core/ui/
├── BootstrapActivity.kt
├── ProjectionPermissionActivity.kt
├── CrashSafeActivity.kt
└── HeadlessModeLauncher.kt

Rules:
- Minimal trampoline UI only
- No heavy rendering
- No Compose
- No persistent dashboard Activities
```

---

# 2. Android 13 Operational Constraints

## 2.1 Accessibility Constraints

AccessibilityService is used for:
- daemon bootstrap,
- event monitoring,
- automation recovery,
- permission flow assistance.

Accessibility does NOT:
- grant silent permissions,
- bypass Android consent,
- bypass SELinux,
- or allow hidden API execution.

Automation is strictly rate-limited.

---

## 2.2 MediaProjection Constraints

MediaProjection:
- requires explicit user approval,
- may revoke tokens unpredictably,
- may fail after reboot,
- may fail after process death,
- may fail after screen-lock transitions.

Projection metadata persists.
Projection validity does NOT persist reliably.

All projection sessions must be revalidated.

Apps may opt out of AudioPlaybackCapture:
- DRM apps,
- secure media apps,
- protected streaming platforms.

---

## 2.3 Foreground Launch Restrictions

Android 13 restricts:
- background activity launches,
- silent projection launches,
- locked-screen UI launches.

ProjectionPermissionActivity may only launch when:
- foreground eligibility exists,
- screen unlocked,
- user interaction mediation available,
- notification trampoline active.

ProjectionLaunchCoordinator enforces these checks.

---

## 2.4 Audio Routing Constraints

The daemon attempts to:
- force speaker routing,
- maintain MODE_IN_COMMUNICATION,
- maintain speakerphone state,
- replay captured media to speaker.

Actual routing behavior remains partially dependent on:
- OEM firmware,
- AudioPolicyManager,
- Unisoc audio implementation,
- physical headset-detection circuitry.

The daemon cannot directly override:
- Audio HAL,
- kernel routing,
- hardware codec detection.

---

# 3. Complete Startup Sequence (Accessibility-First)

## Phase 1 — Installation

```text
1. User installs APK
2. System registers launcher icon
3. User DOES NOT open launcher icon
4. User opens:
   Settings → Accessibility
5. User enables:
   VyzorixAudioRouter
```

Reason:
Launching Activities directly on Nokia C22 may trigger:
- renderer instability,
- zygote collapse,
- soft reboot behavior.

AccessibilityService becomes the primary daemon entrypoint.

---

## Phase 2 — Accessibility Binding

```text
RouterAccessibilityService.onServiceConnected()
```

Actions:
```text
1. AccessibilityStateTracker.markEnabled()
2. LauncherIconHider.disableLauncherEntry()
3. VyzorixAppInitializer.execute()
4. BootstrapCoordinator.begin()
```

Launcher icon hiding:
- reduces accidental UI launches,
- reduces renderer interaction,
- minimizes crash triggers.

This is NOT a security mechanism.

OEMs may still expose:
- launcher entries,
- Open buttons,
- cached shortcuts.

---

## Phase 3 — Initialization

```text
VyzorixAppInitializer.execute()
```

Initializes:
```text
- Notification channels
- Database migrations
- Keystore
- Runtime configuration
- Logging infrastructure
- Coroutine dispatchers
- WorkManager
- Runtime stores
```

PermissionFlowCoordinator:
```text
- verifies notification permission
- verifies overlay permission
- verifies package install permission
- coordinates request flows
```

It does NOT silently grant permissions.

---

## Phase 4 — Projection Coordination

```text
ProjectionLaunchCoordinator.requestProjection()
```

Checks:
```text
- screen unlocked
- foreground eligibility
- automation cooldown state
- notification visibility
```

If safe:
```text
1. FullScreenIntentBridge posts trampoline notification
2. User taps notification
3. ProjectionPermissionActivity launches
4. User grants projection
5. ProjectionTokenManager stores metadata
6. Activity exits immediately
```

---

## Phase 5 — Headless Daemon Launch

```text
HeadlessBootSequence.execute()
```

Starts:
```text
PersistentAudioService.startForeground()
```

Then:
```text
DaemonLifecycleManager.startAll()
```

Subsystem order:
```text
1. Route managers
2. Projection managers
3. Audio focus managers
4. Playback pipeline
5. Monitoring systems
6. Diagnostics
7. Update systems
```

Critical systems start first.

Noncritical observers start later.

---

## Phase 6 — Speaker Force Strategy

```text
SpeakerForceEngine.startLoop()
```

Loop:
```text
1. Query route state
2. Detect phantom headset lock
3. Set MODE_IN_COMMUNICATION
4. Enable speakerphone
5. Reassert communication device
6. Verify route success
7. Retry every 500ms if drift detected
```

OEM-specific behavior:
```text
NokiaC22DeviceProfile.apply()
```

may:
- increase correction frequency,
- modify retry intervals,
- adjust route assertions.

---

## Phase 7 — Audio Capture Pipeline

```text
MediaProjectionCaptureSession.open()
```

Flow:
```text
MediaProjection
→ AudioRecord
→ AudioBufferPool
→ NativeAudioBridge
→ C++ ring buffer
→ PCM processing
→ AudioTrack
→ DEVICE_OUT_SPEAKER
```

Playback uses:
```text
USAGE_VOICE_COMMUNICATION
```

to maximize:
- speaker priority,
- communication-mode routing authority.

---

## Phase 8 — Monitoring + Safety

Monitoring systems activate:
```text
- DaemonWatchdog
- ProcessHealthMonitor
- DeviceThermalMonitor
- SoftRebootPredictor
- RendererFailureDetector
- NetworkStateMonitor
- PipelineHealthChecker
- CrashLoopProtector
```

These systems:
- detect instability,
- detect route drift,
- detect projection death,
- detect memory pressure,
- detect thermal throttling,
- detect zygote-style resets.

---

## Phase 9 — Steady State

Operational loops:
```text
- Dashboard updates every 10s
- Watchdog heartbeat every 5s
- Route verification every 500ms
- Projection health verification
- Focus arbitration
- Recovery orchestration
- Update polling
```

The daemon now operates:
- fully headless,
- notification-driven,
- without persistent UI rendering.

---

# 4. Audio Data Flow

```text
SYSTEM AUDIO
↓
MediaProjection
↓
AudioPlaybackCapture
↓
AudioRecord
↓
AudioBufferPool
↓
NativeAudioBridge
↓
C++ Ring Buffer
↓
PCM Processing
↓
AudioPipelineController
↓
SpeakerPlaybackEngine
↓
AudioTrack
↓
DEVICE_OUT_SPEAKER
↓
Physical Speaker
```

Important:
AudioPlaybackCapture only captures:
- eligible audio streams,
- non-DRM playback,
- apps permitting capture.

---

# 5. Automation Safety Architecture

Accessibility automation is dangerous if unmanaged.

Potential risks:
```text
- recursive settings launches
- endless permission loops
- SurfaceFlinger instability
- rapid Activity churn
- OEM watchdog triggers
```

Safety systems:
```text
AutomationRateLimiter
AutomationCooldownPolicy
AutomationSafetyGate
HumanPresenceDetector
```

These enforce:
```text
- retry ceilings
- cooldown delays
- user-presence validation
- deadman-stop conditions
```

Automation may fully disable itself if instability detected.

---

# 6. Audio Focus Arbitration

The daemon depends heavily on:
```text
MODE_IN_COMMUNICATION
```

Focus systems manage:
```text
- reclaim timing
- interruption recovery
- focus conflicts
- ducking behavior
- communication persistence
```

Key components:
```text
FocusRecoveryCoordinator
FocusConflictResolver
FocusPriorityPolicy
FocusPersistenceEngine
```

Goal:
Maintain stable speaker routing without fighting the OS excessively.

---

# 7. Memory Pressure Adaptation

Nokia C22 is treated as:
```text
LOW RAM / AGGRESSIVE OEM
```

Memory systems:
```text
MemoryClassProfiler
LowRamModeController
CacheBudgetManager
ServiceTrimCoordinator
```

Adaptive degradation may disable:
```text
- overlays
- diagnostics
- native DSP
- high-frequency polling
- expanded dashboard sections
```

This prevents:
- LMK kills,
- memory exhaustion,
- runaway allocations.

---

# 8. Failure Boundaries & Recovery

Recovery pipeline:
```text
1. Detect failure
2. Isolate subsystem
3. Dump runtime state
4. Attempt recovery
5. Verify recovery
6. Escalate fallback
7. Enter SAFE_MODE if necessary
```

Fallback examples:
```text
Projection fails
→ CommunicationModeFallback

Native JNI unstable
→ Java-only pipeline

Thermal overload
→ reduce polling + disable DSP

Crash loop
→ StartupBackoffScheduler
```

---

# 9. Recovery Escalation Strategy

```text
NORMAL
↓
RECOVERING
↓
SAFE_MODE
↓
SILENT_RECOVERY_MODE
↓
MANUAL_USER_INTERVENTION
```

The daemon prioritizes:
```text
stability > performance > diagnostics
```

---

# 10. Thread Model

## Main Thread
Used for:
```text
- Accessibility callbacks
- notification updates
- lightweight UI trampolines
- BroadcastReceivers
```

No:
```text
- DB writes
- heavy audio processing
- network downloads
```

---

## AppDispatchers.IO
Used for:
```text
- Room
- file IO
- logging
- networking
- keystore
- update downloads
```

---

## AppDispatchers.Default
Used for:
```text
- PCM processing
- metrics
- diagnostics correlation
- aggregation
- checksum verification
```

---

## Dedicated Threads
Used for:
```text
- AudioRecord capture
- AudioTrack playback
- native DSP
- JNI isolation
```

JNI crashes must never collapse:
- coroutine pools,
- foreground services,
- watchdog infrastructure.

---

# 11. Update Architecture

Flow:
```text
UpdateChecker
→ UpdateDownloader
→ checksum verification
→ FileProvider URI
→ ACTION_INSTALL_PACKAGE
→ user confirmation
→ system installation
```

Android 13 restrictions:
```text
- user confirmation REQUIRED
- silent install impossible
- package signatures must match
```

---

# 12. Notification Dashboard Architecture

The daemon intentionally avoids:
- Activities,
- fragments,
- Compose dashboards,
- heavy rendering.

Instead:
```text
RemoteViews notification dashboard
```

provides:
```text
- daemon status
- route state
- diagnostics
- health metrics
- update state
```

Benefits:
```text
- lower renderer pressure
- survives process instability
- avoids Nokia UI crash path
```

---

# 13. Android Reality Constraints

VyzorixAudioRouter:
```text
CAN:
- capture eligible media audio
- replay through speaker
- maintain communication mode
- survive reboots
- monitor instability
- automate recovery flows
- operate fully headless
```

VyzorixAudioRouter:
```text
CANNOT:
- override Audio HAL directly
- bypass SELinux
- patch audioserver
- silently grant permissions
- guarantee MediaProjection persistence
- capture DRM-blocked audio
- guarantee speaker routing against all OEM behavior
```

Routing success therefore depends partly on:
```text
- Nokia firmware behavior
- Unisoc audio policy behavior
- hardware headset-detection precedence
- Android focus arbitration
- MediaProjection reliability
```

---

# 14. Core Operational Philosophy

The daemon prioritizes:

```text
1. Device stability
2. Persistent operation
3. Speaker-route persistence
4. Graceful degradation
5. Recovery survivability
6. Diagnostic visibility
7. Minimal UI exposure
```

This is fundamentally:
```text
a resilient Accessibility-driven audio-routing daemon
```

—not:
```text
a system patching framework
```
