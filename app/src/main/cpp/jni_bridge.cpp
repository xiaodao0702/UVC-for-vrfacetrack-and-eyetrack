#include "uvc_engine.h"
#include "http_mjpeg_server.h"

#include <jni.h>
#include <string>

extern "C" {

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeInit(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return uvc_engine::init();
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeOpenDevice(
        JNIEnv *env, jobject thiz, jint index, jint fd) {
    (void) env;
    (void) thiz;
    return uvc_engine::open_device(index, fd);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStopStream(
        JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    uvc_engine::stop_stream(index);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeClose(
        JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    uvc_engine::close_device(index);
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStartStream(
        JNIEnv *env, jobject thiz, jint index, jint width, jint height, jint fps, jstring format) {
    (void) thiz;
    const char *fmt = "mjpeg";
    const char *utf = nullptr;
    if (format) {
        utf = env->GetStringUTFChars(format, nullptr);
        if (utf) fmt = utf;
    }
    const int res = uvc_engine::start_stream(index, width, height, fps, fmt);
    if (utf) {
        env->ReleaseStringUTFChars(format, utf);
    }
    return res;
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetFrameCount(
        JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    return uvc_engine::get_frame_count(index);
}

JNIEXPORT jstring JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetResolutions(
        JNIEnv *env, jobject thiz, jint index) {
    (void) thiz;
    std::string s = uvc_engine::get_resolutions(index);
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeSetPreviewEnabled(
        JNIEnv *env, jobject thiz, jint index, jboolean enabled) {
    (void) env;
    (void) thiz;
    uvc_engine::set_preview_enabled(index, enabled == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeTakeLatestFrame(
        JNIEnv *env, jobject thiz, jint index) {
    (void) thiz;
    return uvc_engine::take_latest_frame(env, index);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeSetFaceTrackEnabled(
        JNIEnv *env, jobject thiz, jint index, jboolean enabled) {
    (void) env;
    (void) thiz;
    uvc_engine::set_facetrack_enabled(index, enabled == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeTakeFaceTrackFrame(
        JNIEnv *env, jobject thiz, jint index) {
    (void) thiz;
    return uvc_engine::take_facetrack_frame(env, index);
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStartHttpServer(
        JNIEnv *env, jobject thiz, jint index, jint port) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::start(index, port);
}

JNIEXPORT void JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeStopHttpServer(
        JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    http_mjpeg_server::stop(index);
}

JNIEXPORT jboolean JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeIsHttpServerRunning(
        JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::is_running(index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetHttpServerPort(
        JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::port(index);
}

JNIEXPORT jint JNICALL
Java_com_omoai_simpleuvcstreamer_uvc_UvcNative_nativeGetHttpClientCount(
        JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    return http_mjpeg_server::client_count(index);
}

}  // extern "C"
