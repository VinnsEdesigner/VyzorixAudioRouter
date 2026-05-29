// audio_latency_profiler.h — header-only inline profiler utilities.
//
// Per `VyzorixAudioRouter_RepoTree.md` §core/audioengine/cpp/include/:
//   "No .cpp required; included by latency_tracker.cpp"
//
// The profiler is intentionally small — Layer 2 only needs the bracketing
// primitives. Layer 3+ may layer a full Tracy / perfetto integration on
// top once we have a real audio pipeline to profile.

#pragma once

#include <cstddef>
#include <cstdint>
#include <ctime>

namespace vyzorix {
namespace audio {

inline int64_t profiler_now_ns() {
    struct timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
}

struct ScopedProfile {
    int64_t  start_ns;
    int64_t* sink_ns;  // optional; if set, receives `now - start_ns` on dtor.

    explicit ScopedProfile(int64_t* sink = nullptr)
        : start_ns(profiler_now_ns()), sink_ns(sink) {}

    ~ScopedProfile() {
        if (sink_ns != nullptr) {
            *sink_ns = profiler_now_ns() - start_ns;
        }
    }
};

}  // namespace audio
}  // namespace vyzorix
