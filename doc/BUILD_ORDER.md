# BUILD_ORDER.md — Phase 1 Layered Build Sequence (Mock-First)

## Document Purpose

The `README.md` previously said only:

```
phase 1 → complete android service first
phase 2 → render update section
```

That framing has a chicken-and-egg problem: Layer 8 (WebSocket + FCM + HMAC, the C2 stack) cannot be meaningfully tested without a server, but the server was previously slotted into Phase 2 — meaning "Phase 1 done" would never actually be testable end-to-end. This was identified in ADR-0009 and resolved by reframing phases as **mock-first** (see also `README.md` Phases section):

| Phase | What ships |
|-------|-----------|
| **Phase 1** | Device runs Layers 0–8 end-to-end against a **thin Go mock server** that lives in `vyzorix-update-server/cmd/mockserver/`. Phase 1 acceptance = 7 days continuous on the Nokia C22 against the mock. |
| **Phase 1.5** | Replace the mock with the real `vyzorix-update-server` (Render-backed, SQLite, secret store, REST + WSS). **No code changes on device.** Only environment config (`updateServerUrl`). |
| **Phase 2** | Vyzorix dashboard (React) + OTA flow from the real server + telemetry visualization. |
| **Phase 3** | Hardening: key rotation, multi-device, audit logging, secret store migration to KMS. |

The mock server is a real Go binary (not test fixtures), implementing just enough of `DEVICE_REGISTRATION.md` to make Layer 8 testable end-to-end. See `CI_CD_WORKFLOWS.md` for how the mock is also used in CI.

With a codebase this size — the `VyzorixAudioRouter_RepoTree.md` lists well over 200 Kotlin files across 30+ packages plus a C++ native layer — you cannot just "start writing the Android service" without a concrete sequence. You will end up writing `DiagnosticCompression.kt` before `SpeakerForceEngine.kt` is even tested on the Nokia C22.

This document defines the **Phase 1 layered build order** so that:

1. Each layer compiles and tests independently before the next one starts.
2. The minimum viable audio slice runs on the Nokia C22 as early as Layer 3 — before the diagnostics, dashboard, update, and C2 stacks are built on top of it.
3. Crashes at any layer have a known scope (e.g., a Layer 4 capture bug cannot break Layer 3's route enforcement).
4. The team — including any LLM-assisted code generation — has a definite "what compiles next" answer at every step.
5. Phase 1's acceptance is **not blocked on a fully built production server**.

Cross-references: this document expands on `SYSTEM_MAP.md` §7 Lifecycle Dependency Graph and ADR-0009 (phase-1 mock-first rationale). Every layer below maps to specific phases in that graph.

---

## Layer Map (Phase 1)

```
Layer 0 — core/common only (pure Kotlin, no Android runtime needed)
Layer 1 — core/data (Room + SQLCipher; no services)
Layer 2 — core/audioengine (native only; JNI bridge smoke test)
Layer 3 — Minimum Viable Route War
          (PersistentAudioService + SpeakerForceEngine + VoIP stack only —
           audio physically routes to the speaker on the Nokia C22)
Layer 4 — Capture pipeline
          (MediaProjection trampoline + PlaybackCaptureEngine + native ring buffer +
           AudioPipelineController + IdleCaptureController + ProjectionDeathHandler)
Layer 5 — Notification dashboard
          (DaemonStatusAggregator + ServiceNotificationDashboard + RemoteViews + tier 1/2/3)
Layer 6 — Crash / diagnostic stack
          (GlobalExceptionHandler, LogStreamCollector, RollingLogWriter,
           CrashSnapshotExporter, observers, soft-reboot detection)
Layer 7 — Update system
          (UpdateChecker + UpdateDownloader + UpdateDownloadService +
           UpdateInstaller + UpdateStateStore)
Layer 8 — WebSocket + FCM + HMAC
          (KeystoreManager seal, DeviceSecretStore, CommandHmacValidator,
           NonceCache, PendingResultQueue, WebSocketClientManager,
           FcmTokenManager, FcmCommandParser)
```

Each layer below has: scope, files compiled, files explicitly stubbed-only, what success looks like, and on-device verification on the Nokia C22.

---

## Layer 0 — `core/common`

**Scope:** Pure Kotlin utilities, constants, enums, models, extensions, dispatchers, logging primitives. No Android runtime calls. No Room. No JNI. No services.

**Compiles (full implementation):**
- `core/common/constants/` — `AppConstants.kt`, `AudioConstants.kt`, `PrefKeys.kt`, `NotificationChannels.kt`, etc.
- `core/common/enums/` — `DaemonState`, `RouteState`, `CaptureState`, `UpdateState`, `CommandValidationResult` (used by Layer 8 later but defined now).
- `core/common/extensions/` — Kotlin stdlib extensions.
- `core/common/model/` — `DaemonStatus`, `RouteStatus`, `CaptureStatus`, `CommandFrame`, `CommandResult`, `UpdateInfo` (DTOs only; no Android types).
- `core/common/logging/` — `TimestampedLogFormatter`, `Logger` (no file I/O yet; logs go to `println`/`stderr` for now).
- `core/common/concurrency/` — `AppDispatchers`, `SafeHandler` (Main dispatcher is OK here because it doesn't fire until injected).
- `core/common/utils/` — `KeystoreManager.kt` interface only (impl deferred to Layer 1/8 — see below).

**Stubs only (compile as TODOs that throw `NotImplementedError`):**
- `KeystoreManager.kt` implementation. The interface lives in Layer 0 but the Android Keystore-backed body is filled in once Room is wired in Layer 1 (the seal flow needs an Android `Context`).

**Success criteria:**
- `./gradlew :core:common:assemble` succeeds.
- Unit tests pass on a JVM (no instrumented tests yet).
- `DaemonStatus`, `CommandFrame`, etc. round-trip through JSON serialization.

**On-device verification:** N/A. Layer 0 never runs on the device.

---

## Layer 1 — `core/data`

**Scope:** Room + SQLCipher persistence, plus encrypted DataStore for the C2 secret. Fills in the Android-bound parts of `KeystoreManager`. No services yet.

**Compiles (full implementation):**
- `core/data/database/` — `AppDatabase.kt`, `SecureSupportHelper.kt`, `AppDatabaseMigrations.kt`, `CryptoHelper.kt`.
- `core/data/dao/` — `DaemonStateDao`, `CrashEventDao`, `UpdateStateDao`, etc.
- `core/data/entity/` — Room entities.
- `core/data/converters/` — Room `TypeConverter`s.
- `core/data/repository/` — Repositories that wrap DAOs.
- `core/data/datastore/DeviceSecretStore.kt` — encrypted DataStore for `command_secret` (uses `TokenEncryptor` via `KeystoreManager`). Note: the C2 *consumers* (`CommandHmacValidator`, `FcmTokenManager`) are in Layer 8; `DeviceSecretStore` itself lives here in Layer 1 because it is purely a persistence concern.
- `core/common/utils/KeystoreManager.kt` — fill in the implementation (Android Keystore + software fallback for Unisoc SC9863A; see `NOKIA_C22_NOTES.md` for the fallback rationale).

**Stubs only:**
- Any DAO method whose call site is in a later layer can stay unused; do not delete the DAO method just because nothing calls it yet.

**Success criteria:**
- `./gradlew :core:data:assemble` succeeds.
- Instrumented test that opens `AppDatabase`, writes a row, closes the DB, reopens it, reads the row back — verifying SQLCipher encryption is wired and `KeystoreManager` seal/unseal works.
- `DeviceSecretStore.put(secret)` round-trips through `TokenEncryptor` and produces an encrypted blob on disk (the blob must be non-plaintext when inspected via `adb shell run-as`).

**On-device verification (Nokia C22):**
- Install a smoke-test APK that just calls `AppDatabase.getInstance(ctx)` from a JUnit instrumented test. Verify it does not crash on the SoC's quirky TEE. If `KeystoreManager` falls back to software-keyed encryption, this is logged but acceptable — that is by design on Unisoc.

---

## Layer 2 — `core/audioengine`

**Scope:** Native C++ ring buffer + JNI bridge. NO Kotlin audio pipeline yet — just the native side and its bridge. No services.

**Compiles (full implementation):**
- `core/audioengine/cpp/` — `capture_ring_buffer.cpp`, `pcm_mixer.cpp`, `playback_resampler.cpp`, `underrun_guard.cpp`, `latency_tracker.cpp`, `audio_clock_sync.cpp`, `thread_priority_guard.cpp`.
- `core/audioengine/include/` — public headers.
- `core/audioengine/NativeAudioBridge.kt` — JNI declarations + load-library wrapper.
- `core/audioengine/NativeLoader.kt` — defensive `loadLibrary` that catches `UnsatisfiedLinkError` (see `SYSTEM_MAP.md` §5 failure matrix).

**Stubs only:**
- `AudioPipelineController.kt` — exists in `services/audio/` (Layer 3+); for Layer 2 we only need the JNI surface to be callable.

**Success criteria:**
- `./gradlew :core:audioengine:externalNativeBuild` succeeds.
- An instrumented smoke test calls `NativeAudioBridge.allocateRingBuffer()`, writes a known sine wave, reads it back, asserts byte equality. Underrun counter at zero.
- `thread_priority_guard.cpp` includes the SCHED_FIFO read-back check documented in `NOKIA_C22_NOTES.md` so that Unisoc fall-back to SCHED_OTHER is detected (do not assume the elevation succeeded just because the syscall did not error).

**On-device verification (Nokia C22):**
- Sine wave round-trip test reports zero underruns at 48kHz mono.
- Logcat shows the actual scheduling policy assigned post-elevation (verify it is SCHED_FIFO on Qualcomm/MediaTek and document the Unisoc fallback if SCHED_OTHER).

---

## Layer 3 — Minimum Viable Route War

**Scope:** **The first layer that physically produces audio on the Nokia C22.** This is the smallest end-to-end slice that proves the route-forcing thesis works. Diagnostics, dashboard, updates, and C2 do not exist yet.

**Compiles (full implementation):**
- `app/` — `VyzorixApplication.kt`, `VyzorixAppInitializer.kt`, `BootstrapActivity.kt`, `AndroidManifest.xml` (permissions trimmed to the bare minimum: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `MODIFY_AUDIO_SETTINGS`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`).
- `core/services/foreground/PersistentAudioService.kt` — `foregroundServiceType="mediaPlayback"`.
- `core/services/foreground/ServiceNotification.kt` — minimal "Vyzorix running" notification (no dashboard yet).
- `core/services/foreground/BootReceiver.kt`.
- `core/services/accessibility/RouterAccessibilityService.kt` — minimal: only `onServiceConnected()` → `LauncherIconHider.nukeLauncherIcon()` → start `PersistentAudioService`.
- `core/services/managers/AudioRouteManager.kt`, `SpeakerForceManager.kt`, `DaemonLifecycleManager.kt`.
- `core/services/voip/SpeakerForceEngine.kt`, `AudioModeKeeper.kt`, `SilentVoipSession.kt`, `CommunicationRouter.kt`, `VoipAudioAnchor.kt`, `CommunicationDeviceSelector.kt`, `RoutePersistenceDaemon.kt`.
- `core/services/audio/AudioRouteWatcher.kt`, `AudioFocusHandler.kt`.
- `core/services/oem/NokiaC22DeviceProfile.kt`.
- `core/services/bootstrap/LauncherIconHider.kt`, `BootStateRestorer.kt` (BootStateRestorer can no-op on first build; it just needs to compile).

**Stubs only:**
- All `capture/`, `diagnostics/`, `dashboard/`, `updates/`, `fcm/`, `websocket/`, `security/`, `crash/`, `monitoring/`, `metrics/`, `memory/`, `stability/`, `state/`, `storage/`, `compat/`, `headless/`, `ipc/`, `permissions/`, `provider/`, `receivers/`, `fallback/`, `testing/`, `scheduler/`, `resilience/` packages — let them compile as empty classes / no-op implementations. Do not delete them.

**Success criteria:**
- APK installs on Nokia C22.
- User opens Settings → Accessibility, enables "VyzorixAudioRouter".
- Launcher icon disappears (LauncherIconHider).
- `PersistentAudioService` starts and shows the placeholder notification.
- Plays a YouTube video (or any media app) on the device.
- Audio comes out the **bottom-firing speaker** — not the broken headset codec.
- `SpeakerForceEngine` 500ms loop visible in logcat re-asserting `setSpeakerphoneOn(true)` on drift.

**On-device verification (Nokia C22):**
- This is THE acceptance gate for Phase 1. If Layer 3 does not produce audible audio through the speaker on the C22, no later layer can rescue it.
- Run for 24 continuous hours without rebooting. Verify no soft reboot.
- Plug and unplug a headphone jack 50 times. SpeakerForceEngine must re-correct every time.

If Layer 3 passes, the route-forcing thesis is proven on hardware. All later layers are now additive.

---

## Layer 4 — Capture Pipeline

**Scope:** Add `MediaProjection`-based system audio capture on top of Layer 3. The audio that was previously coming from a single foreground app via VoIP routing is now the full system mix.

**Compiles (full implementation):**
- `app/ProjectionPermissionActivity.kt`.
- `core/services/capture/MediaProjectionSession.kt`, `PlaybackCaptureEngine.kt`, `CaptureLifecycleController.kt`, `CaptureRecoveryEngine.kt`, `ProjectionTokenManager.kt`, `TokenPersistence.kt`.
- `core/services/capture/ProjectionDeathHandler.kt` — dedicated `MediaProjection.Callback.onStop()` handler; logs to `CrashTraceStore`; triggers `UiRecoveryDaemon`. See `MEDIA_PROJECTION_FLOW.md` §Zombie Prevention.
- `core/services/capture/IdleCaptureController.kt` — silence-detection-driven idle pause for the native PCM pipeline. See `MEDIA_PROJECTION_FLOW.md` §Battery & Soft Reboot Mitigation.
- `core/services/managers/MediaProjectionSession.kt`.
- `core/services/audio/AudioPipelineController.kt`, `SpeakerPlaybackEngine.kt`, `AudioTrackFactory.kt`, `LatencyOptimizer.kt`, `UnderrunRecovery.kt`.
- `core/services/accessibility/*` — full set, including `DialogRecognitionEngine`, `AccessibilityGestureQueue`, `UiInteractionSnapshot` so the projection dialog auto-clicks "Start Now" headlessly.
- `core/services/permissions/ProjectionGrantCache.kt`, `PermissionAutoGranter.kt`.

**Stubs only:**
- Diagnostics, dashboard, updates, FCM, WebSocket remain stubbed.

**Success criteria:**
- First-run: user grants MediaProjection via the trampoline activity; the dialog is auto-accepted by `AccessibilityGestureQueue` in <100ms.
- Token is persisted via `TokenPersistence` (encrypted).
- Audio from **any** app (YouTube, Spotify, Chrome, games) flows out the speaker.
- `IdleCaptureController` pauses the native pipeline after 30s of silence (logcat-visible ~60% CPU drop on `top`).
- `ProjectionDeathHandler` re-launches the trampoline if the system kills the projection.

**On-device verification:**
- Play 5 different apps in sequence. All audio routes correctly.
- Force-stop the SystemUI process (or otherwise kill `MediaProjection`) and observe `ProjectionDeathHandler` recovering within seconds.

---

## Layer 5 — Notification Dashboard

**Scope:** Make the daemon visible without launching a UI. `DaemonStatusAggregator` aggregates from the now-existing subsystems and posts to a `RemoteViews`-based dashboard.

**Compiles (full implementation):**
- `core/services/foreground/DaemonStatusAggregator.kt` — pulls from `SpeakerForceEngine`, `PlaybackCaptureEngine`, `CrashMetrics` (stub-only counters at this layer), `BatteryImpactMonitor` (stub-only), etc.
- `core/services/foreground/ServiceNotificationDashboard.kt` — Tier 1/2/3 expandable notification.
- `core/services/foreground/SilentKeepAliveService.kt`, `RecoveryCoordinator.kt` (Layer A — the sole restart authority), `LivenessProbe.kt` (Layer B signal, broad health, absorbs former `ServiceHeartbeat`), `PipelineHealthChecker.kt` (Layer B signal, audio-only health).
- `core/services/foreground/signals/` — `SignalValue.kt`, `MemoryPressureSignal.kt`, `ThermalSignal.kt`, `ProjectionTokenSignal.kt`, `WebSocketConnectionSignal.kt`, `SafeModeSignal.kt`. **Stub-only at Layer 5** — the wiring through `DaemonStatusAggregator` is real, but signals that depend on later layers (`ProjectionTokenSignal`, `WebSocketConnectionSignal`) emit `UNKNOWN` until their owning layer is built. See ADR-0007.
- `core/services/foreground/actions/` — `NotificationActionReceiver.kt`, `QuickToggleAction.kt`, `RestartPipelineAction.kt`, `EmergencyStopAction.kt`.
- `core/services/compat/NotificationCompatBridge.kt`, `NotificationTrampolineCompat.kt`, `PendingIntentCompatPolicy.kt`.
- `core/services/permissions/NotificationPermissionManager.kt`.
- Resources: `notification_dashboard_collapsed.xml`, `notification_dashboard_expanded.xml`, tier sections.

**Stubs only:**
- Anything the dashboard reads from a not-yet-built subsystem returns a placeholder (e.g., `riskScore = 0`).

**Success criteria:**
- Pulling down the notification shade shows the Tier 1/2/3 layout with live data refreshed every 10s.
- Quick actions work (toggle, restart pipeline, emergency stop).
- `LivenessProbe` and `PipelineHealthChecker` distinction is visible in logs: the former pings broadly every 5s, the latter only audits AudioRecord/AudioTrack loops.

**On-device verification:**
- Visually confirm the dashboard renders correctly on the Nokia C22's small display.
- Kill `PersistentAudioService` from `adb shell am stopservice`. `LivenessProbe` should trigger restart via `RecoveryCoordinator` within ~5s.

---

## Layer 6 — Crash / Diagnostic Stack

**Scope:** Now that audio works, instrument it. The diagnostic stack feeds `DaemonStatusAggregator` so the dashboard light up with real risk scores.

**Compiles (full implementation):**
- `core/services/crash/GlobalExceptionHandler.kt`, `NativeCrashMarker.kt`, `SoftRebootTracker.kt`, `LastKnownStateDumper.kt`.
- `core/services/diagnostics/` — full package including `LogStreamCollector.kt`, `RollingLogWriter.kt`, `LogFileRotator.kt`, `CrashSnapshotExporter.kt`, `RoutingLogCollector.kt`, `AudioPolicySnapshot.kt`, `CrashTraceStore.kt`, `RuntimeEventTimeline.kt`, `RuntimeTraceAssembler.kt`, `DiagnosticCompression.kt`, `EventCorrelationEngine.kt`. (Former `SystemHealthScorer` folded into `DaemonStatusAggregator` per ADR-0007 — no separate file.)
- `core/services/diagnostics/system/` — `AppLaunchObserver.kt`, `WindowTransitionTracker.kt`, `PackageStateObserver.kt`. (Former `SoftRebootPredictor` folded into `RecoveryCoordinator`'s soft-reboot risk policy; former `RendererFailureDetector` folded into `PipelineHealthChecker`. See NAMING_RENAMES.md.)
- `core/services/monitoring/` — `HeadsetStateMonitor.kt`, `BluetoothRouteMonitor.kt`, `AudioFocusMonitor.kt`, `SystemPlaybackMonitor.kt`, `DeviceThermalMonitor.kt`, `RuntimeMemoryMonitor.kt`. (Former `ProcessHealthMonitor` split into `MemoryPressureSignal` + `LivenessProbe` — lives under `foreground/signals/` and `foreground/` respectively. NetworkStateMonitor is Layer 7.)
- `core/services/metrics/` — `AudioLatencyMetrics.kt`, `RouteSwitchMetrics.kt`, `CrashMetrics.kt`, `CapturePerformanceTracker.kt`, `BatteryImpactMonitor.kt`.
- `core/services/memory/` — all files in this package.
- `core/services/stability/` — `SafeModeController.kt` (NOTE: `NonceCache.clear()` call from SafeModeController is gated behind Layer 8 — leave that call as a TODO/stub in Layer 6), `StartupBackoffScheduler.kt`, `ProcessRestartLimiter.kt`. (Former `CrashLoopProtector` folded into `RecoveryCoordinator` per ADR-0007 — crash-loop policy lives in Layer A.)
- `core/services/state/`, `core/services/storage/`, `core/services/fallback/`, `core/services/resilience/`, `core/services/oem/` (full registry including `DeviceQuirkRegistry.kt`, `UnisocPlatformTweaks.kt`, etc.), `core/services/receivers/` (`PackageChangeReceiver.kt`, etc.).
- `AndroidManifest.xml` — add `QUERY_ALL_PACKAGES` for A11+ package state queries (see `SYSTEM_MAP.md` §11 Permission Matrix).

**Stubs only:**
- Update stack, FCM, WebSocket, HMAC remain stubbed.

**Success criteria:**
- `RiskScore` in the dashboard moves based on real signals (thermal, crash counters, RecoveryCoordinator's soft-reboot risk policy).
- Triggering a crash (e.g., via a test-only crash button hidden behind a debug build flag) produces a crash bundle exportable via `CrashSnapshotExporter`.
- `SoftRebootTracker` correctly logs uptime anomalies if you force-restart the device (the tracker is the forensic instrument per ADR-0002 — NOT the predictor policy).

**On-device verification:**
- 7-day burn-in test on Nokia C22. Inspect `crash_bundle_*.log` files. No unexpected crashes. Soft reboot predictor's risk score remains <50 in normal use.

---

## Layer 7 — Update System (talks to the mock server)

**Scope:** OTA updates. Per the mock-first phase strategy (ADR-0009), Layer 7 talks to `vyzorix-update-server/cmd/mockserver/` — a real Go binary that serves `/api/v1/version` with a static `version.json` and a small dummy APK. The same endpoint URL switches to the real server at Phase 1.5 with no Android code change.

**Compiles (full implementation):**
- `core/services/updates/UpdateChecker.kt`, `UpdateConfig.kt`, `UpdateNotificationHandler.kt`, `UpdateDownloader.kt`, `UpdateDownloadService.kt` (`foregroundServiceType="dataSync"`), `UpdateInstaller.kt`, `UpdateStateStore.kt`, `UpdateStateMonitor.kt`.
- `core/services/monitoring/NetworkStateMonitor.kt`.
- `core/services/permissions/OverlayPermissionManager.kt` (for the overlay shortcut; not directly update-related but typically wired in here).
- `core/services/provider/` — `FileProvider` for sharing the downloaded APK with the system installer.
- `AndroidManifest.xml` — add `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE_DATA_SYNC`.

**Stubs only:**
- C2 stack (Layer 8).

**Mock-server deliverable for this layer** (`vyzorix-update-server/cmd/mockserver/`):
- `GET /api/v1/version` — returns `version.json` with `version_code`, `version_name`, `apk_url`, `sha256`. Hardcoded for the test APK.
- `GET /api/v1/apk/<filename>` — serves the dummy APK with `Accept-Ranges: bytes` for resume testing.
- Supports `HEAD` for size pre-check.
- ~80–150 lines of Go. Throwaway-style — the real server replaces this in Phase 1.5.

**Success criteria:**
- `UpdateChecker` polls `/api/v1/version`, compares to `BuildConfig.VERSION_CODE`, shows the "Update available" notification.
- User taps "Download". `UpdateDownloadService` starts as a separate `dataSync` foreground service, downloads the APK with resume support, verifies SHA-256.
- `UpdateInstaller` triggers `ACTION_INSTALL_PACKAGE` via `FileProvider`. The system install dialog appears.
- After install, `BootStateRestorer` resumes the daemon from `LastKnownStateDumper`.

**On-device verification:**
- Roll a v1.0.0 → v1.0.1 update. Verify zero data loss (logs, route state, projection token persist across the update).
- Disable WiFi mid-download. Re-enable. Resume must work via the Range header.

---

## Layer 8 — WebSocket + FCM + HMAC (C2 Stack, talks to the mock server)

**Scope:** Real-time command & telemetry. Everything in this layer assumes Layers 0–7 are stable, because a C2 stack failure must NOT take down the audio pipeline. Layer 8 talks to the same `vyzorix-update-server/cmd/mockserver/` binary that Layer 7 uses — the mock implements just enough of `DEVICE_REGISTRATION.md` to make HMAC, nonces, and reconnect flows testable end-to-end.

**Compiles (full implementation):**
- `core/services/security/CommandHmacValidator.kt`, `NonceCache.kt`, `TokenEncryptor.kt`, `AccessibilityIntegrityChecker.kt`, `SafeIntentSanitizer.kt`, `ServicePermissionVerifier.kt`. (Former `ProjectionTokenValidator` folded into `ProjectionTokenManager` per ADR-0006 — single class owns acquire/validate/refresh/store.)
- `core/common/utils/KeystoreManager.kt` — make sure the C2 secret-sealing path is enabled (the body was added in Layer 1 but the `unsealCommandSecretKey()` call site is here).
- `core/data/datastore/DeviceSecretStore.kt` — already exists from Layer 1; here we wire it to `CommandHmacValidator`.
- `core/services/ipc/RemoteCommandExecutor.kt`, `RemoteCommandResultDispatcher.kt`, `AudioRouterBinder.kt`, `ServiceConnectionManager.kt`, `RemoteCommandDispatcher.kt`.
- `core/services/fcm/VyzorixMessagingService.kt`, `FcmCommandParser.kt`, `FcmTokenManager.kt`, `FcmNotificationGateway.kt`, `FcmWakeLockHolder.kt`, `FcmRegistrationWorker.kt`.
- `core/services/websocket/WebSocketClientManager.kt`, `WebSocketConnectionListener.kt`, `WebSocketFrameHandler.kt`, `WebSocketKeepAliveEngine.kt`, `WebSocketReconnectionPolicy.kt`, `WebSocketTelemetryDispatcher.kt`, `WebSocketSessionMetadata.kt`, `PendingResultQueue.kt`.
- Wire `SafeModeController.enter()` to actually call `NonceCache.clear()` (the call site was a TODO in Layer 6).

**Stubs only:** None — Layer 8 closes out Phase 1 against the mock.

**Mock-server deliverable for this layer** (`vyzorix-update-server/cmd/mockserver/`, extended from Layer 7):
- `POST /v1/device/register` — returns a deterministic command_secret (e.g., `0000...` in dev mode, or random+persisted to a file for soak tests). Idempotent on (`deviceId`, `firebaseInstallId`).
- `WSS /v1/device/:id/stream` — accepts WS upgrade with HMAC headers; can issue signed test commands; receives telemetry frames.
- `POST /v1/device/:id/command` — dashboard-style command-issuance endpoint that the mock signs on behalf of the (non-existent) dashboard.
- `PATCH /v1/device/:id/fcm-token` — echoes the new token back.
- No real persistence beyond an in-memory map. ~300–500 lines of Go total when combined with the Layer 7 endpoints. **Replaced by the real server in Phase 1.5.**
- The mock also publishes a sample FCM-shaped command directly to the device via a local debug intent for offline FCM testing (since Firebase requires real network).

**Success criteria:**
- Device registers via `POST /v1/device/register`; receives `command_secret`; stores encrypted via `DeviceSecretStore`.
- Server can issue a signed `FORCE_SPEAKER` command via WebSocket and the device validates HMAC, executes, and dispatches a result frame.
- Replay test: capture a valid frame, replay it. `NonceCache` rejects with `REPLAYED_NONCE`.
- Tampering test: flip a bit in the HMAC. Validator rejects with `INVALID_SIGNATURE`.
- Disconnect test: kill the WSS connection mid-command. Command result enqueues to `PendingResultQueue`. Reconnect. Result is flushed in FIFO order before telemetry resumes.
- Cross-dispatcher test: under load, fire 100 commands across the `Default` and `IO` dispatchers concurrently and verify `NonceCache` / `PendingResultQueue` invariants (see `SYSTEM_MAP.md` §6.3).

**On-device verification:**
- E2E against the mock server (the Vyzorix dashboard does not exist until Phase 2 — use the mock's `POST /v1/device/:id/command` endpoint or a CLI client against the WSS).
- 48-hour soak test with the device sleeping. Silent FCM push must wake the daemon and execute commands within the 10s / 20s wake-lock windows documented in `VyzorixAudioRouter_RepoTree.md` for `FcmWakeLockHolder.kt`.

---

## Layer Dependency DAG

```
Layer 0 (common)
    │
    ▼
Layer 1 (data)         (depends on Layer 0; needs Android context)
    │
    ▼
Layer 2 (audioengine)  (depends on Layer 0; independent of Layer 1)
    │
    ▼
Layer 3 (route war)    (depends on 0, 1, 2 — THIS IS THE GO/NO-GO GATE)
    │
    ▼
Layer 4 (capture)      (depends on 3)
    │
    ▼
Layer 5 (dashboard)    (depends on 3; can technically start in parallel with 4)
    │
    ▼
Layer 6 (diagnostics)  (depends on 3, 4, 5; instruments them)
    │
    ▼
Layer 7 (updates)      (depends on 6 for crash recovery + dashboard)
    │
    ▼
Layer 8 (C2 stack)     (depends on ALL — must be last)
```

Layers 4 and 5 can run in parallel after Layer 3 is verified. All other layers are strictly sequential because each one assumes the previous layer's invariants hold.

---

## "Definition of Done" for Phase 1

Phase 1 is **complete** (against the mock server) when:

1. All 9 layers (0 through 8) compile, lint clean, detekt clean, and pass their on-device verification step on a real Nokia C22 (not an emulator — the Unisoc SC9863A quirks do not surface on emulators).
2. `./gradlew :app:assembleRelease` produces a signed APK that is byte-identical to what `release.yml` would produce in CI.
3. The 7-day burn-in test from Layer 6 has completed at least once on the C22 with no soft reboots and no audio dropouts >50ms, with the device pointed at the mock server.
4. The HMAC / replay / disconnect tests from Layer 8 have all passed against the mock.
5. The `vyzorix-update-server/cmd/mockserver/` Go binary is in the repo, builds with `go build`, and the integration test (`go test ./cmd/mockserver/...`) is green in CI.
6. `SYSTEM_MAP.md`, `BUILD_ORDER.md` (this file), `MEDIA_PROJECTION_FLOW.md`, `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md`, `COMMAND_SECURITY.md`, `NOKIA_C22_NOTES.md`, and `CI_CD_WORKFLOWS.md` all reference the actually-shipped class names and behaviours — no stale references to types that were renamed during implementation.

Once Phase 1 is done, **Phase 1.5** swaps the mock for the real `vyzorix-update-server` (no Android code changes), and **Phase 2** (Vyzorix dashboard + OTA from real server) begins.
