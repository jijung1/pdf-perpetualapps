package app.folio.core.editing.text

import app.folio.core.editing.geometry.PageTransform
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.TextRunRef
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import kotlin.math.hypot

/** Why a piece of existing text can't be edited in place. */
enum class EditBlocker {
    /** Text lives inside an embedded form object, not the page's own content. */
    EMBEDDED_OBJECT,
    /** Type 3 (drawn) or vertical fonts. */
    UNSUPPORTED_FONT,
    /** Text used as a clipping path. */
    CLIPPING_TEXT,
}

data class EditableTextRun(
    val ref: TextRunRef,
    val blocker: EditBlocker?,
) {
    val bounds: Box get() = ref.bounds
    val text: String get() = ref.originalText
}

data class PageTextLayer(
    val pageIndex: Int,
    val displayWidth: Float,
    val runs: List<EditableTextRun>,
) {
    /** No text layer at all — typical for scanned pages. */
    val isEmpty: Boolean get() = runs.isEmpty()
}

object TextAnalyzer {

    fun pageTransform(page: PDPage): PageTransform {
        val crop = page.cropBox
        return PageTransform(crop.lowerLeftX, crop.lowerLeftY, crop.upperRightX, crop.upperRightY, page.rotation)
    }

    fun analyze(page: PDPage, pageIndex: Int): PageTextLayer {
        val transform = pageTransform(page)
        val records = GlyphCollector().collect(page)
            .filter { it.renderingMode.isFill || it.renderingMode.isStroke } // skip invisible text (e.g. OCR layers)

        val boxes = records.map { r -> toGlyphBox(r, transform) }
        val byKey = records.associateBy { it.key }
        val groups = TextRunGrouper.group(boxes)

        val runs = groups.mapNotNull { group ->
            val recs = group.glyphs.mapNotNull { byKey[it.key] }
            if (recs.isEmpty()) return@mapNotNull null
            val first = recs.first()
            val blocker = when {
                recs.any { it.inForm } -> EditBlocker.EMBEDDED_OBJECT
                recs.any { !GlyphCollector.isEditableFont(it.font) } -> EditBlocker.UNSUPPORTED_FONT
                recs.any { it.renderingMode.isClip } -> EditBlocker.CLIPPING_TEXT
                else -> null
            }
            val m = first.matrix
            EditableTextRun(
                ref = TextRunRef(
                    bounds = group.bounds,
                    originalText = group.text,
                    glyphKeys = if (blocker == null) recs.map { it.key } else emptyList(),
                    matrix = listOf(m.scaleX, m.shearY, m.shearX, m.scaleY, m.translateX, m.translateY),
                    fontSizePt = effectiveSize(first),
                    colorArgb = first.colorArgb,
                    fontName = fontName(first.font),
                    serifHint = isSerif(first.font),
                    boldHint = isBold(first.font),
                    italicHint = isItalic(first.font),
                ),
                blocker = blocker,
            )
        }
        return PageTextLayer(pageIndex, transform.displayWidth, runs)
    }

    fun toGlyphBox(r: GlyphRecord, t: PageTransform): GlyphBox {
        val m = r.matrix
        val desc = r.font.fontDescriptor
        val ascent = (desc?.ascent?.takeIf { it > 0 } ?: 800f).coerceIn(500f, 1100f) / 1000f
        val descent = (desc?.descent?.takeIf { it < 0 } ?: -200f).coerceIn(-400f, 0f) / 1000f
        val adv = if (r.advance > 0f) r.advance else 0.5f

        // Glyph box corners in text space → user space → display space.
        fun map(u: Float, v: Float): Pair<Float, Float> {
            val x = m.scaleX * u + m.shearX * v + m.translateX
            val y = m.shearY * u + m.scaleY * v + m.translateY
            return t.userToDisplay(x, y)
        }
        val corners = listOf(map(0f, descent), map(adv, descent), map(adv, ascent), map(0f, ascent))
        val box = Box(
            corners.minOf { it.first }, corners.minOf { it.second },
            corners.maxOf { it.first }, corners.maxOf { it.second },
        )
        val origin = map(0f, 0f)
        val along = map(1f, 0f)
        val dx = along.first - origin.first
        val dy = along.second - origin.second
        val len = hypot(dx, dy).takeIf { it > 1e-4f } ?: 1f
        return GlyphBox(
            key = r.key,
            text = r.unicode,
            box = box,
            originX = origin.first,
            originY = origin.second,
            dirX = dx / len,
            dirY = dy / len,
            fontSize = effectiveSize(r),
            fontKey = fontName(r.font) + "@" + System.identityHashCode(r.font),
            editable = !r.inForm,
        )
    }

    /** Visual font size in points = length of the matrix's vertical axis. */
    fun effectiveSize(r: GlyphRecord): Float {
        val m = r.matrix
        return hypot(m.shearX, m.scaleY).coerceAtLeast(0.1f)
    }

    fun fontName(font: PDFont): String = (font.name ?: "Unknown").substringAfter('+')

    fun isSerif(font: PDFont): Boolean {
        val n = fontName(font).lowercase()
        if (listOf("sans", "arial", "helvetica", "verdana", "calibri", "segoe", "roboto", "inter").any { it in n }) return false
        if (listOf("times", "serif", "georgia", "garamond", "cambria", "book", "minion", "palatino").any { it in n }) return true
        return font.fontDescriptor?.isSerif == true
    }

    fun isBold(font: PDFont): Boolean {
        val n = fontName(font).lowercase()
        return "bold" in n || "black" in n || "heavy" in n || (font.fontDescriptor?.fontWeight ?: 0f) >= 600f ||
            font.fontDescriptor?.isForceBold == true
    }

    fun isItalic(font: PDFont): Boolean {
        val n = fontName(font).lowercase()
        return "italic" in n || "oblique" in n || font.fontDescriptor?.isItalic == true
    }
}
