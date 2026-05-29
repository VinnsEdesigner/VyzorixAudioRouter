#define __VYZORIX_LOG_TAG "VyzorixAudio.CrashGuard"
#include "crash_guard.h"
#include "logger_engine.h"

#include <atomic>
#include <csignal>
#include <cstring>

namespace vyzorix {
namespace audio {

namespace {

std::atomic<int32_t> g_last_signal{static_cast<int32_t>(CrashGuardSignal::None)};
std::atomic<bool>    g_installed{false};

void handle_signal(int signo, siginfo_t* /*info*/, void* /*ctx*/) {
    CrashGuardSignal kind = CrashGuardSignal::None;
    switch (signo) {
        case SIGSEGV: kind = CrashGuardSignal::Segv;    break;
        case SIGBUS:  kind = CrashGuardSignal::Bus;     break;
        case SIGFPE:  kind = CrashGuardSignal::Fpe;     break;
        case SIGILL:  kind = CrashGuardSignal::Illegal; break;
        default: break;
    }
    g_last_signal.store(static_cast<int32_t>(kind), std::memory_order_relaxed);
    // Re-raise with default action so the platform crash machinery
    // (tombstone, debuggerd) still fires; we are an observer, not a
    // replacement for the platform handler.
    struct sigaction dfl{};
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(signo, &dfl, nullptr);
    raise(signo);
}

}  // namespace

bool crash_guard_install() {
    if (g_installed.load(std::memory_order_acquire)) {
        return true;
    }
    struct sigaction sa{};
    sa.sa_sigaction = handle_signal;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);

    const int signals[] = { SIGSEGV, SIGBUS, SIGFPE, SIGILL };
    for (int s : signals) {
        if (sigaction(s, &sa, nullptr) != 0) {
            VYZORIX_LOGW("sigaction(%d) failed: %s", s, std::strerror(errno));
            return false;
        }
    }
    g_installed.store(true, std::memory_order_release);
    VYZORIX_LOGI("Native crash guard installed");
    return true;
}

CrashGuardSignal crash_guard_poll_and_clear() {
    const int32_t v = g_last_signal.exchange(static_cast<int32_t>(CrashGuardSignal::None),
                                             std::memory_order_acq_rel);
    return static_cast<CrashGuardSignal>(v);
}

}  // namespace audio
}  // namespace vyzorix
