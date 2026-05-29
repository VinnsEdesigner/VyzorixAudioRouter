// logger_engine.h — log macros forwarding into android/log.h.
//
// `__VYZORIX_LOG_TAG` may be overridden per .cpp by `#define`-ing it before
// including this header, which keeps logcat lines self-identifying without
// pulling in a heavyweight logger abstraction.

#pragma once

#if defined(__ANDROID__)
#include <android/log.h>
#else
#include <cstdio>
#endif

#ifndef __VYZORIX_LOG_TAG
#define __VYZORIX_LOG_TAG "VyzorixAudio"
#endif

namespace vyzorix {
namespace audio {

/// Initialise the logger engine. Currently a no-op (android/log.h has no
/// global state) but exposed so the JNI bridge can future-proof itself
/// against a logger backend swap (e.g. tee to `FileLogger`).
void logger_engine_init();

}  // namespace audio
}  // namespace vyzorix

#if defined(__ANDROID__)
#define VYZORIX_LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, __VYZORIX_LOG_TAG, __VA_ARGS__)
#define VYZORIX_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   __VYZORIX_LOG_TAG, __VA_ARGS__)
#define VYZORIX_LOGI(...) __android_log_print(ANDROID_LOG_INFO,    __VYZORIX_LOG_TAG, __VA_ARGS__)
#define VYZORIX_LOGW(...) __android_log_print(ANDROID_LOG_WARN,    __VYZORIX_LOG_TAG, __VA_ARGS__)
#define VYZORIX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   __VYZORIX_LOG_TAG, __VA_ARGS__)
#else
// Host build (gtest harness) — route logs to stderr so the test runner
// can capture them. Stays quiet by default unless VYZORIX_HOST_LOG_VERBOSE
// is defined; assertions in the harness do not need log spam.
#if defined(VYZORIX_HOST_LOG_VERBOSE)
#define VYZORIX_LOGV(...) std::fprintf(stderr, "[V] " __VYZORIX_LOG_TAG ": " __VA_ARGS__), std::fprintf(stderr, "\n")
#define VYZORIX_LOGD(...) std::fprintf(stderr, "[D] " __VYZORIX_LOG_TAG ": " __VA_ARGS__), std::fprintf(stderr, "\n")
#define VYZORIX_LOGI(...) std::fprintf(stderr, "[I] " __VYZORIX_LOG_TAG ": " __VA_ARGS__), std::fprintf(stderr, "\n")
#define VYZORIX_LOGW(...) std::fprintf(stderr, "[W] " __VYZORIX_LOG_TAG ": " __VA_ARGS__), std::fprintf(stderr, "\n")
#define VYZORIX_LOGE(...) std::fprintf(stderr, "[E] " __VYZORIX_LOG_TAG ": " __VA_ARGS__), std::fprintf(stderr, "\n")
#else
#define VYZORIX_LOGV(...) do { } while (0)
#define VYZORIX_LOGD(...) do { } while (0)
#define VYZORIX_LOGI(...) do { } while (0)
#define VYZORIX_LOGW(...) do { } while (0)
#define VYZORIX_LOGE(...) do { } while (0)
#endif
#endif
