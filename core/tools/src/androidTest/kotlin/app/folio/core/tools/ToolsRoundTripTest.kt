package app.folio.core.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.core.editing.PdfBox
import app.folio.core.tools.organize.DocumentAssembler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
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
class ToolsRoundTripTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "tools-test").apply { mkdirs() }
    private lateinit var assembler: DocumentAssembler

    @Before
    fun setUp() {
        PdfBox.init(context)
        assembler = DocumentAssembler(context) { work }
    }

    private fun tmp(name: String, ext: String = "pdf") = File(work, "$name-${System.nanoTime()}.$ext")

    /** A PDF whose page i shows the text labels[i]; page 0 links to the last page. */
    private fun pdf(vararg labels: String, link: Boolean = false): File {
        val f = tmp("src")
        PDDocument().use { d ->
            labels.forEach { label ->
                val p = PDPage(PDRectangle.LETTER); d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 24f); cs.newLineAtOffset(72f, 700f); cs.showText(label); cs.endText()
                }
            }
            if (link) {
                d.getPage(0).annotations = listOf(PDAnnotationLink().apply {
                    rectangle = PDRectangle(72f, 600f, 100f, 20f)
                    destination = PDPageFitDestination().apply { page = d.getPage(labels.lastIndex) }
                })
            }
            d.save(f)
        }
        return f
    }

    private fun png(w: Int, h: Int): File {
        val f = tmp("photo", "png")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 200)) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun src(id: String, file: File, kind: SourceKind, pages: Int) = SourceFile(id, Uri.fromFile(file), file.name, kind, pages)

    private fun pageTexts(file: File): List<String> = PDDocument.load(file).use { d ->
        (0 until d.numberOfPages).map { i -> PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(d).trim() }
    }

    @Test
    fun mergesPdfsAndAPhotoInAnyOrderWithRotation() = runTest {
        val a = pdf("Alpha one", "Alpha two")
        val b = pdf("Bravo one")
        val photo = png(800, 600)
        val sources = mapOf("a" to src("a", a, SourceKind.PDF, 2), "b" to src("b", b, SourceKind.PDF, 1), "p" to src("p", photo, SourceKind.IMAGE, 1))
        val pages = listOf(PageRef("1", "b", 0), PageRef("2", "a", 1, rotation = 90), PageRef("3", "p", 0), PageRef("4", "a", 0))
        val out = tmp("merged")
        val report = assembler.assemble(sources, pages, ImageOptions(ImagePageSize.A4), out)
        assertEquals(4, report.pageCount)
        // Text on the rotated page is extracted vertically, so compare without whitespace.
        val texts = pageTexts(out).map { t -> t.filterNot(Char::isWhitespace) }
        assertEquals(listOf("Bravoone", "Alphatwo", "", "Alphaone"), texts)
        PDDocument.load(out).use { d ->
            assertEquals(90, d.getPage(1).rotation)
            val imagePage = d.getPage(2)
            assertEquals("landscape photo → landscape A4", true, imagePage.mediaBox.width > imagePage.mediaBox.height)
            val names = imagePage.resources.xObjectNames.toList()
            assertEquals(1, names.size)
            assertTrue(imagePage.resources.getXObject(names.single()) is PDImageXObject)
        }
        listOf(a, b, photo, out).forEach { it.delete() }
    }

    @Test
    fun leftOutPagesNeverLeakIntoTheOutput() = runTest {
        // Page 0 links to the secret last page; the link must be dropped, not drag the page in.
        val a = pdf("Public page", "SECRET page", link = true)
        val out = tmp("subset")
        assembler.assemble(mapOf("a" to src("a", a, SourceKind.PDF, 2)), listOf(PageRef("1", "a", 0)), ImageOptions(), out)
        assertEquals(listOf("Public page"), pageTexts(out))
        val raw = out.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("no second page object may be written", Regex("/Type\\s*/Page[^s]").findAll(raw).count() > 1)
        PDDocument.load(out).use { d -> assertTrue(d.getPage(0).annotations.none { it is PDAnnotationLink }) }
        a.delete(); out.delete()
    }

    @Test
    fun linksToIncludedPagesAreRemapped() = runTest {
        val a = pdf("First", "Second", link = true)
        val out = tmp("links")
        assembler.assemble(mapOf("a" to src("a", a, SourceKind.PDF, 2)), listOf(PageRef("1", "a", 1), PageRef("2", "a", 0)), ImageOptions(), out)
        PDDocument.load(out).use { d ->
            val link = d.getPage(1).annotations.filterIsInstance<PDAnnotationLink>().single()
            val dest = link.destination as com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
            assertEquals("points at the new copy of 'Second'", d.getPage(0).cosObject, dest.page.cosObject)
        }
        a.delete(); out.delete()
    }

    @Test
    fun duplicatedPagesAreIndependentCopies() = runTest {
        val a = pdf("Only page")
        val out = tmp("dup")
        assembler.assemble(mapOf("a" to src("a", a, SourceKind.PDF, 1)), listOf(PageRef("1", "a", 0), PageRef("2", "a", 0, rotation = 180)), ImageOptions(), out)
        PDDocument.load(out).use { d ->
            assertEquals(2, d.numberOfPages)
            assertEquals(0, d.getPage(0).rotation)
            assertEquals(180, d.getPage(1).rotation)
        }
        a.delete(); out.delete()
    }

    @Test
    fun passwordProtectedSourcesGiveAClearError() = runTest {
        val plain = pdf("Locked")
        val locked = tmp("locked")
        PDDocument.load(plain).use { d ->
            d.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "user", com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()))
            d.save(locked)
        }
        try {
            assembler.assemble(mapOf("a" to src("a", locked, SourceKind.PDF, 1)), listOf(PageRef("1", "a", 0)), ImageOptions(), tmp("x"))
            fail("expected PasswordProtected")
        } catch (e: ToolException) {
            assertTrue(e.error is ToolError.PasswordProtected)
        }
        plain.delete(); locked.delete()
    }
}
