package app.folio.core.editing.whiteout

import app.folio.core.editing.model.Box

/**
 * Decides what a whiteout removes. Shared by the on-screen preview and the writer so what the
 * user sees highlighted is exactly what gets deleted.
 */
object RedactionRules {

    /** A glyph is removed if its centre is under a box, or at least this share of it is. */
    const val MIN_GLYPH_OVERLAP = 0.15f

    /** Slop (pt) when deciding that something lies entirely inside a box. */
    const val INSIDE_SLOP = 0.5f

    fun removesGlyph(glyph: Box, boxes: List<Box>): Boolean = boxes.any { removesGlyph(glyph, it) }

    fun removesGlyph(glyph: Box, box: Box): Boolean {
        if (box.contains(glyph.centerX, glyph.centerY)) return true
        val area = glyph.area
        if (area <= 0f) return false
        val overlap = glyph.intersection(box)?.area ?: return false
        return overlap / area >= MIN_GLYPH_OVERLAP
    }

    /** True when [item] lies entirely within one of [boxes]. */
    fun fullyCovered(item: Box, boxes: List<Box>): Boolean = boxes.any { it.containsBox(item, INSIDE_SLOP) }

    fun touches(item: Box, boxes: List<Box>): Boolean = boxes.any { it.intersects(item) }

    /** Glyphs that will be removed although part of them lies outside the box (worth telling the user). */
    fun spillover(glyphs: List<Box>, box: Box): Int = glyphs.count { removesGlyph(it, box) && !box.containsBox(it, INSIDE_SLOP) }
}
