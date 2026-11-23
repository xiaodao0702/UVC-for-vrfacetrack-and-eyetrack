package com.omoai.simpleuvcstreamer.service

import android.content.Context
import com.omoai.simpleuvcstreamer.util.FileLogger

/**
 * Survives process death so sticky FGS restart can resume capture.
 * Tracked per camera slot so restarting the process resumes exactly the cameras
 * the user had running.
 */
object CaptureKeepAlive {
    private const val PREFS = "capture_keep_alive"

    private fun key(index: Int) = "want_streaming_$index"

    fun wantStreaming(context: Context, index: Int): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(index), false)
    }

    fun setWantStreaming(context: Context, index: Int, want: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(index), want)
            .apply()
        FileLogger.log("[$index] Keep-alive wantStreaming=$want")
    }

    /** True when at least one camera slot is marked to keep streaming. */
    fun anyWantsStreaming(context: Context, slots: Int = 2): Boolean {
        return (0 until slots).any { wantStreaming(context, it) }
    }
}
