#define __VYZORIX_LOG_TAG "VyzorixAudio.Underrun"
#include "underrun_guard.h"
#include "logger_engine.h"

namespace vyzorix {
namespace audio {

namespace {

constexpr uint32_t kDefaultSeed = 0xACE1u;

uint32_t lfsr_next(uint32_t state) {
    // 16-bit Galois LFSR; period = 65535. Sufficient for low-amplitude
    // comfort noise — we are not trying to be cryptographic.
    const uint32_t lsb = state & 1u;
    state >>= 1;
    if (lsb) {
        state ^= 0xB400u;
    }
    return state == 0 ? kDefaultSeed : state;
}

}  // namespace

void underrun_guard_init(UnderrunGuardState* state, uint32_t seed) {
    if (state == nullptr) return;
    state->lfsr = (seed == 0) ? kDefaultSeed : seed;
    state->last_was_synthetic.store(false, std::memory_order_relaxed);
    state->synthetic_samples_injected.store(0, std::memory_order_relaxed);
}

void underrun_guard_fill_comfort_noise(
    UnderrunGuardState* state,
    sample_t*           dst,
    std::size_t         frames) {
    if (state == nullptr || dst == nullptr || frames == 0) {
        return;
    }
    uint32_t lfsr = state->lfsr;
    for (std::size_t i = 0; i < frames; ++i) {
        lfsr = lfsr_next(lfsr);
        const int32_t centred = static_cast<int32_t>(lfsr & 0xFF) - 128;
        const int32_t scaled  = (centred * kComfortNoisePeak) / 128;
        dst[i] = static_cast<sample_t>(scaled);
    }
    state->lfsr = lfsr;
    state->last_was_synthetic.store(true, std::memory_order_relaxed);
    state->synthetic_samples_injected.fetch_add(frames, std::memory_order_relaxed);
}

void underrun_guard_reset_counters(UnderrunGuardState* state) {
    if (state == nullptr) return;
    state->last_was_synthetic.store(false, std::memory_order_relaxed);
    state->synthetic_samples_injected.store(0, std::memory_order_relaxed);
}

}  // namespace audio
}  // namespace vyzorix
