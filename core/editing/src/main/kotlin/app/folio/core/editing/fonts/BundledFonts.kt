package app.folio.core.editing.fonts

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import app.folio.core.editing.model.FontFamilyId
import app.folio.core.editing.model.TextStyle
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Fonts shipped inside the app (Noto Sans / Noto Serif, SIL OFL 1.1). The same TTF files drive
 * on-screen preview (Android Typeface) and PDF output (embedded, subset), so layout matches exactly.
 */
class BundledFonts(context: Context) {

    private val assets = context.applicationContext.assets
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    fun assetPath(family: FontFamilyId, bold: Boolean): String = when (family) {
        FontFamilyId.SANS -> if (bold) "fonts/NotoSans-Bold.ttf" else "fonts/NotoSans-Regular.ttf"
        FontFamilyId.SERIF -> if (bold) "fonts/NotoSerif-Bold.ttf" else "fonts/NotoSerif-Regular.ttf"
    }

    fun open(family: FontFamilyId, bold: Boolean): InputStream = assets.open(assetPath(family, bold))

    fun typeface(family: FontFamilyId, bold: Boolean): Typeface {
        val path = assetPath(family, bold)
        return typefaces.getOrPut(path) { Typeface.createFromAsset(assets, path) }
    }

    /** A paint configured for [style] at [sizePx]. Italic is a shear, matching the PDF writer. */
    fun paint(style: TextStyle, sizePx: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(style.family, style.bold)
        textSize = sizePx
        color = style.colorArgb
        textSkewX = if (style.italic) -ITALIC_SHEAR else 0f
    }

    /** Layout of a (possibly multi-line) text block, in points, shared by preview and writer. */
    fun layout(text: String, style: TextStyle): TextBlockLayout {
        val paint = paint(style, style.sizePt)
        val lines = text.split('\n')
        val ascent = -paint.fontMetrics.ascent
        val lineHeight = style.sizePt * LINE_HEIGHT
        val width = lines.maxOf { paint.measureText(it) }
        val height = lineHeight * (lines.size - 1) + ascent + paint.fontMetrics.descent
        return TextBlockLayout(lines, width, height, ascent, lineHeight)
    }

    companion object {
        const val LINE_HEIGHT = 1.25f
        /** Horizontal shear for synthetic italics (≈12°). */
        const val ITALIC_SHEAR = 0.21f
    }
}

data class TextBlockLayout(
    val lines: List<String>,
    val width: Float,
    val height: Float,
    /** Distance from the block top to the first baseline. */
    val firstBaseline: Float,
    val lineHeight: Float,
)
