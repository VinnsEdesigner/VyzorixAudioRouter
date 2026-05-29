#define __VYZORIX_LOG_TAG "VyzorixAudio.Watchdog"
#include "watchdog_ping.h"
#include "audio_latency_profiler.h"

namespace vyzorix {
namespace audio {

void watchdog_ping_init(WatchdogPingState* state) {
    if (state == nullptr) return;
    state->last_ping_ns.store(profiler_now_ns(), std::memory_order_relaxed);
    state->ping_count.store(0, std::memory_order_relaxed);
}

void watchdog_ping_record(WatchdogPingState* state) {
    if (state == nullptr) return;
    state->last_ping_ns.store(profiler_now_ns(), std::memory_order_relaxed);
    state->ping_count.fetch_add(1, std::memory_order_relaxed);
}

int64_t watchdog_ping_elapsed_ns(const WatchdogPingState* state) {
    if (state == nullptr) return 0;
    const int64_t now = profiler_now_ns();
    const int64_t last = state->last_ping_ns.load(std::memory_order_relaxed);
    return now - last;
}

}  // namespace audio
}  // namespace vyzorix
