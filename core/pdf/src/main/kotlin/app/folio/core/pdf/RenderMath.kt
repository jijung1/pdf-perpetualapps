package app.folio.core.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure sizing logic for rendering, kept free of Android types so it can be unit tested.
 */
object RenderMath {

    /** Discrete render scales. Rendering at buckets avoids re-rendering on every pinch frame. */
    val SCALE_BUCKETS = floatArrayOf(1f, 1.5f, 2f, 3f, 4f)

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f

    /** Upper bound for a single page bitmap (ARGB_8888). Prevents OOM on huge pages at high zoom. */
    const val MAX_PAGE_BITMAP_BYTES: Long = 24L * 1024 * 1024

    /** Smallest bucket that is >= [scale], so zoomed pages are never upscaled blurry. */
    fun bucketFor(scale: Float): Float {
        for (b in SCALE_BUCKETS) if (scale <= b + 0.01f) return b
        return SCALE_BUCKETS.last()
    }

    /**
     * Pixel width to render a page at, given its laid-out base width and zoom,
     * capped so the resulting bitmap stays under [maxBytes].
     */
    fun renderWidth(
        baseWidthPx: Int,
        scale: Float,
        page: PageSize,
        maxBytes: Long = MAX_PAGE_BITMAP_BYTES,
    ): Int {
        val desired = max(1, (baseWidthPx * bucketFor(scale)).toInt())
        val aspect = page.height.toDouble() / max(1, page.width).toDouble()
        // bytes = w * (w * aspect) * 4  ->  w = sqrt(maxBytes / (4 * aspect))
        val capByMemory = sqrt(maxBytes / (4.0 * max(aspect, 0.01))).toInt()
        return max(1, min(desired, capByMemory))
    }

    /** Maximum horizontal pan so the page edge never detaches from the viewport edge. */
    fun maxPanX(viewportWidthPx: Float, contentWidthPx: Float): Float =
        max(0f, (contentWidthPx - viewportWidthPx) / 2f)

    fun clampPanX(panX: Float, viewportWidthPx: Float, contentWidthPx: Float): Float {
        val limit = maxPanX(viewportWidthPx, contentWidthPx)
        return panX.coerceIn(-limit, limit)
    }

    /**
     * New horizontal pan that keeps the content point under [focusX] fixed while zooming by [ratio].
     * Coordinates: [focusX] is in viewport pixels, pan is offset of content centre from viewport centre.
     */
    fun anchoredPanX(panX: Float, focusX: Float, viewportWidthPx: Float, ratio: Float): Float {
        val center = viewportWidthPx / 2f
        val pointFromContentCenter = focusX - center - panX
        return focusX - center - pointFromContentCenter * ratio
    }
}
