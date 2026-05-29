# GLOSSARY.md — Vyzorix Audio Router Terms

This is a one-page reference for the project-specific vocabulary that the rest of the docs use without re-defining. Skim it once before reading the deeper docs. Cross-references point to where each term is defined in detail.

## Hardware / device

**Nokia C22 / TA-1502** — The specific Android device this project targets. 8× Cortex-A55 SC9863A, 2 GB RAM, Android 13 stock, no Google Mobile Services Lite. See `NOKIA_C22_NOTES.md`.

**SC9863A** — Unisoc system-on-chip in the C22. Known for unreliable Hardware-Backed Keystore and a kernel scheduler that silently downgrades SCHED_FIFO to SCHED_OTHER. See `NOKIA_C22_NOTES.md` §2.

**Phantom headset codec** — A specific routing failure on the C22 where Android's audio policy manager routes media output to a broken / non-existent headset codec node, producing silence. The motivating bug for the whole project. See `VOIP_ROUTE_FORCE.md`.

**Soft reboot** — Android device failure mode where SystemServer or surfaceflinger crashes, the homescreen restarts, and foreground state is lost — but the device itself does not reboot the kernel. Triggered on the C22 most reliably by certain native app cold-starts. See `SOFT_REBOOT_ANALYSIS.md`.

**DeviceQuirkProfile** — Runtime data class capturing all device-specific knobs (scheduler behavior, Keystore reliability, thermal zones, ALSA timing) so that adding a new supported device = adding a profile, not rewriting code. See `DEVICE_QUIRK_PROFILES.md` and ADR-0008.

## Audio path

**Route war** — Informal name for the daemon's continuous effort to force Android's audio policy manager to route media output through the speaker via `MODE_IN_COMMUNICATION`, defeating the phantom headset codec. See `VOIP_ROUTE_FORCE.md`.

**Speaker force** — The mechanism (`SpeakerForceEngine`) that holds `MODE_IN_COMMUNICATION` and `setSpeakerphoneOn(true)` to route audio through the physical speaker. See `DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md` and `VOIP_ROUTE_FORCE.md`.

**Capture pipeline** — The chain that takes system audio via `MediaProjection` → native ring buffer → speaker playback engine → AudioTrack. See `MEDIA_PROJECTION_FLOW.md`.

**Idle pause** — Power-saving state where capture-pipeline native PCM reads are suspended (after 30s of silence) but the AudioTrack stays open in `MODE_IN_COMMUNICATION` so the routing exemption is not lost. Owned by `IdleCaptureController`. See `MEDIA_PROJECTION_FLOW.md` §Mitigation 1.

**Zombie projection** — A `MediaProjection` session whose token has been involuntarily torn down by the OS (Doze enforcement, memory pressure, etc.) without the daemon being notified through normal lifecycle channels. Handled by `ProjectionDeathHandler`. See `MEDIA_PROJECTION_FLOW.md` §Mitigation 3 and ADR-0006.

**Trampoline** — A short-lived `Activity` (`ProjectionPermissionActivity`) launched only to obtain the `MediaProjection` permission token via the system dialog, then `finish()`-ed immediately. See `MEDIA_PROJECTION_FLOW.md` §1.

**Underrun / starvation** — Two ring-buffer failure modes. Underrun = the playback side ran out of data. Starvation = the capture side never got data (e.g. DRM-protected app blocked capture). Both surfaced via `CapturePerformanceTracker`.

## Daemon architecture

**Daemon** — The `PersistentAudioService` foreground service that does the actual work. Used as shorthand throughout the docs for "the running service."

**LivenessProbe** — Layer B signal source that answers "is the daemon process running?" Part of the three-layer health architecture. See ADR-0007.

**PipelineHealthChecker** — Layer B signal source that answers "is audio flowing?" Distinct from LivenessProbe — the process can be alive while the audio pipeline has stalled.

**DaemonStatusAggregator** — Layer C in the three-layer health architecture. Reads all signal sources and produces a single immutable `DaemonStatus` model that the dashboard renders. See ADR-0007.

**DaemonStatus** — The unified read-model for daemon health. The only inter-layer contract in the health architecture. See ADR-0007.

**RecoveryCoordinator** — Layer A in the three-layer health architecture. The ONE class that subscribes to `DaemonStatus` and decides whether to restart, enter safe mode, or apply a fallback. See ADR-0007.

**Safe mode** — Degraded operational mode entered when the daemon detects an unrecoverable failure (e.g. repeated `AEADBadTagException` on the secret store). Drops to a minimum viable route-war configuration with C2 disabled. Managed by `SafeModeController`.

**Degraded mode** — Less severe than safe mode; entered when one or more features are unavailable but the core route-war is functional. E.g. running with software-derived crypto key on a device with unreliable TEE.

## Observability

**Observer fleet** — The collection of classes (`AppLaunchObserver`, `PackageChangeReceiver`, `RuntimeEventTimeline`, etc.) that records forensic state. NOT redundant defense — these are a measurement instrument designed to capture the soft-reboot trigger. See ADR-0002.

**RuntimeEventTimeline** — A unified, persisted, time-ordered log of every significant daemon event. The forensic source-of-truth for replaying what happened in the seconds before a crash or soft-reboot.

**Crash trace store** — Persistent, encrypted store of crash bundles that survive process death and device reboot. See `DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md`.

**Diagnostic bundle** — A ZIP package containing a forensic snapshot (timeline + logs + state + traces) ready for off-device analysis. Output of `DiagnosticCompression`.

## C2 / network

**C2** — Command-and-control. The mechanism for the dashboard to send commands to the device. See ADR-0001 and `DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES_UPDATED.md`.

**Command secret / `command_secret`** — Per-device shared secret used for HMAC authentication of commands. Generated server-side at registration time, stored encrypted on device via `DeviceSecretStore`. See `COMMAND_SECURITY.md` and `DEVICE_REGISTRATION.md`.

**Command frame** — The signed, serialized command envelope flowing from dashboard → server → device. Contains transaction ID, action, parameters, timestamp, nonce, and HMAC. See `COMMAND_SECURITY.md`.

**HMAC validation** — The chain `CommandHmacValidator` runs on every received command: (1) recompute HMAC-SHA256 over canonical command string using `command_secret`, (2) compare constant-time, (3) verify timestamp within 30s window, (4) verify nonce not seen in `NonceCache`.

**Nonce cache** — In-memory LRU cache of recently-seen command nonces, used to detect replay attacks. 200 entries, 5min TTL. See `COMMAND_SECURITY.md`.

**Pending result queue** — Bounded in-memory queue of command results that could not be delivered because WSS was disconnected. Drained on reconnect. 100 entry max. See `SYSTEM_MAP.md` §6.3.

**Dual-channel** — Refers to the C2 stack using both WebSocket (primary, low-latency) and FCM (fallback, wake-from-Doze). See ADR-0005.

**WSS / WebSocket** — The persistent connection from device to `vyzorix-update-server` for real-time command + telemetry flow. Managed by `WebSocketClientManager`.

**FCM (Firebase Cloud Messaging)** — Google's push-notification service. Used as a wake-up channel when WSS is unavailable.

## Server / updates

**vyzorix-update-server** — The Go server that handles device registration, C2 routing, and OTA artifact serving. See `UPDATE_SERVER.md` and `UPDATE_SERVER_ARCHITECTURE_SPEC.md`.

**Mock server** — A small Go binary (`cmd/mockserver/`) that implements just enough of the device contract to support Phase 1 testing. Lives in `vyzorix-update-server` repo. See ADR-0009.

**OTA (Over-The-Air) update** — Self-updating mechanism: device polls server for new APKs, downloads, verifies signature, prompts install. See `UPDATE_MECHANISM.md`.

**UptimeRobot ping** — External cron-style health check that hits `vyzorix-update-server/health` every 5 minutes to keep Render's free-tier dyno from sleeping. Documented in `UPDATE_SERVER.md`.

## Security / persistence

**DeviceSecretStore** — Per-device encrypted DataStore holding the `command_secret`. See `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §3.9.

**TokenEncryptor** — Stateless AES-GCM wrapper used to encrypt sensitive blobs (projection token, command secret) before they touch disk. See `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §3.8.

**KeystoreManager** — The single root-of-trust class that seals all on-disk secrets via Hardware-Backed Keystore (with software fallback for unreliable TEEs). See `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §3.1.

**Software fallback** — On devices where Hardware-Backed Keystore is unreliable (e.g. C22's Unisoc TEE), `KeystoreManager` derives keys from HKDF over install-time UUID + salt. See ADR-0008 and DOC_7 §3.1.

**SQLCipher** — AES-256 full-database encryption layer over SQLite. The Room database is wrapped in SQLCipher. See ADR-0004.

## Phases

**Phase 1** — Device runs Layers 0–8 (full architecture) against the mock server for 7 days continuous on a real C22. See `BUILD_ORDER.md` and ADR-0009.

**Phase 1.5** — Mock server replaced with the real `vyzorix-update-server`. Device-side code unchanged; only `updateServerUrl` changes. See ADR-0009.

**Phase 2** — Dashboard UI, OTA update mechanism polished, key rotation, multi-device support.

**Phase 3** — Hardening, monitoring, observability for production-grade fleet operation.

## Layers (from `BUILD_ORDER.md`)

| Layer | Scope |
|-------|-------|
| L0 | `core/common` — pure Kotlin utilities, no Android runtime |
| L1 | `core/data` — Room + SQLCipher + DeviceSecretStore + KeystoreManager |
| L2 | `core/audioengine` — native C++ ring buffer + JNI |
| **L3** | **Minimum Viable Route War** (acceptance gate — audio physically comes out the C22 speaker) |
| L4 | Capture pipeline |
| L5 | Notification dashboard |
| L6 | Crash / diagnostics stack |
| L7 | Update system |
| L8 | WebSocket + FCM + HMAC (C2 stack) |

## Project-specific stylistic terms

**Anchor** — A class whose only job is to keep something pinned (e.g. `VoipAudioAnchor` pins the audio mode). Conceptually similar to a Rust `Drop` guard.

**Keeper** — A class that periodically re-asserts a state (e.g. `AudioModeKeeper` re-asserts `MODE_IN_COMMUNICATION` if the system drifts it away).

**Guard** — A class that checks a precondition before allowing an operation (e.g. `thread_priority_guard.cpp` checks SCHED_FIFO before starting an audio thread).

**Daemon** — Shorthand for the foreground service. Not a separate process, not Unix-style; just the running service.

**Tier** (as in "RemoteViews tier") — A graceful-degradation level for the notification dashboard. Higher tiers show more detail; lower tiers fall back when RemoteViews capacity is exhausted. See `NOTIFICATION_DASHBOARD.md`.
