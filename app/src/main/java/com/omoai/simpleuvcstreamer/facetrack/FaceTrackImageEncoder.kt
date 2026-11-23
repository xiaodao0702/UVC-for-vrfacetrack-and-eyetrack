package com.omoai.simpleuvcstreamer.facetrack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * Converts a full-size camera JPEG into what the face-tracking software's DIY camera sends:
 * a 320x240 (4:3), grayscale JPEG that fits the u16 uiTotalLen field.
 *
 * Crop + scale + grayscale are done in one Canvas pass (keeps per-frame cost low
 * enough for two cameras at ~20 fps).
 */
object FaceTrackImageEncoder {
    private const val OUT_W = 320
    private const val OUT_H = 240
    private const val QUALITY = 55

    /** Decode at most this large before the scale/crop pass. */
    private const val MAX_DECODE_EDGE = 960

    fun toFaceTrackJpeg(src: ByteArray): ByteArray? {
        if (src.isEmpty()) return null

        val decoded = decode(src) ?: return null
        try {
            val gray = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.RGB_565)
            try {
                val canvas = Canvas(gray)

                // Center-crop to 4:3 in source space, then map the rect onto the canvas.
                val aspect = OUT_W.toFloat() / OUT_H.toFloat()
                var cw = decoded.width
                var ch = decoded.height
                if (cw.toFloat() / ch > aspect) {
                    cw = (ch * aspect).toInt()
                } else {
                    ch = (cw / aspect).toInt()
                }
                if (cw < 1) cw = 1
                if (ch < 1) ch = 1
                val srcLeft = (decoded.width - cw) / 2
                val srcTop = (decoded.height - ch) / 2
                val srcRect = Rect(srcLeft, srcTop, srcLeft + cw, srcTop + ch)
                val dstRect = Rect(0, 0, OUT_W, OUT_H)

                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                canvas.drawBitmap(decoded, srcRect, dstRect, paint)

                val out = ByteArrayOutputStream(16 * 1024)
                if (!gray.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                    return null
                }
                return out.toByteArray()
            } finally {
                gray.recycle()
            }
        } catch (t: Throwable) {
            return null
        } finally {
            decoded.recycle()
        }
    }

    private fun decode(src: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sample = 1
        while (srcW / (sample * 2) >= MAX_DECODE_EDGE || srcH / (sample * 2) >= MAX_DECODE_EDGE) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeByteArray(
                src, 0, src.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        } catch (t: Throwable) {
            null
        }
    }
}
