# core/audioengine native build per doc/VyzorixAudioRouter_RepoTree.md
# §core/audioengine/cpp/CMakeLists.txt comment ("must list all 15 .cpp files
# in add_library(); links liboboe, libOpenSLES, liblog; flags: -O3 -ffast-math").
#
# Oboe is intentionally NOT a hard dependency in this PR — Layer 2 ships the
# JNI surface and the pure-C++ algorithms (ring buffer, mixer, resampler,
# clock sync, underrun guard, latency tracker, thread priority, fallback
# bridge, watchdog, crash guard, memory guard, ringbuffer pressure, logger,
# safe JNI bridge). Oboe / OpenSLES linkage comes in Layer 3 along with the
# `AudioPipelineController` that drives the engine. liblog is required now
# because `logger_engine.cpp` forwards into `__android_log_print`.

cmake_minimum_required(VERSION 3.22.1)

project(vyzorix_audioengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Hidden visibility by default — only JNIEXPORT functions appear in the
# dynamic symbol table.
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# Public headers live in include/. Every .cpp pulls in the same set.
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

add_library(
    audioengine
    SHARED
    capture_ring_buffer.cpp
    pcm_mixer.cpp
    playback_resampler.cpp
    underrun_guard.cpp
    latency_tracker.cpp
    audio_clock_sync.cpp
    thread_priority_guard.cpp
    logger_engine.cpp
    crash_guard.cpp
    safe_jni_bridge.cpp
    watchdog_ping.cpp
    memory_guard.cpp
    ringbuffer_pressure.cpp
    audio_fallback_bridge.cpp
    jni_audio_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    audioengine
    PRIVATE
    ${log-lib}
    android
)
