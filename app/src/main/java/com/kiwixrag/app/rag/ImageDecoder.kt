package com.kiwixrag.app.rag

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DecodedImage(val bitmap: Bitmap, val thumbnailJpeg: ByteArray)

/**
 * Decodes images from raw ZIM bytes without ever holding the full-resolution bitmap. The bounds are read
 * first, the decoder subsamples, and the result is scaled so the longest side is at most [maxSide].
 */
object ImageDecoder {
    const val MODEL_SIDE = 512
    const val THUMB_SIDE = 192
    private const val MIN_SIDE = 96
    private const val MAX_ASPECT = 6f

    fun decode(bytes: ByteArray, maxSide: Int = MODEL_SIDE): DecodedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || min(w, h) < MIN_SIDE) return null
        if (max(w, h).toFloat() / min(w, h) > MAX_ASPECT) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(w, h), maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scaled = fit(decoded, maxSide)
        val thumb = fit(scaled, THUMB_SIDE, recycleSource = false)
        val jpeg = ByteArrayOutputStream().use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        if (thumb !== scaled) thumb.recycle()
        return DecodedImage(scaled, jpeg)
    }

    fun toJpeg(bitmap: Bitmap, quality: Int = 90): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    fun sampleSize(longestSide: Int, target: Int): Int {
        var s = 1
        while (longestSide / (s * 2) >= target) s *= 2
        return s
    }

    private fun fit(src: Bitmap, side: Int, recycleSource: Boolean = true): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= side) return src
        val ratio = side.toFloat() / longest
        val out = Bitmap.createScaledBitmap(src, max(1, (src.width * ratio).roundToInt()), max(1, (src.height * ratio).roundToInt()), true)
        if (recycleSource && out !== src) src.recycle()
        return out
    }
}
