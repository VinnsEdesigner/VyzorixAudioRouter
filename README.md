# VyzorixAudioRouter

Android system-level audio routing daemon (Layer 0–8 architecture, mock-first Phase 1) targeting Android 13 on the Nokia C22 (HMD Global TA-1502, Unisoc SC9863A). Solves a personal device-specific problem: the Nokia C22's hardware codec is fried, so this app uses `MediaProjection` audio capture + `MODE_IN_COMMUNICATION` to redirect every app's audio to a working output route in real time, while surviving the OEM's aggressive background restrictions, soft-reboot triggers, and idle thermal throttling.

The project is **two repositories kept in lockstep by GitHub Actions**:

- **This repo** (`VinnsEdesigner/VyzorixAudioRouter`) — Android client (Kotlin + native C++) **and** the source of truth for the Go server tree under `vyzorix-update-server/`. All editing happens here.
- **`VinnsEdesigner/vyzorix-update-server`** — Publication target. Hosts the Vyzorix dashboard (React / TanStack Start) at the root **and** receives the Go server + `doc/` tree from this repo via `.github/workflows/sync_server.yml` and `.github/workflows/sync_repo.yml`. Do not edit it directly.

## Phases (mock-first, see ADR-0009)

| Phase | What ships | Server |
|-------|-----------|--------|
| **Phase 1** | Android service Layers 0–8 (per [`doc/BUILD_ORDER.md`](./doc/BUILD_ORDER.md)) running end-to-end against a thin Go mock server living in [`vyzorix-update-server/cmd/mockserver/`](./vyzorix-update-server/cmd/mockserver/README.md). Acceptance: 7 days continuous on the Nokia C22 against the mock. | mock |
| **Phase 1.5** | Replace the mock with the real `vyzorix-update-server` (Render-backed, SQLite, secret store, REST + WSS). **No Android code changes** — only the `updateServerUrl` build config flips. | real |
| **Phase 2** | Vyzorix dashboard (React) + OTA flow from the real server + telemetry visualization. | real |
| **Phase 3** | Hardening: key rotation, multi-device, audit logging, secret store migration to KMS. | real |

Do not start Phase 1.5 until Phase 1's "Definition of Done" checklist in [`doc/BUILD_ORDER.md`](./doc/BUILD_ORDER.md) is fully checked off on real hardware against the mock.

The previous "Phase 1 = device; Phase 2 = server" framing had a chicken-and-egg problem (Layer 8 needed a server to be testable). The mock-first reframing solves it. See [`doc/adr/0009-phase-1-mock-first.md`](./doc/adr/0009-phase-1-mock-first.md) for the rationale.

---

## Documents in `doc/`

### Read first

- [`doc/NAMING_RENAMES.md`](./doc/NAMING_RENAMES.md) — class rename table (e.g. `DaemonWatchdog` → `LivenessProbe`, `DaemonStatusProvider` → `DaemonStatusAggregator`, `CrashLoopProtector` folded into `RecoveryCoordinator`). **Read this before grepping for old class names.**
- [`doc/GLOSSARY.md`](./doc/GLOSSARY.md) — ~35 project-specific terms (route war, soft reboot, idle pause, daemon, three-layer health, etc.) defined in one place.
- [`doc/adr/`](./doc/adr/) — architectural decision records. **Read these before re-litigating design choices.** Index: [`doc/adr/README.md`](./doc/adr/README.md).

### Master reference

- [`doc/SYSTEM_MAP.md`](./doc/SYSTEM_MAP.md) — startup sequence, service interaction matrix, failure matrix, thread model (incl. cross-dispatcher locking §6.3), lifecycle graphs, permission matrix, three-layer health architecture. Every other doc cross-references this one.
- [`doc/BUILD_ORDER.md`](./doc/BUILD_ORDER.md) — Phase 1 layered build sequence (Layers 0–8, mock-first). Read this **before** writing any Kotlin or C++.

### Architectural specs (the DOC_N series — canonical)

The DOC_N series is the canonical architectural spec for each subsystem. Topic deep-dives link **into** these documents, not the other way around.

- [`doc/DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md`](./doc/DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md) — application startup, services, foreground service lifecycle.
- [`doc/DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md`](./doc/DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md)
- [`doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md`](./doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md) — audio routing, VoIP exemption, MediaProjection capture (canonical). Deep-dives: `VOIP_ROUTE_FORCE.md`, `MEDIA_PROJECTION_FLOW.md`.
- [`doc/DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md`](./doc/DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md) — recovery ladder, `RecoveryCoordinator` (Layer A in ADR-0007), safe mode.
- [`doc/DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md`](./doc/DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md) — observer fleet, log bundles. Deep-dive: `SOFT_REBOOT_ANALYSIS.md` (why the observer fleet exists per ADR-0002).
- [`doc/DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md`](./doc/DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md) — health signals (Layer B in ADR-0007), thermal, memory pressure.
- [`doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md`](./doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md) — includes `DeviceSecretStore` (§3.9) and the C2 secret storage flow (§1.1).
- [`doc/DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md`](./doc/DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md) — canonical for C2 stack. Deep-dives: `COMMAND_SECURITY.md`, `DEVICE_REGISTRATION.md`, `UPDATE_MECHANISM.md`, `UPDATE_SERVER.md`, `UPDATE_SERVER_ARCHITECTURE_SPEC.md`.

### Topic deep-dives

These are focused on a single subsystem or hardware quirk. They link into the DOC_N series for context.

- [`doc/MEDIA_PROJECTION_FLOW.md`](./doc/MEDIA_PROJECTION_FLOW.md) — capture pipeline, `IdleCaptureController` (idle pause) and `ProjectionDeathHandler` (zombie prevention) detailed specs. (Linked from DOC_3.)
- [`doc/VOIP_ROUTE_FORCE.md`](./doc/VOIP_ROUTE_FORCE.md) — `MODE_IN_COMMUNICATION` mechanism, AOSP exemption path. (Linked from DOC_3.)
- [`doc/SOFT_REBOOT_ANALYSIS.md`](./doc/SOFT_REBOOT_ANALYSIS.md) — soft-reboot failure model + "why the observer fleet exists" (per ADR-0002). (Linked from DOC_5.)
- [`doc/COMMAND_SECURITY.md`](./doc/COMMAND_SECURITY.md) — HMAC contract, `NonceCache`, per-device secret flow, threat model. (Linked from DOC_8.)
- [`doc/NOTIFICATION_DASHBOARD.md`](./doc/NOTIFICATION_DASHBOARD.md) — Tier 1/2/3 expandable notification. (Linked from DOC_1.)
- [`doc/NOKIA_C22_NOTES.md`](./doc/NOKIA_C22_NOTES.md) — populates the `NokiaC22Profile` data in the `DeviceQuirkProfile` system (per ADR-0008). Unisoc SC9863A scheduler trap, ALSA timing, TEE fallback.
- [`doc/DEVICE_QUIRK_PROFILES.md`](./doc/DEVICE_QUIRK_PROFILES.md) — `DeviceQuirkProfile` schema + how to add a new supported device. (Schema for ADR-0008.)

### Update / OTA (deep-dives of DOC_8)

- [`doc/UPDATE_MECHANISM.md`](./doc/UPDATE_MECHANISM.md) — Android-side update flow.
- [`doc/UPDATE_SERVER.md`](./doc/UPDATE_SERVER.md) — server endpoints, UptimeRobot keepalive, Render cold-start mitigation.
- [`doc/UPDATE_SERVER_ARCHITECTURE_SPEC.md`](./doc/UPDATE_SERVER_ARCHITECTURE_SPEC.md) — internal Go server architecture.
- [`doc/DEVICE_REGISTRATION.md`](./doc/DEVICE_REGISTRATION.md) — server-side device lifecycle (registration, token refresh, online/offline, deregistration), REST contract, raw `command_secret` storage. Auto-synced to `vyzorix-update-server/doc/` via `.github/workflows/sync_repo.yml`.

### CI / Release

- [`doc/CI_CD_WORKFLOWS.md`](./doc/CI_CD_WORKFLOWS.md) — workflows including the `command_secret` bypass for fresh CI installs and the mock-server integration test.

### Features & repo tree

- [`doc/FEATURES.md`](./doc/FEATURES.md)
- [`doc/VyzorixAudioRouter_RepoTree.md`](./doc/VyzorixAudioRouter_RepoTree.md) — authoritative list of files in this repo (Android side).
- [`doc/VyzorixUpdate_RepoTree.md`](./doc/VyzorixUpdate_RepoTree.md) — authoritative list of files in the server repo.

### Architecture Decision Records (ADRs)

| # | Title |
|---|-------|
| [0001](./doc/adr/0001-c2-stack-rationale.md) | C2 stack (WebSocket + FCM + HMAC) — why this depth |
| [0002](./doc/adr/0002-observer-fleet-as-measurement-instrument.md) | Observer fleet as measurement instrument (not over-engineering) |
| [0003](./doc/adr/0003-go-server-vs-firebase-functions.md) | Go server vs Firebase Functions |
| [0004](./doc/adr/0004-sqlcipher-full-db-vs-encrypted-columns.md) | SQLCipher full-DB vs encrypted columns only |
| [0005](./doc/adr/0005-websocket-plus-fcm-dual-channel.md) | WebSocket + FCM dual-channel (not WSS-only, not FCM-only) |
| [0006](./doc/adr/0006-projection-death-handler-separate-from-token-manager.md) | `ProjectionDeathHandler` separate from `ProjectionTokenManager` |
| [0007](./doc/adr/0007-three-layer-health-monitoring.md) | Three-layer health monitoring (collapse 11 classes → 3 layers) |
| [0008](./doc/adr/0008-device-quirk-profile-system.md) | `DeviceQuirkProfile` runtime abstraction |
| [0009](./doc/adr/0009-phase-1-mock-first.md) | Phase 1 mock-first (resolve Layer 8 chicken-and-egg) |
