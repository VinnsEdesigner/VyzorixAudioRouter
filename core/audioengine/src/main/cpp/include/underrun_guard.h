// underrun_guard.h — declarations for underrun_guard.cpp.

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "audio_defs.h"

namespace vyzorix {
namespace audio {

struct UnderrunGuardState {
    /// Comfort-noise LFSR seed. Persisted across calls so the noise floor
    /// never repeats identically (avoids audible periodic artefacts when
    /// the guard fires repeatedly during sustained underrun).
    uint32_t lfsr;
    /// Last write was a comfort-noise fill. Used by Layer 5 dashboards to
    /// show the engine state.
    std::atomic<bool> last_was_synthetic;
    /// Running counter of comfort-noise samples injected since reset.
    std::atomic<uint64_t> synthetic_samples_injected;
};

/// Initialise the guard. `seed` may be zero; an internal default is used in
/// that case so the LFSR never gets stuck at 0.
void underrun_guard_init(UnderrunGuardState* state, uint32_t seed);

/// Fill `dst[0..frames]` with comfort noise. The peak is bounded by
/// `kComfortNoisePeak` from `audio_defs.h`. Updates the synthetic-sample
/// counter and the `last_was_synthetic` flag.
void underrun_guard_fill_comfort_noise(
    UnderrunGuardState* state,
    sample_t*           dst,
    std::size_t         frames);

/// Reset the synthetic-sample counter; leaves the LFSR untouched so the
/// next fill keeps decorrelated.
void underrun_guard_reset_counters(UnderrunGuardState* state);

}  // namespace audio
}  // namespace vyzorix
