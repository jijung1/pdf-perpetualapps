package app.folio.core.editing.whiteout

import app.folio.core.editing.model.Box
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Tuning knobs for [BackgroundSampler]. All lengths are in PDF points so results don't depend on zoom.
 * Defaults were chosen for typical office documents and scans; change them in one place.
 */
data class SamplingConfig(
    /** Thickness of the sampled ring around the whiteout box. */
    val ringWidthPt: Float = 6f,
    /** Gap between the box and the ring, so the box's own anti-aliased edge is never sampled. */
    val innerGapPt: Float = 1f,
    /** Resolution the ring is rendered at. */
    val renderDpi: Int = 150,
    /** Pixels darker than this (0–255 luma) are treated as foreground: text, borders, shadows. */
    val minLuma: Int = 180,
    /** Histogram resolution per channel for clustering (4 bits = 16 levels). */
    val clusterBinBits: Int = 4,
    /** Max RGB distance from the dominant cluster's centroid for a pixel to count as background. */
    val clusterTolerance: Float = 18f,
    /** Minimum background pixels needed before a colour is trusted. */
    val minSamples: Int = 40,
    /** The dominant cluster must hold at least this share of the bright pixels. */
    val minDominantShare: Float = 0.55f,
    /** Upper bound on pixels examined (the ring is subsampled evenly beyond this). */
    val maxSamples: Int = 20_000,
) {
    val pxPerPt: Float get() = renderDpi / 72f
}

sealed interface SampleResult {
    data class Confident(val argb: Int, val samples: Int, val dominantShare: Float) : SampleResult

    data class LowConfidence(val reason: Reason, val samples: Int) : SampleResult

    enum class Reason {
        /** The ring was too small (e.g. a box filling the whole page). */
        TOO_FEW_PIXELS,
        /** Almost everything around the box is dark (dark page, or a box surrounded by text/graphics). */
        TOO_DARK,
        /** Bright pixels exist but no single colour dominates (photo, gradient, busy pattern). */
        NO_DOMINANT_COLOR,
    }
}

/** Integer pixel rectangle, [left, right) × [top, bottom). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

/** What to render for sampling: [region] (page points) and the part of it to ignore, in region pixels. */
data class SamplingPlan(val region: Box, val widthPx: Int, val heightPx: Int, val exclude: PixelRect, val pxPerPt: Float)

/**
 * Finds the background colour around a whiteout rectangle:
 * sample a ring around it → drop dark pixels (luma < minLuma) → keep the dominant colour cluster
 * → per-channel median. Returns [SampleResult.LowConfidence] rather than guessing when the
 * evidence is weak, so callers can fall back to white or ask the user.
 */
object BackgroundSampler {

    /** Plans the render region: the box grown by gap + ring, clipped to the page. */
    fun plan(box: Box, pageWidth: Float, pageHeight: Float, config: SamplingConfig = SamplingConfig()): SamplingPlan {
        val grow = config.innerGapPt + config.ringWidthPt
        val region = Box(
            (box.left - grow).coerceAtLeast(0f),
            (box.top - grow).coerceAtLeast(0f),
            (box.right + grow).coerceAtMost(pageWidth),
            (box.bottom + grow).coerceAtMost(pageHeight),
        )
        val s = config.pxPerPt
        val w = ceil(region.width * s).toInt().coerceAtLeast(1)
        val h = ceil(region.height * s).toInt().coerceAtLeast(1)
        val ex = Box(box.left - config.innerGapPt, box.top - config.innerGapPt, box.right + config.innerGapPt, box.bottom + config.innerGapPt)
        val exclude = PixelRect(
            floor((ex.left - region.left) * s).toInt().coerceIn(0, w),
            floor((ex.top - region.top) * s).toInt().coerceIn(0, h),
            ceil((ex.right - region.left) * s).toInt().coerceIn(0, w),
            ceil((ex.bottom - region.top) * s).toInt().coerceIn(0, h),
        )
        return SamplingPlan(region, w, h, exclude, s)
    }

    fun luma(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /**
     * [pixels] is a row-major ARGB image of [width] × [height]; pixels inside [exclude] are ignored.
     */
    fun sample(pixels: IntArray, width: Int, height: Int, exclude: PixelRect, config: SamplingConfig = SamplingConfig()): SampleResult {
        require(pixels.size >= width * height) { "pixel buffer too small" }

        // 1. Ring pixels (subsampled evenly when the ring is large).
        val ringCount = width * height - (exclude.right - exclude.left).coerceAtLeast(0) * (exclude.bottom - exclude.top).coerceAtLeast(0)
        if (ringCount < config.minSamples) return SampleResult.LowConfidence(SampleResult.Reason.TOO_FEW_PIXELS, ringCount.coerceAtLeast(0))
        val stride = if (ringCount > config.maxSamples) sqrt(ringCount.toDouble() / config.maxSamples).let { ceil(it).toInt() } else 1

        // 2. Brightness filter.
        val bright = IntArray(minOf(ringCount, config.maxSamples * 2 + 16))
        var brightCount = 0
        var total = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (!exclude.contains(x, y)) {
                    total++
                    val p = pixels[y * width + x]
                    if (luma(p) >= config.minLuma && brightCount < bright.size) bright[brightCount++] = p
                }
                x += stride
            }
            y += stride
        }
        if (brightCount < config.minSamples) {
            val reason = if (total >= config.minSamples) SampleResult.Reason.TOO_DARK else SampleResult.Reason.TOO_FEW_PIXELS
            return SampleResult.LowConfidence(reason, brightCount)
        }

        // 3. Dominant cluster: largest histogram bin merged with its 26 neighbours gives a centroid;
        //    keep only pixels close to it (drops shadows, light rules and pale coloured ink).
        val bits = config.clusterBinBits.coerceIn(2, 6)
        val levels = 1 shl bits
        val shift = 8 - bits
        val counts = IntArray(levels * levels * levels)
        fun bin(p: Int): Int {
            val r = ((p shr 16) and 0xFF) shr shift
            val g = ((p shr 8) and 0xFF) shr shift
            val b = (p and 0xFF) shr shift
            return (r * levels + g) * levels + b
        }
        for (i in 0 until brightCount) counts[bin(bright[i])]++
        val top = counts.indices.maxBy { counts[it] }
        val tr = top / (levels * levels)
        val tg = (top / levels) % levels
        val tb = top % levels
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        for (i in 0 until brightCount) {
            val p = bright[i]
            val r = ((p shr 16) and 0xFF) shr shift
            val g = ((p shr 8) and 0xFF) shr shift
            val b = (p and 0xFF) shr shift
            if (kotlin.math.abs(r - tr) <= 1 && kotlin.math.abs(g - tg) <= 1 && kotlin.math.abs(b - tb) <= 1) {
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; n++
            }
        }
        val cr = sr.toFloat() / n; val cg = sg.toFloat() / n; val cb = sb.toFloat() / n
        val tol2 = config.clusterTolerance * config.clusterTolerance

        // 4. Per-channel median of the cluster members.
        val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
        var kept = 0
        for (i in 0 until brightCount) {
            val p = bright[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val dr = r - cr; val dg = g - cg; val db = b - cb
            if (dr * dr + dg * dg + db * db <= tol2) {
                hr[r]++; hg[g]++; hb[b]++; kept++
            }
        }
        val share = kept.toFloat() / brightCount
        if (kept < config.minSamples || share < config.minDominantShare) {
            return SampleResult.LowConfidence(SampleResult.Reason.NO_DOMINANT_COLOR, kept)
        }
        val color = (0xFF shl 24) or (median(hr, kept) shl 16) or (median(hg, kept) shl 8) or median(hb, kept)
        return SampleResult.Confident(color, kept, share)
    }

    private fun median(hist: IntArray, count: Int): Int {
        val target = (count - 1) / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc > target) return v
        }
        return 255
    }
}
