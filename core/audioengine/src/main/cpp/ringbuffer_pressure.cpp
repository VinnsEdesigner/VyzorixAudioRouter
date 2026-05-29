#define __VYZORIX_LOG_TAG "VyzorixAudio.Pressure"
#include "ringbuffer_pressure.h"
#include "logger_engine.h"

#include <cstddef>

namespace vyzorix {
namespace audio {

int32_t ring_buffer_pressure_basis_points(const CaptureRingBuffer* rb) {
    if (rb == nullptr || rb->capacity_frames == 0) {
        return 0;
    }
    const std::size_t avail = ring_buffer_available_read(rb);
    return static_cast<int32_t>((avail * 10000) / rb->capacity_frames);
}

bool ring_buffer_pressure_should_discard(const CaptureRingBuffer* rb) {
    return ring_buffer_pressure_basis_points(rb) >= 8000;
}

}  // namespace audio
}  // namespace vyzorix
