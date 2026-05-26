# DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md — Resilience, Fallbacks, and Recovery Strategies

## Document Purpose
This document is Part 4 of the 8-part Vyzorix System Mapping. It details the multi-layered fallback states, exponential backoff schedulers, binder reconnection loops, native JNI crash protectors, and system-level `audioserver` reconnect handlers. This document serves as the implementation specification for ensuring high availability and self-healing resilience on the Nokia C22 under heavy OS-level process reclamation.

---

# 1. System Recovery and Re-Binding Orchestration Flow

The following mapping outlines the programmatic steps executed by the resilience engine when a Binder link dies or the system-level `audioserver` crashes:

```text
                        SYSTEM AUDIOSERVER CRASH / BINDER UNBIND
                                           │
                                           ▼
                    [IBinder.DeathRecipient.binderDied() Callback]
                                           │
                                           ▼
                            AudioServerReconnectHandler
                                           │
                                           ▼
                                   BinderRecoveryLoop
                                           │
                                           ▼
                                 CrashLoopProtector
                                           │
               ┌───────────────────────────┴───────────────────────────┐
               │                                                       │
      Crashes > Max (3x/5min)?                                Crashes <= Max?
               │                                                       │
               ▼ (YES: ESCALATE)                                       ▼ (NO: RE-BIND)
       SafeModeController                                    StartupBackoffScheduler
               │                                                       │
               ▼                                                       ▼
       [Enter Fallback]                                       Re-bind Core Services
               │                                                       │
  ┌────────────┼────────────┐                                          ▼
  ▼            ▼            ▼                                  Validate Session
Playback    VoIP-Only     Silent                             (verify stream headers)
Capture     Speaker      Recovery                                      │
Fallback    Fallback     Mode                                          ▼
                                                              Resume Headless Loop
```

---

# 2. Submodule: `resilience` (The Re-Binding Engine)

The `resilience` submodule manages IPC liveness, recovers from dead binder interfaces, isolates crash-prone operations, and executes the watchdog escalation ladders.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/resilience/
├── AudioServerReconnectHandler.kt
├── BinderRecoveryLoop.kt
├── ThreadIsolationExecutor.kt
├── DeadObjectRecovery.kt
└── WatchdogEscalationPolicy.kt
```

### 2.1 `AudioServerReconnectHandler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/resilience/AudioServerReconnectHandler.kt`
*   **Architectural Role**: Recovers from system audio daemon crashes. If the Android system-level `audioserver` daemon crashes (common on low-RAM Go Edition devices), this handler catches the binder death, flushes active `AudioTrack` references, and schedules a 1500ms delay before rebuilding the capture and playback engines.
*   **Core APIs & State Dependencies**: Registers a death recipient listener directly on the native `IBinder` reference of the system's active media server.
*   **Failure Boundaries & Escape Hatches**: If the `audioserver` fails to restart or remains dead, this handler triggers a complete fallback sequence to `CommunicationModeFallback` to route remaining system-mix segments.

### 2.2 `BinderRecoveryLoop.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/resilience/BinderRecoveryLoop.kt`
*   **Architectural Role**: Binds background reconnection loops. It manages the sequential re-binding of IPC interfaces (`IAudioRouterService`) between separate application processes when binders crash.
*   **State Dependencies**: Relies on `ServiceConnectionManager` state monitors.

### 2.3 `ThreadIsolationExecutor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/resilience/ThreadIsolationExecutor.kt`
*   **Architectural Role**: Isolates crash-prone operations. It executes risk-heavy native JNI calls on isolated daemon worker threads, preventing memory violations or JNI crashes from corrupting the main coroutine pool.
*   **State Dependencies**: Creates a single-threaded Java `ExecutorService` bound to a specific kernel-level thread affinity.

### 2.4 `DeadObjectRecovery.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/resilience/DeadObjectRecovery.kt`
*   **Architectural Role**: Recovers from `DeadObjectException` errors. When a remote binder interface becomes unresponsive, this utility interceptor terminates stale binders, clears caches, and re-establishes a clean connection path.

### 2.5 `WatchdogEscalationPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/resilience/WatchdogEscalationPolicy.kt`
*   **Architectural Role**: Controls the escalation ladder. If repeated route-forcing attempts fail (e.g., due to an active hardware-level headset sensor lockup), it escalates mitigation stages:
    `STAGE_1 (Retry SetSpeaker)` -> `STAGE_2 (Cycle Bluetooth/Profiles)` -> `STAGE_3 (Force Soft HAL Reset)` -> `STAGE_4 (VoIP Fallback)`.

---

# 3. Submodule: `stability` (The Startup Schedulers)

The `stability` submodule manages startup crash prevention, limits process restart frequencies, and handles safe-mode deactivations.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/stability/
├── CrashLoopProtector.kt
├── SafeModeController.kt
├── StartupBackoffScheduler.kt
└── ProcessRestartLimiter.kt
```

### 3.1 `CrashLoopProtector.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/stability/CrashLoopProtector.kt`
*   **Architectural Role**: Detects and mitigates rapid crash loops. It tracks the frequency of service initializations. If the daemon crashes more than 3 times within a rolling 5-minute window, it triggers `SafeModeController` to halt unneeded modules.
*   **State Dependencies**: Persists startup timestamps inside `DaemonDatabase`.

### 3.2 `SafeModeController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/stability/SafeModeController.kt`
*   **Architectural Role**: Manages the safe mode state. Under safe-mode conditions, it shuts down all non-essential modules (FCM push registries, WebSocket streams, disk-intensive logs) and keeps only the core `SpeakerForceEngine` active.

### 3.3 `StartupBackoffScheduler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/stability/StartupBackoffScheduler.kt`
*   **Architectural Role**: Delays service restarts exponentially. If the service crashes and is scheduled for restart, this scheduler applies a delay (e.g., 5s, 30s, 300s) to allow the OS to stabilize.

### 3.4 `ProcessRestartLimiter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/stability/ProcessRestartLimiter.kt`
*   **Architectural Role**: Enforces process-level start limits. It blocks restart storms by checking the time elapsed since the last launch attempt.

---

# 4. Submodule: `fallback` (The Degradation Paths)

The `fallback` submodule implements multi-layered, graded degradation profiles that execute when critical features fail or become unstable.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fallback/
├── PlaybackCaptureFallback.kt
├── CommunicationModeFallback.kt
├── SpeakerBypassFallback.kt
└── SilentRecoveryMode.kt
```

### 4.1 `PlaybackCaptureFallback.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fallback/PlaybackCaptureFallback.kt`
*   **Architectural Role**: Capture fallback manager. If the `MediaProjection` token is revoked or fails to initialize, this class redirects the pipeline to fallback capture configurations (e.g., captured using the Java-only `AudioRecord` APIs).

### 4.2 `CommunicationModeFallback.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fallback/CommunicationModeFallback.kt`
*   **Architectural Role**: VoIP routing fallback. If system-mix capture is blocked by DRM, this fallback mode bypasses capture entirely and maintains `MODE_IN_COMMUNICATION` to ensure basic system sounds are routed to the physical speaker.

### 4.3 `SpeakerBypassFallback.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fallback/SpeakerBypassFallback.kt`
*   **Architectural Role**: Bypasses the main pipeline, writing standard test audio directly to the speaker track to verify physical routing liveness.

### 4.4 `SilentRecoveryMode.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fallback/SilentRecoveryMode.kt`
*   **Architectural Role**: Silent recovery mode. It deactivates all active UI notifications and telemetry streams during critical resource shortages, dedicating the remaining CPU budgets to core audio routing.
