#define __VYZORIX_LOG_TAG "VyzorixAudio.Logger"
#include "logger_engine.h"

namespace vyzorix {
namespace audio {

void logger_engine_init() {
    // Forwarding directly into android/log.h has no global state. Reserved
    // for future tee-into-FileLogger support.
}

}  // namespace audio
}  // namespace vyzorix
