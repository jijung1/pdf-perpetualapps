package app.folio.core.editing.whiteout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.folio.core.editing.geometry.PageTransform
import app.folio.core.editing.model.Box
import app.folio.core.editing.text.ContentStreamEditor
import app.folio.core.editing.text.GlyphRecord
import app.folio.core.editing.text.TextAnalyzer
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.util.Locale

/** A whiteout area in display points with the colour used to paint over removed image pixels. */
data class RedactionArea(val box: Box, val fillArgb: Int)

data class RedactionReport(
    val glyphsRemoved: Int = 0,
    val imagesRemoved: Int = 0,
    val imagesRedacted: Int = 0,
    val pathsRemoved: Int = 0,
    /** Some content under a box couldn't be edited in place; the page must be converted to an image. */
    val needsRasterFallback: Boolean = false,
) {
    operator fun plus(o: RedactionReport) = RedactionReport(
        glyphsRemoved + o.glyphsRemoved, imagesRemoved + o.imagesRemoved, imagesRedacted + o.imagesRedacted,
        pathsRemoved + o.pathsRemoved, needsRasterFallback || o.needsRasterFallback,
    )
}

/**
 * Removes the content under whiteout areas from one page — for real, not by covering it:
 *  - glyphs under a box are deleted from their content stream (page or nested form XObject);
 *  - images entirely under a box are no longer drawn; partly covered images get the covered
 *    pixels painted over and are re-encoded (shared images are copied, never edited in place);
 *  - painted paths entirely under a box are no longer painted;
 *  - everything left is clipped away inside the boxes, so nothing original can show through.
 * Form XObjects are copied on write, so other pages (or other uses) that share them are unaffected.
 */
internal class Redactor(private val doc: PDDocument, private val page: PDPage, private val areas: List<RedactionArea>) {

    private val transform: PageTransform = TextAnalyzer.pageTransform(page)
    private val boxes = areas.map { it.box }

    private sealed interface ImageAction {
        data object Remove : ImageAction
        class Redact(val hit: RedactionScanner.ImageHit) : ImageAction
    }

    fun redact(): RedactionReport {
        if (areas.isEmpty()) return RedactionReport()
        val scan = RedactionScanner(page, transform).scan(page)

        // ---- decide ----------------------------------------------------------------
        val glyphsByCtxOp = HashMap<Int, MutableMap<Int, MutableList<GlyphRecord>>>()
        val removeByCtxOp = HashMap<Int, MutableMap<Int, MutableSet<Int>>>()
        for (g in scan.glyphs) {
            glyphsByCtxOp.getOrPut(g.ctx.id) { HashMap() }.getOrPut(g.record.operatorIndex) { ArrayList() } += g.record
            if (RedactionRules.removesGlyph(g.box, boxes)) {
                removeByCtxOp.getOrPut(g.ctx.id) { HashMap() }.getOrPut(g.record.operatorIndex) { HashSet() } += g.record.ordinal
            }
        }
        val imageActions = HashMap<Int, MutableMap<Int, ImageAction>>()
        for (img in scan.images) {
            val action = when {
                RedactionRules.fullyCovered(img.box, boxes) -> ImageAction.Remove
                RedactionRules.touches(img.box, boxes) -> ImageAction.Redact(img)
                else -> null
            } ?: continue
            imageActions.getOrPut(img.ctx.id) { HashMap() }[img.op] = action
        }
        val pathOps = HashMap<Int, MutableSet<Int>>()
        for (p in scan.paths) {
            if (RedactionRules.fullyCovered(p.box, boxes)) pathOps.getOrPut(p.ctx.id) { HashSet() } += p.op
        }

        val affected = HashSet<Int>()
        fun mark(ctx: RedactionScanner.Ctx?) {
            var c = ctx
            while (c != null && affected.add(c.id)) c = c.parent
        }
        val byId = scan.contexts.associateBy { it.id }
        (removeByCtxOp.keys + imageActions.keys + pathOps.keys).forEach { mark(byId[it]) }

        // ---- rewrite, deepest first so children's copies exist before parents reference them ----
        var glyphsRemoved = 0
        var imagesRemoved = 0
        var imagesRedacted = 0
        var pathsRemoved = 0
        var fallback = false
        val childCopyName = HashMap<Int, COSName>() // ctx id → resource name of its rewritten copy
        val childrenAt = scan.contexts.filter { it.parent != null && it.id in affected }.groupBy { it.parent!!.id }

        for (ctx in scan.contexts.sortedByDescending { it.id }) {
            if (ctx.id !in affected) continue
            val resources = targetResources(ctx)
            val glyphs = glyphsByCtxOp[ctx.id].orEmpty()
            val removals = removeByCtxOp[ctx.id].orEmpty()
            val images = imageActions[ctx.id].orEmpty()
            val paints = pathOps[ctx.id].orEmpty()
            val children = childrenAt[ctx.id].orEmpty().associateBy { it.doOp }

            val parser = if (ctx.form != null) PDFStreamParser(ctx.form) else PDFStreamParser(page)
            parser.parse()
            val rewritten = ContentStreamEditor.rewriteTokens(parser.tokens) { op, operator, operands ->
                removals[op]?.let { ordinals ->
                    val list = glyphs[op].orEmpty().sortedBy { it.ordinal }
                    val (tokens, n) = ContentStreamEditor.removeFromOperator(operator, operands, list, ordinals)
                    glyphsRemoved += n
                    return@rewriteTokens tokens
                }
                images[op]?.let { action ->
                    return@rewriteTokens when (action) {
                        ImageAction.Remove -> { imagesRemoved++; emptyList() }
                        is ImageAction.Redact -> {
                            val replacement = redactPixels(action.hit)
                            if (replacement == null) {
                                fallback = true
                                null // keep it; the page is rasterised after saving
                            } else {
                                imagesRedacted++
                                listOf(resources.add(replacement), Operator.getOperator("Do"))
                            }
                        }
                    }
                }
                if (op in paints) {
                    pathsRemoved++
                    return@rewriteTokens listOf(Operator.getOperator("n"))
                }
                children[op]?.let { child ->
                    childCopyName[child.id]?.let { name -> return@rewriteTokens listOf(name, Operator.getOperator("Do")) }
                }
                null
            }

            if (ctx.form == null) {
                page.setContents(ContentStreamEditor.toStream(doc, rewritten))
            } else {
                val copy = copyForm(ctx.form, rewritten, resources)
                val parentRes = targetResources(ctx.parent!!)
                childCopyName[ctx.id] = parentRes.add(copy)
            }
        }

        clipOut()
        return RedactionReport(glyphsRemoved, imagesRemoved, imagesRedacted, pathsRemoved, fallback)
    }

    /** Checks the page in memory: nothing removable may remain under a box. */
    fun verify(): Boolean {
        val scan = RedactionScanner(page, transform).scan(page)
        if (scan.glyphs.any { RedactionRules.removesGlyph(it.box, boxes) }) return false
        if (scan.images.any { RedactionRules.fullyCovered(it.box, boxes) }) return false
        return true
    }

    // ---- helpers -------------------------------------------------------------------

    private val resourceCache = HashMap<Int, PDResources>()

    /** The resources a rewritten stream for [ctx] uses (and where new names are registered). */
    private fun targetResources(ctx: RedactionScanner.Ctx): PDResources = resourceCache.getOrPut(ctx.id) {
        if (ctx.form == null) {
            page.resources ?: PDResources().also { page.resources = it }
        } else {
            ctx.form.resources ?: ctx.resources ?: PDResources()
        }
    }

    private fun copyForm(original: PDFormXObject, tokens: List<Any>, resources: PDResources): PDFormXObject {
        val stream: PDStream = ContentStreamEditor.toStream(doc, tokens)
        val dict: COSStream = stream.cosObject
        val skip = setOf(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.F, COSName.F_FILTER, COSName.F_DECODE_PARMS, COSName.DL)
        for ((key, value) in original.cosObject.entrySet()) {
            if (key !in skip) dict.setItem(key, value)
        }
        dict.setItem(COSName.RESOURCES, resources.cosObject)
        return PDFormXObject(dict)
    }

    /**
     * Paints the covered part of an image with the box colour and re-encodes it.
     * Returns null when the image can't be decoded here (e.g. JBIG2 / JPEG 2000 / stencil masks).
     */
    private fun redactPixels(hit: RedactionScanner.ImageHit): PDImageXObject? {
        val image: PDImage = hit.image
        if (image.isStencil || !ImageColorSpaces.isSafeToDecode(image)) return null
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_REDACT_PIXELS) return null
        val decoded: Bitmap = try {
            image.image ?: return null
        } catch (_: Throwable) {
            return null
        }
        val bitmap = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap !== decoded) decoded.recycle()
        try {
            val inverse = invert(hit.ctm) ?: return null
            val canvas = Canvas(bitmap)
            for (area in areas) {
                if (!area.box.intersects(hit.box)) continue
                val corners = listOf(
                    area.box.left to area.box.top, area.box.right to area.box.top,
                    area.box.right to area.box.bottom, area.box.left to area.box.bottom,
                ).map { (dx, dy) ->
                    val (ux, uy) = transform.displayToUser(dx, dy)
                    val u = inverse[0] * ux + inverse[2] * uy + inverse[4]
                    val v = inverse[1] * ux + inverse[3] * uy + inverse[5]
                    (u * bitmap.width) to ((1f - v) * bitmap.height)
                }
                val path = Path().apply {
                    moveTo(corners[0].first, corners[0].second)
                    corners.drop(1).forEach { lineTo(it.first, it.second) }
                    close()
                }
                val paint = Paint().apply {
                    color = area.fillArgb or 0xFF000000.toInt()
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = 2f // cover the resampled edge
                    isAntiAlias = false
                }
                canvas.drawPath(path, paint)
            }
            val lossy = image.suffix == "jpg" || image.suffix == "jpx"
            return if (lossy) JPEGFactory.createFromImage(doc, bitmap, 0.92f) else LosslessFactory.createFromImage(doc, bitmap)
        } catch (_: Throwable) {
            return null
        } finally {
            bitmap.recycle()
        }
    }

    /** Inverse of [m] as [a b c d e f], or null if singular. */
    private fun invert(m: Matrix): FloatArray? {
        val a = m.scaleX; val b = m.shearY; val c = m.shearX; val d = m.scaleY; val e = m.translateX; val f = m.translateY
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-9f) return null
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return floatArrayOf(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    /**
     * Wraps the page's existing content in clips that exclude each box (one even-odd clip per box;
     * successive clips intersect, so overlapping boxes are handled correctly).
     */
    private fun clipOut() {
        val media = page.mediaBox
        val ox = media.lowerLeftX - 1000f; val oy = media.lowerLeftY - 1000f
        val ow = media.width + 2000f; val oh = media.height + 2000f
        val sb = StringBuilder("q\n")
        for (b in boxes) {
            val pts = listOf(b.left to b.top, b.right to b.bottom).map { (x, y) -> transform.displayToUser(x, y) }
            val x0 = minOf(pts[0].first, pts[1].first); val x1 = maxOf(pts[0].first, pts[1].first)
            val y0 = minOf(pts[0].second, pts[1].second); val y1 = maxOf(pts[0].second, pts[1].second)
            sb.append(fmt(ox)).append(' ').append(fmt(oy)).append(' ').append(fmt(ow)).append(' ').append(fmt(oh)).append(" re ")
            sb.append(fmt(x0)).append(' ').append(fmt(y0)).append(' ').append(fmt(x1 - x0)).append(' ').append(fmt(y1 - y0)).append(" re W* n\n")
        }
        val prefix = rawStream(sb.toString())
        val suffix = rawStream("Q\n")
        val contents = COSArray()
        contents.add(prefix)
        when (val existing: COSBase? = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
            is COSArray -> for (i in 0 until existing.size()) contents.add(existing.get(i))
            is COSStream -> contents.add(existing)
            else -> Unit
        }
        contents.add(suffix)
        page.cosObject.setItem(COSName.CONTENTS, contents)
    }

    private fun rawStream(text: String): COSStream {
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(text.toByteArray(Charsets.US_ASCII)) }
        return stream.cosObject
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.3f", v)

    companion object {
        /** Images above this are too large to decode safely on a phone; the page is rasterised instead. */
        const val MAX_REDACT_PIXELS = 36_000_000L
    }
}
