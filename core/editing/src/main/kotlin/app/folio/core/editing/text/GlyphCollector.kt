package app.folio.core.editing.text

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.contentstream.operator.text.BeginText
import com.tom_roush.pdfbox.contentstream.operator.text.EndText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveTextSetLeading
import com.tom_roush.pdfbox.contentstream.operator.text.NextLine
import com.tom_roush.pdfbox.contentstream.operator.text.SetCharSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.SetFontAndSize
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextHorizontalScaling
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextLeading
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRenderingMode
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRise
import com.tom_roush.pdfbox.contentstream.operator.text.SetWordSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.ShowText
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextAdjusted
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLine
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLineAndSpace
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import app.folio.core.editing.model.GlyphKey
import java.io.IOException

/** Everything known about one shown glyph. Glyphs inside form XObjects have [inForm] = true. */
class GlyphRecord(
    val key: Long,
    val operatorIndex: Int,
    val ordinal: Int,
    val font: PDFont,
    val code: Int,
    val unicode: String,
    /** Text rendering matrix (includes font size, horizontal scaling, rise, text matrix and CTM). */
    val matrix: Matrix,
    /** Glyph advance in text space for a unit font size (glyph width / 1000). */
    val advance: Float,
    val fontSize: Float,
    val charSpacing: Float,
    val wordSpacing: Float,
    val horizontalScaling: Float,
    val colorArgb: Int,
    val renderingMode: RenderingMode,
    val inForm: Boolean,
)

/**
 * Walks a page's content stream with PDFBox and records every glyph together with a stable
 * identity: the index of the text-showing operator in the page's top-level content stream and the
 * glyph's ordinal within that operator. [ContentStreamEditor] uses the same identities to delete glyphs.
 */
class GlyphCollector : PDFStreamEngine() {

    val glyphs = ArrayList<GlyphRecord>()

    private var operatorIndex = -1
    private var ordinal = 0
    private var formDepth = 0
    /** >0 while PDFBox runs operators it synthesised itself (' expands to T* + Tj, " to Tw Tc '). */
    private var nesting = 0

    init {
        listOf(
            BeginText(), EndText(), SetFontAndSize(), SetTextHorizontalScaling(), SetTextLeading(),
            SetTextRenderingMode(), SetTextRise(), SetCharSpacing(), SetWordSpacing(), MoveText(),
            MoveTextSetLeading(), NextLine(), SetMatrix(), ShowText(), ShowTextAdjusted(), ShowTextLine(),
            ShowTextLineAndSpace(), Save(), Restore(), Concatenate(), SetGraphicsStateParameters(), DrawObject(),
            SetNonStrokingColor(), SetNonStrokingColorN(), SetNonStrokingColorSpace(),
            SetNonStrokingDeviceRGBColor(), SetNonStrokingDeviceGrayColor(), SetNonStrokingDeviceCMYKColor(),
        ).forEach(::addOperator)
    }

    fun collect(page: PDPage): List<GlyphRecord> {
        glyphs.clear(); operatorIndex = -1; ordinal = 0; formDepth = 0; nesting = 0
        processPage(page)
        return glyphs
    }

    // operands is nullable: PDFBox passes null for the operators it synthesises (e.g. T* inside ').
    override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
        // Count only operators that are in the page's content stream: not those inside forms, and
        // not the ones PDFBox runs internally while processing ' and ".
        if (formDepth == 0 && nesting == 0) {
            operatorIndex++
            ordinal = 0
        }
        nesting++
        try {
            super.processOperator(operator, operands)
        } finally {
            nesting--
        }
    }

    override fun showForm(form: PDFormXObject) {
        // A form's own operators run with a fresh nesting count; they're excluded by formDepth.
        val saved = nesting
        nesting = 0
        formDepth++
        try {
            super.showForm(form)
        } finally {
            formDepth--
            nesting = saved
        }
    }

    override fun operatorException(operator: Operator, operands: MutableList<COSBase>?, e: IOException) {
        // Malformed operators are skipped (as viewers do) rather than aborting analysis.
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val state = graphicsState
        val text = state.textState
        val color = runCatching { state.nonStrokingColor.toRGB() or 0xFF000000.toInt() }.getOrDefault(0xFF000000.toInt())
        val unicode = runCatching { font.toUnicode(code) }.getOrNull() ?: ""
        val inForm = formDepth > 0
        val ord = if (inForm) -1 else ordinal++
        glyphs += GlyphRecord(
            key = if (inForm) -1L else GlyphKey.pack(operatorIndex, ord),
            operatorIndex = if (inForm) -1 else operatorIndex,
            ordinal = ord,
            font = font,
            code = code,
            unicode = unicode,
            matrix = textRenderingMatrix.clone(),
            advance = displacement.x,
            fontSize = text.fontSize,
            charSpacing = text.characterSpacing,
            wordSpacing = text.wordSpacing,
            horizontalScaling = text.horizontalScaling / 100f,
            colorArgb = color,
            renderingMode = text.renderingMode,
            inForm = inForm,
        )
    }

    companion object {
        /**
         * Glyphs can be removed for any horizontal font, Type 3 included (TJ adjustments work in text
         * space for every font type). Vertical writing is not supported.
         */
        fun isEditableFont(font: PDFont): Boolean = !font.isVertical

        /** Whether new text could ever be drawn with this font (Type 3 glyphs are procedures, not outlines). */
        fun canReuseForNewText(font: PDFont): Boolean = font !is PDType3Font
    }
}
