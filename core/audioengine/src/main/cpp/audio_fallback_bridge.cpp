#define __VYZORIX_LOG_TAG "VyzorixAudio.Fallback"
#include "audio_fallback_bridge.h"
#include "playback_resampler.h"
#include "logger_engine.h"

#include <algorithm>
#include <cstring>

namespace vyzorix {
namespace audio {

std::size_t fallback_read_with_comfort_noise(
    CaptureRingBuffer*  rb,
    UnderrunGuardState* guard,
    sample_t*           dst,
    std::size_t         frames_requested) {
    if (rb == nullptr || guard == nullptr || dst == nullptr || frames_requested == 0) {
        return 0;
    }
    const std::size_t got = ring_buffer_read(rb, dst, frames_requested);
    if (got < frames_requested) {
        const std::size_t missing = frames_requested - got;
        underrun_guard_fill_comfort_noise(guard, dst + got, missing);
    }
    return frames_requested;
}

}  // namespace audio
}  // namespace vyzorix
