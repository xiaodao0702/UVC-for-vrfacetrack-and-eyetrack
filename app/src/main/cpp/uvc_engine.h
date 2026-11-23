#pragma once

#include <jni.h>
#include <string>

/**
 * libuvc capture engine with two independent camera slots.
 *
 * Each slot owns its own uvc_device_handle_t (0 / 1). Out-of-range slots are
 * rejected with an error so a stale Java-side handle can never touch another
 * camera's device.
 */
namespace uvc_engine {

constexpr int kMaxCameras = 2;

int init();

int open_device(int cam, int fd);
void stop_stream(int cam);
void close_device(int cam);
int start_stream(int cam, int width, int height, int fps, const char *format);
int get_frame_count(int cam);
std::string get_resolutions(int cam);

void set_preview_enabled(int cam, bool enabled);
jbyteArray take_latest_frame(JNIEnv *env, int cam);

/** Cymple 直连: dedicated drop-old JPEG slot per camera (see frame_pipeline). */
void set_cymple_enabled(int cam, bool enabled);
jbyteArray take_cymple_frame(JNIEnv *env, int cam);

}  // namespace uvc_engine
