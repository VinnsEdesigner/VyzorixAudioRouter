# DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md — Diagnostics, Crash Forensics, and Storage Management

## Document Purpose
This document is Part 5 of the 8-part Vyzorix System Mapping. It details the black-box passive monitoring engines, window transition trackers (flash crash detection), flight data recorders (`last_state.json`), SQLite database trace caches, and log file rotators. This document serves as the implementation specification for diagnosing system crashes, tracking soft reboots, and packaging telemetry on stock Android 13 without root.

---

# 1. Diagnostic Timeline Event Correlation Flow (The Black-Box Strategy)

The following mapping outlines the chronological pipeline tracking app launches, UI transitions, and logging events to generate a secure ZIP diagnostic package:

```text
  [PASSIVE OBSERVERS]
   ├── AppLaunchObserver (UsageStats MOVE_TO_FOREGROUND)
   ├── WindowTransitionTracker (TYPE_WINDOWS_CHANGED <500ms)
   ├── PackageStateObserver (Tracks fresh app installs)
   ├── SoftRebootTracker (SystemClock.uptimeMillis() checks; forensic instrument per ADR-0002 — does NOT trigger recovery)
   └── PipelineHealthChecker (audio-pipeline observable proxy for GPU visual stasis; absorbs former RendererFailureDetector)
         │
         ▼ (Pushed as serialized, type-safe events)
   LogStreamCollector (In-memory structured logger aggregator)
         │
         ▼ (Continuous background write stream)
   RollingLogWriter (Writes UTC formatted trace logs)
         │
         ▼ (File limit check: current_session.log > 2MB?)
   LogFileRotator
         │
         ├── YES: Renames to crash_bundle_TIMESTAMP.log & purges if count > 10
         └── NO: Appends and flushes descriptors
         │
         ▼ (Triggered on demand or post crash re-start)
   CrashSnapshotExporter (Compresses directory to encrypted ZIP archive)
         │
         ▼ (Secure file URI generated via FileProvider)
   DiagnosticContentProvider (Shares zip securely to system intents)
```

---

# 2. Submodule: `crash` (The Flight Data Recorders)

The `crash` package manages unhandled JVM exceptions, heuristic indicators of native failures, and records system state details in a persistent JSON flight recorder.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/crash/
├── GlobalExceptionHandler.kt
├── NativeCrashMarker.kt
├── SoftRebootTracker.kt
└── LastKnownStateDumper.kt
```

### 2.1 `GlobalExceptionHandler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/crash/GlobalExceptionHandler.kt`
*   **Architectural Role**: Master uncaught exception handler. It implements `Thread.UncaughtExceptionHandler` to intercept fatal crashes. It analyzes the exception stack trace to classify the failure type (`SYSTEM_DIED` vs `APP_BUG`), writes a panic log to disk, and flushes the databases before the process exits.
*   **Core APIs & State Dependencies**: Binds directly to `Thread.setDefaultUncaughtExceptionHandler()`.
*   **Failure Boundaries & Escape Hatches**: If the logging disk writes crash or block, this class aborts the operation and calls `Runtime.getRuntime().halt(-1)` to prevent thread deadlocks.

### 2.2 `NativeCrashMarker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/crash/NativeCrashMarker.kt`
*   **Architectural Role**: Heuristic native crash marker. It scans local directories for signs of native `SIGSEGV` or `SIGBUS` exceptions, logging these events to SQLite as a `NATIVE_FAILURE` type.

### 2.3 `SoftRebootTracker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/crash/SoftRebootTracker.kt`
*   **Architectural Role**: Keeps a database record of recent system restarts. It is the forensic measurement instrument (ADR-0002) — it records that a reboot happened. Reboot-*pattern* matching (predicting that one is coming) is a recovery policy that lives in `RecoveryCoordinator` (Layer A, ADR-0007), not here. SoftRebootTracker is the data source the policy reads from.

### 2.4 `LastKnownStateDumper.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/crash/LastKnownStateDumper.kt`
*   **Architectural Role**: The flight recorder. It continuously overwrites a lightweight JSON file (`last_state.json`) with active parameters (uptime, active package, focus mode, and speakerphone state) to restore the daemon after restarts.

---

# 3. Submodule: `diagnostics` (The Black-Box Observers)

The `diagnostics` package passive monitors on-device events, analyzes window transitions, and calculates active system risk scores.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/
├── RoutingLogCollector.kt
├── AudioPolicySnapshot.kt
├── NokiaC22Compatibility.kt
├── CrashTraceStore.kt
├── SoftRebootDetector.kt
├── RuntimeEventTimeline.kt
├── LogStreamCollector.kt
├── RuntimeTraceAssembler.kt
├── DiagnosticCompression.kt
├── EventCorrelationEngine.kt
# NOTE: SystemHealthScorer.kt removed — folded into core/services/foreground/DaemonStatusAggregator (ADR-0007).
└── system/
    ├── AppLaunchObserver.kt
    ├── WindowTransitionTracker.kt
    └── PackageStateObserver.kt
    # NOTE: SoftRebootPredictor.kt removed — policy folded into RecoveryCoordinator (ADR-0007).
    # NOTE: RendererFailureDetector.kt removed — folded into PipelineHealthChecker.
    #       SoftRebootTracker.kt is in services/crash/, not here (it is a measurement instrument, not a signal).
```

### 3.1 Core Telemetry collectors

#### 3.1.1 `RoutingLogCollector.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/RoutingLogCollector.kt`
*   **Architectural Role**: Gathers and structures audio routing transition details, logging them to the active SQLite databases.

#### 3.1.2 `AudioPolicySnapshot.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/AudioPolicySnapshot.kt`
*   **Architectural Role**: Dumps raw system-wide audio routing states and active devices by querying the `AudioManager` APIs.

#### 3.1.3 `NokiaC22Compatibility.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/NokiaC22Compatibility.kt`
*   **Architectural Role**: Adjusts diagnostic thresholds based on the Nokia C22's low-resource environment to prevent CPU stress.

#### 3.1.4 `CrashTraceStore.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/CrashTraceStore.kt`
*   **Architectural Role**: Persists and indexifies recent JVM stack traces, providing quick access for remote telemetry reporting.

#### 3.1.5 `SoftRebootDetector.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/SoftRebootDetector.kt`
*   **Architectural Role**: Scans system parameters to detect framework restart behaviors, logging events to the database.

#### 3.1.6 `RuntimeEventTimeline.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/RuntimeEventTimeline.kt`
*   **Architectural Role**: Chronologically logs daemon status changes, routing switches, and network reconnects to build a troubleshooting timeline.

#### 3.1.7 `LogStreamCollector.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/LogStreamCollector.kt`
*   **Architectural Role**: In-memory logger aggregator. It buffers logs from all subsystems and flushes them to disk at regular intervals.

#### 3.1.8 `RuntimeTraceAssembler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/RuntimeTraceAssembler.kt`
*   **Architectural Role**: Correlates launch timelines and crash events, building a unified trace log post-crash.

#### 3.1.9 `DiagnosticCompression.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/DiagnosticCompression.kt`
*   **Architectural Role**: Compresses diagnostic files into encrypted ZIP format, reducing data payload sizes.

#### 3.1.10 `EventCorrelationEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/EventCorrelationEngine.kt`
*   **Architectural Role**: Matches recent app launches against system crashes to identify if specific packages trigger framework instabilities.

#### 3.1.11 ~~`SystemHealthScorer.kt`~~ — folded into `DaemonStatusAggregator` (ADR-0007)
*   **Architectural Role**: The 0–100 risk score IS the aggregate health. It is now computed inside `DaemonStatusAggregator` as part of producing the unified `DaemonStatus` model. No separate scorer class. See `core/services/foreground/DaemonStatusAggregator.kt`.

---

### 3.2 System Watcher Observers (`system/`)

#### 3.2.1 `AppLaunchObserver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/system/AppLaunchObserver.kt`
*   **Architectural Role**: Tracks active packages entering the foreground using `UsageStatsManager` APIs.

#### 3.2.2 `WindowTransitionTracker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/system/WindowTransitionTracker.kt`
*   **Architectural Role**: Watches window states. It identifies "Flash Crashes" (where an app window is spawned and terminated in under 500ms), logging these events to database timelines.

#### 3.2.3 `PackageStateObserver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/diagnostics/system/PackageStateObserver.kt`
*   **Architectural Role**: Differentiates between fresh app installs and stable system packages to isolate new software sources of instability.

#### 3.2.4 ~~`SoftRebootPredictor.kt`~~ — folded into `RecoveryCoordinator` soft-reboot risk policy (ADR-0007)
*   **Architectural Role**: "Predicting" a reboot is a recovery policy, not a forensic signal. The pattern-matching logic lives in `RecoveryCoordinator` (Layer A). The data it reads from is `SoftRebootTracker` (services/crash/). Clock-offset analysis from the old predictor is now a private helper inside the coordinator.

#### 3.2.5 ~~`RendererFailureDetector.kt`~~ — folded into `PipelineHealthChecker` (ADR-0007)
*   **Architectural Role**: A surfaceflinger / GPU stasis on this device manifests as audio pipeline starvation (AudioRecord reads stall, AudioTrack writes stutter). Rather than maintain a separate UI-stasis detector, we collapse this into `PipelineHealthChecker`, which already monitors the audio loops and is the Layer B signal that the dashboard surfaces.

---

# 4. Submodule: `storage` (Checkpoints and File Management)

The `storage` package manages file writing, handles file rotation limits, and purges obsolete logs.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/
├── RuntimeCheckpointWriter.kt
├── PersistentEventQueue.kt
├── CrashBundleRetentionPolicy.kt
└── logs/
    ├── LogFileRotator.kt
    ├── CrashSnapshotExporter.kt
    ├── TimestampedLogFormatter.kt
    └── RuntimeSessionIndexer.kt
```

### 4.1 `RuntimeCheckpointWriter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/RuntimeCheckpointWriter.kt`
*   **Architectural Role**: Writes lightweight checkpoint logs of active system states to database tables.

### 4.2 `PersistentEventQueue.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/PersistentEventQueue.kt`
*   **Architectural Role**: Thread-safe persistent file-backed queue that caches events and prevents data loss during process crashes.

### 4.3 `CrashBundleRetentionPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/CrashBundleRetentionPolicy.kt`
*   **Architectural Role**: Retention manager. It enforces a strict cap of 10 archived log files on disk, purging older logs first to keep disk overhead below 25MB.

### 4.4 `LogFileRotator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/logs/LogFileRotator.kt`
*   **Architectural Role**: Monitors active session log files and triggers file rotation when sizes exceed 2MB.

### 4.5 `CrashSnapshotExporter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/logs/CrashSnapshotExporter.kt`
*   **Architectural Role**: Compresses diagnostic files into encrypted ZIP archives, generating secure file URIs via `FileProvider`.

### 4.6 `TimestampedLogFormatter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/logs/TimestampedLogFormatter.kt`
*   **Architectural Role**: Formats log lines with high-precision UTC timestamps, thread identifiers, and package sources.

### 4.7 `RuntimeSessionIndexer.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/storage/logs/RuntimeSessionIndexer.kt`
*   **Architectural Role**: Maps active session identifiers and indexes log folders to prevent data corruption.
