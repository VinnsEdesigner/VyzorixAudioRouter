// safe_jni_bridge.h — safe JNI wrapper declarations.
//
// Provides RAII-style helpers around the more error-prone JNI patterns:
// pinning a byte array, fetching its critical pointer, releasing on scope
// exit. The Kotlin side uses `NativeAudioBridge.kt`; this header is
// included by `jni_audio_bridge.cpp`.

#pragma once

#include <jni.h>
#include <cstddef>
#include <cstdint>

namespace vyzorix {
namespace audio {

/// RAII wrapper around `GetByteArrayElements` / `ReleaseByteArrayElements`.
/// The destructor copies modifications back to the JVM array unless
/// `commit_disabled()` was called.
class JByteArrayHandle {
public:
    JByteArrayHandle(JNIEnv* env, jbyteArray array);
    ~JByteArrayHandle();

    JByteArrayHandle(const JByteArrayHandle&)            = delete;
    JByteArrayHandle& operator=(const JByteArrayHandle&) = delete;

    jbyte* data() const { return data_; }
    jsize length() const { return length_; }
    bool valid() const { return data_ != nullptr; }

    /// Suppress the writeback in the destructor (use for read-only borrows
    /// of the array — slightly faster).
    void commit_disabled() { commit_ = false; }

private:
    JNIEnv*    env_;
    jbyteArray array_;
    jbyte*     data_;
    jsize      length_;
    bool       commit_;
};

}  // namespace audio
}  // namespace vyzorix
