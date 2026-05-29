// thread_priority_guard.h — declarations for thread_priority_guard.cpp.

#pragma once

#include "audio_defs.h"

namespace vyzorix {
namespace audio {

/// Elevate the *calling* thread to SCHED_FIFO at the supplied priority.
/// Performs the read-back check required by `NOKIA_C22_NOTES.md` §2.3 so
/// the Unisoc silent fallback to SCHED_OTHER is distinguishable from a
/// genuine real-time elevation.
PriorityResult thread_priority_guard_elevate_self(int priority);

/// Restore the calling thread to SCHED_OTHER at default priority. Used in
/// graceful shutdown so a long-lived daemon thread doesn't keep a
/// real-time slot when the engine is torn down.
PriorityResult thread_priority_guard_restore_self();

}  // namespace audio
}  // namespace vyzorix
