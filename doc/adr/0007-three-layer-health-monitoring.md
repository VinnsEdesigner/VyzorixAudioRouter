# ADR-0007: Three-layer health monitoring (Signal → Aggregator → Coordinator)

## Status
Accepted

## Context

Early architecture drafts had eleven classes whose job was some flavor of "watch the daemon and react":

- `DaemonWatchdog`
- `PipelineHealthChecker`
- `ServiceRecoveryManager`
- `ServiceHeartbeat`
- `ProcessHealthMonitor`
- `SystemHealthScorer`
- `DaemonStatusProvider`
- `SoftRebootPredictor`
- `SoftRebootTracker`
- `CrashLoopProtector`
- `RendererFailureDetector`

Each had a documented purpose, but cumulatively they created:

- **Boundary confusion** — what does `ProcessHealthMonitor` know that `DaemonWatchdog` doesn't?
- **Recovery loops** — multiple classes can independently trigger a restart, each reacting to the consequences of another's restart.
- **Cognitive load** — onboarding requires understanding eleven classes' interactions.
- **Test surface explosion** — eleven classes × their interactions = a combinatorial number of test cases.

## Decision

Collapse to a **three-layer architecture** with strict boundaries:

```
┌───────────────────────────────────────────────────────────────┐
│ Layer C: STATUS AGGREGATOR                                    │
│   DaemonStatusAggregator                                      │
│     - subscribes to all signal sources                        │
│     - produces ONE immutable DaemonStatus model               │
│     - emits via SharedFlow<DaemonStatus> @ 10s cadence        │
│     - has NO recovery logic (pure read model)                 │
└──────────────┬────────────────────────────────────────────────┘
               │ reads from
               ▼
┌──────────────────────────────────┬────────────────────────────┐
│ Layer B: SIGNAL SOURCES          │  (one class per signal)    │
│                                  │                            │
│ • LivenessProbe                  │  "is the service running?" │
│ • PipelineHealthChecker          │  "is audio flowing?"       │
│ • MemoryPressureSignal           │  "are we near OOM?"        │
│ • ThermalSignal                  │  "are we throttling?"      │
│ • ProjectionTokenSignal          │  "do we have the token?"   │
│ • WebSocketConnectionSignal      │  "is C2 reachable?"        │
│ • SafeModeSignal                 │  "are we in safe mode?"    │
└──────────────────────────────────┴────────────────────────────┘
                                                     ▲
                                                     │ subscribes to DaemonStatus
                                          ┌──────────┴──────────┐
                                          │ Layer A: RECOVERY   │
                                          │   RecoveryCoordinator│
                                          │   - reads status     │
                                          │   - decides + acts:  │
                                          │     restart / safe   │
                                          │     mode / fallback  │
                                          └──────────────────────┘
```

### Mapping from old to new

| Old class | New location |
|-----------|--------------|
| `DaemonWatchdog` | `LivenessProbe` (Layer B) |
| `PipelineHealthChecker` | `PipelineHealthChecker` (Layer B, unchanged) |
| `ServiceHeartbeat` | folded into `LivenessProbe` |
| `ProcessHealthMonitor` | folded into `MemoryPressureSignal` + `LivenessProbe` |
| `SystemHealthScorer` | folded into `DaemonStatusAggregator` (the score IS the aggregate) |
| `DaemonStatusProvider` | renamed `DaemonStatusAggregator` (Layer C) |
| `SoftRebootPredictor` | folded into `RecoveryCoordinator` (it's an action policy, not a signal) |
| `SoftRebootTracker` | kept — it is a **forensic measurement tool** (see ADR-0002), not a health signal |
| `CrashLoopProtector` | folded into `RecoveryCoordinator` |
| `RendererFailureDetector` | folded into `PipelineHealthChecker` (audio pipeline already owns this concern) |
| `ServiceRecoveryManager` | renamed `RecoveryCoordinator` (Layer A) |

### Invariants

1. **No recovery logic in signals or aggregator.** Signals report state. The aggregator combines. The coordinator acts. These are three orthogonal jobs.
2. **One model class** — `DaemonStatus` is the only thing that flows through the system. Everything that wants to know "how's the daemon doing?" reads this one immutable struct.
3. **No watchdog-on-watchdog loops** — there is exactly one entity that can issue a restart (`RecoveryCoordinator`), and exactly one liveness signal (`LivenessProbe`). The old design's 11 classes could each independently decide to "do something about it"; that's where the cascade bugs hide.
4. **Pure read model in Layer C.** `DaemonStatus` is immutable. The aggregator builds a new one every cycle from current signal values. No partial-update bugs.

## Alternatives Considered

### Keep all 11 classes

Rejected for the reasons in Context.

### Two layers (signals + something that combines AND acts)

Considered. Rejected because combining the aggregator with the recovery coordinator brings back the boundary-confusion problem at smaller scale. The split between "knowing" and "doing" pays for itself.

### Event-bus driven (everything listens, everything reacts)

Considered. Rejected because event-bus architectures distribute the decision-making — the same problem the 11-class design had, in a different form. The explicit Aggregator → Coordinator pipeline forces decisions to flow through one chokepoint.

## Consequences

**Locked in:**
- Three named layers with strict boundaries. No new class can be "kind of a signal and kind of a recovery action."
- `DaemonStatus` is the only inter-layer contract. Adding a new signal means adding a new field to `DaemonStatus` and a new producer.
- The forensic measurement tools (`SoftRebootTracker`, the observer fleet from ADR-0002) live OUTSIDE this hierarchy — they are not health signals, they are evidence collectors.

**Closed off:**
- "Quick win" recoveries — e.g. having `PipelineHealthChecker` restart its own pipeline. Recovery actions must flow through `RecoveryCoordinator` even when the action is locally scoped. This adds one frame of latency in exchange for centralized decision-making.

**Opened up:**
- Adding a new signal is mechanical: write a class that exposes `current(): SignalValue`, register it with the aggregator. No coordination with existing signals required.
- Recovery policy changes are isolated to `RecoveryCoordinator`. Tuning "when do we restart vs enter safe mode vs fall back?" is one file, not eleven.

## References

- `doc/SYSTEM_MAP.md` §6 thread model + §7 lifecycle phases (updated to reflect the three-layer design).
- `doc/DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md` — recovery coordinator policy.
- ADR-0002 — the observer fleet is a measurement instrument, distinct from this health architecture.
