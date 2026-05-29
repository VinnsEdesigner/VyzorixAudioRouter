# VyzorixAudioRouter — `doc/` Index

## Phases (mock-first, see ADR-0009)

| Phase | What ships | Server |
|-------|-----------|--------|
| **Phase 1** | Android service Layers 0–8 (per [`BUILD_ORDER.md`](./BUILD_ORDER.md)) running end-to-end against a thin Go mock server living in `vyzorix-update-server/cmd/mockserver/`. Acceptance: 7 days continuous on the Nokia C22 against the mock. | mock |
| **Phase 1.5** | Replace the mock with the real `vyzorix-update-server` (Render-backed, SQLite, secret store, REST + WSS). **No Android code changes** — only the `updateServerUrl` build config flips. | real |
| **Phase 2** | Vyzorix dashboard (React) + OTA flow from the real server + telemetry visualization. | real |
| **Phase 3** | Hardening: key rotation, multi-device, audit logging, secret store migration to KMS. | real |

Do not start Phase 1.5 until Phase 1's "Definition of Done" checklist in [`BUILD_ORDER.md`](./BUILD_ORDER.md) is fully checked off on real hardware against the mock.

The previous "Phase 1 = device; Phase 2 = server" framing had a chicken-and-egg problem (Layer 8 needed a server to be testable). The mock-first reframing solves it. See [`adr/0009-phase-1-mock-first.md`](./adr/0009-phase-1-mock-first.md) for the rationale.

---

## Documents in this folder

### Read first

- [`NAMING_RENAMES.md`](./NAMING_RENAMES.md) — class rename table (e.g. `DaemonWatchdog` → `LivenessProbe`, `DaemonStatusProvider` → `DaemonStatusAggregator`, `CrashLoopProtector` folded into `RecoveryCoordinator`). **Read this before grepping for old class names.**
- [`GLOSSARY.md`](./GLOSSARY.md) — ~35 project-specific terms (route war, soft reboot, idle pause, daemon, three-layer health, etc.) defined in one place.
- [`adr/`](./adr/) — architectural decision records. **Read these before re-litigating design choices.** Index: [`adr/README.md`](./adr/README.md).

### Master reference

- [`SYSTEM_MAP.md`](./SYSTEM_MAP.md) — startup sequence, service interaction matrix, failure matrix, thread model (incl. cross-dispatcher locking §6.3), lifecycle graphs, permission matrix, three-layer health architecture. Every other doc cross-references this one.
- [`BUILD_ORDER.md`](./BUILD_ORDER.md) — Phase 1 layered build sequence (Layers 0–8, mock-first). Read this **before** writing any Kotlin or C++.

### Architectural specs (the DOC_N series — canonical)

The DOC_N series is the canonical architectural spec for each subsystem. Topic deep-dives link **into** these documents, not the other way around.

- [`DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md`](./DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md) — application startup, services, foreground service lifecycle.
- [`DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md`](./DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md)
- [`DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md`](./DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md) — audio routing, VoIP exemption, MediaProjection capture (canonical). Deep-dives: `VOIP_ROUTE_FORCE.md`, `MEDIA_PROJECTION_FLOW.md`.
- [`DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md`](./DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md) — recovery ladder, `RecoveryCoordinator` (Layer A in ADR-0007), safe mode.
- [`DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md`](./DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md) — observer fleet, log bundles. Deep-dive: `SOFT_REBOOT_ANALYSIS.md` (why the observer fleet exists per ADR-0002).
- [`DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md`](./DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md) — health signals (Layer B in ADR-0007), thermal, memory pressure.
- [`DOC_7_DATA_SECURITY_AND_PERSISTENCE.md`](./DOC_7_DATA_SECURITY_AND_PERSISTENCE.md) — includes `DeviceSecretStore` (§3.9) and the C2 secret storage flow (§1.1).
- [`DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md`](./DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md) — canonical for C2 stack. Deep-dives: `COMMAND_SECURITY.md`, `DEVICE_REGISTRATION.md`, `UPDATE_MECHANISM.md`, `UPDATE_SERVER.md`, `UPDATE_SERVER_ARCHITECTURE_SPEC.md`.

### Topic deep-dives

These are focused on a single subsystem or hardware quirk. They link into the DOC_N series for context.

- [`MEDIA_PROJECTION_FLOW.md`](./MEDIA_PROJECTION_FLOW.md) — capture pipeline, `IdleCaptureController` (idle pause) and `ProjectionDeathHandler` (zombie prevention) detailed specs. (Linked from DOC_3.)
- [`VOIP_ROUTE_FORCE.md`](./VOIP_ROUTE_FORCE.md) — `MODE_IN_COMMUNICATION` mechanism, AOSP exemption path. (Linked from DOC_3.)
- [`SOFT_REBOOT_ANALYSIS.md`](./SOFT_REBOOT_ANALYSIS.md) — soft-reboot failure model + "why the observer fleet exists" (per ADR-0002). (Linked from DOC_5.)
- [`COMMAND_SECURITY.md`](./COMMAND_SECURITY.md) — HMAC contract, `NonceCache`, per-device secret flow, threat model. (Linked from DOC_8.)
- [`NOTIFICATION_DASHBOARD.md`](./NOTIFICATION_DASHBOARD.md) — Tier 1/2/3 expandable notification. (Linked from DOC_1.)
- [`NOKIA_C22_NOTES.md`](./NOKIA_C22_NOTES.md) — populates the `NokiaC22Profile` data in the `DeviceQuirkProfile` system (per ADR-0008). Unisoc SC9863A scheduler trap, ALSA timing, TEE fallback.
- [`DEVICE_QUIRK_PROFILES.md`](./DEVICE_QUIRK_PROFILES.md) — `DeviceQuirkProfile` schema + how to add a new supported device. (Schema for ADR-0008.)

### Update / OTA (deep-dives of DOC_8)

- [`UPDATE_MECHANISM.md`](./UPDATE_MECHANISM.md) — Android-side update flow.
- [`UPDATE_SERVER.md`](./UPDATE_SERVER.md) — server endpoints, UptimeRobot keepalive, Render cold-start mitigation.
- [`UPDATE_SERVER_ARCHITECTURE_SPEC.md`](./UPDATE_SERVER_ARCHITECTURE_SPEC.md) — internal Go server architecture.
- [`DEVICE_REGISTRATION.md`](./DEVICE_REGISTRATION.md) — server-side device lifecycle (registration, token refresh, online/offline, deregistration), REST contract, raw `command_secret` storage. Auto-synced to `vyzorix-update-server/doc/` via `sync_repo.yml`.

### CI / Release

- [`CI_CD_WORKFLOWS.md`](./CI_CD_WORKFLOWS.md) — workflows including the `command_secret` bypass for fresh CI installs and the mock-server integration test.

### Features & repo tree

- [`FEATURES.md`](./FEATURES.md)
- [`VyzorixAudioRouter_RepoTree.md`](./VyzorixAudioRouter_RepoTree.md) — authoritative list of files in this repo (Android side).
- [`VyzorixUpdate_RepoTree.md`](./VyzorixUpdate_RepoTree.md) — authoritative list of files in the server repo.

### Architecture Decision Records (ADRs)

| # | Title |
|---|-------|
| [0001](./adr/0001-c2-stack-rationale.md) | C2 stack (WebSocket + FCM + HMAC) — why this depth |
| [0002](./adr/0002-observer-fleet-as-measurement-instrument.md) | Observer fleet as measurement instrument (not over-engineering) |
| [0003](./adr/0003-go-server-vs-firebase-functions.md) | Go server vs Firebase Functions |
| [0004](./adr/0004-sqlcipher-full-db-vs-encrypted-columns.md) | SQLCipher full-DB vs encrypted columns only |
| [0005](./adr/0005-websocket-plus-fcm-dual-channel.md) | WebSocket + FCM dual-channel (not WSS-only, not FCM-only) |
| [0006](./adr/0006-projection-death-handler-separate-from-token-manager.md) | `ProjectionDeathHandler` separate from `ProjectionTokenManager` |
| [0007](./adr/0007-three-layer-health-monitoring.md) | Three-layer health monitoring (collapse 11 classes → 3 layers) |
| [0008](./adr/0008-device-quirk-profile-system.md) | `DeviceQuirkProfile` runtime abstraction |
| [0009](./adr/0009-phase-1-mock-first.md) | Phase 1 mock-first (resolve Layer 8 chicken-and-egg) |
