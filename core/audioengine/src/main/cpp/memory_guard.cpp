#define __VYZORIX_LOG_TAG "VyzorixAudio.Memory"
#include "memory_guard.h"
#include "logger_engine.h"

#include <cstdlib>
#include <new>

namespace vyzorix {
namespace audio {

namespace {

// Single global counters instance. The audio engine has at most a handful
// of allocation sites (the ring buffer, the resampler scratch, the latency
// window) so a single instance is sufficient — no per-arena tracking.
MemoryGuardCounters g_counters{};

void bump_live(uint64_t delta) {
    const uint64_t new_live = g_counters.live_bytes.fetch_add(delta, std::memory_order_relaxed) + delta;
    uint64_t current_peak = g_counters.peak_live_bytes.load(std::memory_order_relaxed);
    while (new_live > current_peak &&
           !g_counters.peak_live_bytes.compare_exchange_weak(
               current_peak, new_live, std::memory_order_relaxed)) {
        // CAS loop until the peak is consistent.
    }
}

}  // namespace

MemoryGuardCounters* memory_guard_counters() {
    return &g_counters;
}

void* memory_guard_alloc(std::size_t bytes) {
    if (bytes == 0) {
        return nullptr;
    }
    void* p = std::malloc(bytes);
    if (p == nullptr) {
        VYZORIX_LOGE("memory_guard_alloc(%zu) failed", bytes);
        return nullptr;
    }
    g_counters.alloc_count.fetch_add(1, std::memory_order_relaxed);
    bump_live(static_cast<uint64_t>(bytes));
    return p;
}

void memory_guard_free(void* p, std::size_t bytes) {
    if (p == nullptr) {
        return;
    }
    std::free(p);
    g_counters.free_count.fetch_add(1, std::memory_order_relaxed);
    g_counters.live_bytes.fetch_sub(static_cast<uint64_t>(bytes), std::memory_order_relaxed);
}

}  // namespace audio
}  // namespace vyzorix
