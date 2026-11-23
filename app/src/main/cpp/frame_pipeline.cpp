#include "frame_pipeline.h"

#include "jpeg_encode.h"
#include "log.h"

#include <atomic>
#include <cstring>
#include <mutex>
#include <vector>

namespace frame_pipeline {
namespace {

struct HttpSlot {
    std::mutex mu;
    std::vector<uint8_t> jpeg;
    uint64_t seq = 0;
    bool has = false;
};

struct PreviewSlot {
    std::mutex mu;
    std::vector<uint8_t> jpeg;
    bool has = false;
};

struct CympleSlot {
    std::mutex mu;
    std::vector<uint8_t> jpeg;
    bool has = false;
};

HttpSlot g_http[kCameras];
PreviewSlot g_preview[kCameras];
CympleSlot g_cymple[kCameras];
std::atomic<int> g_frame_count[kCameras];
std::atomic<int> g_callback_seq[kCameras];
std::atomic<bool> g_preview_enabled[kCameras];
std::atomic<bool> g_cymple_enabled[kCameras];
std::atomic<int> g_http_subscribers[kCameras];

// Reused JPEG scratch for the YUV encode path (per callback thread).
thread_local std::vector<uint8_t> t_jpeg_scratch;

bool valid(int cam) {
    return cam >= 0 && cam < kCameras;
}

bool encode_to_jpeg(uvc_frame_t *frame, std::vector<uint8_t> &out) {
    if (!frame || !frame->data || frame->data_bytes == 0) return false;
    if (frame->frame_format == UVC_FRAME_FORMAT_MJPEG) {
        out.assign(
                static_cast<const uint8_t *>(frame->data),
                static_cast<const uint8_t *>(frame->data) + frame->data_bytes);
        return !out.empty();
    }
    if (frame->frame_format == UVC_FRAME_FORMAT_YUYV) {
        out = jpeg_encode::yuyv_to_jpeg(
                static_cast<const uint8_t *>(frame->data),
                frame->data_bytes,
                static_cast<int>(frame->width),
                static_cast<int>(frame->height),
                frame->step);
        return !out.empty();
    }
    if (frame->frame_format == UVC_FRAME_FORMAT_UYVY) {
        out = jpeg_encode::uyvy_to_jpeg(
                static_cast<const uint8_t *>(frame->data),
                frame->data_bytes,
                static_cast<int>(frame->width),
                static_cast<int>(frame->height),
                frame->step);
        return !out.empty();
    }
    return false;
}

void replace_http_slot(int cam, const uint8_t *data, size_t len) {
    HttpSlot &slot = g_http[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    slot.jpeg.assign(data, data + len);
    slot.has = true;
    ++slot.seq;
}

void replace_preview_slot(int cam, const uint8_t *data, size_t len) {
    PreviewSlot &slot = g_preview[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    slot.jpeg.assign(data, data + len);
    slot.has = true;
}

void replace_cymple_slot(int cam, const uint8_t *data, size_t len) {
    CympleSlot &slot = g_cymple[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    slot.jpeg.assign(data, data + len);
    slot.has = true;
}

}  // namespace

void on_frame(uvc_frame_t *frame, int cam) {
    if (!valid(cam)) return;
    g_frame_count[cam].fetch_add(1, std::memory_order_relaxed);
    int n = g_callback_seq[cam].fetch_add(1, std::memory_order_relaxed) + 1;
    if (n == 1 || n % 30 == 0) {
        LOGI("cam %d frame #%d size=%zu fmt=%d %ux%u",
             cam,
             n,
             frame ? frame->data_bytes : 0,
             frame ? (int) frame->frame_format : -1,
             frame ? frame->width : 0,
             frame ? frame->height : 0);
    }

    const bool want_http = g_http_subscribers[cam].load(std::memory_order_relaxed) > 0;
    const bool want_preview = g_preview_enabled[cam].load(std::memory_order_relaxed);
    const bool want_cymple = g_cymple_enabled[cam].load(std::memory_order_relaxed);
    if (!want_http && !want_preview && !want_cymple) {
        return;
    }
    if (!encode_to_jpeg(frame, t_jpeg_scratch) || t_jpeg_scratch.empty()) {
        return;
    }

    if (want_http) {
        replace_http_slot(cam, t_jpeg_scratch.data(), t_jpeg_scratch.size());
    }
    if (want_preview) {
        replace_preview_slot(cam, t_jpeg_scratch.data(), t_jpeg_scratch.size());
    }
    if (want_cymple) {
        replace_cymple_slot(cam, t_jpeg_scratch.data(), t_jpeg_scratch.size());
    }
}

void set_preview_enabled(int cam, bool enabled) {
    if (!valid(cam)) return;
    g_preview_enabled[cam].store(enabled, std::memory_order_relaxed);
    if (!enabled) {
        PreviewSlot &slot = g_preview[cam];
        std::lock_guard<std::mutex> lock(slot.mu);
        slot.jpeg.clear();
        slot.has = false;
    }
}

bool preview_enabled(int cam) {
    return valid(cam) && g_preview_enabled[cam].load(std::memory_order_relaxed);
}

void set_cymple_enabled(int cam, bool enabled) {
    if (!valid(cam)) return;
    g_cymple_enabled[cam].store(enabled, std::memory_order_relaxed);
    if (!enabled) {
        CympleSlot &slot = g_cymple[cam];
        std::lock_guard<std::mutex> lock(slot.mu);
        slot.jpeg.clear();
        slot.has = false;
    }
}

jbyteArray take_cymple_frame(JNIEnv *env, int cam) {
    if (!valid(cam)) return nullptr;
    CympleSlot &slot = g_cymple[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    if (!slot.has || slot.jpeg.empty()) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(slot.jpeg.size()));
    if (!arr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
            arr, 0, static_cast<jsize>(slot.jpeg.size()),
            reinterpret_cast<const jbyte *>(slot.jpeg.data()));
    slot.has = false;
    return arr;
}

jbyteArray take_latest_frame(JNIEnv *env, int cam) {
    if (!valid(cam)) return nullptr;
    PreviewSlot &slot = g_preview[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    if (!slot.has || slot.jpeg.empty()) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(slot.jpeg.size()));
    if (!arr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
            arr, 0, static_cast<jsize>(slot.jpeg.size()),
            reinterpret_cast<const jbyte *>(slot.jpeg.data()));
    slot.has = false;
    return arr;
}

void http_subscriber_add(int cam) {
    if (!valid(cam)) return;
    g_http_subscribers[cam].fetch_add(1, std::memory_order_relaxed);
}

void http_subscriber_remove(int cam) {
    if (!valid(cam)) return;
    int prev = g_http_subscribers[cam].fetch_sub(1, std::memory_order_relaxed);
    if (prev <= 1) {
        g_http_subscribers[cam].store(0, std::memory_order_relaxed);
        HttpSlot &slot = g_http[cam];
        std::lock_guard<std::mutex> lock(slot.mu);
        slot.jpeg.clear();
        slot.has = false;
    }
}

int http_subscriber_count(int cam) {
    return valid(cam) ? g_http_subscribers[cam].load(std::memory_order_relaxed) : 0;
}

bool copy_http_frame_if_newer(int cam, uint64_t &last_seq, std::vector<uint8_t> &out) {
    if (!valid(cam)) return false;
    HttpSlot &slot = g_http[cam];
    std::lock_guard<std::mutex> lock(slot.mu);
    if (!slot.has || slot.jpeg.empty() || slot.seq == last_seq) {
        return false;
    }
    out = slot.jpeg;
    last_seq = slot.seq;
    return true;
}

void clear(int cam) {
    if (!valid(cam)) return;
    {
        PreviewSlot &slot = g_preview[cam];
        std::lock_guard<std::mutex> lock(slot.mu);
        slot.jpeg.clear();
        slot.has = false;
    }
    {
        HttpSlot &slot = g_http[cam];
        std::lock_guard<std::mutex> lock(slot.mu);
        slot.jpeg.clear();
        slot.has = false;
    }
    {
        CympleSlot &slot = g_cymple[cam];
        std::lock_guard<std::mutex> lock(slot.mu);
        slot.jpeg.clear();
        slot.has = false;
    }
}

void clear_all() {
    for (int cam = 0; cam < kCameras; ++cam) {
        clear(cam);
    }
}

void reset_counters(int cam) {
    if (!valid(cam)) return;
    g_frame_count[cam].store(0, std::memory_order_relaxed);
    g_callback_seq[cam].store(0, std::memory_order_relaxed);
}

int exchange_frame_count(int cam) {
    return valid(cam) ? g_frame_count[cam].exchange(0) : 0;
}

}  // namespace frame_pipeline
