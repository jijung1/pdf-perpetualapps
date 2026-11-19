package app.folio.core.editing.whiteout

import android.graphics.Path
import android.graphics.PointF
import app.folio.core.editing.geometry.PageTransform
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.GlyphKey
import app.folio.core.editing.text.GlyphRecord
import app.folio.core.editing.text.TextAnalyzer
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.IOException
import kotlin.math.abs

/**
 * Walks a page's content — including every form XObject it draws, at any depth — and records each
 * glyph, image and painted path with its display-space bounds and its exact location: the content
 * stream it lives in ([Ctx], one per *use* of a form) and the operator index within that stream.
 */
internal class RedactionScanner(page: PDPage, private val transform: PageTransform) : PDFGraphicsStreamEngine(page) {

    /** One content stream as drawn: the page itself, or one particular use of a form XObject. */
    class Ctx(val id: Int, val parent: Ctx?, val doOp: Int, val form: PDFormXObject?, val resources: PDResources?) {
        var op = -1
        var ordinal = 0
        /** >0 while PDFBox runs operators it synthesised itself (' expands to T* + Tj, " to Tw Tc '). */
        var nesting = 0
    }

    class GlyphHit(val ctx: Ctx, val record: GlyphRecord, val box: Box)
    class ImageHit(val ctx: Ctx, val op: Int, val image: PDImage, val ctm: Matrix, val box: Box)
    class PathHit(val ctx: Ctx, val op: Int, val box: Box)

    val contexts = ArrayList<Ctx>()
    val glyphs = ArrayList<GlyphHit>()
    val images = ArrayList<ImageHit>()
    val paths = ArrayList<PathHit>()

    private val stack = ArrayDeque<Ctx>()

    // Current path bounds in user space.
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var current = PointF(0f, 0f)

    fun scan(page: PDPage): RedactionScanner {
        val root = Ctx(0, null, -1, null, page.resources)
        contexts += root
        stack.addLast(root)
        processPage(page)
        return this
    }

    // operands is nullable: PDFBox passes null for the operators it synthesises (e.g. T* inside ').
    override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
        val ctx = stack.last()
        // Count only operators that are in the content stream. Ones PDFBox runs internally while
        // processing ' and " must not shift the numbering, or edits land on the wrong operator.
        if (ctx.nesting == 0) {
            ctx.op++
            ctx.ordinal = 0
        }
        ctx.nesting++
        try {
            super.processOperator(operator, operands)
        } finally {
            ctx.nesting--
        }
    }

    override fun operatorException(operator: Operator, operands: MutableList<COSBase>?, e: IOException) {
        // Skip malformed operators, as viewers do.
    }

    override fun showForm(form: PDFormXObject) = withChild(form) { super.showForm(form) }

    override fun showTransparencyGroup(form: PDTransparencyGroup) = withChild(form) { super.showTransparencyGroup(form) }

    private inline fun withChild(form: PDFormXObject, block: () -> Unit) {
        val parent = stack.last()
        val ctx = Ctx(contexts.size, parent, parent.op, form, form.resources ?: parent.resources)
        contexts += ctx
        stack.addLast(ctx)
        try {
            block()
        } finally {
            stack.removeLast()
        }
    }

    // ---- text ------------------------------------------------------------------------

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val ctx = stack.last()
        val state = graphicsState
        val text = state.textState
        val ordinal = ctx.ordinal++
        val record = GlyphRecord(
            key = GlyphKey.pack(ctx.op, ordinal),
            operatorIndex = ctx.op,
            ordinal = ordinal,
            font = font,
            code = code,
            unicode = runCatching { font.toUnicode(code) }.getOrNull() ?: "",
            matrix = textRenderingMatrix.clone(),
            advance = displacement.x,
            fontSize = text.fontSize,
            charSpacing = text.characterSpacing,
            wordSpacing = text.wordSpacing,
            horizontalScaling = text.horizontalScaling / 100f,
            colorArgb = 0xFF000000.toInt(),
            renderingMode = text.renderingMode,
            inForm = ctx.form != null,
        )
        glyphs += GlyphHit(ctx, record, TextAnalyzer.toGlyphBox(record, transform).box)
        // Type 3 glyph procedures are deliberately not executed: the glyph is handled as a whole.
    }

    // ---- images ----------------------------------------------------------------------

    override fun drawImage(pdImage: PDImage) {
        val ctx = stack.last()
        val ctm = graphicsState.currentTransformationMatrix.clone()
        images += ImageHit(ctx, ctx.op, pdImage, ctm, unitSquareBounds(ctm))
    }

    private fun unitSquareBounds(m: Matrix): Box {
        val pts = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).map { (u, v) ->
            transform.userToDisplay(m.scaleX * u + m.shearX * v + m.translateX, m.shearY * u + m.scaleY * v + m.translateY)
        }
        return Box(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    // ---- paths -----------------------------------------------------------------------

    private fun extend(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    private fun resetPath() {
        minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
    }

    private fun paint(stroked: Boolean) {
        if (minX <= maxX && minY <= maxY) {
            val ctx = stack.last()
            val pad = if (stroked) {
                val m = graphicsState.currentTransformationMatrix
                graphicsState.lineWidth * maxOf(abs(m.scalingFactorX), abs(m.scalingFactorY)) / 2f
            } else 0f
            val a = transform.userToDisplay(minX - pad, minY - pad)
            val b = transform.userToDisplay(maxX + pad, maxY + pad)
            paths += PathHit(ctx, ctx.op, Box.of(a.first, a.second, b.first, b.second))
        }
        resetPath()
    }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
        listOf(p0, p1, p2, p3).forEach { extend(it.x, it.y) }
        current = PointF(p0.x, p0.y)
    }

    override fun clip(windingRule: Path.FillType) = Unit
    override fun moveTo(x: Float, y: Float) { extend(x, y); current = PointF(x, y) }
    override fun lineTo(x: Float, y: Float) { extend(x, y); current = PointF(x, y) }
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        // The control-point hull contains the curve, so these bounds are conservative.
        extend(x1, y1); extend(x2, y2); extend(x3, y3); current = PointF(x3, y3)
    }
    override fun getCurrentPoint(): PointF = current
    override fun closePath() = Unit
    override fun endPath() = resetPath()
    override fun strokePath() = paint(stroked = true)
    override fun fillPath(windingRule: Path.FillType) = paint(stroked = false)
    override fun fillAndStrokePath(windingRule: Path.FillType) = paint(stroked = true)
    override fun shadingFill(shadingName: COSName) = Unit
}
