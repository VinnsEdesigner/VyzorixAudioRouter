// audio_defs.h — pure definitions header (sample rates, buffer sizes, enums).
// Header-only by design — no .cpp counterpart per `VyzorixAudioRouter_RepoTree.md`.
//
// Every constant here is intentionally a compile-time literal so the
// optimizer can fold them into the audio fast path; do not promote any of
// these to `extern const` without a measurement.

#pragma once

#include <cstddef>
#include <cstdint>

namespace vyzorix {
namespace audio {

// ---- Sample format ---------------------------------------------------------

/// 16-bit signed little-endian PCM is the canonical on-the-wire format
/// produced by `MediaProjection.AudioPlaybackCapture` on the Nokia C22.
using sample_t = int16_t;

/// Hardware bytes-per-frame for the daemon's working format (mono S16LE).
constexpr std::size_t kBytesPerSample = sizeof(sample_t);

/// Default capture/playback channel count. The Nokia C22's `setSpeakerphoneOn`
/// path is mono; Layer 2 leaves multi-channel support to Layer 3+.
constexpr std::size_t kDefaultChannelCount = 1;

// ---- Sample rates ----------------------------------------------------------

/// Native capture rate from the MediaProjection capture node. Empirically
/// the Nokia C22 reports 48 kHz here, but the upstream policy manager may
/// resample to 44.1 kHz under load — `playback_resampler.cpp` handles the
/// mismatch.
constexpr uint32_t kCaptureSampleRateHz = 48000;

/// Playback rate fed into AudioTrack. Matches capture in the steady state;
/// any drift is corrected by `audio_clock_sync.cpp`.
constexpr uint32_t kPlaybackSampleRateHz = 48000;

// ---- Buffer sizing ---------------------------------------------------------

/// Capture chunk size in frames at SCHED_FIFO real-time priority.
/// On the Nokia C22 the engine often falls back to SCHED_OTHER (see
/// `NOKIA_C22_NOTES.md` §2) — when that happens, `LatencyOptimizer.kt`
/// in Layer 3+ may grow the chunk to `kCaptureChunkFramesFallback`.
constexpr std::size_t kCaptureChunkFrames = 256;
constexpr std::size_t kCaptureChunkFramesFallback = 512;

/// Ring buffer capacity. ~512ms of headroom at 48 kHz mono S16LE
/// (24576 frames × 2 bytes = 48 KiB). Power-of-two so that the
/// modulo-by-mask trick works in `capture_ring_buffer.cpp`.
constexpr std::size_t kRingBufferFrames = 32768;
constexpr std::size_t kRingBufferBytes  = kRingBufferFrames * kBytesPerSample;

static_assert(
    (kRingBufferFrames & (kRingBufferFrames - 1)) == 0,
    "kRingBufferFrames must be a power of two for masked-modulo arithmetic");

// ---- Underrun guard --------------------------------------------------------

/// Comfort-noise floor injected when the consumer outpaces the producer.
/// Matches the lowest perceptible level above silence for typical phone
/// speakers — see DOC_3 §3.4 for the choice rationale.
constexpr sample_t kComfortNoisePeak = 4;

// ---- Result codes ----------------------------------------------------------

/// Generic engine status returned across the JNI boundary. Values are stable
/// and surface in `NativeAudioBridge.kt` via a Kotlin sealed wrapper; do NOT
/// renumber.
enum class EngineResult : int32_t {
    Ok                       = 0,
    InvalidHandle            = 1,
    OutOfMemory              = 2,
    InvalidArgument          = 3,
    BufferFull               = 4,
    BufferEmpty              = 5,
    UnderrunInjected         = 6,
    NotImplemented           = 7,
    ResamplerStateError      = 8,
    ClockSyncOutOfRange      = 9,
};

/// Scheduling-policy outcome surfaced by `thread_priority_guard.cpp`.
/// Mirrored on the Kotlin side via `NativeAudioBridge.PriorityResult`.
enum class PriorityResult : int32_t {
    RealTime                 = 0, // SCHED_FIFO confirmed via read-back.
    BestEffort               = 1, // SCHED_OTHER active (Unisoc fallback or non-privileged).
    SyscallFailed            = 2, // sched_setscheduler returned non-zero.
    SilentFallback           = 3, // Syscall returned 0 but read-back showed SCHED_OTHER.
};

}  // namespace audio
}  // namespace vyzorix
