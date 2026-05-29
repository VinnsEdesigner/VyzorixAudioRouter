# BUILD_ORDER.md — Phase 1 Layered Build Sequence

## Document Purpose

The `README.md` previously said only:

```
phase 1 → complete android service first
phase 2 → render update section
```

That is too vague to build from. With a codebase this size — the `VyzorixAudioRouter_RepoTree.md` lists well over 200 Kotlin files across 30+ packages plus a C++ native layer — you cannot just "start writing the Android service" without a concrete sequence. You will end up writing `DiagnosticCompression.kt` before `SpeakerForceEngine.kt` is even tested on the Nokia C22.

This document defines the **Phase 1 layered build order** so that:

1. Each layer compiles and tests independently before the next one starts.
2. The minimum viable audio slice runs on the Nokia C22 as early as Layer 3 — before the diagnostics, dashboard, update, and C2 stacks are built on top of it.
3. Crashes at any layer have a known scope (e.g., a Layer 4 capture bug cannot break Layer 3's route enforcement).
4. The team — including any LLM-assisted code generation — has a definite "what compiles next" answer at every step.

Phase 2 (Render update server + Vyzorix dashboard) only begins after Phase 1 Layer 8 is verified end-to-end on the Nokia C22.

Cross-references: this document expands on `SYSTEM_MAP.md` §7 Lifecycle Dependency Graph. Every layer below maps to specific phases in that graph.

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
          (DaemonStatusProvider + ServiceNotificationDashboard + RemoteViews + tier 1/2/3)
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
- `core/data/database/` — `DaemonDatabase.kt`, `SecureSupportHelper.kt`, `DaemonDatabaseMigrations.kt`, `CryptoHelper.kt`.
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
- Instrumented test that opens `DaemonDatabase`, writes a row, closes the DB, reopens it, reads the row back — verifying SQLCipher encryption is wired and `KeystoreManager` seal/unseal works.
- `DeviceSecretStore.put(secret)` round-trips through `TokenEncryptor` and produces an encrypted blob on disk (the blob must be non-plaintext when inspected via `adb shell run-as`).

**On-device verification (Nokia C22):**
- Install a smoke-test APK that just calls `DaemonDatabase.getInstance(ctx)` from a JUnit instrumented test. Verify it does not crash on the SoC's quirky TEE. If `KeystoreManager` falls back to software-keyed encryption, this is logged but acceptable — that is by design on Unisoc.

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
- `core/services/capture/MediaProjectionCaptureSession.kt`, `PlaybackCaptureEngine.kt`, `CaptureLifecycleController.kt`, `CaptureRecoveryEngine.kt`, `ProjectionTokenManager.kt`, `TokenPersistence.kt`.
- `core/services/capture/ProjectionDeathHandler.kt` — dedicated `MediaProjection.Callback.onStop()` handler; logs to `CrashTraceStore`; triggers `UiRecoveryDaemon`. See `MEDIA_PROJECTION_FLOW.md` §Zombie Prevention.
- `core/services/capture/IdleCaptureController.kt` — silence-detection-driven idle pause for the native PCM pipeline. See `MEDIA_PROJECTION_FLOW.md` §Battery & Soft Reboot Mitigation.
- `core/services/managers/ProjectionSessionManager.kt`.
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

**Scope:** Make the daemon visible without launching a UI. `DaemonStatusProvider` aggregates from the now-existing subsystems and posts to a `RemoteViews`-based dashboard.

**Compiles (full implementation):**
- `core/services/foreground/DaemonStatusProvider.kt` — pulls from `SpeakerForceEngine`, `PlaybackCaptureEngine`, `CrashMetrics` (stub-only counters at this layer), `BatteryImpactMonitor` (stub-only), etc.
- `core/services/foreground/ServiceNotificationDashboard.kt` — Tier 1/2/3 expandable notification.
- `core/services/foreground/SilentKeepAliveService.kt`, `ServiceHeartbeat.kt`, `ServiceRecoveryManager.kt`, `DaemonWatchdog.kt` (broad health), `PipelineHealthChecker.kt` (audio-only health).
- `core/services/foreground/actions/` — `NotificationActionReceiver.kt`, `QuickToggleAction.kt`, `RestartPipelineAction.kt`, `EmergencyStopAction.kt`.
- `core/services/compat/NotificationCompatBridge.kt`, `NotificationTrampolineCompat.kt`, `PendingIntentCompatPolicy.kt`.
- `core/services/permissions/NotificationPermissionManager.kt`.
- Resources: `notification_dashboard_collapsed.xml`, `notification_dashboard_expanded.xml`, tier sections.

**Stubs only:**
- Anything the dashboard reads from a not-yet-built subsystem returns a placeholder (e.g., `riskScore = 0`).

**Success criteria:**
- Pulling down the notification shade shows the Tier 1/2/3 layout with live data refreshed every 10s.
- Quick actions work (toggle, restart pipeline, emergency stop).
- `DaemonWatchdog` and `PipelineHealthChecker` distinction is visible in logs: the former pings broadly every 5s, the latter only audits AudioRecord/AudioTrack loops.

**On-device verification:**
- Visually confirm the dashboard renders correctly on the Nokia C22's small display.
- Kill `PersistentAudioService` from `adb shell am stopservice`. `DaemonWatchdog` should trigger restart via `ServiceRecoveryManager` within ~5s.

---

## Layer 6 — Crash / Diagnostic Stack

**Scope:** Now that audio works, instrument it. The diagnostic stack feeds `DaemonStatusProvider` so the dashboard light up with real risk scores.

**Compiles (full implementation):**
- `core/services/crash/GlobalExceptionHandler.kt`, `NativeCrashMarker.kt`, `SoftRebootTracker.kt`, `LastKnownStateDumper.kt`.
- `core/services/diagnostics/` — full package including `LogStreamCollector.kt`, `RollingLogWriter.kt`, `LogFileRotator.kt`, `CrashSnapshotExporter.kt`, `RoutingLogCollector.kt`, `AudioPolicySnapshot.kt`, `CrashTraceStore.kt`, `RuntimeEventTimeline.kt`, `RuntimeTraceAssembler.kt`, `DiagnosticCompression.kt`, `EventCorrelationEngine.kt`, `SystemHealthScorer.kt`.
- `core/services/diagnostics/system/` — `AppLaunchObserver.kt`, `WindowTransitionTracker.kt`, `PackageStateObserver.kt`, `SoftRebootPredictor.kt`, `RendererFailureDetector.kt`.
- `core/services/monitoring/` — `HeadsetStateMonitor.kt`, `BluetoothRouteMonitor.kt`, `AudioFocusMonitor.kt`, `SystemPlaybackMonitor.kt`, `DeviceThermalMonitor.kt`, `RuntimeMemoryMonitor.kt`, `ProcessHealthMonitor.kt`. (NetworkStateMonitor is Layer 7.)
- `core/services/metrics/` — `AudioLatencyMetrics.kt`, `RouteSwitchMetrics.kt`, `CrashMetrics.kt`, `CapturePerformanceTracker.kt`, `BatteryImpactMonitor.kt`.
- `core/services/memory/` — all files in this package.
- `core/services/stability/` — `CrashLoopProtector.kt`, `SafeModeController.kt` (NOTE: `NonceCache.clear()` call from SafeModeController is gated behind Layer 8 — leave that call as a TODO/stub in Layer 6), `StartupBackoffScheduler.kt`, `ProcessRestartLimiter.kt`.
- `core/services/state/`, `core/services/storage/`, `core/services/fallback/`, `core/services/resilience/`, `core/services/oem/` (full registry including `DeviceQuirkRegistry.kt`, `UnisocPlatformTweaks.kt`, etc.), `core/services/receivers/` (`PackageChangeReceiver.kt`, etc.).
- `AndroidManifest.xml` — add `QUERY_ALL_PACKAGES` for A11+ package state queries (see `SYSTEM_MAP.md` §11 Permission Matrix).

**Stubs only:**
- Update stack, FCM, WebSocket, HMAC remain stubbed.

**Success criteria:**
- `RiskScore` in the dashboard moves based on real signals (thermal, crash counters, soft-reboot predictor).
- Triggering a crash (e.g., via a test-only crash button hidden behind a debug build flag) produces a crash bundle exportable via `CrashSnapshotExporter`.
- `SoftRebootPredictor` correctly logs uptime anomalies if you force-restart the device.

**On-device verification:**
- 7-day burn-in test on Nokia C22. Inspect `crash_bundle_*.log` files. No unexpected crashes. Soft reboot predictor's risk score remains <50 in normal use.

---

## Layer 7 — Update System

**Scope:** OTA updates pulling from the Render-backed update server. Phase 2 has not started yet — for now, the server endpoints can be served from a static `version.json` hosted on GitHub Pages or the eventual `vyzorix-update-server` repo with a dummy APK.

**Compiles (full implementation):**
- `core/services/updates/UpdateChecker.kt`, `UpdateConfig.kt`, `UpdateNotificationHandler.kt`, `UpdateDownloader.kt`, `UpdateDownloadService.kt` (`foregroundServiceType="dataSync"`), `UpdateInstaller.kt`, `UpdateStateStore.kt`, `UpdateStateMonitor.kt`.
- `core/services/monitoring/NetworkStateMonitor.kt`.
- `core/services/permissions/OverlayPermissionManager.kt` (for the overlay shortcut; not directly update-related but typically wired in here).
- `core/services/provider/` — `FileProvider` for sharing the downloaded APK with the system installer.
- `AndroidManifest.xml` — add `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE_DATA_SYNC`.

**Stubs only:**
- C2 stack (Layer 8).

**Success criteria:**
- `UpdateChecker` polls `/api/v1/version`, compares to `BuildConfig.VERSION_CODE`, shows the "Update available" notification.
- User taps "Download". `UpdateDownloadService` starts as a separate `dataSync` foreground service, downloads the APK with resume support, verifies SHA-256.
- `UpdateInstaller` triggers `ACTION_INSTALL_PACKAGE` via `FileProvider`. The system install dialog appears.
- After install, `BootStateRestorer` resumes the daemon from `LastKnownStateDumper`.

**On-device verification:**
- Roll a v1.0.0 → v1.0.1 update. Verify zero data loss (logs, route state, projection token persist across the update).
- Disable WiFi mid-download. Re-enable. Resume must work via the Range header.

---

## Layer 8 — WebSocket + FCM + HMAC (C2 Stack)

**Scope:** Real-time command & telemetry. Everything in this layer assumes Layers 0–7 are stable, because a C2 stack failure must NOT take down the audio pipeline.

**Compiles (full implementation):**
- `core/services/security/CommandHmacValidator.kt`, `NonceCache.kt`, `TokenEncryptor.kt`, `ProjectionTokenValidator.kt`, `AccessibilityIntegrityChecker.kt`, `SafeIntentSanitizer.kt`, `ServicePermissionVerifier.kt`.
- `core/common/utils/KeystoreManager.kt` — make sure the C2 secret-sealing path is enabled (the body was added in Layer 1 but the `unsealCommandSecretKey()` call site is here).
- `core/data/datastore/DeviceSecretStore.kt` — already exists from Layer 1; here we wire it to `CommandHmacValidator`.
- `core/services/ipc/RemoteCommandExecutor.kt`, `RemoteCommandResultDispatcher.kt`, `AudioRouterBinder.kt`, `ServiceConnectionManager.kt`, `DaemonCommandDispatcher.kt`.
- `core/services/fcm/VyzorixMessagingService.kt`, `FcmCommandParser.kt`, `FcmTokenManager.kt`, `FcmNotificationGateway.kt`, `FcmWakeLockHolder.kt`, `FcmRegistrationWorker.kt`.
- `core/services/websocket/WebSocketClientManager.kt`, `WebSocketConnectionListener.kt`, `WebSocketFrameHandler.kt`, `WebSocketKeepAliveEngine.kt`, `WebSocketReconnectionPolicy.kt`, `WebSocketTelemetryDispatcher.kt`, `WebSocketSessionMetadata.kt`, `PendingResultQueue.kt`.
- Wire `SafeModeController.enter()` to actually call `NonceCache.clear()` (the call site was a TODO in Layer 6).

**Stubs only:** None — Layer 8 closes out Phase 1.

**Success criteria:**
- Device registers via `POST /v1/device/register`; receives `command_secret`; stores encrypted via `DeviceSecretStore`.
- Server can issue a signed `FORCE_SPEAKER` command via WebSocket and the device validates HMAC, executes, and dispatches a result frame.
- Replay test: capture a valid frame, replay it. `NonceCache` rejects with `REPLAYED_NONCE`.
- Tampering test: flip a bit in the HMAC. Validator rejects with `INVALID_SIGNATURE`.
- Disconnect test: kill the WSS connection mid-command. Command result enqueues to `PendingResultQueue`. Reconnect. Result is flushed in FIFO order before telemetry resumes.
- Cross-dispatcher test: under load, fire 100 commands across the `Default` and `IO` dispatchers concurrently and verify `NonceCache` / `PendingResultQueue` invariants (see `SYSTEM_MAP.md` §6.3).

**On-device verification:**
- E2E from the Vyzorix dashboard (when it exists in Phase 2 — for Layer 8 acceptance use a CLI client or Postman against the server).
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

Phase 1 is **complete** when:

1. All 9 layers (0 through 8) compile, lint clean, detekt clean, and pass their on-device verification step on a real Nokia C22 (not an emulator — the Unisoc SC9863A quirks do not surface on emulators).
2. `./gradlew :app:assembleRelease` produces a signed APK that is byte-identical to what `release.yml` would produce in CI.
3. The 7-day burn-in test from Layer 6 has completed at least once on the C22 with no soft reboots and no audio dropouts >50ms.
4. The HMAC / replay / disconnect tests from Layer 8 have all passed.
5. `SYSTEM_MAP.md`, `BUILD_ORDER.md` (this file), `MEDIA_PROJECTION_FLOW.md`, `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md`, `COMMAND_SECURITY.md`, `NOKIA_C22_NOTES.md`, and `CI_CD_WORKFLOWS.md` all reference the actually-shipped class names and behaviours — no stale references to types that were renamed during implementation.

Only then does Phase 2 (Render update server + Vyzorix dashboard) begin.
