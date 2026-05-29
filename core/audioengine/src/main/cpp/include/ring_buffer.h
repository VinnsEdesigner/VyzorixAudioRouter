// ring_buffer.h — lock-free single-producer / single-consumer ring buffer
// declarations.
//
// The buffer carries `int16_t` samples (mono S16LE) by default. Frame-vs-byte
// arithmetic lives at the caller side; this layer only knows samples and
// frames-as-sample-count (with frame == sample for mono).
//
// Memory model: the producer writes `samples[]` then publishes via a
// `memory_order_release` store on `write_index`. The consumer loads
// `write_index` with `memory_order_acquire`, reads `samples[]`, then publishes
// the new read position with `memory_order_release`. This pairing is
// sufficient for the SPSC case; multi-producer or multi-consumer use is NOT
// supported.

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "audio_defs.h"

namespace vyzorix {
namespace audio {

struct CaptureRingBuffer {
    sample_t* samples;
    std::size_t capacity_frames;  // power of two
    std::size_t mask;             // capacity_frames - 1
    std::atomic<std::size_t> write_index;
    std::atomic<std::size_t> read_index;
    std::atomic<uint64_t> underrun_count;
    std::atomic<uint64_t> overrun_count;
};

/// Allocate a ring buffer with the supplied power-of-two frame capacity.
/// Returns nullptr on allocation failure or on a non-power-of-two argument.
CaptureRingBuffer* ring_buffer_create(std::size_t capacity_frames);

/// Free the ring buffer + its backing storage. Safe to call with nullptr.
void ring_buffer_destroy(CaptureRingBuffer* rb);

/// Number of frames currently occupied. Wait-free; suitable for monitoring.
std::size_t ring_buffer_available_read(const CaptureRingBuffer* rb);

/// Free space in frames. Wait-free.
std::size_t ring_buffer_available_write(const CaptureRingBuffer* rb);

/// Push `frames` from `src` into the buffer. Returns the number of frames
/// actually written (may be less than `frames` if the buffer is near full;
/// the overflow counter is bumped in that case).
std::size_t ring_buffer_write(CaptureRingBuffer* rb, const sample_t* src, std::size_t frames);

/// Pull `frames` into `dst`. Returns the number of frames actually read; on
/// short reads the underrun counter is bumped and the caller may invoke the
/// underrun guard to fill the remainder with comfort noise.
std::size_t ring_buffer_read(CaptureRingBuffer* rb, sample_t* dst, std::size_t frames);

/// Drain the buffer to empty. Returns the number of frames discarded.
std::size_t ring_buffer_reset(CaptureRingBuffer* rb);

}  // namespace audio
}  // namespace vyzorix
