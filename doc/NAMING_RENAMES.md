# NAMING_RENAMES.md — Class rename table (canonical)

This file is the canonical source-of-truth for class renames that have happened during architectural cleanups. If you read an older doc that uses an old name and a newer doc that uses a new name, this table is what reconciles them.

See **ADR-0007** (three-layer health monitoring) and **ADR-0006** (Projection class boundaries) for the design decisions that motivated these renames.

## How to use this table

- When writing **code** for the first time, use the **new** name. Always.
- When **editing existing docs**, prefer to update old names to new names in your edit. Don't make a separate rename-only edit; bundle it with the substantive change.
- When **reading code or docs**, treat the table as a lookup index.

## 1:1 renames

These are simple substring renames. Same concept, same responsibilities, just a cleaner name.

| Old name | New name | Reason |
|----------|----------|--------|
| `DaemonStatusProvider` | `DaemonStatusAggregator` | "Provider" doesn't say what it does. "Aggregator" describes the action: combining signal sources into one read-model. |
| `ServiceRecoveryManager` | `RecoveryCoordinator` | "Manager" is overloaded. "Coordinator" matches the Layer A role in the three-layer health architecture (ADR-0007). |
| `DaemonCommandDispatcher` | `RemoteCommandDispatcher` | The command comes FROM remote (dashboard via WSS/FCM). "Daemon" was redundant — every class here is part of the daemon. Reuses the "Remote" prefix already present in `RemoteCommandExecutor`. |
| `DaemonDatabase` | `AppDatabase` | Standard Room convention. The DB is the app's, not specifically the daemon's. |
| `DaemonDatabaseMigrations` | `AppDatabaseMigrations` | Match the database rename. |
| `ProjectionSessionManager` | `MediaProjectionSession` | "Session" is the active runtime concept; "Manager" was redundant. |
| `MediaProjectionCaptureSession` | `MediaProjectionSession` | These were two names for the same concept. Collapsed to one. |
| `DaemonWatchdog` | `LivenessProbe` | "Watchdog" implies recovery responsibility; the class only reports liveness as a signal. Recovery actions live in `RecoveryCoordinator`. See ADR-0007. |

## Folded classes (consolidated into others)

These classes' responsibilities were absorbed into other classes. The original class names disappear.

| Old name | Folded into | Reason |
|----------|-------------|--------|
| `ServiceHeartbeat` | `LivenessProbe` | Heartbeat is the mechanism the probe uses internally. Not a separate concept. |
| `ProcessHealthMonitor` | `MemoryPressureSignal` + `LivenessProbe` | "Process health" split into "is the process responsive" (LivenessProbe) and "is the process near OOM" (MemoryPressureSignal). |
| `SystemHealthScorer` | `DaemonStatusAggregator` | The 0-100 health score IS the aggregate. Aggregator now computes the score as part of producing `DaemonStatus`. |
| `SoftRebootPredictor` | `RecoveryCoordinator` | "Predicting" a soft-reboot is a recovery policy decision, not a signal. The pattern-matching logic moves into the coordinator. |
| `CrashLoopProtector` | `RecoveryCoordinator` | Crash-loop detection is a recovery policy: "do not restart more than N times in M seconds." Moves into the coordinator. |
| `RendererFailureDetector` | `PipelineHealthChecker` | Renderer (surfaceflinger) failures already affect the audio pipeline indirectly. Folded into the existing pipeline checker. |
| `ProjectionTokenValidator` | `ProjectionTokenManager` | Validation is part of the token's lifecycle. Single class owns acquire / validate / refresh / store. |

## Kept (no rename, listed for completeness)

| Name | Why mentioned |
|------|---------------|
| `PipelineHealthChecker` | Kept as-is. Audio-specific health checker, distinct from generic liveness. |
| `DaemonLifecycleManager` | Kept as-is. Actually about the daemon's lifecycle (start, stop, restart) — the only legitimate `Daemon*` class. |
| `ProjectionTokenManager` | Kept as-is. Owns token lifecycle. |
| `ProjectionDeathHandler` | Kept as-is. Separate from `ProjectionTokenManager` per ADR-0006. |
| `SoftRebootTracker` | Kept as-is. Distinct from the deleted `SoftRebootPredictor` — `SoftRebootTracker` is a **measurement instrument** (ADR-0002), it does NOT trigger recovery. |

## New classes introduced by the rename

These didn't exist before the rename. They appear in code paths that the old design lacked.

| Name | Role | Layer |
|------|------|-------|
| `DaemonStatusAggregator` | Reads all Layer B signals, produces one `DaemonStatus` model | Layer C |
| `RecoveryCoordinator` | Subscribes to `DaemonStatus`, decides restart vs safe-mode vs fallback | Layer A |
| `LivenessProbe` | Reports "is the process responsive" as a signal | Layer B |
| `MemoryPressureSignal` | Reports current memory pressure as a signal | Layer B |
| `ThermalSignal` | Reports current SoC temperature as a signal | Layer B |
| `ProjectionTokenSignal` | Reports "do we have a valid projection token" as a signal | Layer B |
| `WebSocketConnectionSignal` | Reports "is WSS connected" as a signal | Layer B |
| `SafeModeSignal` | Reports "are we in safe mode" as a signal | Layer B |
| `DaemonStatus` | The unified read-model (immutable data class) | n/a (data) |

## Files most affected by the rename

For code search-and-replace purposes, these are the files where old names appear most frequently. Newer commits should keep them in sync with this table.

- `doc/SYSTEM_MAP.md` (master reference)
- `doc/VyzorixAudioRouter_RepoTree.md` (file tree)
- `doc/BUILD_ORDER.md` (Layer 3-6 sections)
- `doc/DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md` (startup sequence)
- `doc/DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md` (recovery section)
- `doc/DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md` (status reporting)

## ADR history

- **ADR-0006** — established `ProjectionDeathHandler` as separate from `ProjectionTokenManager`. Established the folding of `ProjectionTokenValidator` into the manager.
- **ADR-0007** — established the three-layer health architecture. Drove the bulk of the renames.
- **ADR-0008** — `DeviceQuirkRegistry` formalized as data-driven (not behavior-driven). Did not rename anything but related to the cleanup.
