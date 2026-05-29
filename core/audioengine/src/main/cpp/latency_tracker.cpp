#define __VYZORIX_LOG_TAG "VyzorixAudio.Latency"
#include "latency_tracker.h"
#include "audio_latency_profiler.h"
#include "memory_guard.h"
#include "logger_engine.h"

#include <cstring>

namespace vyzorix {
namespace audio {

bool latency_tracker_init(LatencyTrackerState* state, std::size_t window_capacity) {
    if (state == nullptr || window_capacity == 0) {
        return false;
    }
    const std::size_t bytes = window_capacity * sizeof(int64_t);
    state->samples_ns = static_cast<int64_t*>(memory_guard_alloc(bytes));
    if (state->samples_ns == nullptr) {
        return false;
    }
    std::memset(state->samples_ns, 0, bytes);
    state->window_capacity = window_capacity;
    state->window_size = 0;
    state->cursor = 0;
    state->sum_ns = 0;
    return true;
}

void latency_tracker_destroy(LatencyTrackerState* state) {
    if (state == nullptr || state->samples_ns == nullptr) {
        return;
    }
    memory_guard_free(state->samples_ns, state->window_capacity * sizeof(int64_t));
    state->samples_ns = nullptr;
    state->window_capacity = 0;
    state->window_size = 0;
    state->cursor = 0;
    state->sum_ns = 0;
}

void latency_tracker_record(LatencyTrackerState* state, int64_t latency_ns) {
    if (state == nullptr || state->samples_ns == nullptr || state->window_capacity == 0) {
        return;
    }
    const int64_t old = state->samples_ns[state->cursor];
    state->samples_ns[state->cursor] = latency_ns;
    state->cursor = (state->cursor + 1) % state->window_capacity;
    if (state->window_size < state->window_capacity) {
        ++state->window_size;
        state->sum_ns += latency_ns;
    } else {
        state->sum_ns += latency_ns - old;
    }
}

int64_t latency_tracker_mean_ns(const LatencyTrackerState* state) {
    if (state == nullptr || state->window_size == 0) {
        return 0;
    }
    return state->sum_ns / static_cast<int64_t>(state->window_size);
}

int64_t latency_tracker_now_ns() {
    return profiler_now_ns();
}

}  // namespace audio
}  // namespace vyzorix
