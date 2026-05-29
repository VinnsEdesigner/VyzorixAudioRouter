#define __VYZORIX_LOG_TAG "VyzorixAudio.Ring"
#include "ring_buffer.h"
#include "memory_guard.h"
#include "logger_engine.h"

#include <algorithm>
#include <cstring>

namespace vyzorix {
namespace audio {

namespace {

bool is_power_of_two(std::size_t n) {
    return n > 0 && (n & (n - 1)) == 0;
}

}  // namespace

CaptureRingBuffer* ring_buffer_create(std::size_t capacity_frames) {
    if (!is_power_of_two(capacity_frames)) {
        VYZORIX_LOGE("ring_buffer_create: %zu is not a power of two", capacity_frames);
        return nullptr;
    }
    const std::size_t bytes = capacity_frames * sizeof(sample_t);
    auto* rb = static_cast<CaptureRingBuffer*>(memory_guard_alloc(sizeof(CaptureRingBuffer)));
    if (rb == nullptr) {
        return nullptr;
    }
    rb->samples = static_cast<sample_t*>(memory_guard_alloc(bytes));
    if (rb->samples == nullptr) {
        memory_guard_free(rb, sizeof(CaptureRingBuffer));
        return nullptr;
    }
    std::memset(rb->samples, 0, bytes);
    rb->capacity_frames = capacity_frames;
    rb->mask = capacity_frames - 1;
    rb->write_index.store(0, std::memory_order_relaxed);
    rb->read_index.store(0, std::memory_order_relaxed);
    rb->underrun_count.store(0, std::memory_order_relaxed);
    rb->overrun_count.store(0, std::memory_order_relaxed);
    return rb;
}

void ring_buffer_destroy(CaptureRingBuffer* rb) {
    if (rb == nullptr) {
        return;
    }
    const std::size_t bytes = rb->capacity_frames * sizeof(sample_t);
    memory_guard_free(rb->samples, bytes);
    memory_guard_free(rb, sizeof(CaptureRingBuffer));
}

std::size_t ring_buffer_available_read(const CaptureRingBuffer* rb) {
    if (rb == nullptr) return 0;
    const auto w = rb->write_index.load(std::memory_order_acquire);
    const auto r = rb->read_index.load(std::memory_order_relaxed);
    return static_cast<std::size_t>(w - r);
}

std::size_t ring_buffer_available_write(const CaptureRingBuffer* rb) {
    if (rb == nullptr) return 0;
    return rb->capacity_frames - ring_buffer_available_read(rb);
}

std::size_t ring_buffer_write(CaptureRingBuffer* rb, const sample_t* src, std::size_t frames) {
    if (rb == nullptr || src == nullptr || frames == 0) {
        return 0;
    }
    const auto r = rb->read_index.load(std::memory_order_acquire);
    auto w = rb->write_index.load(std::memory_order_relaxed);
    const std::size_t free_frames = rb->capacity_frames - static_cast<std::size_t>(w - r);
    const std::size_t to_write = std::min(frames, free_frames);
    if (to_write < frames) {
        rb->overrun_count.fetch_add(1, std::memory_order_relaxed);
    }
    for (std::size_t i = 0; i < to_write; ++i) {
        rb->samples[(w + i) & rb->mask] = src[i];
    }
    rb->write_index.store(w + to_write, std::memory_order_release);
    return to_write;
}

std::size_t ring_buffer_read(CaptureRingBuffer* rb, sample_t* dst, std::size_t frames) {
    if (rb == nullptr || dst == nullptr || frames == 0) {
        return 0;
    }
    const auto w = rb->write_index.load(std::memory_order_acquire);
    auto r = rb->read_index.load(std::memory_order_relaxed);
    const std::size_t avail = static_cast<std::size_t>(w - r);
    const std::size_t to_read = std::min(frames, avail);
    if (to_read < frames) {
        rb->underrun_count.fetch_add(1, std::memory_order_relaxed);
    }
    for (std::size_t i = 0; i < to_read; ++i) {
        dst[i] = rb->samples[(r + i) & rb->mask];
    }
    rb->read_index.store(r + to_read, std::memory_order_release);
    return to_read;
}

std::size_t ring_buffer_reset(CaptureRingBuffer* rb) {
    if (rb == nullptr) return 0;
    const auto w = rb->write_index.load(std::memory_order_acquire);
    const auto r = rb->read_index.load(std::memory_order_relaxed);
    const std::size_t discarded = static_cast<std::size_t>(w - r);
    rb->read_index.store(w, std::memory_order_release);
    return discarded;
}

}  // namespace audio
}  // namespace vyzorix
