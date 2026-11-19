package app.folio.core.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfRenderSessionTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = PageBitmapCache(32 * 1024 * 1024)

    private fun samplePdf(pages: List<Pair<Int, Int>>): File {
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 18f }
        pages.forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawText("Page ${i + 1}", 40f, 60f, paint)
            doc.finishPage(page)
        }
        val file = File(context.cacheDir, "sample-${System.nanoTime()}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    @Test
    fun opensDocumentAndReportsPageGeometry() = runTest {
        val file = samplePdf(listOf(612 to 792, 842 to 595, 300 to 300))
        val session = PdfRenderSession.open(context, Uri.fromFile(file), cache)
        try {
            assertEquals(3, session.pageCount)
            assertEquals(PageSize(612, 792), session.pageSizes[0])
            assertEquals(PageSize(842, 595), session.pageSizes[1])
        } finally {
            session.close(); file.delete()
        }
    }

    @Test
    fun rendersAtRequestedWidthAndCaches() = runTest {
        val file = samplePdf(listOf(612 to 792))
        val session = PdfRenderSession.open(context, Uri.fromFile(file), cache)
        try {
            val bmp = session.render(0, 600)
            assertEquals(600, bmp.width)
            assertEquals(PageSize(612, 792).heightForWidth(600), bmp.height)
            assertSame(bmp, session.render(0, 600))
            assertSame(bmp, session.cached(0, 600))
        } finally {
            session.close(); file.delete()
        }
    }

    @Test
    fun corruptedFileReportsTypedError() = runTest {
        val file = File(context.cacheDir, "broken.pdf").apply { writeText("%PDF-1.7 this is not really a pdf") }
        try {
            PdfRenderSession.open(context, Uri.fromFile(file), cache).close()
            fail("expected PdfOpenException")
        } catch (e: PdfOpenException) {
            assertEquals(PdfOpenError.Corrupted, e.error)
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingFileReportsNotFound() = runTest {
        try {
            PdfRenderSession.open(context, Uri.fromFile(File(context.cacheDir, "nope.pdf")), cache).close()
            fail("expected PdfOpenException")
        } catch (e: PdfOpenException) {
            assertEquals(PdfOpenError.NotFound, e.error)
        }
    }
}
