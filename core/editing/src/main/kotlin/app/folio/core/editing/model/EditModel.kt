package app.folio.core.editing.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Coordinates: "display points" — PDF points (1/72 in) on the page as the user sees it
 * (after /Rotate), origin top-left, y pointing down. Each edit records the page width it was
 * authored against ([PageEdit.refPageWidth]) so the writer can rescale if engines disagree slightly.
 */

@Serializable
enum class FontFamilyId { SANS, SERIF }

@Serializable
data class TextStyle(
    val family: FontFamilyId = FontFamilyId.SANS,
    val sizePt: Float = 14f,
    val colorArgb: Int = 0xFF1A1C21.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
)

@Serializable
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float, slop: Float = 0f) =
        x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    val area: Float get() = if (width > 0f && height > 0f) width * height else 0f
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun intersection(o: Box): Box? =
        if (!intersects(o)) null else Box(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))
    fun containsBox(o: Box, slop: Float = 0f) =
        o.left >= left - slop && o.right <= right + slop && o.top >= top - slop && o.bottom <= bottom + slop
    fun inset(d: Float) = Box(left + d, top + d, right - d, bottom - d)

    companion object {
        /** A box from two arbitrary corners. */
        fun of(x0: Float, y0: Float, x1: Float, y1: Float) = Box(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }
}

/** A single pen stroke; points are flattened (x0, y0, x1, y1, …) and normalised to 0..1 of the ink box. */
@Serializable
data class Stroke(val points: List<Float>)

/**
 * Vector ink normalised to its own bounding box.
 * [aspect] = width / height of the original drawing; [widthRatio] = pen width / box height.
 */
@Serializable
data class Ink(val strokes: List<Stroke>, val aspect: Float, val widthRatio: Float)

@Serializable
sealed interface PageEdit {
    val id: String
    val page: Int
    val refPageWidth: Float
}

/** New text placed on the page. This is an addition, never a modification of existing content. */
@Serializable
@SerialName("added_text")
data class AddedText(
    override val id: String,
    override val page: Int,
    override val refPageWidth: Float,
    val x: Float,
    val y: Float,
    val text: String,
    val style: TextStyle,
) : PageEdit

/** A handwritten signature drawn as vector strokes (not a cryptographic digital signature). */
@Serializable
@SerialName("signature")
data class PlacedSignature(
    override val id: String,
    override val page: Int,
    override val refPageWidth: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val ink: Ink,
    val colorArgb: Int,
) : PageEdit {
    val bounds: Box get() = Box(x, y, x + width, y + height)
}

/**
 * Identifies a run of existing text in the page content stream.
 * [glyphKeys] are stable (operatorIndex, glyphOrdinal) pairs packed into longs; [originalText] is used
 * to verify the document hasn't changed between analysis and save.
 */
@Serializable
data class TextRunRef(
    val bounds: Box,
    val originalText: String,
    val glyphKeys: List<Long>,
    /** Text rendering matrix of the first glyph in user space (a b c d e f), font size baked in. */
    val matrix: List<Float>,
    val fontSizePt: Float,
    val colorArgb: Int,
    val fontName: String,
    val serifHint: Boolean,
    val boldHint: Boolean,
    val italicHint: Boolean,
)

/**
 * Replaces (or, with empty [newText], deletes) an existing run of text. The original glyphs are
 * removed from the content stream; the new text is written in their place.
 */
@Serializable
@SerialName("text_replacement")
data class TextReplacement(
    override val id: String,
    override val page: Int,
    override val refPageWidth: Float,
    val target: TextRunRef,
    val newText: String,
    /** Null = keep the original font, size and colour where the font allows it. */
    val style: TextStyle? = null,
) : PageEdit

/** Where a whiteout's fill colour came from. */
@Serializable
enum class FillSource {
    /** Sampled from the page background around the box. */
    AUTO,
    WHITE,
    /** Chosen by the user (swatch or eyedropper); never overwritten by re-sampling. */
    MANUAL,
}

/**
 * Permanently removes everything under [box] when saved (text, image pixels, drawings), then
 * paints the box with [fillArgb].
 */
@Serializable
@SerialName("whiteout")
data class Whiteout(
    override val id: String,
    override val page: Int,
    override val refPageWidth: Float,
    val box: Box,
    val fillArgb: Int = WHITE,
    val fillSource: FillSource = FillSource.AUTO,
    /** For [FillSource.AUTO]: true when the background was matched; false means it fell back to white. */
    val autoMatched: Boolean = false,
) : PageEdit {
    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}

object GlyphKey {
    fun pack(operatorIndex: Int, ordinal: Int): Long = (operatorIndex.toLong() shl 24) or (ordinal.toLong() and 0xFFFFFF)
    fun operator(key: Long): Int = (key shr 24).toInt()
    fun ordinal(key: Long): Int = (key and 0xFFFFFF).toInt()
}
