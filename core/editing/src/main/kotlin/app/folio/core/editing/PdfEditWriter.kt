package app.folio.core.editing

import android.content.Context
import app.folio.core.editing.flatten.FlattenReport
import app.folio.core.editing.flatten.Flattener
import app.folio.core.editing.flatten.PageRasterizer
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.editing.geometry.PageTransform
import app.folio.core.editing.ink.InkGeometry
import app.folio.core.editing.ink.PathOp
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.FontFamilyId
import app.folio.core.editing.model.PageEdit
import app.folio.core.editing.model.PlacedSignature
import app.folio.core.editing.model.TextReplacement
import app.folio.core.editing.model.TextStyle
import app.folio.core.editing.model.Whiteout
import app.folio.core.editing.whiteout.RedactionArea
import app.folio.core.editing.whiteout.RedactionReport
import app.folio.core.editing.whiteout.Redactor
import app.folio.core.editing.text.ContentStreamEditor
import app.folio.core.editing.text.GlyphCollector
import app.folio.core.editing.text.GlyphRecord
import app.folio.core.editing.text.TextAnalyzer
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class WriteReport(
    /** Replacements whose original font couldn't render the new text, so a bundled font was used. */
    val fontSubstitutions: Int,
    val glyphsRemoved: Int,
    val flatten: FlattenReport = FlattenReport(),
    val redaction: RedactionReport = RedactionReport(),
    /** Pages converted to images because content under a whiteout couldn't be removed in place. */
    val rasterizedPages: List<Int> = emptyList(),
)

/**
 * Applies edits to a PDF snapshot and writes the result to a new file. The input is never modified.
 *
 * Order (each step sees the result of the previous one):
 *  1. existing-text replacements (glyph removal + new text);
 *  2. flatten form fields and annotations into page content;
 *  3. whiteouts remove everything underneath, verified in memory;
 *  4. overlays: whiteout fills, then added text and stamps, then signatures.
 * The output is re-opened and validated before success is reported.
 */
class PdfEditWriter(private val context: Context, private val fonts: BundledFonts) {

    /** Debug builds explain fallbacks and failures in logcat; release builds never log (and R8 strips it). */
    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private fun diag(message: String, error: Throwable? = null) {
        if (debuggable) android.util.Log.w("PdfEditor", message, error)
    }

    suspend fun write(
        source: File,
        edits: List<PageEdit>,
        output: File,
        flatten: Boolean = true,
        onProgress: (Float) -> Unit = {},
    ): WriteReport = withContext(EditableDocument.pdfboxDispatcher) {
        PdfBox.init(context)
        var substitutions = 0
        var removed = 0
        var redaction = RedactionReport()
        val rasterize = HashSet<Int>()
        val doc = try {
            PDDocument.load(source, MemoryUsageSetting.setupMixed(16L * 1024 * 1024))
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw EditException(EditError.PasswordRequired, e)
        } catch (e: IOException) {
            throw EditException(EditError.Corrupted, e)
        }
        var flattenReport = FlattenReport()
        doc.use {
            if (doc.isEncrypted && !doc.currentAccessPermission.canModify()) throw EditException(EditError.EditingNotPermitted)
            val fontCache = HashMap<String, PDFont>()
            val byPage = edits.filter { it.page in 0 until doc.numberOfPages }.groupBy { it.page }.toSortedMap()
            val steps = byPage.size * 2 + 2
            var done = 0
            fun step() { done++; onProgress(done.toFloat() / steps) }

            // 1. Replacements, against the untouched content (glyph identities refer to it).
            for ((pageIndex, pageEdits) in byPage) {
                coroutineContext.ensureActive()
                val replacements = pageEdits.filterIsInstance<TextReplacement>()
                if (replacements.isEmpty()) continue
                val page = doc.getPage(pageIndex)
                val all = GlyphCollector().collect(page)
                val records = all.filter { !it.inForm }.associateBy { it.key }
                replacements.forEach { verifyTarget(it, records) }
                val keys = replacements.flatMap { it.target.glyphKeys }.toSet()
                removed += ContentStreamEditor.removeGlyphs(doc, page, keys, all)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (r in replacements) {
                        if (r.newText.isBlank()) continue
                        if (drawReplacement(cs, doc, r, records, fontCache)) substitutions++
                    }
                }
                step()
            }

            // 2. Flatten.
            if (flatten) {
                flattenReport = try {
                    Flattener.flatten(doc) { bundled(doc, FontFamilyId.SANS, false, fontCache) }
                } catch (e: IOException) {
                    diag("flattening failed", e)
                    throw EditException(EditError.WriteFailed, e)
                }
            }
            step()

            // 3. Whiteouts: remove what's underneath, then check nothing removable is left.
            //    Whiteout must never make a save fail. If in-place removal hits anything it can't
            //    handle (unusual content, an image it can't decode safely, an error, or leftovers),
            //    the page is put back exactly as it was and converted to an image with the boxes
            //    painted in, which removes everything underneath just as surely.
            for ((pageIndex, pageEdits) in byPage) {
                coroutineContext.ensureActive()
                val whiteouts = pageEdits.filterIsInstance<Whiteout>()
                if (whiteouts.isEmpty()) continue
                val page = doc.getPage(pageIndex)
                val t = TextAnalyzer.pageTransform(page)
                val areas = whiteouts.map { RedactionArea(scaled(it.box, scaleFor(it, t)), it.fillArgb) }
                val originalContents = page.cosObject.getItem(COSName.CONTENTS)
                val report = try {
                    val redactor = Redactor(doc, page, areas)
                    val r = redactor.redact()
                    when {
                        r.needsRasterFallback -> { diag("page ${pageIndex + 1}: content under the box can't be edited in place"); null }
                        !redactor.verify() -> { diag("page ${pageIndex + 1}: content remained under the box after removal"); null }
                        else -> r
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    diag("page ${pageIndex + 1}: in-place removal failed", e)
                    null
                }
                if (report == null) {
                    if (originalContents == null) page.cosObject.removeItem(COSName.CONTENTS) else page.cosObject.setItem(COSName.CONTENTS, originalContents)
                    rasterize += pageIndex
                } else {
                    redaction += report
                }
                step()
            }

            // 4. Overlays.
            for ((pageIndex, pageEdits) in byPage) {
                coroutineContext.ensureActive()
                val overlays = pageEdits.filter { it !is TextReplacement }
                if (overlays.isEmpty()) continue
                val page = doc.getPage(pageIndex)
                val transform = TextAnalyzer.pageTransform(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    val m = transform.displayUpToUser
                    cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                    // Fills first, so text and signatures placed on a whiteout stay visible.
                    overlays.filterIsInstance<Whiteout>().forEach { drawWhiteout(cs, it, transform) }
                    for (e in overlays) when (e) {
                        is AddedText -> drawAddedText(cs, doc, e, transform, fontCache)
                        is PlacedSignature -> drawSignature(cs, e, transform)
                        is Whiteout, is TextReplacement -> Unit
                    }
                    cs.restoreGraphicsState()
                }
                step()
            }
            try {
                output.parentFile?.mkdirs()
                Encryption.keepProtectionForSave(doc)
                doc.save(output)
            } catch (e: IOException) {
                diag("saving failed", e)
                output.delete()
                throw EditException(if (e.isNoSpace()) EditError.InsufficientStorage else EditError.WriteFailed, e)
            } catch (e: RuntimeException) {
                diag("saving failed", e)
                output.delete()
                throw EditException(EditError.WriteFailed, e)
            }
            validate(output, doc.numberOfPages)
        }
        var rasterized = emptyList<Int>()
        if (rasterize.isNotEmpty()) {
            rasterized = try {
                PageRasterizer.rasterize(output, rasterize, output.parentFile ?: context.cacheDir)
            } catch (e: Throwable) {
                diag("converting whited-out pages to images failed", e)
                output.delete()
                throw EditException(if (e is IOException && e.isNoSpace()) EditError.InsufficientStorage else EditError.WriteFailed, e)
            }
            validate(output, null)
        }
        onProgress(1f)
        WriteReport(substitutions, removed, flattenReport, redaction, rasterized)
    }

    /** Re-open the written file; anything unreadable is deleted and reported as a failure. */
    private fun validate(file: File, expectedPages: Int?) {
        try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use {
                if (expectedPages != null && it.numberOfPages != expectedPages) throw IOException("page count mismatch")
            }
        } catch (e: IOException) {
            file.delete()
            throw EditException(EditError.WriteFailed, e)
        }
    }

    private fun scaled(b: Box, s: Float) = if (s == 1f) b else Box(b.left * s, b.top * s, b.right * s, b.bottom * s)

    private fun drawWhiteout(cs: PDPageContentStream, w: Whiteout, t: PageTransform) {
        val s = scaleFor(w, t)
        val b = scaled(w.box, s)
        cs.setNonStrokingColor(red(w.fillArgb), green(w.fillArgb), blue(w.fillArgb))
        cs.addRect(b.left, t.displayHeight - b.bottom, b.width, b.height)
        cs.fill()
    }

    private fun verifyTarget(r: TextReplacement, records: Map<Long, GlyphRecord>) {
        val glyphs = r.target.glyphKeys.map { records[it] ?: throw EditException(EditError.DocumentChanged) }
        val actual = glyphs.joinToString("") { it.unicode }.filterNot(Char::isWhitespace)
        val expected = r.target.originalText.filterNot(Char::isWhitespace)
        if (actual != expected) throw EditException(EditError.DocumentChanged)
    }

    /** Returns true if a bundled font had to be substituted for the original. */
    private fun drawReplacement(
        cs: PDPageContentStream,
        doc: PDDocument,
        r: TextReplacement,
        records: Map<Long, GlyphRecord>,
        cache: HashMap<String, PDFont>,
    ): Boolean {
        val text = r.newText.replace('\n', ' ')
        val target = r.target
        val m = target.matrix
        var matrix = Matrix(m[0], m[1], m[2], m[3], m[4], m[5])
        val originalFont = target.glyphKeys.firstOrNull()?.let { records[it]?.font }

        val style = r.style
        val useOriginal = style == null && originalFont != null && canRender(originalFont, text)
        val font: PDFont
        val color: Int
        if (useOriginal) {
            font = originalFont!!
            color = target.colorArgb
        } else {
            val s = style ?: TextStyle(
                family = if (target.serifHint) FontFamilyId.SERIF else FontFamilyId.SANS,
                sizePt = target.fontSizePt,
                colorArgb = target.colorArgb,
                bold = target.boldHint,
                italic = target.italicHint,
            )
            font = bundled(doc, s.family, s.bold, cache)
            color = s.colorArgb
            val scale = s.sizePt / target.fontSizePt.coerceAtLeast(0.1f)
            if (scale != 1f) matrix = Matrix(scale, 0f, 0f, scale, 0f, 0f).multiply(matrix)
            if (s.italic) matrix = Matrix(1f, 0f, BundledFonts.ITALIC_SHEAR, 1f, 0f, 0f).multiply(matrix)
        }
        cs.beginText()
        cs.setFont(font, 1f)
        cs.setTextMatrix(matrix)
        cs.setNonStrokingColor(red(color), green(color), blue(color))
        cs.showText(text)
        cs.endText()
        return !useOriginal && style == null
    }

    /**
     * True when [font] can encode every character, actually contains the glyphs (subset fonts often
     * don't), and gives each visible character a width. Subset fonts often list widths only for the
     * characters they originally used; with a zero width, viewers stack the new letters on top of
     * each other.
     */
    private fun canRender(font: PDFont, text: String): Boolean = try {
        if (!GlyphCollector.canReuseForNewText(font)) throw IOException("type3")
        var ok = true
        var i = 0
        while (ok && i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += ch.length
            val input = ByteArrayInputStream(font.encode(ch))
            while (ok && input.available() > 0) {
                val code = font.readCode(input)
                if (font is PDVectorFont && !(font as PDVectorFont).hasGlyph(code)) ok = false
                if (!Character.isWhitespace(cp) && font.getWidth(code) <= 0f) ok = false
            }
        }
        ok
    } catch (e: Exception) {
        false
    }

    private fun bundled(doc: PDDocument, family: FontFamilyId, bold: Boolean, cache: HashMap<String, PDFont>): PDFont {
        val path = fonts.assetPath(family, bold)
        return cache.getOrPut(path) { fonts.open(family, bold).use { PDType0Font.load(doc, it, true) } }
    }

    private fun drawAddedText(
        cs: PDPageContentStream,
        doc: PDDocument,
        e: AddedText,
        t: PageTransform,
        cache: HashMap<String, PDFont>,
    ) {
        if (e.text.isBlank()) return
        val s = scaleFor(e, t)
        val layout = fonts.layout(e.text, e.style)
        val font = bundled(doc, e.style.family, e.style.bold, cache)
        val size = e.style.sizePt * s
        val shear = if (e.style.italic) BundledFonts.ITALIC_SHEAR else 0f
        cs.setNonStrokingColor(red(e.style.colorArgb), green(e.style.colorArgb), blue(e.style.colorArgb))
        layout.lines.forEachIndexed { i, line ->
            if (line.isEmpty()) return@forEachIndexed
            val baselineDown = (e.y + layout.firstBaseline + i * layout.lineHeight) * s
            val x = e.x * s
            val yUp = t.displayHeight - baselineDown
            cs.beginText()
            cs.setFont(font, size)
            cs.setTextMatrix(Matrix(1f, 0f, shear, 1f, x, yUp))
            cs.showText(line)
            cs.endText()
        }
    }

    private fun drawSignature(cs: PDPageContentStream, sig: PlacedSignature, t: PageTransform) {
        val s = scaleFor(sig, t)
        cs.setStrokingColor(red(sig.colorArgb), green(sig.colorArgb), blue(sig.colorArgb))
        cs.setLineWidth((sig.ink.widthRatio * sig.height * s).coerceAtLeast(0.2f))
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        for (stroke in sig.ink.strokes) {
            val ops = InkGeometry.path(stroke.points) { nx, ny ->
                val x = (sig.x + nx * sig.width) * s
                val yDown = (sig.y + ny * sig.height) * s
                x to (t.displayHeight - yDown)
            }
            if (ops.isEmpty()) continue
            for (op in ops) when (op) {
                is PathOp.MoveTo -> cs.moveTo(op.x, op.y)
                is PathOp.LineTo -> cs.lineTo(op.x, op.y)
                is PathOp.CubicTo -> cs.curveTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
            }
            cs.stroke()
        }
    }

    private fun scaleFor(e: PageEdit, t: PageTransform): Float =
        if (e.refPageWidth > 0f) t.displayWidth / e.refPageWidth else 1f

    private fun red(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun green(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun blue(c: Int) = (c and 0xFF) / 255f
}
