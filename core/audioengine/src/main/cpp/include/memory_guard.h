// memory_guard.h — declarations for memory_guard.cpp.
//
// Layer 2 ships a *tracking* allocator (counts live + total allocations).
// Intercepting malloc/free at the bionic level is intentionally NOT done
// here — that pattern is racy on Android 13 and would block normal
// libc usage from peer libraries. The Kotlin side polls
// `memory_guard_live_byte_count()` from `MemoryPressureSignal` (Layer 6).

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace vyzorix {
namespace audio {

struct MemoryGuardCounters {
    std::atomic<uint64_t> alloc_count;
    std::atomic<uint64_t> free_count;
    std::atomic<uint64_t> live_bytes;
    std::atomic<uint64_t> peak_live_bytes;
};

MemoryGuardCounters* memory_guard_counters();

/// Tracked allocation. Behaves like `::operator new[]` but bumps the
/// counters. Returns nullptr on failure (does NOT throw).
void* memory_guard_alloc(std::size_t bytes);

/// Tracked free. Safe to call with nullptr (no-op).
void memory_guard_free(void* p, std::size_t bytes);

}  // namespace audio
}  // namespace vyzorix
