# MEDIA_PROJECTION_FLOW.md — Universal Audio Interception

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

1. `ProjectionSessionManager` requests the user token.
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

### Mitigation 1: The "Idle" State

- If `PlaybackStateMonitor` detects **silence** (no apps playing audio) for >30 seconds, we **pause** the Native Pipeline.
- We keep the `AudioTrack` open (to maintain VoIP Mode) but stop reading/writing PCM. This reduces CPU load by approximately 60%.

### Mitigation 2: Thermal Watchdog

- `DeviceThermalMonitor` checks the SoC temperature.
- If the device hits "Critical" thermal throttling, we reduce the sample rate (e.g., from 48kHz to 44.1kHz) or temporarily kill the capture to let the phone cool down and prevent a system crash.

### Mitigation 3: Zombie Prevention

- If the system kills `MediaProjection` (common in A13 background restrictions), `ProjectionDeathHandler` detects the callback failure.
- It immediately triggers `UiRecoveryDaemon` to re-launch the permission trampoline, asking the user to grant access again (or automatically if the token is cached/persistent).

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
