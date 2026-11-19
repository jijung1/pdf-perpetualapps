package app.folio.core.tools.images

import app.folio.core.tools.ImageOptions
import app.folio.core.tools.ImagePageSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where an image goes on its page, in PDF points (origin bottom-left). */
data class ImagePlacement(
    val pageWidth: Float,
    val pageHeight: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Pure page-layout math for photos (unit tested). */
object ImageLayout {

    const val A4_W = 595.28f
    const val A4_H = 841.89f
    const val LETTER_W = 612f
    const val LETTER_H = 792f
    const val MARGIN = 28f

    /** "Fit to image" pages use the A4 long side, so photo pages sit naturally next to documents. */
    const val FIT_LONG_SIDE = 841.89f

    /** Images are never embedded above this resolution at their placed size. */
    const val MAX_DPI = 300f

    fun place(imageWidthPx: Int, imageHeightPx: Int, options: ImageOptions): ImagePlacement {
        val iw = imageWidthPx.coerceAtLeast(1).toFloat()
        val ih = imageHeightPx.coerceAtLeast(1).toFloat()
        val landscape = iw > ih
        val margin = if (options.margins) MARGIN else 0f
        return when (options.pageSize) {
            ImagePageSize.FIT -> {
                val scale = (FIT_LONG_SIDE - 2 * margin) / max(iw, ih)
                val w = iw * scale
                val h = ih * scale
                ImagePlacement(w + 2 * margin, h + 2 * margin, margin, margin, w, h)
            }
            ImagePageSize.A4, ImagePageSize.LETTER -> {
                val (short, long) = if (options.pageSize == ImagePageSize.A4) A4_W to A4_H else LETTER_W to LETTER_H
                val pw = if (landscape) long else short
                val ph = if (landscape) short else long
                val scale = min((pw - 2 * margin) / iw, (ph - 2 * margin) / ih)
                val w = iw * scale
                val h = ih * scale
                ImagePlacement(pw, ph, (pw - w) / 2f, (ph - h) / 2f, w, h)
            }
        }
    }

    /** Pixel size to embed the image at: its own size, capped at [MAX_DPI] of the placed size. */
    fun targetPixels(imageWidthPx: Int, imageHeightPx: Int, placement: ImagePlacement): Pair<Int, Int> {
        val maxW = (placement.width / 72f * MAX_DPI).roundToInt().coerceAtLeast(1)
        val maxH = (placement.height / 72f * MAX_DPI).roundToInt().coerceAtLeast(1)
        if (imageWidthPx <= maxW && imageHeightPx <= maxH) return imageWidthPx to imageHeightPx
        val scale = min(maxW.toFloat() / imageWidthPx, maxH.toFloat() / imageHeightPx)
        return (imageWidthPx * scale).roundToInt().coerceAtLeast(1) to (imageHeightPx * scale).roundToInt().coerceAtLeast(1)
    }

    /** Largest power-of-two decode subsampling that still yields at least the target size. */
    fun inSampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        var s = 1
        while (srcW / (s * 2) >= targetW && srcH / (s * 2) >= targetH) s *= 2
        return s
    }
}
