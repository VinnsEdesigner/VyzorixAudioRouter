# DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md — Bootstrap, Initialization, and IPC Orchestration Blueprint

## Document Purpose
This document is Part 1 of the 8-part Vyzorix System Mapping. It details the bootstrap, process security, UI trampolines, foreground services, broadcast receivers, content providers, and Inter-Process Communication (IPC) layers. This document serves as the implementation specification for hands-free background consent, boot recovery, and local command-control boundaries.

---

# 1. System Cold-Boot and Headless Initialization Lifecycle

The following mapping outlines the complete lifecycle from a cold system reboot to the restoration of services, bypassing the standard launcher and avoiding visual activity rendering entirely:

```text
                     DEVICE COLD BOOT / SYSTEM COMPLETED REBOOT
                                         │
                                         ▼
                      [RECEIVE_BOOT_COMPLETED Broadcast]
                                         │
                                         ▼
                                   BootReceiver
                                         │
                                         ▼
                                 BootStateRestorer
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 │                                               │
    Is Accessibility Enabled?                       Is Accessibility Disabled?
                 │                                               │
                 ▼ (YES: Bypasses UI Setup)                      ▼ (NO: Re-engagement)
       HeadlessBootSequence                         AccessibilityRecoveryHandler
                 │                                               │
                 ▼                                               ▼
     VyzorixAppInitializer                           UiRecoveryDaemon (Settings Intent)
                 │                                               │
                 ▼                                               ▼
    Keystore & Room DB Init                            User Re-enables Service
                 │                                               │
                 └───────────────────────┬───────────────────────┘
                                         │
                                         ▼
                             PersistentAudioService
                             (foregroundServiceType)
                                         │
                                         ▼
                             DaemonLifecycleManager
                                         │
                 ┌───────────────────────┼───────────────────────┐
                 ▼                       ▼                       ▼
       [Route Managers]       [Projection Managers]    [Playback & Capture]
```

---

# 2. Module Blueprint: `:app` (Application Sign-off)

The `:app` module acts as the aggregation root. It configures the Gradle build rules, Proguard obfustication, release keys, and executes the early-stage process entry guards.

```text
app/src/main/kotlin/com/vyzorix/audiorouter/
├── VyzorixApplication.kt
├── VyzorixAppInitializer.kt
├── BuildInfo.kt
├── ProcessEntryGuard.kt
├── StrictModeInitializer.kt
└── StartupProfiler.kt
```

### 2.1 `VyzorixApplication.kt`
*   **Path**: `app/src/main/kotlin/com/vyzorix/audiorouter/VyzorixApplication.kt`
*   **Architectural Role**: Binds the global application context lifecycle. On execution, it intercepts uncaught JVM exceptions across all threads and redirects crashes to disk logs. It initializes process entry protection and profiles cold-start latencies.
*   **Core APIs & State Dependencies**: Binds to `android.app.Application` and initializes `GlobalExceptionHandler`.
*   **Failure Boundaries & Escape Hatches**: This class must run no blocking database or disk operations. If `onCreate()` stalls or crashes, the system terminates the process immediately.

### 2.2 `VyzorixAppInitializer.kt`
*   **Path**: `app/src/main/kotlin/com/vyzorix/audiorouter/VyzorixAppInitializer.kt`
*   **Architectural Role**: Coordinates sequential component setups. It configures notification channels, database migrations, and cryptographic keystore keys before background daemon lifecycles start.
*   **Core APIs & State Dependencies**: Relies on `NotificationChannelManager`, `AppDatabase`, and `KeystoreManager`.
*   **Failure Boundaries**: If the local database is corrupted, it catches the exception and falls back to a clean database rebuild to prevent startup crashes.

### 2.3 `BuildInfo.kt`
*   **Path**: `app/src/main/kotlin/com/vyzorix/audiorouter/BuildInfo.kt`
*   **Architectural Role**: Compile-time constant wrapper. Keeps track of version code, git hashes, package namespaces, and active build variant flags to expose to update clients and remote telemetry backends.
*   **State Dependencies**: Binds directly to Gradle’s `BuildConfig` properties.

### 2.4 `ProcessEntryGuard.kt`
*   **Path**: `app/src/main/kotlin/com/vyzorix/audiorouter/ProcessEntryGuard.kt`
*   **Architectural Role**: Prevents duplicate process initialization. Since different services can spawn in separate system processes, this guard uses local lockfiles and process ID checks to block concurrent process collisions.
*   **State Dependencies**: Relies on `FileChannel.tryLock()` on a private application-level file descriptor.
*   **Failure Boundaries**: If the lockfile is corrupted or locked by a crashed zombie process, the guard verifies process liveness before force-reclaiming the lock to avoid deadlock blocks.

### 2.5 `StrictModeInitializer.kt`
*   **Path**: `app/src/main/kotlin/com/vyzorix/audiorouter/StrictModeInitializer.kt`
*   **Architectural Role**: Configures strict thread execution rules in debug builds. It detects if any thread performs accidental disk or network IO directly on the Main UI thread, throwing warnings immediately.
*   **State Dependencies**: Binds to `android.os.StrictMode`.

### 2.6 `StartupProfiler.kt`
*   **Path**: `app/src/main/kotlin/com/vyzorix/audiorouter/StartupProfiler.kt`
*   **Architectural Role**: Measures cold-start delays. It captures timestamps at the beginning of `Application.attachBaseContext()` and compares them against the time `PersistentAudioService` finishes binding. Telemetry is sent to identify if Nokia's low-memory killer is putting excessive launch strain on the JVM.

---

# 3. Module Blueprint: `:core:ui` (Trampoline Interfaces)

The `:core:ui` module contains translucent, short-lived trampoline activities designed to handle system permission intents and then exit immediately. This avoids rendering any persistent UI surfaces that could trigger the Nokia C22's Zygote/SurfaceFlinger crashes.

```text
core/ui/src/main/kotlin/com/vyzorix/audiorouter/ui/
├── BootstrapActivity.kt
├── ProjectionPermissionActivity.kt
├── CrashSafeActivity.kt
├── HeadlessModeLauncher.kt
└── UiExitController.kt
```

### 3.1 `BootstrapActivity.kt`
*   **Path**: `core/ui/src/main/kotlin/com/vyzorix/audiorouter/ui/BootstrapActivity.kt`
*   **Architectural Role**: Initial launch entrypoint. It displays a brief configuration prompt before launching `Settings.ACTION_ACCESSIBILITY_SETTINGS`. Once the accessibility service is granted, it triggers `LauncherIconHider.nukeLauncherIcon()` and immediately exits.
*   **State Dependencies**: Binds to the system Accessibility permission state.
*   **Failure Boundaries**: If settings fail to launch, it redirects to the app info screen.

### 3.2 `ProjectionPermissionActivity.kt`
*   **Path**: `core/ui/src/main/kotlin/com/vyzorix/audiorouter/ui/ProjectionPermissionActivity.kt`
*   **Architectural Role**: Mediates MediaProjection. It launches the un-bypassable system casting warning dialog. Once the token is approved by the user (or automated by the accessibility daemon), it forwards the result to `ProjectionTokenManager` and finishes.
*   **State Dependencies**: Relies on `MediaProjectionManager.createScreenCaptureIntent()`.
*   **Failure Boundaries**: If the user denies consent, it transitions to `VoIP-only` routing fallback.

### 3.3 `CrashSafeActivity.kt`
*   **Path**: `core/ui/src/main/kotlin/com/vyzorix/audiorouter/ui/CrashSafeActivity.kt`
*   **Architectural Role**: A baseline Activity with all hardware rendering and window animations disabled. If the Nokia C22's GPU driver freezes during an app launch, this activity bypasses standard rendering blocks to keep the permission dialog accessible.

### 3.4 `HeadlessModeLauncher.kt`
*   **Path**: `core/ui/src/main/kotlin/com/vyzorix/audiorouter/ui/HeadlessModeLauncher.kt`
*   **Architectural Role**: Confirms successful background service startup. It checks `PersistentAudioService` health and terminates itself in under 50ms, ensuring no window frames persist in the system’s Recents task list.

### 3.5 `UiExitController.kt`
*   **Path**: `core/ui/src/main/kotlin/com/vyzorix/audiorouter/ui/UiExitController.kt`
*   **Architectural Role**: Clean-up utility. It clears all activity references from the process memory, finishes pending transitions, and triggers garbage collection.

---

# 4. Submodule: `bootstrap` (State Transitions)

Manages early-stage authorization checks, state transitions, and state restoration on device reboot.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/
├── TrampolineService.kt
├── BootstrapCoordinator.kt
├── PermissionStateMachine.kt
├── ServiceTrampoline.kt
├── SelfDestructController.kt
├── LauncherIconHider.kt
└── BootStateRestorer.kt
```

### 4.1 `TrampolineService.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/TrampolineService.kt`
*   **Architectural Role**: Binds the boot process. It runs as a lightweight foreground service during initialization, keeping the system process alive while permission states are resolved.

### 4.2 `BootstrapCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/BootstrapCoordinator.kt`
*   **Architectural Role**: Coordinates startup sequences. It checks if the accessibility and projection permissions are ready. If yes, it hands control to `ServiceTrampoline`; if no, it launches the required UI overlays.

### 4.3 `PermissionStateMachine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/PermissionStateMachine.kt`
*   **Architectural Role**: Enforces state safety. It models permission acquisitions:
    `INITIAL` -> `ACCESSIBILITY_GRANTED` -> `NOTIFICATIONS_GRANTED` -> `PROJECTION_GRANTED` -> `READY`.
*   **Failure Boundaries**: If any stage is revoked, it reverts to the previous step and triggers the corresponding recovery path.

### 4.4 `ServiceTrampoline.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/ServiceTrampoline.kt`
*   **Architectural Role**: Handles execution handoff. It launches `PersistentAudioService` and signals `TrampolineService` to stop, ensuring only one foreground service type runs at a time.

### 4.5 `SelfDestructController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/SelfDestructController.kt`
*   **Architectural Role**: Stops all transitional initialization services once the daemon reaches steady-state, freeing RAM for audio processing.

### 4.6 `LauncherIconHider.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/LauncherIconHider.kt`
*   **Architectural Role**: Hides the app icon. It disables `BootstrapActivity` programmatically using the package manager. This prevents users from tapping the icon, bypassing the Nokia C22's launcher crash bug.
  ```kotlin
  packageManager.setComponentEnabledSetting(
      ComponentName(context, BootstrapActivity::class.java),
      PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
      PackageManager.DONT_KILL_APP
  )
  ```

### 4.7 `BootStateRestorer.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/bootstrap/BootStateRestorer.kt`
*   **Architectural Role**: Restores state on reboot. It reads `last_state.json`, checks if the projection token was valid, and automatically re-binds the services.

---

# 5. Submodule: `managers` (Subsystem Coordinators)

The `managers` submodule coordinates individual subsystems (routing, capture, and updates) under a unified controller API.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/managers/
├── AudioRouteManager.kt
├── MediaProjectionSession.kt
├── DaemonLifecycleManager.kt
├── SpeakerForceManager.kt
└── RecoveryOrchestrator.kt
```

### 5.1 `AudioRouteManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/managers/AudioRouteManager.kt`
*   **Architectural Role**: Binds the audio route. It acts as the central interface for executing speakerphone overrides, monitoring active hardware routes, and logging device transitions.

### 5.2 `MediaProjectionSession.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/managers/MediaProjectionSession.kt`
*   **Architectural Role**: Binds `MediaProjection` lifecycles. It monitors token revocation callbacks and notifies the capture engines to halt or re-request tokens.

### 5.3 `DaemonLifecycleManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/managers/DaemonLifecycleManager.kt`
*   **Architectural Role**: Coordinates start/stop sequences. It enforces the strict lifecycle order:
    1. Focus handlers.
    2. Routing engines.
    3. Capture session.
    4. Schedulers.

### 5.4 `SpeakerForceManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/managers/SpeakerForceManager.kt`
*   **Architectural Role**: The single source of truth for routing. It evaluates current system modes and commands the `SpeakerForceEngine` to force or release speaker routing.

### 5.5 `RecoveryOrchestrator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/managers/RecoveryOrchestrator.kt`
*   **Architectural Role**: Oversees recovery actions. It evaluates subsystem failures and triggers target fallbacks (e.g., restarting capture, cycling sockets).

---

# 6. Submodule: `foreground` (Service Persistence)

The `foreground` submodule manages persistent background execution, RemoteViews notification dashboards, and action broadcast intent receivers.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/
├── PersistentAudioService.kt
├── ServiceNotification.kt
├── ServiceNotificationDashboard.kt
├── SilentKeepAliveService.kt
# NOTE: ServiceHeartbeat.kt folded into LivenessProbe (ADR-0007).
├── LivenessProbe.kt
├── PipelineHealthChecker.kt
├── RecoveryCoordinator.kt
├── signals/ (Layer B — see ADR-0007)
├── BootReceiver.kt
└── actions/
    ├── NotificationActionReceiver.kt
    ├── QuickToggleAction.kt
    ├── RestartPipelineAction.kt
    └── EmergencyStopAction.kt
```

### 6.1 `PersistentAudioService.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/PersistentAudioService.kt`
*   **Architectural Role**: Primary service coordinator. It runs as an OS-protected foreground service (`foregroundServiceType="mediaPlayback"`). It holds the capture loops, native JNI bridges, and C2 WebSocket managers alive.

### 6.2 `ServiceNotification.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/ServiceNotification.kt`
*   **Architectural Role**: Configures the base notification layout. It manages builder configurations, priorities, and sets non-clickable intent flags.

### 6.3 `ServiceNotificationDashboard.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/ServiceNotificationDashboard.kt`
*   **Architectural Role**: Updates the dashboard UI. It collects telemetry data from `DaemonStatusAggregator` every 10 seconds and pushes layout modifications to the system status bar via `RemoteViews`.

### 6.4 `SilentKeepAliveService.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/SilentKeepAliveService.kt`
*   **Architectural Role**: Dual-service backup. It runs as a low-priority bound service to maintain binder references, preventing the OS from killing the main process when resources are low.

### 6.5 ~~`ServiceHeartbeat.kt`~~ — folded into `LivenessProbe.kt` (ADR-0007)
*   **Architectural Role**: Heartbeat *is* the mechanism the liveness probe uses internally to ping active threads at 5-second intervals. The old `ServiceHeartbeat.kt` is no longer a separate class — see `LivenessProbe.kt` instead, which is the Layer B signal that answers "is the daemon process responsive?" Liveness ping output flows to `DaemonStatusAggregator`; recovery decisions belong to `RecoveryCoordinator` (Layer A).

### 6.6 `RecoveryCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/RecoveryCoordinator.kt`
*   **Architectural Role**: Re-binds crashed services. It intercepts crash loops and executes the `StartupBackoffScheduler` to delay re-registrations safely.

### 6.7 `BootReceiver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/BootReceiver.kt`
*   **Architectural Role**: Passive boot listener. It receives the `RECEIVE_BOOT_COMPLETED` broadcast and triggers `BootStateRestorer` to launch the headless boot sequence.

### 6.8 `NotificationActionReceiver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/actions/NotificationActionReceiver.kt`
*   **Architectural Role**: Binds notification buttons. It processes broadcast clicks from the dashboard layout (e.g., Quick Toggle, Restart Pipeline).

### 6.9 `QuickToggleAction.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/actions/QuickToggleAction.kt`
*   **Architectural Role**: Reroute switch. It instantly toggles speaker-forcing on or off, updating the active `RemoteViews` status.

### 6.10 `RestartPipelineAction.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/actions/RestartPipelineAction.kt`
*   **Architectural Role**: Resets pipelines. It halts, flushes, and restarts the `AudioRecord` and `AudioTrack` threads without restarting the entire app process.

### 6.11 `EmergencyStopAction.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/foreground/actions/EmergencyStopAction.kt`
*   **Architectural Role**: Emergency stop. It stops all services and releases permissions if the device enters a bootloop state.

---

# 7. Submodule: `headless` (No-UI Coordinators)

The `headless` submodule manages operations without displaying active window surfaces.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/headless/
├── HeadlessDaemonController.kt
├── HeadlessBootSequence.kt
├── SilentPermissionFlow.kt
└── InvisibleRecoveryCoordinator.kt
```

### 7.1 `HeadlessDaemonController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/headless/HeadlessDaemonController.kt`
*   **Architectural Role**: Headless coordinator. It manages background processes, ensures no activities are spawned during runtime, and routes log payloads directly to local databases.

### 7.2 `HeadlessBootSequence.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/headless/HeadlessBootSequence.kt`
*   **Architectural Role**: Bypasses UI during start. It launches core services directly on system boot, avoiding standard launcher activities and preventing UI rendering overhead.

### 7.3 `SilentPermissionFlow.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/headless/SilentPermissionFlow.kt`
*   **Architectural Role**: Checks permission requirements. It handles silent permission verification and schedules notification prompts if any permissions are missing.

### 7.4 `InvisibleRecoveryCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/headless/InvisibleRecoveryCoordinator.kt`
*   **Architectural Role**: Headless recovery manager. It handles component restarts behind the scenes, ensuring the user experiences zero UI flashing or service interruption.

---

# 8. Submodule: `receivers` (Broadcast Listeners)

The `receivers` submodule listens for system-wide broadcasts and adjusts background services accordingly.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/receivers/
├── NoOpReceiver.kt
├── StatusRefreshReceiver.kt
├── PackageChangeReceiver.kt
├── MediaButtonReceiver.kt
└── ScreenStateReceiver.kt
```

### 8.1 `NoOpReceiver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/receivers/NoOpReceiver.kt`
*   **Architectural Role**: Null-intent receiver. It provides a placeholder target for notification clicks, ensuring the notification remains strictly read-only and doesn't launch activities on tap.

### 8.2 `StatusRefreshReceiver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/receivers/StatusRefreshReceiver.kt`
*   **Architectural Role**: Manual refresh trigger. It receives user clicks and forces an immediate dashboard telemetry refresh.

### 8.3 `PackageChangeReceiver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/receivers/PackageChangeReceiver.kt`
*   **Architectural Role**: Package listener. It monitors new app installations and removals. It notifies `AppLaunchObserver` to update blacklist databases on app install.

### 8.4 `MediaButtonReceiver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/receivers/MediaButtonReceiver.kt`
*   **Architectural Role**: Media button interceptor. It blocks incoming hardware media events (e.g., from headsets) to prevent other apps from hijacking the audio routing path.

### 8.5 `ScreenStateReceiver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/receivers/ScreenStateReceiver.kt`
*   **Architectural Role**: Monitors screen states. It pauses high-frequency audio polling and drops WebSocket intervals when the screen is off, conserving battery.

---

# 9. Submodule: `provider` (Safe Sharing Boundaries)

Exposes secure sharing boundaries for logs via Content URIs.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/provider/
├── DiagnosticContentProvider.kt
└── AuthorityDefinitions.kt
```

### 9.1 `DiagnosticContentProvider.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/provider/DiagnosticContentProvider.kt`
*   **Architectural Role**: Secure file exporter. It extends `ContentProvider` to wrap and share local encrypted ZIP log bundles, ensuring safe data transfer using Android's standard sharing contracts.

### 9.2 `AuthorityDefinitions.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/provider/AuthorityDefinitions.kt`
*   **Architectural Role**: Specifies Content Provider authority URIs (`com.vyzorix.audiorouter.diagnostics`) and sets permission flags.

---

# 10. Submodule: `ipc` (Inter-Process Communications)

Coordinates IPC bindings, command execution, and response routing back to the control server.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/ipc/
├── AudioRouterBinder.kt
├── ServiceConnectionManager.kt
├── RemoteCommandDispatcher.kt
├── RemoteCommandExecutor.kt
└── RemoteCommandResultDispatcher.kt
```

### 10.1 `AudioRouterBinder.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/ipc/AudioRouterBinder.kt`
*   **Architectural Role**: Binder implementation. It exposes the AIDL interface methods, allowing the trampoline UI and services to query daemon status parameters securely.

### 10.2 `ServiceConnectionManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/ipc/ServiceConnectionManager.kt`
*   **Architectural Role**: Binds IPC connections. It manages service binding, processes dead-object exceptions, and re-binds failed connections.

### 10.3 `RemoteCommandDispatcher.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/ipc/RemoteCommandDispatcher.kt`
*   **Architectural Role**: Central command dispatcher. It routes received commands to their target modules (e.g., forwarding "FORCE_SPEAKER" to the routing engine).

### 10.4 `RemoteCommandExecutor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/ipc/RemoteCommandExecutor.kt`
*   **Architectural Role**: Command execution manager. It validates, decrypts, and executes incoming C2 commands (such as HAL reset or trampoline re-grants).

### 10.5 `RemoteCommandResultDispatcher.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/ipc/RemoteCommandResultDispatcher.kt`
*   **Architectural Role**: Response dispatcher. It compiles command execution results into JSON format and dispatches them back to the server via active WebSockets or HTTP postbacks.
