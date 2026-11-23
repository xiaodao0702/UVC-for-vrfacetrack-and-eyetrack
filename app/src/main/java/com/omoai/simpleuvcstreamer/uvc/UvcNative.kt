package com.omoai.simpleuvcstreamer.uvc

import android.util.Log

/**
 * JNI surface. Every capture-level call takes a camera [index] (0 / 1) so both
 * cameras can stream independently and be served on separate HTTP ports.
 */
object UvcNative {
    private const val TAG = "UVCStreamer"

    /** How many camera slots the native engine supports. */
    const val CAMERA_COUNT = 2

    var isLibLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("simpleuvcstreamer")
            isLibLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Library Load Failed", t)
            isLibLoaded = false
        }
    }

    external fun nativeInit(): Int
    external fun nativeOpenDevice(index: Int, fd: Int): Int
    external fun nativeGetResolutions(index: Int): String
    external fun nativeStartStream(index: Int, width: Int, height: Int, fps: Int, format: String): Int
    external fun nativeStopStream(index: Int)
    external fun nativeGetFrameCount(index: Int): Int
    external fun nativeClose(index: Int)
    external fun nativeSetPreviewEnabled(index: Int, enabled: Boolean)
    external fun nativeTakeLatestFrame(index: Int): ByteArray?

    /** 面捕眼追软件直连：每路一份最新 JPEG（drop-old），供 UDP 发送端取走。 */
    external fun nativeSetFaceTrackEnabled(index: Int, enabled: Boolean)
    external fun nativeTakeFaceTrackFrame(index: Int): ByteArray?

    external fun nativeStartHttpServer(index: Int, port: Int): Int
    external fun nativeStopHttpServer(index: Int)
    external fun nativeIsHttpServerRunning(index: Int): Boolean
    external fun nativeGetHttpServerPort(index: Int): Int
    external fun nativeGetHttpClientCount(index: Int): Int
}
