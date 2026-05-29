#define __VYZORIX_LOG_TAG "VyzorixAudio.Mixer"
#include "pcm_mixer.h"
#include "logger_engine.h"

#include <algorithm>
#include <cstdint>
#include <limits>

namespace vyzorix {
namespace audio {

namespace {

constexpr int32_t kSampleMin = std::numeric_limits<sample_t>::min();
constexpr int32_t kSampleMax = std::numeric_limits<sample_t>::max();

sample_t clamp_to_sample(int32_t v) {
    if (v > kSampleMax) return static_cast<sample_t>(kSampleMax);
    if (v < kSampleMin) return static_cast<sample_t>(kSampleMin);
    return static_cast<sample_t>(v);
}

}  // namespace

std::size_t mixer_sum_saturating(
    const sample_t* a,
    const sample_t* b,
    sample_t*       out,
    std::size_t     frames) {
    if (a == nullptr || b == nullptr || out == nullptr) {
        return 0;
    }
    std::size_t clipped = 0;
    for (std::size_t i = 0; i < frames; ++i) {
        const int32_t s = static_cast<int32_t>(a[i]) + static_cast<int32_t>(b[i]);
        if (s > kSampleMax || s < kSampleMin) {
            ++clipped;
        }
        out[i] = clamp_to_sample(s);
    }
    return clipped;
}

std::size_t mixer_apply_gain_q15(
    sample_t* samples,
    std::size_t frames,
    int32_t gain_q15) {
    if (samples == nullptr || frames == 0) {
        return 0;
    }
    const int32_t gain = std::clamp<int32_t>(gain_q15, 0, 32767);
    std::size_t clipped = 0;
    for (std::size_t i = 0; i < frames; ++i) {
        const int32_t scaled = (static_cast<int32_t>(samples[i]) * gain) >> 15;
        if (scaled > kSampleMax || scaled < kSampleMin) {
            ++clipped;
        }
        samples[i] = clamp_to_sample(scaled);
    }
    return clipped;
}

void mixer_downmix_stereo_to_mono(
    const sample_t* src,
    sample_t*       dst,
    std::size_t     stereo_frames) {
    if (src == nullptr || dst == nullptr) {
        return;
    }
    for (std::size_t i = 0; i < stereo_frames; ++i) {
        const int32_t l = static_cast<int32_t>(src[2 * i]);
        const int32_t r = static_cast<int32_t>(src[2 * i + 1]);
        dst[i] = static_cast<sample_t>((l + r) / 2);
    }
}

}  // namespace audio
}  // namespace vyzorix
