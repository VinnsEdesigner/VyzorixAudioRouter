// crash_guard.h — signal handler setup declarations for crash_guard.cpp.
//
// We trap SIGSEGV and SIGBUS *inside the JNI library only* so that a native
// fault in the audio pipeline doesn't kill the parent JVM before the
// Kotlin-side crash recorder (`NativeCrashRecovery.kt`, Layer 6) has a
// chance to capture forensics.

#pragma once

#include <cstdint>

namespace vyzorix {
namespace audio {

enum class CrashGuardSignal : int32_t {
    None       = 0,
    Segv       = 1,
    Bus        = 2,
    Fpe        = 3,
    Illegal    = 4,
};

/// Install signal handlers for SIGSEGV + SIGBUS + SIGFPE + SIGILL.
/// Idempotent — safe to call multiple times. Returns true on success.
bool crash_guard_install();

/// Returns the most recent signal observed and resets the flag. `None` if
/// no signal has fired since the last poll. Lockfree-readable; the Kotlin
/// JNI bridge polls this for periodic forensic checks.
CrashGuardSignal crash_guard_poll_and_clear();

}  // namespace audio
}  // namespace vyzorix
