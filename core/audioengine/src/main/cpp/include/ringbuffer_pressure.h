// ringbuffer_pressure.h — declarations for ringbuffer_pressure.cpp.
//
// Pressure observability for the capture ring buffer. Layer 3+
// (AudioPipelineController) reads the basis-points value every audio
// chunk and asks the backpressure controller whether to drop a frame.
//
// Per VyzorixAudioRouter_RepoTree.md §core/audioengine/cpp/include this
// header is included by capture_ring_buffer.cpp and the JNI bridge
// pressure query.

#pragma once

#include "ring_buffer.h"

#include <cstdint>

namespace vyzorix {
namespace audio {

/// Pressure ratio in basis points (0 = empty, 10000 = full).
///
/// Used by Layer 3+'s `PipelineBackpressureController` to decide whether
/// to apply backpressure or grow the chunk size. Returns 0 if `rb` is
/// `nullptr` or has zero capacity (safe-default behaviour for the
/// fallback path).
int32_t ring_buffer_pressure_basis_points(const CaptureRingBuffer* rb);

/// True if the ring buffer is at or above the 80% high-water mark.
///
/// Matches the canonical `>80% capacity → discard` threshold from
/// VyzorixAudioRouter_RepoTree.md. The Kotlin side
/// (`PipelineBackpressureController`) implements hysteresis on top of this
/// raw signal so the audio thread doesn't oscillate at the boundary.
bool ring_buffer_pressure_should_discard(const CaptureRingBuffer* rb);

}  // namespace audio
}  // namespace vyzorix
