// jni_audio_bridge.cpp — JNI bindings for `NativeAudioBridge.kt`.
//
// The Kotlin side declares `external fun` methods on
// `com.vyzorix.audiorouter.audioengine.NativeAudioBridge`; this file provides
// the C ABI those methods bind to.
//
// Memory model: ring buffers are heap-allocated on the C++ side and surfaced
// to Kotlin as opaque `jlong` handles. Kotlin must call
// `releaseRingBuffer(handle)` before discarding the handle; otherwise the
// memory_guard counters will show a leak.

#define __VYZORIX_LOG_TAG "VyzorixAudio.JNI"

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <cstring>

#include "audio_defs.h"
#include "clock_sync.h"
#include "crash_guard.h"
#include "latency_tracker.h"
#include "logger_engine.h"
#include "memory_guard.h"
#include "pcm_mixer.h"
#include "playback_resampler.h"
#include "ring_buffer.h"
#include "safe_jni_bridge.h"
#include "thread_priority_guard.h"
#include "underrun_guard.h"
#include "watchdog_ping.h"

using namespace vyzorix::audio;

extern "C" {

JNIEXPORT void JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeInit(
        JNIEnv*, jclass) {
    logger_engine_init();
    crash_guard_install();
    VYZORIX_LOGI("Native audio engine initialised");
}

JNIEXPORT jlong JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeAllocateRingBuffer(
        JNIEnv*, jclass, jint capacity_frames) {
    if (capacity_frames <= 0) {
        return 0;
    }
    auto* rb = ring_buffer_create(static_cast<std::size_t>(capacity_frames));
    return reinterpret_cast<jlong>(rb);
}

JNIEXPORT void JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeReleaseRingBuffer(
        JNIEnv*, jclass, jlong handle) {
    auto* rb = reinterpret_cast<CaptureRingBuffer*>(handle);
    ring_buffer_destroy(rb);
}

JNIEXPORT jint JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeRingBufferWrite(
        JNIEnv* env, jclass, jlong handle, jbyteArray src, jint offset_bytes, jint length_bytes) {
    auto* rb = reinterpret_cast<CaptureRingBuffer*>(handle);
    if (rb == nullptr || src == nullptr || offset_bytes < 0 || length_bytes <= 0) {
        return 0;
    }
    JByteArrayHandle h(env, src);
    if (!h.valid() || offset_bytes + length_bytes > h.length()) {
        return 0;
    }
    h.commit_disabled();
    const std::size_t samples = static_cast<std::size_t>(length_bytes) / sizeof(sample_t);
    const auto* src_samples = reinterpret_cast<const sample_t*>(h.data() + offset_bytes);
    const std::size_t written = ring_buffer_write(rb, src_samples, samples);
    return static_cast<jint>(written * sizeof(sample_t));
}

JNIEXPORT jint JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeRingBufferRead(
        JNIEnv* env, jclass, jlong handle, jbyteArray dst, jint offset_bytes, jint length_bytes) {
    auto* rb = reinterpret_cast<CaptureRingBuffer*>(handle);
    if (rb == nullptr || dst == nullptr || offset_bytes < 0 || length_bytes <= 0) {
        return 0;
    }
    JByteArrayHandle h(env, dst);
    if (!h.valid() || offset_bytes + length_bytes > h.length()) {
        return 0;
    }
    const std::size_t samples = static_cast<std::size_t>(length_bytes) / sizeof(sample_t);
    auto* dst_samples = reinterpret_cast<sample_t*>(h.data() + offset_bytes);
    const std::size_t read = ring_buffer_read(rb, dst_samples, samples);
    return static_cast<jint>(read * sizeof(sample_t));
}

JNIEXPORT jint JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeRingBufferAvailableRead(
        JNIEnv*, jclass, jlong handle) {
    auto* rb = reinterpret_cast<CaptureRingBuffer*>(handle);
    return static_cast<jint>(ring_buffer_available_read(rb));
}

JNIEXPORT jint JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeRingBufferAvailableWrite(
        JNIEnv*, jclass, jlong handle) {
    auto* rb = reinterpret_cast<CaptureRingBuffer*>(handle);
    return static_cast<jint>(ring_buffer_available_write(rb));
}

JNIEXPORT jlong JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeRingBufferUnderrunCount(
        JNIEnv*, jclass, jlong handle) {
    auto* rb = reinterpret_cast<CaptureRingBuffer*>(handle);
    if (rb == nullptr) return 0;
    return static_cast<jlong>(rb->underrun_count.load(std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeRingBufferOverrunCount(
        JNIEnv*, jclass, jlong handle) {
    auto* rb = reinterpret_cast<CaptureRingBuffer*>(handle);
    if (rb == nullptr) return 0;
    return static_cast<jlong>(rb->overrun_count.load(std::memory_order_relaxed));
}

JNIEXPORT jint JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeElevatePriority(
        JNIEnv*, jclass, jint priority) {
    return static_cast<jint>(thread_priority_guard_elevate_self(priority));
}

JNIEXPORT jint JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeRestorePriority(
        JNIEnv*, jclass) {
    return static_cast<jint>(thread_priority_guard_restore_self());
}

JNIEXPORT jint JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeCrashGuardPoll(
        JNIEnv*, jclass) {
    return static_cast<jint>(crash_guard_poll_and_clear());
}

JNIEXPORT jlong JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeMonotonicNs(
        JNIEnv*, jclass) {
    return latency_tracker_now_ns();
}

JNIEXPORT jlong JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeLiveBytes(
        JNIEnv*, jclass) {
    return static_cast<jlong>(memory_guard_counters()->live_bytes.load(std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativePeakLiveBytes(
        JNIEnv*, jclass) {
    return static_cast<jlong>(memory_guard_counters()->peak_live_bytes.load(std::memory_order_relaxed));
}

JNIEXPORT jstring JNICALL
Java_com_vyzorix_audiorouter_audioengine_NativeAudioBridge_nativeEngineVersion(
        JNIEnv* env, jclass) {
    return env->NewStringUTF("vyzorix-audioengine/0.1.0-layer-2");
}

}  // extern "C"
