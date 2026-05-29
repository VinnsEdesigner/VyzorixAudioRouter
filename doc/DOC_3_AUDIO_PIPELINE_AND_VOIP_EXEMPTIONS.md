# DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md — Native DSP Pipeline, Audio Routing, and VoIP Exemption Mechanics

## Document Purpose
This document is Part 3 of the 8-part Vyzorix System Mapping. It details the low-latency native C++ digital signal processing (DSP) engines, JNI Kotlin boundaries, MediaProjection system-mix capture engines, Voice over IP (VoIP) routing exemption loops, and sub-millisecond audio playback pipelines. This document serves as the implementation specification for routing system-wide audio to the physical speakerphone on a hardware-failed Nokia C22.

---

# 1. The Audio Data Capture, DSP, and Playback Pipeline Flow

The following mapping outlines the complete sub-millisecond data flow from the physical system mixer down to JNI memory boundaries and finally back to the hardware speaker via VoIP routing overrides:

```text
  [SYSTEM MIXER] (Spotify, YouTube, browser, system alarms, notifications)
         │
         ▼ (Captured via Android 10+ AudioPlaybackCapture API)
   PlaybackCaptureEngine (Java/Kotlin Layer)
         │
         ▼ (Buffered in thread-safe, non-allocating allocator)
   AudioBufferPool (Prevents JVM garbage collection latency spikes)
         │
         ▼ (JNI Call: safe_jni_bridge.cpp)
   NativeAudioBridge (Kotlin -> JNI Memory boundary)
         │
         ▼ (Written to lock-free Native Memory ring buffer)
   capture_ring_buffer.cpp (Lock-free Single-Producer Single-Consumer)
         │
         ├── playback_resampler.cpp (Converts source rate -> 48kHz mono/stereo)
         ├── pcm_mixer.cpp (Applies remote gain configurations and stream mixtures)
         ├── audio_clock_sync.cpp (Synchronizes clock drift between capture and playback)
         └── underrun_guard.cpp (Monitors buffer thresholds and injects silent packets)
         │
         ▼ (Read from Ring Buffer via JNI callback)
   AudioPipelineController (Kotlin coordination layer)
         │
         ▼ (Pushed to low-latency AudioTrack write thread)
   SpeakerPlaybackEngine
         │
         ▼ (USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH)
   AudioTrack (Android System Output)
         │
         ▼ (Forced by SpeakerForceEngine running in 500ms loops)
   [PHYSICAL SPEAKERPHONE] (Bypasses headset sensor stuck in connected state)---- 500ms loop: This is running every 500ms indefinitely. On my 2GB device, this + capture + WebSocket heartbeat + dashboard updates = constant CPU churn. AdaptiveSamplingController should dynamically push this to 2000ms+ when the route is stable, only tightening when drift is detected. 
```

---

# 2. Module Blueprint: `:core:audioengine` (Native C++ & JNI Bridge)

The `:core:audioengine` module manages JNI bindings, native memory allocations, and low-latency digital signal processing to ensure smooth audio stream rendering.

```text
core/audioengine/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   ├── capture_ring_buffer.cpp
│   ├── playback_resampler.cpp
│   ├── latency_tracker.cpp
│   ├── pcm_mixer.cpp
│   ├── underrun_guard.cpp
│   ├── audio_clock_sync.cpp
│   ├── logger_engine.cpp
│   ├── crash_guard.cpp
│   ├── safe_jni_bridge.cpp
│   ├── watchdog_ping.cpp
│   ├── memory_guard.cpp
│   ├── ringbuffer_pressure.cpp
│   ├── audio_fallback_bridge.cpp
│   ├── thread_priority_guard.cpp
│   └── include/
│       ├── ring_buffer.h
│       ├── audio_defs.h
│       ├── latency_tracker.h
│       ├── pcm_mixer.h
│       ├── clock_sync.h
│       ├── crash_guard.h
│       ├── watchdog_ping.h
│       ├── safe_jni_bridge.h
│       └── audio_latency_profiler.h
└── kotlin/com/vyzorix/audiorouter/audioengine/
    ├── NativeAudioBridge.kt
    ├── NativeLoader.kt
    ├── AudioPipeline.kt
    ├── PcmFrame.kt
    ├── AudioPipelineController.kt
    ├── PipelineStateTracker.kt
    ├── NativeSafetyController.kt
    ├── NativeCrashRecovery.kt
    ├── PipelineBackpressureController.kt
    └── AudioEngineHealthState.kt
```

### 2.1 Native C++ Submodule (`cpp/`)

#### 2.1.1 `CMakeLists.txt`
*   **Path**: `core/audioengine/src/main/cpp/CMakeLists.txt`
*   **Architectural Role**: Configures the CMake build rules. It links native source files, specifies compiler optimization flags (`-O3`, `-ffast-math`), and binds system NDK libraries (`liboboe`, `libOpenSLES`, `liblog`).

#### 2.1.2 `capture_ring_buffer.cpp`
*   **Path**: `core/audioengine/src/main/cpp/capture_ring_buffer.cpp`
*   **Architectural Role**: Implements a lock-free circular ring-buffer. It allows the high-frequency capture thread and playback thread to write and read PCM bytes simultaneously without resource lockups or memory allocations.
*   **Dependencies**: Implements definitions from `include/ring_buffer.h`.

#### 2.1.3 `playback_resampler.cpp`
*   **Path**: `core/audioengine/src/main/cpp/playback_resampler.cpp`
*   **Architectural Role**: Performs real-time sample rate conversions (e.g., converting captured 44.1kHz audio streams to the physical speaker's native 48kHz output rate).
*   **Failure Boundaries**: If resampling algorithms overflow native stack frames under CPU stress, the class falls back to a linear interpolation method to reduce load.

#### 2.1.4 `latency_tracker.cpp`
*   **Path**: `core/audioengine/src/main/cpp/latency_tracker.cpp`
*   **Architectural Role**: Logs and profilers end-to-end processing delays (the elapsed duration from system-mix capture to hardware-track output) to optimize buffer sizes.

#### 2.1.5 `pcm_mixer.cpp`
*   **Path**: `core/audioengine/src/main/cpp/pcm_mixer.cpp`
*   **Architectural Role**: Mixes separate capture buffers and scales volume gains programmatically to prevent audio clipping or distortion on the speaker hardware.

#### 2.1.6 `underrun_guard.cpp`
*   **Path**: `core/audioengine/src/main/cpp/underrun_guard.cpp`
*   **Architectural Role**: Playback underrun protection. It monitors read pointer offsets; if the buffer is starved of data due to high CPU load, it programmatically injects low-amplitude comfort noise to keep the hardware audio track from stalling.

#### 2.1.7 `audio_clock_sync.cpp`
*   **Path**: `core/audioengine/src/main/cpp/audio_clock_sync.cpp`
*   **Architectural Role**: Manages clock sync. It monitors jitter between capture and playback clocks, dynamically adding or dropping micro-samples to prevent cumulative clock drift.

#### 2.1.8 `logger_engine.cpp`
*   **Path**: `core/audioengine/src/main/cpp/logger_engine.cpp`
*   **Architectural Role**: Redirects native C++ logs into Android's low-overhead platform logging systems (`android/log.h`).

#### 2.1.9 `crash_guard.cpp`
*   **Path**: `core/audioengine/src/main/cpp/crash_guard.cpp`
*   **Architectural Role**: Implements native crash protection. It traps signals like `SIGSEGV` or `SIGBUS` occurring in native memory operations, preventing them from crashing the parent JVM process.

#### 2.1.10 `safe_jni_bridge.cpp`
*   **Path**: `core/audioengine/src/main/cpp/safe_jni_bridge.cpp`
*   **Architectural Role**: Binds JNI execution methods. It manages safe object casting, array pin/release scopes, and converts native telemetry objects into Kotlin data models.

#### 2.1.11 `watchdog_ping.cpp`
*   **Path**: `core/audioengine/src/main/cpp/watchdog_ping.cpp`
*   **Architectural Role**: Native watchdog interface. It responds to periodic ping requests from the service layer to confirm that the native C++ processing loops are healthy and running.

#### 2.1.12 `memory_guard.cpp`
*   **Path**: `core/audioengine/src/main/cpp/memory_guard.cpp`
*   **Architectural Role**: Prevents memory leaks. It intercepts memory allocations (`malloc`, `free`) in the native layer to verify that all blocks are released properly.

#### 2.1.13 `ringbuffer_pressure.cpp`
*   **Path**: `core/audioengine/src/main/cpp/ringbuffer_pressure.cpp`
*   **Architectural Role**: Tracks ring-buffer pressure. It calculates queue density; if the buffer exceeds 80% capacity under heavy processing loads, it signals the controller to discard unneeded frames and prevent pipeline blockages.

#### 2.1.14 `audio_fallback_bridge.cpp`
*   **Path**: `core/audioengine/src/main/cpp/audio_fallback_bridge.cpp`
*   **Architectural Role**: Fallback bridge. If JNI calls fail or native libraries are missing, this class routes the raw capture stream directly to Java-only fallback pipelines.

#### 2.1.15 `thread_priority_guard.cpp`
*   **Path**: `core/audioengine/src/main/cpp/thread_priority_guard.cpp`
*   **Architectural Role**: Adjusts thread priorities. It elevates native C++ processing threads to high-priority Real-Time (RT) scheduling classes (`SCHED_FIFO`), bypassing standard process priorities.

---

### 2.2 Kotlin Engine Submodule (`kotlin/.../audioengine/`)

#### 2.2.1 `NativeAudioBridge.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/NativeAudioBridge.kt`
*   **Architectural Role**: Handles Kotlin JNI declarations. It maps method names (`nativeWriteBuffer`, `nativeReadBuffer`, `nativeGetLatency`) to their compiled C++ counterparts.

#### 2.2.2 `NativeLoader.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/NativeLoader.kt`
*   **Architectural Role**: Handles native library loading. It loads `libaudioengine.so` safely.
*   **Failure Boundaries**: If loading fails (e.g., due to an `UnsatisfiedLinkError` on older APIs), it catches the exception and falls back to Java-only capture pipelines.

#### 2.2.3 `AudioPipeline.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/AudioPipeline.kt`
*   **Architectural Role**: Binds the audio pipeline. It manages lifecycle hooks to initialize, start, and teardown the active native audio processing loops.

#### 2.2.4 `PcmFrame.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/PcmFrame.kt`
*   **Architectural Role**: Maps raw PCM structures. It defines frame lengths, sample rates, byte sizes, and provides pooling to avoid garbage collection overhead.

#### 2.2.5 `AudioPipelineController.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/AudioPipelineController.kt`
*   **Architectural Role**: Coordinates audio processing stages. It acts as the bridge between native JNI code and Kotlin threads, monitoring buffer levels and dispatching processing tasks.

#### 2.2.6 `PipelineStateTracker.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/PipelineStateTracker.kt`
*   **Architectural Role**: Tracks pipeline states. It provides state flags (`INITIALIZING`, `STREAMING`, `PAUSED`, `ERROR`) to other services to coordinate play/pause loops.

#### 2.2.7 `NativeSafetyController.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/NativeSafetyController.kt`
*   **Architectural Role**: Monitors native processing health. It receives warning signals from the native C++ layer and coordinates graceful fallbacks if errors occur.

#### 2.2.8 `NativeCrashRecovery.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/NativeCrashRecovery.kt`
*   **Architectural Role**: Recovers from native crashes. It intercepts JVM crashes originating from JNI and attempts to rebuild the native state safely.

#### 2.2.9 `PipelineBackpressureController.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/PipelineBackpressureController.kt`
*   **Architectural Role**: Prevents pipeline congestion. It drops older frames when consumer pipeline stalls, maintaining a stable stream of live audio.

#### 2.2.10 `AudioEngineHealthState.kt`
*   **Path**: `core/audioengine/src/main/kotlin/com/vyzorix/audiorouter/audioengine/AudioEngineHealthState.kt`
*   **Architectural Role**: Telemetry data model. It packages native pipeline statistics (e.g., buffer pressure, resampler rates, underrun counts) into a structured model for remote reporting.

---

# 3. Submodule: `audio` (The Routing and Focus Arbitration Core)

The `audio` package manages audio focus requests, active playback session identification, and route assertions.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/
├── AudioFocusHandler.kt
├── InterruptionPolicy.kt
├── focus/
│   ├── FocusRecoveryCoordinator.kt
│   ├── FocusPriorityPolicy.kt
│   ├── FocusConflictResolver.kt
│   ├── FocusPersistenceEngine.kt
│   ├── FocusEventHistory.kt
│   ├── FocusSuppressionPolicy.kt
│   └── AudioDuckController.kt
├── media/
│   ├── ActiveMediaSessionResolver.kt
│   ├── MediaPriorityPolicy.kt
│   ├── ForegroundPlaybackResolver.kt
│   ├── CaptureOwnershipArbitrator.kt
│   ├── MediaSessionWatcher.kt
│   ├── PlaybackOriginClassifier.kt
│   ├── SessionEvictionPolicy.kt
│   └── PlaybackStateMonitor.kt
├── route/
│   ├── RouteAssertionEngine.kt
│   ├── RouteConflictResolver.kt
│   ├── RouteEscalationPolicy.kt
│   └── RouteFailureJournal.kt
└── session/
    ├── AudioSessionRegistry.kt
    ├── SessionPriorityManager.kt
    ├── PlaybackUidTracker.kt
    └── CaptureEligibilityChecker.kt
```

### 3.1 `AudioFocusHandler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/AudioFocusHandler.kt`
*   **Architectural Role**: Binds the system audio focus listener. It handles focus gains and losses, notifying other modules to adjust playback or capture accordingly.
*   **Core APIs**: Binds directly to `AudioManager.OnAudioFocusChangeListener`.

### 3.2 `InterruptionPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/InterruptionPolicy.kt`
*   **Architectural Role**: Defines focus interruption policies. It specifies how the daemon responds to transient focus losses (e.g., pausing capture during calls, ducking volume during notification ringtones).

---

### 3.3 Focus Arbitration Subpackage (`focus/`)

#### 3.3.1 `FocusRecoveryCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/focus/FocusRecoveryCoordinator.kt`
*   **Architectural Role**: Coordinates focus recovery. After a system interruption ends, this class schedules a brief delay before reclaiming focus, preventing focus conflicts.

#### 3.3.2 `FocusPriorityPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/focus/FocusPriorityPolicy.kt`
*   **Architectural Role**: Defines focus priority rules. It establishes the priority hierarchy:
    `PHONE_CALL` -> `SYSTEM_ALARM` -> `ACTIVE_DAEMON` -> `BACKGROUND_MEDIA`.

#### 3.3.3 `FocusConflictResolver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/focus/FocusConflictResolver.kt`
*   **Architectural Role**: Resolves active focus conflicts between background media sessions.

#### 3.3.4 `FocusPersistenceEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/focus/FocusPersistenceEngine.kt`
*   **Architectural Role**: Forces focus lock. It continuously plays a sub-audible silent wave (`silent_anchor.wav`) using `USAGE_VOICE_COMMUNICATION` to maintain state dominance.

#### 3.3.5 `FocusEventHistory.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/focus/FocusEventHistory.kt`
*   **Architectural Role**: Logs focus changes. It maintains a database journal of active focus transitions for debugging.

#### 3.3.6 `FocusSuppressionPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/focus/FocusSuppressionPolicy.kt`
*   **Architectural Role**: Focus suppression policy. It temporarily suspends focus reclaim requests if the system is unstable or repeatedly rejects focus.

#### 3.3.7 `AudioDuckController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/focus/AudioDuckController.kt`
*   **Architectural Role**: Handles system audio ducking, reducing volume when system alerts or notifications ring.

---

### 3.4 Media stream Tracking Subpackage (`media/`)

#### 3.4.1 `ActiveMediaSessionResolver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/ActiveMediaSessionResolver.kt`
*   **Architectural Role**: Resolves active media sessions, identifying which app holds the dominant playback stream.
*   **State Dependencies**: Relies on `MediaSessionManager.getActiveSessions()`.

#### 3.4.2 `MediaPriorityPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/MediaPriorityPolicy.kt`
*   **Architectural Role**: Establishes player priority rules. It defines priorities (e.g., foreground media overrides navigation streams).

#### 3.4.3 `ForegroundPlaybackResolver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/ForegroundPlaybackResolver.kt`
*   **Architectural Role**: Resolves foreground playback. It correlates `UsageStats` and active media sessions to identify the active pipeline source.

#### 3.4.4 `CaptureOwnershipArbitrator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/CaptureOwnershipArbitrator.kt`
*   **Architectural Role**: Resolves capture conflicts when multiple apps play audio simultaneously.

#### 3.4.5 `MediaSessionWatcher.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/MediaSessionWatcher.kt`
*   **Architectural Role**: Binds listeners to watch for new media players starting up.

#### 3.4.6 `PlaybackOriginClassifier.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/PlaybackOriginClassifier.kt`
*   **Architectural Role**: Categorizes playback stream sources (e.g., distinguishing between music apps and system alerts).

#### 3.4.7 `SessionEvictionPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/SessionEvictionPolicy.kt`
*   **Architectural Role**: Drops old, inactive playback structures to conserve memory.

#### 3.4.8 `PlaybackStateMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/media/PlaybackStateMonitor.kt`
*   **Architectural Role**: Passive listener for active system player changes, notifying capture engines to adjust buffer sizes.

---

### 3.5 Routing Enforcement Subpackage (`route/`)

#### 3.5.1 `RouteAssertionEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/route/RouteAssertionEngine.kt`
*   **Architectural Role**: Validates audio routing states, ensuring audio output is routed to the physical speakerphone.

#### 3.5.2 `RouteConflictResolver.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/route/RouteConflictResolver.kt`
*   **Architectural Role**: Resolves routing conflicts when external hardware is connected (e.g., Bluetooth audio connections).

#### 3.5.3 `RouteEscalationPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/route/RouteEscalationPolicy.kt`
*   **Architectural Role**: Escalates failed route corrections (e.g., performing a soft HAL reset if standard reassertions fail).

#### 3.5.4 `RouteFailureJournal.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/route/RouteFailureJournal.kt`
*   **Architectural Role**: Keeps database records of routing issues to analyze hardware degradation patterns over time.

---

### 3.6 Capture Session Registry Subpackage (`session/`)

#### 3.6.1 `AudioSessionRegistry.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/session/AudioSessionRegistry.kt`
*   **Architectural Role**: Tracks active playback sessions and lists current UIDs in a local database table.

#### 3.6.2 `SessionPriorityManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/session/SessionPriorityManager.kt`
*   **Architectural Role**: Chooses which playback session holds capture dominance.

#### 3.6.3 `PlaybackUidTracker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/session/PlaybackUidTracker.kt`
*   **Architectural Role**: Maps active player UIDs to process identifiers.

#### 3.6.4 `CaptureEligibilityChecker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/audio/session/CaptureEligibilityChecker.kt`
*   **Architectural Role**: Checks if an audio session can be captured, verifying if the target package permits casting.

---

# 4. Submodule: `voip` (Voice over IP Routing Exemption)

The `voip` package implements the core logic to keep the system locked in `MODE_IN_COMMUNICATION` to override the stuck headset jack.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/
├── SilentVoipSession.kt
├── CommunicationRouter.kt
├── VoipAudioAnchor.kt
├── AudioModeKeeper.kt
├── SpeakerForceEngine.kt
├── CommunicationDeviceSelector.kt
└── RoutePersistenceDaemon.kt
```

### 4.1 `SilentVoipSession.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/SilentVoipSession.kt`
*   **Architectural Role**: Initializes an active VoIP session state in memory. This keeps the OS in a high-priority voice routing state, ensuring speaker routing remains dominant.

### 4.2 `CommunicationRouter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/CommunicationRouter.kt`
*   **Architectural Role**: Reroutes audio paths. It forces system audio streams through the active VoIP routing layers.

### 4.3 `VoipAudioAnchor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/VoipAudioAnchor.kt`
*   **Architectural Role**: Manages the VoIP playback track. It maintains a silent looping audio track configured with voice usage attributes to keep routing exemptions active.

### 4.4 `AudioModeKeeper.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/AudioModeKeeper.kt`
*   **Architectural Role**: Reapplies system audio parameters, programmatically reasserting the target communication mode if other apps attempt to override it.

### 4.5 `SpeakerForceEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/SpeakerForceEngine.kt`
*   **Architectural Role**: Forces speaker routing. It runs a continuous loop that checks the route state and reapplies speakerphone modes.
*   **Operation**:
    ```kotlin
    if (!audioManager.isSpeakerphoneOn) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }
    ```

### 4.6 `CommunicationDeviceSelector.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/CommunicationDeviceSelector.kt`
*   **Architectural Role**: Output device selector. On Android 11+, it queries `audioManager.getCommunicationDevice()` and reasserts the physical speakerphone as the target output route.

### 4.7 `RoutePersistenceDaemon.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/voip/RoutePersistenceDaemon.kt`
*   **Architectural Role**: Monitors routing persistence. It detects if the physical speakerphone falls back to the broken headset jack, immediately triggering recovery actions.

---

# 5. Submodule: `playback` (Sub-millisecond AudioTrack Pipeline)

The `playback` package manages high-speed playback threads and write loops.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/
├── SpeakerPlaybackEngine.kt
├── AudioTrackController.kt
├── AudioTrackFactory.kt
├── LatencyOptimizer.kt
├── RouteRecoveryEngine.kt
├── PlaybackGainController.kt
├── SpeakerOutputVerifier.kt
├── PlaybackThread.kt
└── UnderrunRecovery.kt
```

### 5.1 `SpeakerPlaybackEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/SpeakerPlaybackEngine.kt`
*   **Architectural Role**: Sub-millisecond PCM playback coordinator. It reads frames from the native buffer and writes them to the high-priority output track.

### 5.2 `AudioTrackController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/AudioTrackController.kt`
*   **Architectural Role**: Coordinates play, pause, and flush commands on the physical `AudioTrack` instance.

### 5.3 `AudioTrackFactory.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/AudioTrackFactory.kt`
*   **Architectural Role**: Creates optimized `AudioTrack` instances configured with `USAGE_VOICE_COMMUNICATION` and `CONTENT_TYPE_SPEECH` to guarantee routing to the physical speaker.

### 5.4 `LatencyOptimizer.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/LatencyOptimizer.kt`
*   **Architectural Role**: Optimizes playback latency. It monitors buffer levels and dynamically resizes buffers to prevent audio stutters under heavy processing loads.

### 5.5 `RouteRecoveryEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/RouteRecoveryEngine.kt`
*   **Architectural Role**: Handles track recovery. It re-initializes output tracks if routing failures are detected.

### 5.6 `PlaybackGainController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/PlaybackGainController.kt`
*   **Architectural Role**: Manages volume normalization, preventing signal clipping on the speaker hardware.

### 5.7 `SpeakerOutputVerifier.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/SpeakerOutputVerifier.kt`
*   **Architectural Role**: Verifies speaker routing states, checking if the active output device matches the built-in speaker.

### 5.8 `PlaybackThread.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/PlaybackThread.kt`
*   **Architectural Role**: High-priority worker thread executing the main output write loops.

### 5.9 `UnderrunRecovery.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/UnderrunRecovery.kt`
*   **Architectural Role**: Recovers from buffer starvation. It injects silence frames if input data drops, preventing the hardware track from stalling.

---

# 6. Submodule: `capture` (MediaProjection System-Mix Capture)

The `capture` package captures system-wide audio using the privileged `MediaProjection` token.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/
├── MediaProjectionSession.kt
├── PlaybackCaptureEngine.kt
├── AudioCaptureConfig.kt
├── CapturePermissionStore.kt
├── PlaybackCaptureFactory.kt
├── CaptureLifecycleController.kt
├── CaptureRecoveryEngine.kt
├── ProjectionTokenManager.kt
└── TokenPersistence.kt
```

### 6.1 `MediaProjectionSession.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/MediaProjectionSession.kt`
*   **Architectural Role**: Manages active projection sessions, binding tokens, and managing system callbacks.

### 6.2 `PlaybackCaptureEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/PlaybackCaptureEngine.kt`
*   **Architectural Role**: Primary capture manager. It configures `AudioRecord` with `AudioPlaybackCaptureConfiguration` to extract raw PCM frames from the system mixer.

### 6.3 `AudioCaptureConfig.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/AudioCaptureConfig.kt`
*   **Architectural Role**: Defines capture parameters (sample rates, mono/stereo configurations, buffer budgets).

### 6.4 `CapturePermissionStore.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/CapturePermissionStore.kt`
*   **Architectural Role**: Persists the active MediaProjection consent state, tracking token expiration limits.

### 6.5 `PlaybackCaptureFactory.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/PlaybackCaptureFactory.kt`
*   **Architectural Role**: Factory building target `AudioPlaybackCaptureConfiguration` setups mapping to the system audio source.

### 6.6 `CaptureLifecycleController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/CaptureLifecycleController.kt`
*   **Architectural Role**: Manages capture start/stop actions. It pauses loops when no active player is detected to conserve resources.

### 6.7 `CaptureRecoveryEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/CaptureRecoveryEngine.kt`
*   **Architectural Role**: Recovers capture loops if thread halts or resources are reclaimed by the OS.

### 6.8 `ProjectionTokenManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/ProjectionTokenManager.kt`
*   **Architectural Role**: Manages token lifecycles and handles system-level revocation callbacks.

### 6.9 `TokenPersistence.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/TokenPersistence.kt`
*   **Architectural Role**: Securely serializes token metadata using `CryptoHelper` and stores it to disk.

---

# 7. Submodule: `projection` (MediaProjection Background Workarounds)

The `projection` package implements legal workarounds to request projection permissions from background threads on Android 13.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/
├── ProjectionLaunchCoordinator.kt
├── FullScreenIntentBridge.kt
├── ProjectionActivityMediator.kt
├── ProjectionLaunchConditions.kt
├── ProjectionRetryPolicy.kt
├── ProjectionVisibilityGuard.kt
└── ProjectionForegroundEscalator.kt
```

### 7.1 `ProjectionLaunchCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/ProjectionLaunchCoordinator.kt`
*   **Architectural Role**: Orchestrates projection requests. It verifies system readiness (e.g., screen status, lock screen status) before initiating permission flows.

### 7.2 `FullScreenIntentBridge.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/FullScreenIntentBridge.kt`
*   **Architectural Role**: Bypasses background activity blocks. It posts a high-priority notification using `fullScreenIntent` to legally surface the permission dialog over active windows.

### 7.3 `ProjectionActivityMediator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/ProjectionActivityMediator.kt`
*   **Architectural Role**: Trampoline mediator. It opens translucentactivities and listens for result callbacks once user grants are completed.

### 7.4 `ProjectionLaunchConditions.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/ProjectionLaunchConditions.kt`
*   **Architectural Role**: Evaluates system readiness before launch (confirming screen is unlocked and notification channel is active).

### 7.5 `ProjectionRetryPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/ProjectionRetryPolicy.kt`
*   **Architectural Role**: Throttles projection requests, preventing layout loops under stress.

### 7.6 `ProjectionVisibilityGuard.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/ProjectionVisibilityGuard.kt`
*   **Architectural Role**: Prevents background activity exceptions. It aborts flows if foreground eligibility is missing, protecting the daemon from crash reports.

### 7.7 `ProjectionForegroundEscalator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/projection/ProjectionForegroundEscalator.kt`
*   **Architectural Role**: Temporarily elevates service priority during permission re-grants to protect the process from OS-level reclamation.
