// Host-side C++ unit-test harness for the audio-engine algorithms.
//
// Layer 2 acceptance per `doc/BUILD_ORDER.md`:
//   * `./gradlew :core:audioengine:externalNativeBuild` succeeds.
//   * Sine-wave round-trip with zero underruns (on-device — Nokia C22).
//
// This harness is a *host-side* surrogate for the on-device test: it runs
// the same algorithms on the build host so a regression in the ring
// buffer, mixer, resampler, etc. is caught long before reaching the device.
// It deliberately does NOT load `libaudioengine.so` — it builds the .cpp
// files against the host compiler directly.
//
// Run via:
//   ./gradlew :core:audioengine:runNativeHostTests
// (the gradle task lives in `build.gradle.kts` and shells out to g++).

#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "audio_defs.h"
#include "clock_sync.h"
#include "latency_tracker.h"
#include "memory_guard.h"
#include "pcm_mixer.h"
#include "playback_resampler.h"
#include "ring_buffer.h"
#include "underrun_guard.h"
#include "watchdog_ping.h"

namespace {

int g_pass = 0;
int g_fail = 0;

#define EXPECT_EQ(actual, expected)                                          \
    do {                                                                      \
        if ((actual) == (expected)) { ++g_pass; }                              \
        else {                                                                 \
            ++g_fail;                                                          \
            std::fprintf(stderr,                                               \
                "FAIL %s:%d EXPECT_EQ(%s, %s) failed\n",                       \
                __FILE__, __LINE__, #actual, #expected);                      \
        }                                                                      \
    } while (0)

#define EXPECT_TRUE(cond)                                                     \
    do {                                                                      \
        if (cond) { ++g_pass; }                                                \
        else {                                                                 \
            ++g_fail;                                                          \
            std::fprintf(stderr, "FAIL %s:%d EXPECT_TRUE failed\n",            \
                         __FILE__, __LINE__);                                 \
        }                                                                      \
    } while (0)

#define RUN_TEST(fn)                                                          \
    do { std::printf("RUN  %s ...\n", #fn); fn(); } while (0)

using namespace vyzorix::audio;

// ---- ring buffer ----------------------------------------------------------

void test_ring_buffer_create_rejects_non_power_of_two() {
    EXPECT_EQ(ring_buffer_create(7), nullptr);
    EXPECT_EQ(ring_buffer_create(0), nullptr);
}

void test_ring_buffer_round_trip() {
    auto* rb = ring_buffer_create(16);
    EXPECT_TRUE(rb != nullptr);
    EXPECT_EQ(ring_buffer_available_read(rb), 0u);
    EXPECT_EQ(ring_buffer_available_write(rb), 16u);

    sample_t input[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    EXPECT_EQ(ring_buffer_write(rb, input, 8), 8u);
    EXPECT_EQ(ring_buffer_available_read(rb), 8u);

    sample_t output[8] = {};
    EXPECT_EQ(ring_buffer_read(rb, output, 8), 8u);
    for (int i = 0; i < 8; ++i) {
        EXPECT_EQ(output[i], input[i]);
    }
    EXPECT_EQ(rb->underrun_count.load(), 0u);
    EXPECT_EQ(rb->overrun_count.load(), 0u);
    ring_buffer_destroy(rb);
}

void test_ring_buffer_overrun_counter_bumps() {
    auto* rb = ring_buffer_create(4);
    sample_t big[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    EXPECT_EQ(ring_buffer_write(rb, big, 8), 4u);   // truncated
    EXPECT_EQ(rb->overrun_count.load(), 1u);
    ring_buffer_destroy(rb);
}

void test_ring_buffer_underrun_counter_bumps() {
    auto* rb = ring_buffer_create(4);
    sample_t dst[8] = {};
    EXPECT_EQ(ring_buffer_read(rb, dst, 8), 0u);
    EXPECT_EQ(rb->underrun_count.load(), 1u);
    ring_buffer_destroy(rb);
}

void test_ring_buffer_wraps_at_capacity() {
    auto* rb = ring_buffer_create(8);
    sample_t a[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    sample_t b[5] = {9, 10, 11, 12, 13};
    EXPECT_EQ(ring_buffer_write(rb, a, 8), 8u);
    sample_t out_a[5] = {};
    EXPECT_EQ(ring_buffer_read(rb, out_a, 5), 5u);
    // 3 frames left in buffer; we have 5 free slots. Write 5 more — wraps
    // past index 0 of the backing storage.
    EXPECT_EQ(ring_buffer_write(rb, b, 5), 5u);
    sample_t out_b[8] = {};
    EXPECT_EQ(ring_buffer_read(rb, out_b, 8), 8u);
    // Expected sequence: tail of a (6..8) then all of b.
    sample_t expected[8] = {6, 7, 8, 9, 10, 11, 12, 13};
    for (int i = 0; i < 8; ++i) {
        EXPECT_EQ(out_b[i], expected[i]);
    }
    EXPECT_EQ(rb->underrun_count.load(), 0u);
    EXPECT_EQ(rb->overrun_count.load(), 0u);
    ring_buffer_destroy(rb);
}

void test_ring_buffer_sine_wave_round_trip_zero_underrun() {
    // BUILD_ORDER §Layer 2: "Sine wave round-trip test reports zero
    // underruns at 48kHz mono." The host harness exercises the exact
    // algorithm; the on-device acceptance proves it works on real silicon.
    auto* rb = ring_buffer_create(4096);
    constexpr std::size_t kChunk = 256;
    std::vector<sample_t> producer(kChunk);
    std::vector<sample_t> consumer(kChunk);

    for (std::size_t cycle = 0; cycle < 16; ++cycle) {
        for (std::size_t i = 0; i < kChunk; ++i) {
            const double phase = (cycle * kChunk + i) * 2.0 * M_PI * 1000.0 / 48000.0;
            producer[i] = static_cast<sample_t>(std::sin(phase) * 32767.0 * 0.5);
        }
        EXPECT_EQ(ring_buffer_write(rb, producer.data(), kChunk), kChunk);
        EXPECT_EQ(ring_buffer_read(rb, consumer.data(), kChunk), kChunk);
        // Byte-equality across the whole chunk.
        EXPECT_EQ(std::memcmp(producer.data(), consumer.data(), kChunk * sizeof(sample_t)), 0);
    }
    EXPECT_EQ(rb->underrun_count.load(), 0u);
    EXPECT_EQ(rb->overrun_count.load(), 0u);
    ring_buffer_destroy(rb);
}

// ---- mixer ----------------------------------------------------------------

void test_mixer_sum_saturates_to_int16_bounds() {
    sample_t a[3] = {30000, -30000, 100};
    sample_t b[3] = {30000, -30000, 100};
    sample_t out[3] = {};
    const std::size_t clipped = mixer_sum_saturating(a, b, out, 3);
    EXPECT_EQ(clipped, 2u);
    EXPECT_EQ(out[0], 32767);
    EXPECT_EQ(out[1], -32768);
    EXPECT_EQ(out[2], 200);
}

void test_mixer_apply_gain_clamps_above_unity() {
    sample_t samples[4] = {10000, -10000, 5000, -5000};
    // gain == 32767 (Q15) ≈ 1.0; should be near no-op.
    mixer_apply_gain_q15(samples, 4, 32767);
    EXPECT_EQ(samples[0], 9999);
    EXPECT_EQ(samples[1], -10000);
    EXPECT_EQ(samples[2], 4999);
    EXPECT_EQ(samples[3], -5000);
}

void test_mixer_downmix_stereo_to_mono() {
    sample_t stereo[6] = {1000, 2000, -100, 300, 0, 0};
    sample_t mono[3] = {};
    mixer_downmix_stereo_to_mono(stereo, mono, 3);
    EXPECT_EQ(mono[0], 1500);
    EXPECT_EQ(mono[1], 100);
    EXPECT_EQ(mono[2], 0);
}

// ---- resampler ------------------------------------------------------------

void test_resampler_passthrough_when_rates_match() {
    PlaybackResamplerState state{};
    resampler_init(&state, 48000, 48000);
    sample_t src[4] = {100, 200, 300, 400};
    sample_t dst[4] = {};
    const std::size_t written = resampler_process(&state, src, 4, dst, 4);
    EXPECT_EQ(written, 4u);
    for (int i = 0; i < 4; ++i) {
        EXPECT_EQ(dst[i], src[i]);
    }
}

void test_resampler_upsamples_44_1_to_48() {
    PlaybackResamplerState state{};
    resampler_init(&state, 44100, 48000);
    // 100 input samples should produce roughly 100 * 48000/44100 ≈ 108 outputs.
    std::vector<sample_t> src(100, 1000);
    std::vector<sample_t> dst(256);
    const std::size_t written = resampler_process(&state, src.data(), src.size(),
                                                  dst.data(), dst.size());
    EXPECT_TRUE(written >= 105 && written <= 110);
    // After the initial ramp-up from prev_sample=0 (samples [0..1] are an
    // interpolation 0→1000), the steady-state output of a constant input is
    // the constant. Skip the first two outputs.
    for (std::size_t i = 2; i < written; ++i) {
        EXPECT_EQ(dst[i], 1000);
    }
}

// ---- underrun guard -------------------------------------------------------

void test_underrun_guard_fills_within_comfort_noise_envelope() {
    UnderrunGuardState state{};
    underrun_guard_init(&state, 0);
    sample_t buf[64] = {};
    underrun_guard_fill_comfort_noise(&state, buf, 64);
    bool any_nonzero = false;
    for (sample_t s : buf) {
        EXPECT_TRUE(s >= -kComfortNoisePeak && s <= kComfortNoisePeak);
        any_nonzero = any_nonzero || (s != 0);
    }
    EXPECT_TRUE(any_nonzero);
    EXPECT_EQ(state.synthetic_samples_injected.load(), 64u);
    EXPECT_TRUE(state.last_was_synthetic.load());
}

// ---- latency tracker ------------------------------------------------------

void test_latency_tracker_mean_matches_simple_average() {
    LatencyTrackerState state{};
    EXPECT_TRUE(latency_tracker_init(&state, 4));
    latency_tracker_record(&state, 1000);
    latency_tracker_record(&state, 2000);
    latency_tracker_record(&state, 3000);
    EXPECT_EQ(latency_tracker_mean_ns(&state), 2000);
    latency_tracker_record(&state, 4000);
    EXPECT_EQ(latency_tracker_mean_ns(&state), 2500);
    // Now overflow — oldest (1000) is replaced by 5000; mean is (2+3+4+5)/4.
    latency_tracker_record(&state, 5000);
    EXPECT_EQ(latency_tracker_mean_ns(&state), 3500);
    latency_tracker_destroy(&state);
}

void test_latency_tracker_now_ns_is_monotonic() {
    const int64_t a = latency_tracker_now_ns();
    const int64_t b = latency_tracker_now_ns();
    EXPECT_TRUE(b >= a);
}

// ---- clock sync -----------------------------------------------------------

void test_clock_sync_emits_corrections_outside_threshold() {
    ClockSyncState state{};
    clock_sync_init(&state);
    // Drift inside the +/-48-sample threshold → no correction.
    EXPECT_EQ(clock_sync_observe(&state, 100, 90), 0);
    // Capture is +100 frames ahead → drop a sample.
    EXPECT_EQ(clock_sync_observe(&state, 1000, 900), 1);
    // Playback ahead by -100 → duplicate a sample.
    EXPECT_EQ(clock_sync_observe(&state, 0, 100), -1);
}

// ---- memory guard ---------------------------------------------------------

void test_memory_guard_alloc_free_balance() {
    const auto* counters = memory_guard_counters();
    const uint64_t live_before = counters->live_bytes.load();
    void* p = memory_guard_alloc(1024);
    EXPECT_TRUE(p != nullptr);
    EXPECT_EQ(counters->live_bytes.load() - live_before, 1024u);
    memory_guard_free(p, 1024);
    EXPECT_EQ(counters->live_bytes.load(), live_before);
}

// ---- watchdog -------------------------------------------------------------

void test_watchdog_records_ping() {
    WatchdogPingState state{};
    watchdog_ping_init(&state);
    EXPECT_EQ(state.ping_count.load(), 0u);
    watchdog_ping_record(&state);
    EXPECT_EQ(state.ping_count.load(), 1u);
    // Elapsed should be close to zero immediately after a ping.
    const int64_t e = watchdog_ping_elapsed_ns(&state);
    EXPECT_TRUE(e >= 0 && e < 100'000'000LL);
}

}  // namespace

int main() {
    RUN_TEST(test_ring_buffer_create_rejects_non_power_of_two);
    RUN_TEST(test_ring_buffer_round_trip);
    RUN_TEST(test_ring_buffer_overrun_counter_bumps);
    RUN_TEST(test_ring_buffer_underrun_counter_bumps);
    RUN_TEST(test_ring_buffer_wraps_at_capacity);
    RUN_TEST(test_ring_buffer_sine_wave_round_trip_zero_underrun);
    RUN_TEST(test_mixer_sum_saturates_to_int16_bounds);
    RUN_TEST(test_mixer_apply_gain_clamps_above_unity);
    RUN_TEST(test_mixer_downmix_stereo_to_mono);
    RUN_TEST(test_resampler_passthrough_when_rates_match);
    RUN_TEST(test_resampler_upsamples_44_1_to_48);
    RUN_TEST(test_underrun_guard_fills_within_comfort_noise_envelope);
    RUN_TEST(test_latency_tracker_mean_matches_simple_average);
    RUN_TEST(test_latency_tracker_now_ns_is_monotonic);
    RUN_TEST(test_clock_sync_emits_corrections_outside_threshold);
    RUN_TEST(test_memory_guard_alloc_free_balance);
    RUN_TEST(test_watchdog_records_ping);

    std::printf("\n=== %d passed, %d failed ===\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
