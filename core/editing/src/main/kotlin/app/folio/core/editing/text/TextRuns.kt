package app.folio.core.editing.text

import app.folio.core.editing.model.Box
import kotlin.math.abs
import kotlin.math.hypot

/** A glyph's geometry in display space (top-left origin, points). Pure data so grouping is unit-testable. */
data class GlyphBox(
    val key: Long,
    val text: String,
    val box: Box,
    /** Baseline origin in display space. */
    val originX: Float,
    val originY: Float,
    /** Unit vector of the baseline direction in display space. */
    val dirX: Float,
    val dirY: Float,
    val fontSize: Float,
    val fontKey: String,
    val editable: Boolean,
)

data class RunGroup(val glyphs: List<GlyphBox>, val text: String, val bounds: Box) {
    val editable: Boolean get() = glyphs.all { it.editable }
}

object TextRunGrouper {

    /**
     * Groups glyphs (in content-stream order) into single-line runs: same font and size, same
     * baseline and direction, and horizontally contiguous. A space is inserted where the gap between
     * glyphs is wider than a typical word space but no space glyph exists (common with TJ kerning).
     */
    fun group(glyphs: List<GlyphBox>): List<RunGroup> {
        val runs = ArrayList<RunGroup>()
        var current = ArrayList<GlyphBox>()
        val text = StringBuilder()

        fun flush() {
            if (current.isNotEmpty() && text.isNotBlank()) {
                val bounds = current.map { it.box }.reduce(Box::union)
                runs += RunGroup(current.toList(), text.toString().trim(), bounds)
            }
            current = ArrayList(); text.setLength(0)
        }

        for (g in glyphs) {
            val prev = current.lastOrNull()
            if (prev == null) {
                current += g; text.append(g.text); continue
            }
            val size = maxOf(prev.fontSize, 0.5f)
            val sameStyle = prev.fontKey == g.fontKey && abs(prev.fontSize - g.fontSize) <= size * 0.05f
            val sameDir = abs(prev.dirX - g.dirX) < 0.02f && abs(prev.dirY - g.dirY) < 0.02f
            // Distance from the previous glyph's end, measured along and across the baseline.
            val prevEndX = prev.originX + prev.dirX * advance(prev)
            val prevEndY = prev.originY + prev.dirY * advance(prev)
            val along = (g.originX - prevEndX) * g.dirX + (g.originY - prevEndY) * g.dirY
            val across = abs(-(g.originX - prevEndX) * g.dirY + (g.originY - prevEndY) * g.dirX)
            val contiguous = across < size * 0.25f && along > -size * 0.35f && along < size * 1.2f

            if (sameStyle && sameDir && contiguous) {
                if (along > size * 0.18f && !prev.text.endsWith(" ") && !g.text.startsWith(" ")) text.append(' ')
                current += g; text.append(g.text)
            } else {
                flush()
                current += g; text.append(g.text)
            }
        }
        flush()
        return runs
    }

    private fun advance(g: GlyphBox): Float {
        // Length of the glyph box along the baseline direction.
        val w = g.box.width
        val h = g.box.height
        return if (abs(g.dirX) >= abs(g.dirY)) w else h
    }

    /** The run under a point, preferring the smallest box when runs overlap. */
    fun hitTest(runs: List<RunGroup>, x: Float, y: Float, slop: Float): RunGroup? =
        runs.filter { it.bounds.contains(x, y, slop) }
            .minByOrNull { it.bounds.width * it.bounds.height + distance(it.bounds, x, y) * 1000f }

    private fun distance(b: Box, x: Float, y: Float): Float {
        val dx = maxOf(b.left - x, 0f, x - b.right)
        val dy = maxOf(b.top - y, 0f, y - b.bottom)
        return hypot(dx, dy)
    }
}
