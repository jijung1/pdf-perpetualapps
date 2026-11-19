package app.folio.core.tools.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import app.folio.core.tools.ToolError
import app.folio.core.tools.ToolException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Size and orientation of a photo, read without decoding pixels. */
data class ImageInfo(
    /** Width and height as the photo should be displayed (EXIF rotation applied). */
    val width: Int,
    val height: Int,
    /** Clockwise rotation from EXIF. */
    val exifRotation: Int,
    val exifMirrored: Boolean,
    val isJpeg: Boolean,
    val isHeif: Boolean,
)

/**
 * Decodes JPG / PNG / HEIC (Android 9+) photos with EXIF orientation applied and with
 * subsampling during decode, so a 50-megapixel photo never needs 200 MB of memory.
 */
object ImageDecoding {

    fun info(context: Context, uri: Uri, name: String): ImageInfo {
        val header = open(context, uri).use { input ->
            val buf = ByteArray(12)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }
        val isJpeg = header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
        val isHeif = header.size >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            String(header, 8, 4, Charsets.US_ASCII).lowercase() in setOf("heic", "heix", "hevc", "mif1", "msf1", "heim", "heis", "avif")
        if (isHeif && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw ToolException(ToolError.UnsupportedImage(name, needsNewerAndroid = true))

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri).use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw ToolException(ToolError.UnsupportedImage(name, needsNewerAndroid = false))

        var rotation = 0
        var mirrored = false
        runCatching {
            open(context, uri).use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> mirrored = true
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> { mirrored = true; rotation = 180 }
                    ExifInterface.ORIENTATION_TRANSPOSE -> { mirrored = true; rotation = 90 }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { mirrored = true; rotation = 270 }
                }
            }
        }
        val swap = rotation == 90 || rotation == 270
        return ImageInfo(
            width = if (swap) opts.outHeight else opts.outWidth,
            height = if (swap) opts.outWidth else opts.outHeight,
            exifRotation = rotation,
            exifMirrored = mirrored,
            isJpeg = isJpeg,
            isHeif = isHeif,
        )
    }

    /** Decodes upright at roughly [targetW] × [targetH] (display orientation), never larger than the source. */
    fun decode(context: Context, uri: Uri, name: String, info: ImageInfo, targetW: Int, targetH: Int): Bitmap {
        val swap = info.exifRotation == 90 || info.exifRotation == 270
        val rawW = if (swap) info.height else info.width
        val rawH = if (swap) info.width else info.height
        val rawTargetW = if (swap) targetH else targetW
        val rawTargetH = if (swap) targetW else targetH
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageLayout.inSampleSize(rawW, rawH, rawTargetW, rawTargetH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            open(context, uri).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: OutOfMemoryError) {
            throw ToolException(ToolError.UnsupportedImage(name, needsNewerAndroid = false), e)
        } ?: throw ToolException(ToolError.UnsupportedImage(name, needsNewerAndroid = info.isHeif && Build.VERSION.SDK_INT < Build.VERSION_CODES.P))

        val m = Matrix()
        if (info.exifMirrored) m.postScale(-1f, 1f)
        if (info.exifRotation != 0) m.postRotate(info.exifRotation.toFloat())
        val sx = rawTargetW.toFloat() / decoded.width
        val sy = rawTargetH.toFloat() / decoded.height
        val scale = minOf(1f, minOf(sx, sy))
        if (scale < 0.999f) m.preScale(scale, scale)
        if (m.isIdentity) return decoded
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (out !== decoded) decoded.recycle()
        return out
    }

    /** A small upright preview for thumbnails. */
    fun thumbnail(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val info = info(context, uri, "")
        val scale = minOf(1f, maxSide.toFloat() / maxOf(info.width, info.height))
        decode(context, uri, "", info, (info.width * scale).toInt().coerceAtLeast(1), (info.height * scale).toInt().coerceAtLeast(1))
    }.getOrNull()

    fun readBytes(context: Context, uri: Uri): ByteArray = open(context, uri).use { it.readBytes() }

    private fun open(context: Context, uri: Uri): InputStream = try {
        context.contentResolver.openInputStream(uri) ?: throw ToolException(ToolError.SourceUnavailable)
    } catch (e: FileNotFoundException) {
        throw ToolException(ToolError.SourceUnavailable, e)
    } catch (e: SecurityException) {
        throw ToolException(ToolError.SourceUnavailable, e)
    } catch (e: IOException) {
        throw ToolException(ToolError.SourceUnavailable, e)
    }
}
