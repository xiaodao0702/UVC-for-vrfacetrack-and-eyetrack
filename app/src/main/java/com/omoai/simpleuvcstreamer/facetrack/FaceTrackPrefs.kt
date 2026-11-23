package com.omoai.simpleuvcstreamer.facetrack

import android.content.Context

/** Persisted face/eye-tracking bridge settings (enabled + per-camera position). */
object FaceTrackPrefs {
    private const val PREFS = "facetrack_bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FLAG = "flag_"

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun flagFor(context: Context, index: Int): Int {
        return prefs(context).getInt(KEY_FLAG + index, defaultFlag(index))
    }

    fun setFlag(context: Context, index: Int, flag: Int) {
        prefs(context).edit().putInt(KEY_FLAG + index, flag).apply()
    }

    /** Default: camera 1 = left eye, camera 2 = right eye. */
    private fun defaultFlag(index: Int): Int = when (index) {
        0 -> FaceTrackProtocol.FLAG_LEFT_EYE
        1 -> FaceTrackProtocol.FLAG_RIGHT_EYE
        else -> FaceTrackProtocol.FLAG_OFF
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
