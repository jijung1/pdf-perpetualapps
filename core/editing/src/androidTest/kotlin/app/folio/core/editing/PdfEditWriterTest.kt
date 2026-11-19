package app.folio.core.editing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.Ink
import app.folio.core.editing.model.PlacedSignature
import app.folio.core.editing.model.Stroke
import app.folio.core.editing.model.TextReplacement
import app.folio.core.editing.model.TextStyle
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfEditWriterTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var writer: PdfEditWriter

    @Before
    fun setUp() {
        PdfBox.init(context)
        writer = PdfEditWriter(context, BundledFonts(context))
    }

    private fun samplePdf(vararg lines: String): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        val paint = Paint().apply { color = Color.BLACK; textSize = 18f; isAntiAlias = true }
        lines.forEachIndexed { i, l -> page.canvas.drawText(l, 72f, 120f + i * 40f, paint) }
        doc.finishPage(page)
        val f = File(context.cacheDir, "edit-src-${System.nanoTime()}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun textOf(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    /** A PDF made by PDFBox with an embedded TrueType (Type 0) font, like most office exports. */
    private fun trueTypePdf(subset: Boolean, vararg lines: String): File {
        val f = File(context.cacheDir, "tt-src-${System.nanoTime()}.pdf")
        PDDocument().use { d ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.LETTER)
            d.addPage(page)
            val font = context.assets.open("fonts/NotoSerif-Regular.ttf").use {
                com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(d, it, subset)
            }
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(d, page).use { cs ->
                lines.forEachIndexed { i, l ->
                    cs.beginText(); cs.setFont(font, 16f); cs.newLineAtOffset(72f, 700f - i * 30f); cs.showText(l); cs.endText()
                }
            }
            d.save(f)
        }
        return f
    }

    private suspend fun replaceAndCheck(src: File, find: String, replacement: String, keep: String): WriteReport {
        val editable = EditableDocument.open(context, Uri.fromFile(src))
        try {
            val layer = editable.textLayer(0)
            val run = layer.runs.first { find in it.text }
            assertEquals(null, run.blocker)
            val out = File(context.cacheDir, "edit-out-${System.nanoTime()}.pdf")
            val report = writer.write(editable.snapshot, listOf(TextReplacement("r", 0, layer.displayWidth, run.ref, replacement)), out)
            val text = textOf(out)
            assertTrue(text, replacement in text)
            assertFalse("original glyphs must be removed, not covered: $text", find in text)
            assertTrue("other content untouched", keep in text)
            out.delete()
            return report
        } finally {
            editable.close(); src.delete()
        }
    }

    @Test
    fun replacementReusesOriginalFontWhenItHasTheGlyphs() = runTest {
        val report = replaceAndCheck(trueTypePdf(false, "Invoice total due", "Keep this line"), "Invoice", "Receipt paid", "Keep this line")
        assertEquals(0, report.fontSubstitutions)
    }

    @Test
    fun replacementSubstitutesFontWhenSubsetLacksGlyphs() = runTest {
        // A subset only contains glyphs already used; "Z" and "q" are not in the source text.
        val report = replaceAndCheck(trueTypePdf(true, "Invoice total due", "Keep this line"), "Invoice", "Zq", "Keep this line")
        assertEquals(1, report.fontSubstitutions)
    }

    @Test
    fun replacesExistingTextForReal() = runTest {
        val src = samplePdf("Invoice total due", "Keep this line")
        val editable = EditableDocument.open(context, Uri.fromFile(src))
        try {
            val layer = editable.textLayer(0)
            val run = layer.runs.first { "Invoice" in it.text }
            assertEquals(null, run.blocker)
            val out = File(context.cacheDir, "edit-out-${System.nanoTime()}.pdf")
            val report = writer.write(
                editable.snapshot,
                listOf(TextReplacement("r1", 0, layer.displayWidth, run.ref, "Receipt paid")),
                out,
            )
            val text = textOf(out)
            assertTrue(text, "Receipt paid" in text)
            assertFalse("original glyphs must be removed, not covered: $text", "Invoice" in text)
            assertTrue("other content untouched", "Keep this line" in text)
            assertTrue(report.glyphsRemoved > 0)
            out.delete()
        } finally {
            editable.close(); src.delete()
        }
    }

    @Test
    fun deletingTextRemovesItAndKeepsRest() = runTest {
        val src = samplePdf("Remove me", "Stay here")
        val editable = EditableDocument.open(context, Uri.fromFile(src))
        try {
            val layer = editable.textLayer(0)
            val run = layer.runs.first { "Remove" in it.text }
            val out = File(context.cacheDir, "edit-del-${System.nanoTime()}.pdf")
            writer.write(editable.snapshot, listOf(TextReplacement("d", 0, layer.displayWidth, run.ref, "")), out)
            val text = textOf(out)
            assertFalse("Remove" in text)
            assertTrue("Stay here" in text)
            out.delete()
        } finally {
            editable.close(); src.delete()
        }
    }

    @Test
    fun addsTextAndSignatureWithoutTouchingOriginal() = runTest {
        val src = samplePdf("Original")
        val before = src.readBytes()
        val out = File(context.cacheDir, "edit-add-${System.nanoTime()}.pdf")
        val sig = PlacedSignature(
            "s", 0, 612f, 300f, 600f, 150f, 50f,
            Ink(listOf(Stroke(listOf(0f, 0.5f, 0.3f, 0.1f, 0.6f, 0.9f, 1f, 0.4f)), Stroke(listOf(0.5f, 0.5f))), 3f, 0.06f),
            0xFF102060.toInt(),
        )
        writer.write(
            src,
            listOf(AddedText("t", 0, 612f, 72f, 300f, "Added note\nsecond line ünïcödé", TextStyle(sizePt = 16f)), sig),
            out,
        )
        val text = textOf(out)
        assertTrue(text, "Added note" in text)
        assertTrue(text, "ünïcödé" in text)
        assertTrue("Original" in text)
        assertTrue("source file must be untouched", before.contentEquals(src.readBytes()))
        out.delete(); src.delete()
    }

    /** Statements and invoices often carry an owner password: they open freely but used to fail to save. */
    @Test
    fun savesOwnerProtectedPdfAndKeepsItsRestrictions() = runTest {
        val src = samplePdf("Statement")
        val locked = File(context.cacheDir, "owner-${System.nanoTime()}.pdf")
        PDDocument.load(src).use { d ->
            val perms = AccessPermission().apply { setCanPrint(false) }
            d.protect(StandardProtectionPolicy("owner-secret", "", perms).apply { encryptionKeyLength = 128 })
            d.save(locked)
        }
        val out = File(context.cacheDir, "owner-out-${System.nanoTime()}.pdf")
        writer.write(locked, listOf(AddedText("t", 0, 612f, 72f, 300f, "Paid", TextStyle())), out)
        PDDocument.load(out).use { d ->
            assertTrue("still protected", d.isEncrypted)
            assertFalse("printing restriction kept", d.currentAccessPermission.canPrint())
        }
        assertTrue("Paid" in textOf(out))
        src.delete(); locked.delete(); out.delete()
    }

    @Test
    fun refusesWhenOwnerPermissionsForbidEditing() = runTest {
        val src = samplePdf("Locked")
        val locked = File(context.cacheDir, "locked-${System.nanoTime()}.pdf")
        PDDocument.load(src).use { d ->
            val perms = AccessPermission().apply { setCanModify(false) }
            d.protect(StandardProtectionPolicy("owner-secret", "", perms).apply { encryptionKeyLength = 128 })
            d.save(locked)
        }
        val editable = EditableDocument.open(context, Uri.fromFile(locked))
        try {
            assertFalse(editable.canModify)
            try {
                writer.write(editable.snapshot, listOf(AddedText("t", 0, 612f, 0f, 0f, "x", TextStyle())), File(context.cacheDir, "nope.pdf"))
                fail("expected EditingNotPermitted")
            } catch (e: EditException) {
                assertEquals(EditError.EditingNotPermitted, e.error)
            }
        } finally {
            editable.close(); src.delete(); locked.delete()
        }
    }
}
