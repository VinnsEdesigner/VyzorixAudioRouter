#define __VYZORIX_LOG_TAG "VyzorixAudio.JniBridge"
#include "safe_jni_bridge.h"
#include "logger_engine.h"

namespace vyzorix {
namespace audio {

JByteArrayHandle::JByteArrayHandle(JNIEnv* env, jbyteArray array)
    : env_(env), array_(array), data_(nullptr), length_(0), commit_(true) {
    if (env_ == nullptr || array_ == nullptr) {
        return;
    }
    data_ = env_->GetByteArrayElements(array_, nullptr);
    if (data_ != nullptr) {
        length_ = env_->GetArrayLength(array_);
    } else {
        VYZORIX_LOGW("GetByteArrayElements returned nullptr");
    }
}

JByteArrayHandle::~JByteArrayHandle() {
    if (env_ == nullptr || array_ == nullptr || data_ == nullptr) {
        return;
    }
    env_->ReleaseByteArrayElements(array_, data_, commit_ ? 0 : JNI_ABORT);
}

}  // namespace audio
}  // namespace vyzorix
