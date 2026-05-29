#define __VYZORIX_LOG_TAG "VyzorixAudio.ThreadPriority"
#include "thread_priority_guard.h"
#include "logger_engine.h"

#include <cerrno>
#include <cstring>
#include <sched.h>

namespace vyzorix {
namespace audio {

PriorityResult thread_priority_guard_elevate_self(int priority) {
    struct sched_param sp{};
    sp.sched_priority = priority;

    const int rc = sched_setscheduler(0, SCHED_FIFO, &sp);
    if (rc != 0) {
        VYZORIX_LOGW(
            "SCHED_FIFO elevation failed at syscall: %s (errno=%d). "
            "Falling back to SCHED_OTHER best-effort scheduling.",
            std::strerror(errno), errno);
        return PriorityResult::SyscallFailed;
    }

    // READ-BACK CHECK — required for Unisoc SC9863A. See NOKIA_C22_NOTES.md
    // §2.3: syscall returning 0 does NOT mean the policy was applied.
    const int actual_policy = sched_getscheduler(0);
    struct sched_param actual_sp{};
    if (sched_getparam(0, &actual_sp) != 0) {
        VYZORIX_LOGW(
            "sched_getparam failed after elevation: %s — assuming silent fallback",
            std::strerror(errno));
        return PriorityResult::SilentFallback;
    }

    if (actual_policy != SCHED_FIFO) {
        VYZORIX_LOGW(
            "SCHED_FIFO requested but actual policy is %d (priority=%d). "
            "Likely Unisoc SC9863A cgroup downgrade — operating in best-effort mode.",
            actual_policy, actual_sp.sched_priority);
        return PriorityResult::SilentFallback;
    }

    VYZORIX_LOGI("SCHED_FIFO confirmed at priority %d", actual_sp.sched_priority);
    return PriorityResult::RealTime;
}

PriorityResult thread_priority_guard_restore_self() {
    struct sched_param sp{};
    sp.sched_priority = 0;
    const int rc = sched_setscheduler(0, SCHED_OTHER, &sp);
    if (rc != 0) {
        VYZORIX_LOGW("SCHED_OTHER restore failed: %s", std::strerror(errno));
        return PriorityResult::SyscallFailed;
    }
    return PriorityResult::BestEffort;
}

}  // namespace audio
}  // namespace vyzorix
