package com.ravango.engine.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.ravango.core.common.log.RgLog
import com.ravango.core.media.toUri
import kotlin.math.max

/** Decodes images (content:// or file) downsampled to [maxDimension], honouring EXIF orientation. */
object ImageLoading {
    fun decode(context: Context, uriString: String, maxDimension: Int): Bitmap? = try {
        val uri = uriString.toUri()
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                val largest = max(size.width, size.height)
                if (largest > maxDimension) {
                    val k = maxDimension.toFloat() / largest
                    decoder.setTargetSize((size.width * k).toInt().coerceAtLeast(1), (size.height * k).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val rotation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (decoded != null && rotation != 0) {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            } else decoded
        }
    } catch (e: Exception) {
        RgLog.w("ImageLoading", "Could not decode $uriString", e)
        null
    }

    /** Scales [bitmap] so its width equals [targetWidth] (aspect kept). */
    fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        val w = targetWidth.coerceIn(1, 8192)
        if (w == bitmap.width) return bitmap
        val h = (bitmap.height * (w.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
