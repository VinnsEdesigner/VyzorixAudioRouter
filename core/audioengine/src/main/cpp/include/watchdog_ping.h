// watchdog_ping.h — watchdog callback declarations for watchdog_ping.cpp.

#pragma once

#include <atomic>
#include <cstdint>

namespace vyzorix {
namespace audio {

struct WatchdogPingState {
    /// Monotonic ns timestamp of the most recent ping; updated on
    /// `watchdog_ping_record`.
    std::atomic<int64_t> last_ping_ns;
    /// Total number of pings observed since reset.
    std::atomic<uint64_t> ping_count;
};

void watchdog_ping_init(WatchdogPingState* state);

/// Record a ping; updates the timestamp + counter.
void watchdog_ping_record(WatchdogPingState* state);

/// Returns the ns elapsed since the last ping. Wraps a single
/// `clock_gettime(CLOCK_MONOTONIC)` call.
int64_t watchdog_ping_elapsed_ns(const WatchdogPingState* state);

}  // namespace audio
}  // namespace vyzorix
