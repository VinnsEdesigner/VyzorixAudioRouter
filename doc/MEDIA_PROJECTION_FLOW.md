# MEDIA_PROJECTION_FLOW.md — Universal Audio Interception (deep-dive of DOC_3)

> **This is a deep-dive of [`DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md`](./DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md).** DOC_3 is the canonical architectural spec for the audio pipeline; this document covers the MediaProjection-specific capture chain, idle pause, and projection death handling in implementation-level detail. If anything here contradicts DOC_3, DOC_3 wins and this document should be updated.

## Objective

Capture the **global system audio mix** (YouTube, Spotify, Browser, Games) and feed it into our **VoIP Speaker Force pipeline**, bypassing the device's broken headset codec routing.

## The Problem: Why `AudioRecord` Isn't Enough

Standard Android API restrictions (`AudioRecord` with `MediaPlayback` source) are locked down. You cannot simply "listen" to other apps.

- **App-Level Blocking:** Apps like Netflix or Banking apps use `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)`.
- **The Loophole:** Android 10+ introduced `AudioPlaybackCapture`, which is unlocked *only* if you have a valid `MediaProjection` session token (granted by the user via the system screen-casting dialog).

## The Capture Chain (Architecture)

We treat the `MediaProjection` token as a **Key** to unlock the system audio mixer, even though we don't actually need the video.

### 1. The "Trampoline" Permission Flow

- **Trigger:** `BootstrapCoordinator` detects `MediaProjection` permission is missing.
- **Action:** Launches `ProjectionPermissionActivity`.
- **User Action:** Taps "Start Now" on the system dialog.
- **Result:** We get a `MediaProjection` token. The Activity immediately calls `finish()`.
- **Headless State:** The token is passed to the `PersistentAudioService`. The user sees no recording overlay (we suppress it via `NotificationCompatBridge` and running in the background).

### 2. The Audio Pipeline (Input to Processing to Output)

| Stage | Component | Description |
|-------|-----------|-------------|
| **Input** | `PlaybackCaptureEngine` | Uses the `MediaProjection` token to build `AudioPlaybackCaptureConfiguration`. It requests the System Mixer. |
| **Buffer** | `capture_ring_buffer.cpp` | A lock-free ring buffer in Native memory. We write raw PCM here to avoid GC pressure and underruns. |
| **Processing** | `pcm_mixer.cpp` | If multiple apps play audio, this stage normalizes volume (preventing clipping) and resamples to 48kHz (standard speaker rate). |
| **Output** | `SpeakerPlaybackEngine` | Reads from the ring buffer and writes to an `AudioTrack` configured as `USAGE_VOICE_COMMUNICATION`. |
| **Routing** | `SpeakerForceEngine` | Ensures the `AudioTrack` output goes to the physical Speaker, not the phantom headset. |

## The Execution Flow

### Phase 1: Initialization (The "Key")

1. `MediaProjectionSession` requests the user token.
2. Once granted, it calls `registerCallback()` to detect if the system revokes it (e.g., user stops casting).
3. **Optimization:** We configure the capture to **Audio Only**. We do not create a virtual display for video, saving massive battery and CPU.

### Phase 2: The Capture Loop

1. `PlaybackCaptureEngine` opens an `AudioRecord` instance linked to the projection.
2. It enters a `while(isRunning)` loop, reading bytes into `AudioBufferPool`.
3. **Drift Detection:** If the buffer gets too full (lag) or too empty (starvation), `LatencyOptimizer` adjusts the read chunk size.

### Phase 3: The Handoff (Native Bridge)

1. When the buffer hits a "High Water Mark" (e.g., 50% full), it signals the `NativeAudioBridge`.
2. The Native bridge copies the PCM data to the C++ `ring_buffer`.
3. **Why Native?** Java garbage collection can cause "micro-stutters" in audio. C++ ensures smooth, uninterrupted flow.

### Phase 4: Playback (The "Route War")

1. The `SpeakerPlaybackEngine` reads from the C++ ring buffer.
2. It writes to the `AudioTrack` (VoIP Mode).
3. **Result:** The sound that *would* have gone to the broken headset is now blasting out of the speaker.

## Handling "Blocked" Apps (DRM/Privacy)

Some apps (Netflix, Prime Video) will refuse to send audio to our capture engine.

- **Detection:** `CapturePerformanceTracker` notices the buffer is empty (starvation) even though `UsageStats` shows a media app is playing.
- **Fallback Strategy:**
  - If we detect a specific app is blocking capture, we log it in `CrashTraceStore`.
  - We *cannot* bypass this on stock Android. The audio remains silent in our pipeline (but might still be audible if the system routes it to the speaker via `MODE_IN_COMMUNICATION` - we rely on the `SpeakerForceEngine` to handle the routing for these specific apps).

## Battery & Soft Reboot Mitigation

`MediaProjection` is a resource-heavy service. On a Nokia C22, keeping this alive 24/7 can trigger the **Soft Reboot** issue you are diagnosing.

### Mitigation 1: The "Idle" State (owned by `IdleCaptureController.kt`)

The idle-pause loop is owned by a dedicated class: `core/services/capture/IdleCaptureController.kt`. It does NOT live in `PlaybackCaptureEngine` itself — the capture engine stays single-purpose (move PCM bytes), and the controller wraps it with a silence-detection policy.

- **Trigger:** `IdleCaptureController` subscribes to `PlaybackStateMonitor` and tracks silence duration via a debounced timer.
- **Threshold:** If silence (no apps playing audio) persists for >30 seconds, `IdleCaptureController` calls `PlaybackCaptureEngine.pauseNativeReads()`.
- **What "pause" actually means:** Native PCM reads from `capture_ring_buffer.cpp` stop. `AudioTrack` stays open in `MODE_IN_COMMUNICATION` so the VoIP routing exemption is not lost — dropping out of communication mode would relinquish the speaker-force advantage and let the broken headset codec re-engage. CPU drops ~60% (verified via `top` on the Nokia C22; idle daemon goes from ~12% to ~5%).
- **Resume:** `IdleCaptureController` resumes immediately on the first of: (a) `PlaybackStateMonitor` reports any active media playback, (b) `AppLaunchObserver` reports a known media app entering foreground, (c) `UsageStatsManager` poll detects new media events. Resume latency target: <200ms (perceptually instant).
- **State machine integration:** This is the ACTIVE ⇄ IDLE_PAUSED transition pair documented in `SYSTEM_MAP.md` §8.3.
- **Failure case:** If `pauseNativeReads()` or `resumeNativeReads()` throws (e.g., the native ring buffer entered a bad state during pause), `CaptureRecoveryEngine` restarts the capture loop from scratch.
- **Battery footprint:** Combined with thermal throttling, this mitigation is the primary reason a 24/7 deployment on the Nokia C22 does not trigger the soft-reboot cascade documented in `SOFT_REBOOT_ANALYSIS.md`.

### Mitigation 2: Thermal Watchdog

- `DeviceThermalMonitor` checks the SoC temperature.
- If the device hits "Critical" thermal throttling, we reduce the sample rate (e.g., from 48kHz to 44.1kHz) or temporarily kill the capture to let the phone cool down and prevent a system crash.

### Mitigation 3: Zombie Prevention (owned by `ProjectionDeathHandler.kt`)

Projection death recovery is owned by a dedicated class: `core/services/capture/ProjectionDeathHandler.kt`. It is **distinct** from `ProjectionTokenManager.kt` — the manager tracks the token's general lifecycle (grant / revoke / persist), while the death handler is a single-purpose listener for the specific `MediaProjection.Callback.onStop()` callback that fires when the system involuntarily tears down the session.

- **Registration:** `MediaProjectionSession` registers `ProjectionDeathHandler` as a `MediaProjection.Callback` on the active projection during T+10s of the startup sequence (see `SYSTEM_MAP.md` §2).
- **Trigger:** Android's `MediaProjection.Callback.onStop()` fires. On the Nokia C22 this is most commonly caused by:
  - A13 background-restriction enforcement during Doze mode.
  - Memory pressure-driven `oom_score_adj` recalculation killing the projection process.
  - System update / app update reinstalling the package.
  - User explicitly stopping casting from the system UI (rare; we suppress the recording overlay).
- **Response sequence:**
  1. Log `ProjectionDeathEvent { reason, uptime_ms, capture_state, last_pcm_timestamp }` to `CrashTraceStore` for forensic analysis.
  2. Increment `RuntimeEventTimeline` with a high-severity entry.
  3. Pause `IdleCaptureController` (it is invalid to keep silence-detecting on a dead projection).
  4. Invoke `UiRecoveryDaemon.recoverProjection()`. The daemon re-launches `ProjectionPermissionActivity`; `AccessibilityGestureQueue` auto-clicks "Start Now" within ~100ms; the trampoline finishes; a new token flows back to `ProjectionTokenManager` which updates `TokenPersistence`.
  5. On successful re-grant: `CaptureRecoveryEngine` rebuilds `PlaybackCaptureEngine` against the new token and resumes from the last known capture state.
- **Failure-of-failure case:** If three consecutive re-grant attempts fail within 60 seconds, `CommunicationModeFallback` activates: the daemon abandons the projection-based capture path and relies on VoIP-mode speaker forcing alone. This degrades capability (apps' system audio no longer routes through our pipeline) but preserves the headset-codec bypass.
- **State machine integration:** This is the ACTIVE → REVOKED transition documented in `SYSTEM_MAP.md` §8.3.
- **Why a separate class:** Keeping the onStop handler isolated from `ProjectionTokenManager` means a bug in the recovery path cannot corrupt the normal token-lifecycle bookkeeping, and vice versa. The two classes communicate only through `ProjectionTokenManager`'s public API.

## On-Device Verification (No PC Required)

### Method 1: In-App Capture Status Screen

- Accessible via tap pattern on the app icon or settings menu.
- Display:
  - `MediaProjection` token state (Active / Revoked / Pending)
  - Capture engine status (Running / Paused / Starved)
  - Buffer health (Percentage full, underrun count)
  - Current sample rate and bit depth

### Method 2: Audio Loopback Test

- Built-in diagnostic feature:
  1. Tap "Test Capture" in diagnostic screen
  2. System plays a short test tone through `USAGE_VOICE_COMMUNICATION`
  3. `PlaybackCaptureEngine` attempts to capture it
  4. If captured tone matches original: Pipeline functional
  5. If silent or corrupted: Pipeline blocked

### Method 3: Notification-Based Status

- Persistent notification shows capture state:
  - Blue indicator: Active capture, all streams flowing
  - Yellow indicator: Partial capture, some apps blocked
  - Red indicator: Capture session lost, needs re-grant
  - Gray indicator: Idle, no audio playing

### Method 4: Physical Confirmation

- Play a YouTube video in another app.
- If audio comes through the speaker (via VoIP route) and the capture status shows "Active": Working.
- If audio is silent but YouTube is playing: Capture blocked or routing failed.
- If audio crackles or stutters: Buffer underrun or thermal throttling active.
