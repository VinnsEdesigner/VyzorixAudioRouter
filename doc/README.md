# VyzorixAudioRouter — `doc/` Index

So the Priority Build Order will be:

- **Phase 1** → complete Android service first (getting it compiled and tested on a physical Nokia C22). See [`BUILD_ORDER.md`](./BUILD_ORDER.md) for the strict Layer 0–8 sequence.
- **Phase 2** → render update section, then after it's verified and tested, remote server will now start coming up.

Do not start Phase 2 until Phase 1's "Definition of Done" checklist in [`BUILD_ORDER.md`](./BUILD_ORDER.md) is fully checked off on real hardware.

---

## Documents in this folder

### Master reference

- [`SYSTEM_MAP.md`](./SYSTEM_MAP.md) — startup sequence, service interaction matrix, failure matrix, thread model (incl. cross-dispatcher locking §6.3), lifecycle graphs, permission matrix. Every other doc cross-references this one.
- [`BUILD_ORDER.md`](./BUILD_ORDER.md) — Phase 1 layered build sequence (Layers 0–8). Read this **before** writing any Kotlin or C++.

### Architectural specs (the DOC_N series)

- [`DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md`](./DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md)
- [`DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md`](./DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md)
- [`DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md`](./DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md)
- [`DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md`](./DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md)
- [`DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md`](./DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md)
- [`DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md`](./DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md)
- [`DOC_7_DATA_SECURITY_AND_PERSISTENCE.md`](./DOC_7_DATA_SECURITY_AND_PERSISTENCE.md) — Now includes `DeviceSecretStore` (§3.9) and the C2 secret storage flow (§1.1).
- [`DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES_UPDATED.md`](./DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES_UPDATED.md)

### Topic deep-dives

- [`MEDIA_PROJECTION_FLOW.md`](./MEDIA_PROJECTION_FLOW.md) — capture pipeline, including `IdleCaptureController` (idle pause) and `ProjectionDeathHandler` (zombie prevention) detailed specs.
- [`VOIP_ROUTE_FORCE.md`](./VOIP_ROUTE_FORCE.md)
- [`SOFT_REBOOT_ANALYSIS.md`](./SOFT_REBOOT_ANALYSIS.md)
- [`COMMAND_SECURITY.md`](./COMMAND_SECURITY.md) — HMAC contract, `NonceCache`, per-device secret flow.
- [`NOTIFICATION_DASHBOARD.md`](./NOTIFICATION_DASHBOARD.md)
- [`NOKIA_C22_NOTES.md`](./NOKIA_C22_NOTES.md) — Unisoc SC9863A scheduler trap, ALSA timing, TEE fallback. Hardware-specific.

### Update / OTA

- [`UPDATE_MECHANISM.md`](./UPDATE_MECHANISM.md)
- [`UPDATE_SERVER.md`](./UPDATE_SERVER.md)
- [`UPDATE_SERVER_ARCHITECTURE_SPEC.md`](./UPDATE_SERVER_ARCHITECTURE_SPEC.md)
- [`DEVICE_REGISTRATION.md`](./DEVICE_REGISTRATION.md) — **Server-side**: device lifecycle (registration, token refresh, online/offline, deregistration), REST contract, where the raw `command_secret` lives. Auto-synced to `vyzorix-update-server/doc/` via `sync_repo.yml`.

### CI / Release

- [`CI_CD_WORKFLOWS.md`](./CI_CD_WORKFLOWS.md) — Now includes the `command_secret` bypass for fresh CI installs.

### Features & repo tree

- [`FEATURES_UPDATED.md`](./FEATURES_UPDATED.md)
- [`VyzorixAudioRouter_RepoTree.md`](./VyzorixAudioRouter_RepoTree.md) — authoritative list of files in this repo.
- [`VyzorixUpdate_RepoTree.md`](./VyzorixUpdate_RepoTree.md) — authoritative list of files in the server repo.
