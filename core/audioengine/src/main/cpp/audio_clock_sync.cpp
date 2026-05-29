#define __VYZORIX_LOG_TAG "VyzorixAudio.ClockSync"
#include "clock_sync.h"
#include "logger_engine.h"

namespace vyzorix {
namespace audio {

namespace {

// Number of accumulated drift samples that triggers a single +/-1 sample
// adjustment. Empirically — at 48 kHz a 100ppm drift accumulates ~4.8
// samples/second; rate-limiting corrections to one every ~50 samples gives
// the resampler plenty of slack and stays inaudible.
constexpr int64_t kDriftCorrectionThreshold = 48;

}  // namespace

void clock_sync_init(ClockSyncState* state) {
    if (state == nullptr) return;
    state->drift_samples = 0;
    state->last_adjustment = 0;
}

int clock_sync_observe(
    ClockSyncState* state,
    int64_t         capture_frame_index,
    int64_t         playback_frame_index) {
    if (state == nullptr) {
        return 0;
    }
    state->drift_samples = capture_frame_index - playback_frame_index;
    if (state->drift_samples > kDriftCorrectionThreshold) {
        state->last_adjustment = 1;
        return 1;
    }
    if (state->drift_samples < -kDriftCorrectionThreshold) {
        state->last_adjustment = -1;
        return -1;
    }
    state->last_adjustment = 0;
    return 0;
}

}  // namespace audio
}  // namespace vyzorix
