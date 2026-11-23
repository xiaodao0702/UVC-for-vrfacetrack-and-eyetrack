#pragma once

#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <vector>

// libusb before libuvc — required for LIBUSB_API_VERSION / uvc_wrap visibility.
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

/**
 * Frame path for the capture callback, one independent slot per camera.
 *
 * Main-line (no HTTP subscribers, preview off): only counters — zero JPEG copy.
 * HTTP subscribers > 0: replace a dedicated latest-JPEG slot (drop-old).
 * Preview on: replace a separate preview slot for UI BitmapFactory.
 *
 * Every entry point takes a camera index in [0, kCameras). Out-of-range indices
 * are no-ops so a stale JNI call can never corrupt another camera's slot.
 */
namespace frame_pipeline {

/** Camera slots this pipeline supports (index 0 / 1). */
constexpr int kCameras = 2;

void on_frame(uvc_frame_t *frame, int cam);

void set_preview_enabled(int cam, bool enabled);
bool preview_enabled(int cam);

/** Take-and-clear latest JPEG for preview. Returns null jbyteArray if none. */
jbyteArray take_latest_frame(JNIEnv *env, int cam);

/**
 * Cymple 直连 (DIY camera protocol) needs its own latest-JPEG slot per camera:
 * the sender polls/takes frames without stealing them from the on-device preview.
 * Drop-old semantics, same as the preview slot.
 */
void set_cymple_enabled(int cam, bool enabled);
jbyteArray take_cymple_frame(JNIEnv *env, int cam);

void http_subscriber_add(int cam);
void http_subscriber_remove(int cam);
int http_subscriber_count(int cam);

/**
 * Copy latest HTTP JPEG for `cam` if seq is newer than last_seq.
 * On success updates last_seq and fills out; returns true.
 */
bool copy_http_frame_if_newer(int cam, uint64_t &last_seq, std::vector<uint8_t> &out);

/** Drop buffered frames for one camera (device closed / stream stopped). */
void clear(int cam);
void clear_all();
void reset_counters(int cam);
int exchange_frame_count(int cam);

}  // namespace frame_pipeline
