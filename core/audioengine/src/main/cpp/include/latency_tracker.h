// latency_tracker.h — declarations for latency_tracker.cpp.
//
// Wraps `clock_gettime(CLOCK_MONOTONIC, ...)` into a tiny rolling-window
// estimator. `CLOCK_BOOTTIME` is NOT used here — we want a clock that ticks
// at the same rate as audio frame counters; suspend-aware time would
// introduce spurious latency spikes on devices that suspend during capture.

#pragma once

#include <cstddef>
#include <cstdint>

#include "audio_defs.h"

namespace vyzorix {
namespace audio {

struct LatencyTrackerState {
    /// Capacity of the rolling window in samples. Stored once on init.
    std::size_t window_capacity;
    /// Current number of samples in the window (saturates at window_capacity).
    std::size_t window_size;
    /// Insertion index in the circular buffer.
    std::size_t cursor;
    /// Rolling samples in nanoseconds.
    int64_t* samples_ns;
    /// Sum of `samples_ns` for O(1) mean computation.
    int64_t sum_ns;
};

/// Initialise tracker. Returns false on allocation failure.
bool latency_tracker_init(LatencyTrackerState* state, std::size_t window_capacity);

/// Free the rolling-window buffer. Safe to call with a zero-initialised
/// state (no-op).
void latency_tracker_destroy(LatencyTrackerState* state);

/// Record a sample. `latency_ns` is the measured capture→playback delay.
void latency_tracker_record(LatencyTrackerState* state, int64_t latency_ns);

/// Mean of the rolling window in nanoseconds, or 0 if the window is empty.
int64_t latency_tracker_mean_ns(const LatencyTrackerState* state);

/// Returns the current monotonic timestamp in nanoseconds. Useful for the
/// caller to bracket calls into the engine.
int64_t latency_tracker_now_ns();

}  // namespace audio
}  // namespace vyzorix
