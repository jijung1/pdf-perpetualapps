package app.folio.core.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.core.editing.PdfBox
import app.folio.core.tools.organize.DocumentAssembler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Large inputs under the app's real heap limit: these are the cases that used to end in
 * OutOfMemoryError heap dumps. Peak Java heap is logged for the record.
 */
@RunWith(AndroidJUnit4::class)
class StressTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "stress").apply { mkdirs() }

    private fun usedMb(): Long { val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / (1024 * 1024) }

    @Test
    fun reordersA400PageDocument() = runTest(timeout = 10.minutes) {
        PdfBox.init(context)
        val src = File(work, "big.pdf")
        PDDocument().use { d ->
            repeat(400) { i ->
                val p = PDPage(PDRectangle.LETTER); d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f)
                    for (line in 0 until 40) { cs.newLineAtOffset(if (line == 0) 72f else 0f, if (line == 0) 740f else -16f); cs.showText("Page ${i + 1} line $line lorem ipsum dolor sit amet") }
                    cs.endText()
                }
            }
            d.save(src)
        }
        val pages = (0 until 400).reversed().map { PageRef("k$it", "s", it, rotation = if (it % 2 == 0) 90 else 0) }
        val out = File(work, "big-out.pdf")
        val start = System.currentTimeMillis()
        val report = DocumentAssembler(context) { work }.assemble(
            mapOf("s" to SourceFile("s", Uri.fromFile(src), "big.pdf", SourceKind.PDF, 400)), pages, ImageOptions(), out,
        )
        Log.i("FolioStress", "400 pages reordered in ${System.currentTimeMillis() - start} ms, heap ${usedMb()} MB, ${out.length() / 1024} KB")
        assertEquals(400, report.pageCount)
        src.delete(); out.delete()
    }

    @Test
    fun mergesTwelveLargePhotos() = runTest(timeout = 10.minutes) {
        PdfBox.init(context)
        val paint = Paint().apply { color = Color.DKGRAY; textSize = 220f }
        val photos = (1..12).map { i ->
            val f = File(work, "photo$i.jpg")
            // 12 MP, like a phone camera.
            val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.rgb(200, 180 + i, 150))
            Canvas(bmp).drawText("Photo $i", 300f, 1500f, paint)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            f
        }
        val sources = photos.mapIndexed { i, f -> "p$i" to SourceFile("p$i", Uri.fromFile(f), f.name, SourceKind.IMAGE, 1) }.toMap()
        val pages = sources.keys.map { PageRef("k$it", it, 0) }
        val out = File(work, "photos.pdf")
        val start = System.currentTimeMillis()
        DocumentAssembler(context) { work }.assemble(sources, pages, ImageOptions(ImagePageSize.A4), out)
        Log.i("FolioStress", "12 × 12 MP photos merged in ${System.currentTimeMillis() - start} ms, heap ${usedMb()} MB, ${out.length() / 1024} KB")
        PDDocument.load(out).use { assertEquals(12, it.numberOfPages) }

        (photos + out).forEach { it.delete() }
    }
}
