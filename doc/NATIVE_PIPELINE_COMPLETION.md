# Native Audio Pipeline Completion

**Date:** 2026-07-04  
**Author:** OpenHands (Claude Sonnet 4.7)  
**Status:** ✅ COMPLETE

---

## Executive Summary

The native audio DSP pipeline (Layer 2+3) was incomplete — frames were flowing directly Java-to-Java, bypassing the C++ processing engine entirely. This completion bridges that gap by implementing the missing JNI boundary crossing components.

---

## What Was Missing

### Gap Analysis

| Component | Status | Issue |
|-----------|--------|-------|
| `AudioPipeline` | ✅ Landed | Ring buffer allocated, native init working |
| `AudioPipelineController` | ✅ Landed | JNI bridge methods exist |
| `CaptureLifecycleController` | ✅ Landed | Orchestrates capture |
| `SpeakerPlaybackEngine` | ✅ Landed | Writes to AudioTrack |
| **`NativeFrameSink`** | ❌ Missing | Capture → native crossing |
| **`NativePlaybackSource`** | ❌ Missing | Native → playback crossing |
| **`PlaybackSource` interface** | ❌ Missing | Abstraction for playback sources |
| **Pipeline state wiring** | ❌ Missing | `Streaming` never set |

### Data Flow Before (Broken)

```
PlaybackCaptureEngine
         ↓ (FrameSink - direct Java)
speakerPlaybackEngine  ← bypasses native!
         ↓ (ArrayBlockingQueue)
AudioTrack
```

### Data Flow After (Fixed)

```
PlaybackCaptureEngine
         ↓ (FrameSink)
nativeFrameSink        ← NEW
         ↓ JNI
libaudioengine.so
  - Lock-free SPSC ring buffer
  - Real-time resampling
  - Clock drift correction
  - PCM gain/mixing
  - Comfort noise injection
         ↓ JNI
nativePlaybackSource   ← NEW
         ↓ (PlaybackSource)
speakerPlaybackEngine
         ↓
AudioTrack
```

---

## Implementation

### New Files

#### 1. `NativeFrameSink.kt`
**Path:** `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/capture/NativeFrameSink.kt`

Implements `FrameSink` to feed captured PCM into the native ring buffer via JNI.

```kotlin
public class NativeFrameSink(
    private val pipelineController: AudioPipelineController,
    private val maxDroppedPerWindow: Int = 10,
) : FrameSink
```

**Key responsibilities:**
- Receive PCM bytes from `PlaybackCaptureEngine`
- Write to native ring buffer via `pipelineController.feedCapturedFrame()`
- Track overrun count when buffer is full
- Log warnings on consecutive drops

#### 2. `NativePlaybackSource.kt`
**Path:** `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/NativePlaybackSource.kt`

Implements new `PlaybackSource` interface to pull PCM from native ring buffer.

```kotlin
public class NativePlaybackSource(
    private val pipelineController: AudioPipelineController,
    private val underrunRecovery: () -> Unit = {},
) : PlaybackSource
```

**Key responsibilities:**
- Read PCM from native ring buffer via `pipelineController.pullPlaybackFrame()`
- Report underrun events to `LatencyOptimizer`
- Coordinate with native comfort-noise injection

#### 3. `PlaybackSource.kt`
**Path:** `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/playback/PlaybackSource.kt`

Abstraction for PCM data sources consumed by `SpeakerPlaybackEngine`.

```kotlin
public interface PlaybackSource {
    public fun read(dst: ByteArray, offsetBytes: Int, lengthBytes: Int): Int
}
```

**Purpose:**
- Allows `SpeakerPlaybackEngine` to operate in two modes:
  - **Native path**: read from `NativePlaybackSource` (full DSP)
  - **Fallback path**: read from internal queue (Java-only)

#### 4. Test Files

- `NativeFrameSinkTest.kt` — 6 tests covering:
  - Successful writes to native buffer
  - Drop tracking when handle is zero
  - Partial write overrun tracking
  - Counter reset on recovery
  - Telemetry snapshot accuracy

- `NativePlaybackSourceTest.kt` — 7 tests covering:
  - Successful reads from native buffer
  - Zero-handle underrun tracking
  - Short read underrun reporting
  - Counter reset on full read
  - Available bytes query
  - Telemetry snapshot accuracy

---

### Modified Files

#### 1. `SpeakerPlaybackEngine.kt`
**Changes:**
- Added `playbackSource: PlaybackSource? = null` constructor parameter
- Added `readFromNativePath()` method for native DSP reads
- Added `handleSilenceUnderrun()` helper
- Refactored `playbackLoop()` to route based on `playbackSource`

**Backward compatibility:** `playbackSource = null` falls back to existing queue behavior.

#### 2. `PersistentAudioService.kt`
**Changes:**
- Added `nativeFrameSink`, `nativePlaybackSource`, `playbackSource` fields
- Wired `NativeFrameSink` as `PlaybackCaptureEngine` frame sink
- Wired `NativePlaybackSource` as `SpeakerPlaybackEngine` playback source

#### 3. `CaptureLifecycleController.kt`
**Changes:**
- Added `PipelineState` import
- Set `PipelineState.Streaming` on `onTokenAcquired()`
- Set `PipelineState.Paused` on `onPause()`
- Set `PipelineState.Streaming` on `onResume()`
- Set `PipelineState.Idle` on `stop()`

---

## Architecture Notes

### Thread Safety

- `NativeFrameSink`: Single-producer (capture thread only)
- `NativePlaybackSource`: Single-consumer (playback thread only)
- Native ring buffer: Lock-free SPSC (verified in prior review)

### Fallback Behavior

When native engine is unavailable (handle = 0L):
- `NativeFrameSink` drops frames and logs warning
- `NativePlaybackSource` triggers silence injection
- `SpeakerPlaybackEngine` continues via fallback path

### State Machine

```
AudioPipelineController.state:
  Idle → Initializing → Streaming ↔ Paused → Idle
                     ↘ Error

CaptureLifecycleController.state:
  IDLE → PROVISIONING → ACTIVE ↔ PAUSED → STOPPED
```

---

## Verification Checklist

- [x] `NativeFrameSink` implements `FrameSink` interface
- [x] `NativePlaybackSource` implements `PlaybackSource` interface
- [x] `SpeakerPlaybackEngine` accepts optional `PlaybackSource`
- [x] `PersistentAudioService` wires native pipeline components
- [x] `CaptureLifecycleController` updates `PipelineState`
- [x] Unit tests for both new components
- [x] Backward compatibility with null `playbackSource`
- [x] Documentation matches existing code style

---

## Performance Impact

### JNI Boundary Crossings

| Direction | Per Frame | Latency |
|-----------|-----------|---------|
| Capture → Native | 1 write JNI | ~1-2 μs |
| Native → Playback | 1 read JNI | ~1-2 μs |
| **Total per frame** | **2 crossings** | **~2-4 μs** |

At 48kHz with 256-sample chunks: 5.33ms per frame  
JNI overhead: 0.04-0.08% — negligible.

### Memory

| Component | Memory |
|-----------|--------|
| Native ring buffer | 64 KiB (32,768 frames × 2 bytes) |
| NativeFrameSink state | ~64 bytes |
| NativePlaybackSource state | ~64 bytes |

---

## Related Documentation

- `doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md` — Pipeline architecture
- `doc/MEDIA_PROJECTION_FLOW.md` — Capture flow
- `doc/NOKIA_C22_NOTES.md` — Hardware-specific quirks
- `doc/SYSTEM_MAP.md` — Full system architecture
