# ADR-0002: The observer fleet is a measurement instrument, not over-engineering

## Status
Accepted

## Context

Reading the codebase cold, a reasonable engineer's first reaction is:

> Why does this daemon have `AppLaunchObserver`, `PackageChangeReceiver`, `RuntimeEventTimeline`, `RoutingLogCollector`, `LogStreamCollector`, `CrashTraceStore`, `RuntimeTraceAssembler`, `EventCorrelationEngine`, `LastKnownStateDumper`, `SoftRebootTracker`, `WindowTransitionTracker`, `RendererFailureDetector`, `RollingLogWriter`, and a half-dozen more observers? This is enormous over-engineering for a service that just forces audio to the speaker.

That reaction is wrong. The observers exist because of a specific, unsolved problem.

## The Problem

The Nokia C22 (Unisoc SC9863A, Android 13) exhibits a **soft-reboot failure mode** — the device's SystemServer or surfaceflinger appears to crash, the homescreen restarts, and any foreground state is lost. The bug is documented in `SOFT_REBOOT_ANALYSIS.md`.

What we know:
- Soft reboots happen most reliably **when launching new native apps cold** — i.e. when an app process is forked and `Application.onCreate()` runs for the first time in that process.
- Soft reboots do NOT happen at a steady rate from a running daemon's own load. A daemon idling in the background for hours does not trigger them on its own. The trigger appears to be the **system reaping memory + an in-flight cold-start IPC race**.

What we do NOT know:
- **Which** native apps reliably trigger it.
- **What system state** (memory level, thermal state, ongoing IPC, projection state, audio mode) makes a launch dangerous vs safe.
- **Whether the trigger is a specific binder transaction, a specific gralloc allocation, a specific zygote fork pattern**, or something else.

We cannot reproduce the soft-reboot on demand. It happens irregularly during normal use.

## The Decision

The observer fleet is a **measurement instrument** designed to capture forensic state at the moment a soft-reboot is about to occur (or right after it occurs and we boot back up). Each observer is recording a specific signal that might correlate with the trigger.

| Observer | What it records | Why it might correlate |
|----------|-----------------|-------------------------|
| `AppLaunchObserver` | Every native app launch + timestamp | The trigger is hypothesized to be a launch |
| `PackageChangeReceiver` | Package installs / updates / uninstalls | Updates often produce cold starts |
| `WindowTransitionTracker` | Window switches via accessibility events | Cold starts are often window changes |
| `RuntimeEventTimeline` | Unified timeline of all daemon events | Lets us replay the seconds before a crash |
| `LogStreamCollector` | logcat snapshots around suspicious events | System-level evidence |
| `RoutingLogCollector` | Audio routing changes | The codec bug interacts with policy changes |
| `MemoryPressureCoordinator` + signals | Memory state | Low memory triggers reaping |
| `DeviceThermalMonitor` | SoC temperature | Thermal events correlate with system stress |
| `RendererFailureDetector` | UI surface state failures | A surfaceflinger crash is one hypothesis |
| `LastKnownStateDumper` | State snapshot on shutdown / restart | Survives the soft-reboot via on-disk persistence |
| `SoftRebootTracker` | Detection of "we just rebooted" on next launch | Anchors the event in time |
| `CrashTraceStore` + `RuntimeTraceAssembler` | Bundled forensic packages | Output of the whole instrument |

The diagnostic compression / rolling log / bundle-and-zip layer is **not redundant defense**; it is the output stage of the measurement instrument. Once we accumulate enough soft-reboot events with correlated state, we can statistically isolate the trigger and either avoid it or work around it.

## Alternatives Considered

- **Run a minimal daemon with no observers, observe the bug externally** — Rejected because we don't have a host PC for ADB log capture and the C22's own logs are wiped on soft-reboot. We need to capture forensic data **on the device itself, persisted across reboot**.
- **One generic "log everything" observer** — Rejected because logcat alone doesn't capture daemon-internal state (audio mode, projection token, ring buffer health). We need structured per-subsystem capture.
- **Defer instrumentation until after the audio path works** — Rejected because if Layer 3 (audio path) is shipping and we see a soft-reboot, we have nothing to debug with. The instrument needs to be in place BEFORE we hit the bug, otherwise the bug just keeps happening with no signal.

## Consequences

**Locked in:**
- The observer fleet is non-negotiable. Removing observers to "simplify" the codebase will destroy the measurement instrument that we built specifically to fight this bug.
- Significant disk I/O for log rotation / bundle generation. Justified by the use case.
- A non-trivial CPU + memory budget for the observers themselves. Justified by the use case.

**Closed off:**
- A "minimalist" daemon. The shape of the codebase is determined by the experiment.

**Opened up:**
- Once we identify the trigger, we can ship a focused mitigation and consider deprecating the most expensive observers. Until then, they earn their keep by being on standby for the next event.

## Reader Guidance

If you are reading the codebase and considering simplifying the observer fleet:

1. **Read `SOFT_REBOOT_ANALYSIS.md` §"Why the Observer Fleet Exists"** first.
2. Ask: which observer is recording which forensic signal? If you cannot answer that, you don't yet understand what would be lost by removing it.
3. Observers can be **batched, sampled, or moved to lower-priority dispatchers** to reduce cost — they cannot be **deleted** until the soft-reboot trigger is positively identified.

## References

- `doc/SOFT_REBOOT_ANALYSIS.md` — the bug being measured.
- `doc/SYSTEM_MAP.md` §3 service interaction matrix — observer wiring.
- `doc/DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md` — diagnostic output layer.
