package app.folio.core.editing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.FillSource
import app.folio.core.editing.model.TextStyle
import app.folio.core.editing.model.Whiteout
import app.folio.core.editing.text.TextAnalyzer
import app.folio.core.editing.whiteout.RedactionScanner
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round trips through the real writer: whiteout must *remove* content (text, form text, images,
 * invisible OCR text), and saving must flatten forms while keeping links.
 * Coordinates: Letter page, display space = top-left origin, so display y = 792 − PDF y.
 */
@RunWith(AndroidJUnit4::class)
class WhiteoutFlattenTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var writer: PdfEditWriter

    @Before
    fun setUp() {
        PdfBox.init(context)
        writer = PdfEditWriter(context, BundledFonts(context))
    }

    private fun tmp(name: String) = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")
    private fun text(file: File, page: Int? = null): String = PDDocument.load(file).use { d ->
        PDFTextStripper().apply { if (page != null) { startPage = page + 1; endPage = page + 1 } }.getText(d)
    }
    private fun font(d: PDDocument) = context.assets.open("fonts/NotoSans-Regular.ttf").use { PDType0Font.load(d, it, true) }

    private fun PDPageContentStream.line(font: com.tom_roush.pdfbox.pdmodel.font.PDFont, x: Float, y: Float, s: String, size: Float = 14f) {
        beginText(); setFont(font, size); newLineAtOffset(x, y); showText(s); endText()
    }

    private fun whiteout(box: Box, page: Int = 0) = Whiteout("w-$page-${box.top}", page, 612f, box, Whiteout.WHITE, FillSource.WHITE)

    private suspend fun write(src: File, vararg edits: app.folio.core.editing.model.PageEdit): Pair<File, WriteReport> {
        val out = tmp("out")
        return out to writer.write(src, edits.toList(), out)
    }

    @Test
    fun textUnderWhiteoutIsDeletedNotCovered() = runTest {
        val src = tmp("wo-text")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            val f = font(d)
            PDPageContentStream(d, p).use { cs ->
                cs.line(f, 72f, 700f, "Account number 1234-5678")
                cs.line(f, 72f, 660f, "Keep this line")
            }
            d.save(src)
        }
        // "Account number 1234-5678" baseline at display y = 92.
        val (out, report) = write(src, whiteout(Box(60f, 78f, 400f, 97f)))
        val t = text(out)
        assertFalse("must be removed: $t", "Account" in t || "1234" in t)
        assertTrue("Keep this line" in t)
        assertTrue(report.redaction.glyphsRemoved >= 20)
        out.delete(); src.delete()
    }

    @Test
    fun textInSharedFormIsRemovedOnlyWhereWhitedOut() = runTest {
        val src = tmp("wo-form")
        PDDocument().use { d ->
            val f = font(d)
            val form = PDFormXObject(d).apply {
                bBox = PDRectangle(0f, 0f, 400f, 40f)
                resources = PDResources()
            }
            PDPageContentStream(d, form, form.contentStream.createOutputStream(COSName.FLATE_DECODE)).use { cs ->
                cs.line(f, 0f, 10f, "Shared letterhead text")
            }
            repeat(2) {
                val p = PDPage(PDRectangle.LETTER); d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.saveGraphicsState(); cs.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(72f, 700f)); cs.drawForm(form); cs.restoreGraphicsState()
                    cs.line(f, 72f, 600f, "Body $it")
                }
            }
            d.save(src)
        }
        // Form text baseline at PDF y = 710 → display 82.
        val (out, _) = write(src, whiteout(Box(60f, 66f, 400f, 88f), page = 0))
        assertFalse(text(out, 0).contains("Shared letterhead"))
        assertTrue("other page keeps the shared object", text(out, 1).contains("Shared letterhead"))
        assertTrue(text(out, 0).contains("Body 0"))
        out.delete(); src.delete()
    }

    /**
     * The ' and " operators (common in forms made by Adobe tools) are expanded by PDFBox into
     * several internal operations. Operator numbering must count only what's in the file, or every
     * later edit lands on the wrong operator.
     */
    private fun quoteOperatorPdf(inForm: Boolean): File {
        val src = tmp(if (inForm) "quotes-form" else "quotes")
        val content = "BT /F1 12 Tf 16 TL 72 700 Td (Line one) Tj (Line two) ' 2 1 (Line three) \" (Secret four) ' (Line five) ' ET"
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            val res = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            if (inForm) {
                val form = PDFormXObject(d).apply { bBox = PDRectangle.LETTER; resources = res }
                form.contentStream.createOutputStream().use { it.write(content.toByteArray(Charsets.US_ASCII)) }
                p.resources = PDResources()
                PDPageContentStream(d, p).use { cs -> cs.drawForm(form) }
            } else {
                p.resources = res
                val s = com.tom_roush.pdfbox.pdmodel.common.PDStream(d)
                s.createOutputStream().use { it.write(content.toByteArray(Charsets.US_ASCII)) }
                p.setContents(s)
            }
            d.save(src)
        }
        return src
    }

    @Test
    fun whiteoutWorksAfterQuoteOperators() = runTest {
        for (inForm in listOf(false, true)) {
            val src = quoteOperatorPdf(inForm)
            // "Secret four" is the 4th line: baseline y = 700 − 3×16 = 652 → display 140.
            val (out, report) = write(src, whiteout(Box(60f, 126f, 300f, 145f)))
            val t = text(out)
            assertFalse("inForm=$inForm: removed: $t", "Secret" in t)
            assertTrue("inForm=$inForm: neighbours kept: $t", "Line three" in t && "Line five" in t && "Line one" in t)
            assertTrue("inForm=$inForm: removed in place, not by converting the page: ${report.rasterizedPages}", report.rasterizedPages.isEmpty())
            out.delete(); src.delete()
        }
    }

    @Test
    fun editTextWorksAfterQuoteOperators() = runTest {
        val src = quoteOperatorPdf(inForm = false)
        val editable = EditableDocument.open(context, android.net.Uri.fromFile(src))
        try {
            val layer = editable.textLayer(0)
            val run = layer.runs.first { "Secret" in it.text }
            val out = tmp("quotes-edit")
            writer.write(editable.snapshot, listOf(app.folio.core.editing.model.TextReplacement("r", 0, layer.displayWidth, run.ref, "Public four")), out)
            val t = text(out)
            assertTrue(t, "Public four" in t && "Line five" in t && "Line three" in t)
            assertFalse(t, "Secret" in t)
            out.delete()
        } finally {
            editable.close(); src.delete()
        }
    }

    /** A font whose /Widths covers only the letters it used: new letters would get zero width and pile up. */
    @Test
    fun editTextFallsBackWhenFontLacksWidths() = runTest {
        val src = tmp("widths")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            val font = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, COSName.TYPE1)
                setName(COSName.BASE_FONT, "Helvetica"); setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING)
                setInt(COSName.FIRST_CHAR, 'H'.code); setInt(COSName.LAST_CHAR, 'i'.code)
                // Widths only for "H" and "i"; everything between is 0.
                setItem(COSName.WIDTHS, com.tom_roush.pdfbox.cos.COSArray().apply {
                    for (c in 'H'.code..'i'.code) add(com.tom_roush.pdfbox.cos.COSInteger.get(if (c == 'H'.code) 722L else if (c == 'i'.code) 222L else 0L))
                })
            }
            p.resources = PDResources().apply { put(COSName.getPDFName("F1"), com.tom_roush.pdfbox.pdmodel.font.PDFontFactory.createFont(font)) }
            val s = com.tom_roush.pdfbox.pdmodel.common.PDStream(d)
            s.createOutputStream().use { it.write("BT /F1 20 Tf 72 700 Td (Hi Hi) Tj ET".toByteArray(Charsets.US_ASCII)) }
            p.setContents(s)
            d.save(src)
        }
        val editable = EditableDocument.open(context, android.net.Uri.fromFile(src))
        try {
            val layer = editable.textLayer(0)
            val run = layer.runs.first { "Hi" in it.text }
            val out = tmp("widths-out")
            val report = writer.write(editable.snapshot, listOf(app.folio.core.editing.model.TextReplacement("r", 0, layer.displayWidth, run.ref, "Zq Edited")), out)
            assertEquals("fell back to a bundled font", 1, report.fontSubstitutions)
            PDDocument.load(out).use { o ->
                val runs = app.folio.core.editing.text.TextAnalyzer.analyze(o.getPage(0), 0).runs
                assertTrue("one readable run: ${runs.map { it.text }}", runs.any { it.text.trim() == "Zq Edited" })
            }
            out.delete()
        } finally {
            editable.close(); src.delete()
        }
    }

    @Test
    fun invisibleOcrTextUnderWhiteoutIsRemoved() = runTest {
        val src = tmp("wo-ocr")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setRenderingMode(RenderingMode.NEITHER); cs.setFont(PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(72f, 700f); cs.showText("Hidden secret"); cs.endText()
            }
            d.save(src)
        }
        val (out, _) = write(src, whiteout(Box(60f, 78f, 300f, 97f)))
        assertFalse("Hidden secret" in text(out))
        out.delete(); src.delete()
    }

    private fun solidBitmap(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun imagesAreRemovedOrHaveTheirCoveredPixelsReplaced() = runTest {
        val src = tmp("wo-img")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            val red = LosslessFactory.createFromImage(d, solidBitmap(200, 100, Color.RED))
            val blue = LosslessFactory.createFromImage(d, solidBitmap(20, 20, Color.BLUE))
            PDPageContentStream(d, p).use { cs ->
                cs.drawImage(red, 100f, 500f, 200f, 100f)   // display box 100..300 × 192..292
                cs.drawImage(blue, 400f, 500f, 20f, 20f)    // display box 400..420 × 272..292
            }
            d.save(src)
        }
        val (out, report) = write(
            src,
            whiteout(Box(90f, 180f, 200f, 300f)), // covers the left half of the red image
            whiteout(Box(395f, 265f, 425f, 297f)), // covers the blue image entirely
        )
        assertEquals(1, report.redaction.imagesRedacted)
        assertEquals(1, report.redaction.imagesRemoved)
        PDDocument.load(out).use { d ->
            val page = d.getPage(0)
            val scan = RedactionScanner(page, TextAnalyzer.pageTransform(page)).scan(page)
            assertEquals("only the redacted red image is still drawn", 1, scan.images.size)
            val bmp = (scan.images.single().image as PDImageXObject).image
            val left = bmp.getPixel(10, 50)
            val right = bmp.getPixel(bmp.width - 10, 50)
            assertEquals("covered pixels are replaced", Color.WHITE, left or 0xFF000000.toInt())
            assertEquals("uncovered pixels are untouched", Color.RED, right or 0xFF000000.toInt())
        }
        out.delete(); src.delete()
    }

    private fun formPdf(value: String, withLink: Boolean): File {
        val src = tmp("form")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            val acro = PDAcroForm(d)
            d.documentCatalog.acroForm = acro
            val res = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            acro.defaultResources = res
            acro.defaultAppearance = "/Helv 12 Tf 0 g"
            val field = PDTextField(acro).apply { partialName = "name" }
            val widget = field.widgets.first() as PDAnnotationWidget
            widget.rectangle = PDRectangle(72f, 680f, 250f, 24f) // display y 88..112
            widget.page = p
            p.annotations = buildList {
                add(widget)
                if (withLink) add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(72f, 600f, 100f, 20f)
                    action = PDActionURI().apply { uri = "https://example.com" }
                })
            }
            acro.fields = listOf(field)
            field.value = value
            d.save(src)
        }
        return src
    }

    @Test
    fun savingFlattensFormsAndKeepsLinks() = runTest {
        val src = formPdf("Jane Doe", withLink = true)
        val (out, report) = write(src, AddedText("t", 0, 612f, 72f, 300f, "Signed today", TextStyle()))
        assertTrue(report.flatten.fieldsFlattened >= 1)
        PDDocument.load(out).use { d ->
            assertNull("no interactive form left", d.documentCatalog.acroForm)
            val annots = d.getPage(0).annotations
            assertTrue("widgets gone", annots.none { it is PDAnnotationWidget })
            assertTrue("links kept", annots.any { it is PDAnnotationLink })
        }
        val t = text(out)
        assertTrue("field value is now page content: $t", "Jane Doe" in t)
        assertTrue("Signed today" in t)
        out.delete(); src.delete()
    }

    @Test
    fun whiteoutRemovesAFlattenedFieldValue() = runTest {
        val src = formPdf("Private value", withLink = false)
        val (out, _) = write(src, whiteout(Box(70f, 86f, 330f, 114f)))
        assertFalse("Private value" in text(out))
        out.delete(); src.delete()
    }
}
