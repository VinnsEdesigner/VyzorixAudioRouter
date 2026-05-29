#define __VYZORIX_LOG_TAG "VyzorixAudio.Resampler"
#include "playback_resampler.h"
#include "logger_engine.h"

#include <algorithm>
#include <cstdint>
#include <cstring>

namespace vyzorix {
namespace audio {

namespace {

sample_t lerp_q32(sample_t a, sample_t b, uint32_t frac_q32) {
    // a + (b - a) * frac, with frac in Q32.
    const int64_t diff = static_cast<int64_t>(b) - static_cast<int64_t>(a);
    const int64_t delta = (diff * static_cast<int64_t>(frac_q32)) >> 32;
    return static_cast<sample_t>(static_cast<int64_t>(a) + delta);
}

}  // namespace

void resampler_init(PlaybackResamplerState* state, uint32_t src_rate_hz, uint32_t dst_rate_hz) {
    if (state == nullptr) return;
    state->src_rate_hz = src_rate_hz == 0 ? kCaptureSampleRateHz : src_rate_hz;
    state->dst_rate_hz = dst_rate_hz == 0 ? kPlaybackSampleRateHz : dst_rate_hz;
    state->phase = 0;
    state->prev_sample = 0;
}

void resampler_reset(PlaybackResamplerState* state) {
    if (state == nullptr) return;
    state->phase = 0;
    state->prev_sample = 0;
}

std::size_t resampler_process(
    PlaybackResamplerState* state,
    const sample_t*         src,
    std::size_t             in_samples,
    sample_t*               dst,
    std::size_t             dst_capacity) {
    if (state == nullptr || src == nullptr || dst == nullptr || in_samples == 0 || dst_capacity == 0) {
        return 0;
    }

    if (state->src_rate_hz == state->dst_rate_hz) {
        const std::size_t copy = std::min(in_samples, dst_capacity);
        std::memcpy(dst, src, copy * sizeof(sample_t));
        if (copy > 0) {
            state->prev_sample = src[copy - 1];
        }
        return copy;
    }

    // step = src_rate / dst_rate, in Q32.
    const uint64_t step_q32 =
        (static_cast<uint64_t>(state->src_rate_hz) << 32) / state->dst_rate_hz;

    std::size_t out_count = 0;
    uint64_t phase = state->phase;

    while (out_count < dst_capacity) {
        const uint64_t int_idx = phase >> 32;
        const uint32_t frac    = static_cast<uint32_t>(phase & 0xFFFFFFFFu);

        if (int_idx >= in_samples) {
            break;
        }

        const sample_t a = (int_idx == 0) ? state->prev_sample : src[int_idx - 1];
        const sample_t b = src[int_idx];
        dst[out_count++] = lerp_q32(a, b, frac);
        phase += step_q32;
    }

    // Advance phase by the consumed input samples so subsequent calls
    // pick up where we left off.
    const uint64_t consumed_idx = phase >> 32;
    if (consumed_idx > 0 && consumed_idx <= in_samples) {
        state->prev_sample = src[consumed_idx - 1];
    } else if (in_samples > 0) {
        state->prev_sample = src[in_samples - 1];
    }
    // Carry only the fractional part across calls — integer part was the
    // index into `src` and is now consumed.
    state->phase = phase & 0xFFFFFFFFu;
    return out_count;
}

}  // namespace audio
}  // namespace vyzorix
