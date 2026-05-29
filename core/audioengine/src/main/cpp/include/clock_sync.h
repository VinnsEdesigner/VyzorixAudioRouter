// clock_sync.h — declarations for audio_clock_sync.cpp.
//
// Capture↔playback clock drift compensation: we slowly add or drop a single
// sample over a long window to nudge the playback consumer back into phase
// with the capture producer. This is the safe-mode equivalent of a
// fractional-rate resampler — the resampler in `playback_resampler.cpp`
// owns the steady-state rate conversion; this module only handles the small
// (~ppm) drift that accumulates over minutes of capture.

#pragma once

#include <cstddef>
#include <cstdint>

#include "audio_defs.h"

namespace vyzorix {
namespace audio {

struct ClockSyncState {
    /// Accumulated drift in samples (positive: playback is behind capture;
    /// we should drop a sample. negative: playback is ahead; we should
    /// duplicate one).
    int64_t drift_samples;
    /// Last drift correction direction; +1 / -1 / 0. Used to clamp the
    /// adjustment rate so we never apply more than one correction per call.
    int last_adjustment;
};

/// Initialise the state. All zero by default.
void clock_sync_init(ClockSyncState* state);

/// Observe a new capture/playback timestamp pair. Returns an "adjustment"
/// hint in samples (typically -1, 0, or +1) that the caller should apply
/// to the next playback chunk to nudge the timelines back together.
int clock_sync_observe(
    ClockSyncState* state,
    int64_t         capture_frame_index,
    int64_t         playback_frame_index);

}  // namespace audio
}  // namespace vyzorix
