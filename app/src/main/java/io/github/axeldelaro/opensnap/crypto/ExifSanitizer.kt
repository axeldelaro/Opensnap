package io.github.axeldelaro.opensnap.crypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Strips 100% of EXIF, GPS coordinates, camera serial numbers, timestamps, and MakerNotes
 * by decoding pixels, applying orientation in-memory, drawing onto a fresh clean Bitmap buffer,
 * and re-encoding as compact WebP ready for XChaCha20-Poly1305 encryption.
 */
object ExifSanitizer {

    data class SanitizedImage(
        val cleanWebpBytes: ByteArray,
        val width: Int,
        val height: Int
    )

    fun sanitizeAndCompressImage(
        rawImageBytes: ByteArray,
        maxDimensionPx: Int = 1280,
        quality: Int = 78
    ): SanitizedImage {
        // 1. Read EXIF rotation angle purely from RAM before discarding original bytes
        val rotationDegrees = try {
            val exif = ExifInterface(ByteArrayInputStream(rawImageBytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }

        // 2. Decode bounds to compute safe subsampling
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size, boundsOpts)
        var sampleSize = 1
        val longestSide = max(boundsOpts.outWidth, boundsOpts.outHeight)
        while (longestSide / (sampleSize * 2) >= maxDimensionPx) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size, decodeOpts)
            ?: error("Impossible de décoder l'image capturée")

        // 3. Scale & rotate onto a brand-new pixel buffer (zero metadata carryover)
        val scale = if (max(decoded.width, decoded.height) > maxDimensionPx) {
            maxDimensionPx.toFloat() / max(decoded.width, decoded.height).toFloat()
        } else {
            1.0f
        }

        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotationDegrees != 0f) {
                postRotate(rotationDegrees)
            }
        }

        val transformed = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            matrix,
            true
        )

        val cleanBitmap = Bitmap.createBitmap(
            transformed.width,
            transformed.height,
            Bitmap.Config.ARGB_8888
        )
        Canvas(cleanBitmap).drawBitmap(transformed, 0f, 0f, null)

        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        cleanBitmap.compress(format, quality, out)

        val finalWidth = cleanBitmap.width
        val finalHeight = cleanBitmap.height

        if (decoded !== transformed) decoded.recycle()
        transformed.recycle()
        cleanBitmap.recycle()

        return SanitizedImage(
            cleanWebpBytes = out.toByteArray(),
            width = finalWidth,
            height = finalHeight
        )
    }
}
