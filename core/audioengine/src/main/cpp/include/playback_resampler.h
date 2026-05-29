// playback_resampler.h — declarations for playback_resampler.cpp.
//
// The resampler is a small fractional-step linear interpolator. Polyphase /
// sinc would give better quality but Layer 2 is about establishing the
// surface; quality tuning is a Layer 3+ concern when we have on-device
// listening tests to drive it.

#pragma once

#include <cstddef>
#include <cstdint>

#include "audio_defs.h"

namespace vyzorix {
namespace audio {

struct PlaybackResamplerState {
    uint32_t src_rate_hz;
    uint32_t dst_rate_hz;
    /// Fractional accumulator in Q32 (units of `1 / (src_rate_hz << 32)`).
    uint64_t phase;
    /// Last input sample retained across calls so we can interpolate over
    /// the chunk boundary without a click.
    sample_t prev_sample;
};

/// Initialise an idle resampler state for the given rate pair. Both rates
/// must be non-zero; `dst_rate_hz == src_rate_hz` is allowed and is a fast
/// passthrough.
void resampler_init(PlaybackResamplerState* state, uint32_t src_rate_hz, uint32_t dst_rate_hz);

/// Reset the phase and the retained sample without changing rates. Call this
/// at the start of a fresh capture session to avoid bleeding the previous
/// session's tail into the new one.
void resampler_reset(PlaybackResamplerState* state);

/// Run the resampler. Returns the number of samples written to `dst`. The
/// caller sizes `dst` so it has at least
/// `ceil(in_samples * dst_rate / src_rate) + 1` headroom.
std::size_t resampler_process(
    PlaybackResamplerState* state,
    const sample_t*         src,
    std::size_t             in_samples,
    sample_t*               dst,
    std::size_t             dst_capacity);

}  // namespace audio
}  // namespace vyzorix
