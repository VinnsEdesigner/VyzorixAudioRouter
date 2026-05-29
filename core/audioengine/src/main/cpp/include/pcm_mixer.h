// pcm_mixer.h — declarations for pcm_mixer.cpp.

#pragma once

#include <cstddef>
#include <cstdint>

#include "audio_defs.h"

namespace vyzorix {
namespace audio {

/// Sum `a[]` and `b[]` into `out[]`, clamping each sample at the int16 range.
/// Returns the number of samples clipped (caller-side telemetry).
std::size_t mixer_sum_saturating(
    const sample_t* a,
    const sample_t* b,
    sample_t*       out,
    std::size_t     frames);

/// Apply a `gain` (Q15 fixed-point: gain == 32767 -> 1.0) in place. Clamps.
/// `gain_q15` must be in [0, 32767]; out-of-range values are clipped at the
/// boundary rather than wrapped (defensive — the upstream Kotlin clamps too).
std::size_t mixer_apply_gain_q15(
    sample_t* samples,
    std::size_t frames,
    int32_t gain_q15);

/// Stereo → mono downmix using a simple average. `stereo_frames` is the
/// number of *interleaved L/R pairs* in `src`; `dst` receives `stereo_frames`
/// mono samples.
void mixer_downmix_stereo_to_mono(
    const sample_t* src,
    sample_t*       dst,
    std::size_t     stereo_frames);

}  // namespace audio
}  // namespace vyzorix
