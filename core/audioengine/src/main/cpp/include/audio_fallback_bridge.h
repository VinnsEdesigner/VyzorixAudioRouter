// audio_fallback_bridge.h — declarations for audio_fallback_bridge.cpp.
//
// Fallback read path consumed by the Java-only audio pipeline when the
// steady-state Layer 3+ pipeline can't size its AudioTrack writes to the
// instantaneous ring-buffer fill (e.g. on Java-thread starvation).
//
// Per VyzorixAudioRouter_RepoTree.md §core/audioengine/cpp/include this
// header is included by NativeAudioBridge.kt's JNI mapping (via
// jni_audio_bridge.cpp) and the NativeSafetyController fallback decision
// surface.

#pragma once

#include "audio_defs.h"
#include "ring_buffer.h"
#include "underrun_guard.h"

#include <cstddef>

namespace vyzorix {
namespace audio {

/// Drain `frames_requested` frames from the capture ring buffer into
/// `dst`, filling any short read with comfort noise from `guard`.
///
/// Always writes exactly `frames_requested` mono samples to `dst` when
/// all input pointers are non-null. If the ring buffer underruns the
/// remainder is filled with low-amplitude Galois-LFSR comfort noise so
/// the speaker never receives silence (silence on the speaker is the
/// failure signature that motivated this whole project — see
/// doc/AUDIO_PIPELINE.md §3).
///
/// Returns the number of frames written. On nullptr inputs or zero
/// `frames_requested` returns 0 without writing.
std::size_t fallback_read_with_comfort_noise(
    CaptureRingBuffer*  rb,
    UnderrunGuardState* guard,
    sample_t*           dst,
    std::size_t         frames_requested);

}  // namespace audio
}  // namespace vyzorix
