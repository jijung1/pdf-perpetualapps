package app.folio.core.editing

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.FillSource
import app.folio.core.editing.model.Whiteout
import app.folio.core.editing.text.TextAnalyzer
import app.folio.core.editing.whiteout.RedactionArea
import app.folio.core.editing.whiteout.RedactionRules
import app.folio.core.editing.whiteout.RedactionScanner
import app.folio.core.editing.whiteout.Redactor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whiteout over real documents (a Microsoft Word export, Android-generated PDFs, merged files).
 * Every text line on every page gets a whiteout; any save that fails is reported with the reason.
 */
@RunWith(AndroidJUnit4::class)
class WhiteoutRealWorldTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var writer: PdfEditWriter

    @Before
    fun setUp() {
        PdfBox.init(context)
        writer = PdfEditWriter(context, BundledFonts(context))
    }

    private fun fixtures(): List<File> = testAssets.list("realworld")!!.map { name ->
        File(context.cacheDir, "rw-$name").also { f -> testAssets.open("realworld/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
    }

    /** Runs the redactor directly and lists what survives under the boxes. */
    @Test
    fun redactorLeavesNothingUnderBoxes() {
        val problems = ArrayList<String>()
        for (file in fixtures()) {
            PDDocument.load(file).use { doc ->
                for ((index, page) in doc.pages.withIndex()) {
                    val t = TextAnalyzer.pageTransform(page)
                    val layer = TextAnalyzer.analyze(page, index)
                    val boxes = layer.runs.map { it.bounds }.filter { it.width > 2 && it.height > 2 }
                    if (boxes.isEmpty()) continue
                    val redactor = Redactor(doc, page, boxes.map { RedactionArea(it, Whiteout.WHITE) })
                    try {
                        redactor.redact()
                    } catch (e: Throwable) {
                        problems += "${file.name} p${index + 1}: redact threw ${e.javaClass.simpleName}: ${e.message}"
                        Log.e("WhiteoutRW", "redact threw", e)
                        continue
                    }
                    val scan = RedactionScanner(page, t).scan(page)
                    val left = scan.glyphs.filter { RedactionRules.removesGlyph(it.box, boxes) }
                    if (left.isNotEmpty()) {
                        val sample = left.take(12).joinToString(" ") { g ->
                            "'${g.record.unicode}'@ctx${g.ctx.id}${if (g.ctx.form != null) "(form)" else ""}/op${g.record.operatorIndex}/${g.record.font.javaClass.simpleName}"
                        }
                        problems += "${file.name} p${index + 1}: ${left.size} glyphs left: $sample"
                    }
                }
            }
        }
        problems.forEach { Log.e("WhiteoutRW", it) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Large boxes: the middle of every page, and one page-sized box (covers images and drawings too). */
    @Test
    fun savingWithLargeWhiteoutsSucceeds() = runTest {
        val problems = ArrayList<String>()
        for (file in fixtures()) {
            for (mode in listOf("middle", "full", "corner")) {
                val edits = PDDocument.load(file).use { doc ->
                    doc.pages.mapIndexed { index, page ->
                        val t = TextAnalyzer.pageTransform(page)
                        val w = t.displayWidth; val h = t.displayHeight
                        val box = when (mode) {
                            "middle" -> Box(w * 0.1f, h * 0.3f, w * 0.9f, h * 0.7f)
                            "full" -> Box(0f, 0f, w, h)
                            else -> Box(w * 0.05f, h * 0.05f, w * 0.45f, h * 0.2f)
                        }
                        Whiteout("w$index", index, w, box, Whiteout.WHITE, FillSource.WHITE)
                    }
                }
                val out = File(context.cacheDir, "rw-big-$mode-${file.name}")
                try {
                    writer.write(file, edits, out)
                } catch (e: Throwable) {
                    var c: Throwable? = e
                    val chain = buildString { while (c != null) { append(" <- ${c!!.javaClass.simpleName}: ${c!!.message}"); c = c!!.cause } }
                    problems += "${file.name} [$mode]:$chain"
                    Log.e("WhiteoutRW", "large save failed ${file.name} $mode", e)
                }
                out.delete()
            }
        }
        problems.forEach { Log.e("WhiteoutRW", it) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Print-style PDFs: CMYK text, drawings and images (PDFBox-Android has no CMYK support). */
    @Test
    fun cmykDocumentsSave() = runTest {
        val src = File(context.cacheDir, "rw-cmyk.pdf")
        PDDocument().use { d ->
            val p = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.LETTER); d.addPage(p)
            // A 120×60 CMYK image (Flate-compressed), drawn at 100,500 → display box 100..340 × 172..292.
            val w = 120; val h = 60
            val raw = ByteArray(w * h * 4) { i -> when (i % 4) { 0 -> 0; 1 -> 200.toByte(); 2 -> 255.toByte(); else -> 0 } }
            val stream = com.tom_roush.pdfbox.pdmodel.common.PDStream(d, java.io.ByteArrayInputStream(raw), com.tom_roush.pdfbox.cos.COSName.FLATE_DECODE)
            val img = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject(stream, null).apply {
                width = w; height = h; bitsPerComponent = 8
                cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.COLORSPACE, com.tom_roush.pdfbox.cos.COSName.DEVICECMYK)
            }
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(d, p).use { cs ->
                cs.setNonStrokingColor(0f, 0f, 0f, 1f) // CMYK black text
                cs.beginText(); cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(72f, 700f); cs.showText("CMYK account 1234"); cs.endText()
                cs.setNonStrokingColor(0.2f, 0f, 0f, 0f); cs.addRect(72f, 620f, 300f, 40f); cs.fill()
                cs.drawImage(img, 100f, 500f, 240f, 120f)
            }
            d.save(src)
        }
        val problems = ArrayList<String>()
        val cases = mapOf(
            "text" to Box(60f, 78f, 300f, 97f),
            "drawing" to Box(60f, 125f, 400f, 180f),
            "image-part" to Box(90f, 160f, 200f, 300f),
            "image-all" to Box(90f, 160f, 350f, 300f),
        )
        // Same document, encrypted with an owner password (opens without a password, editing allowed):
        // the page-image fallback has to render it through a decrypted temporary copy.
        val encrypted = File(context.cacheDir, "rw-cmyk-enc.pdf")
        PDDocument.load(src).use { d ->
            d.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-secret", "", com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()).apply { encryptionKeyLength = 128 })
            d.save(encrypted)
        }
        for ((name, box) in cases + ("encrypted-image-part" to cases.getValue("image-part"))) {
            val out = File(context.cacheDir, "rw-cmyk-$name.pdf")
            val input = if (name.startsWith("encrypted")) encrypted else src
            try {
                val report = writer.write(input, listOf(Whiteout("w", 0, 612f, box, Whiteout.WHITE, FillSource.WHITE)), out)
                Log.i("WhiteoutRW", "cmyk $name ok: ${report.redaction} raster=${report.rasterizedPages}")
                when (name) {
                    // A CMYK image can't be decoded safely, so its page becomes an image instead of being garbled.
                    "image-part", "encrypted-image-part" -> if (report.rasterizedPages != listOf(0) || report.redaction.imagesRedacted != 0)
                        problems += "cmyk $name: expected page-image fallback, got ${report.redaction} raster=${report.rasterizedPages}"
                    "image-all" -> if (report.redaction.imagesRemoved != 1) problems += "cmyk $name: image not removed"
                    "text" -> if (PDDocument.load(out).use { com.tom_roush.pdfbox.text.PDFTextStripper().getText(it) }.contains("1234"))
                        problems += "cmyk text: text still extractable"
                }
                if (name.startsWith("encrypted") && !PDDocument.load(out).use { it.isEncrypted }) problems += "cmyk $name: output lost its encryption"
            } catch (e: Throwable) {
                var c: Throwable? = e
                val chain = buildString { while (c != null) { append(" <- ${c!!.javaClass.simpleName}: ${c!!.message}"); c = c!!.cause } }
                problems += "cmyk $name:$chain"
                Log.e("WhiteoutRW", "cmyk $name failed", e)
            }
            out.delete()
        }
        problems.forEach { Log.e("WhiteoutRW", it) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** The full save path, one whiteout over a text line per page, as the app does it. */
    @Test
    fun savingWithWhiteoutsSucceeds() = runTest {
        val problems = ArrayList<String>()
        for (file in fixtures()) {
            val edits = PDDocument.load(file).use { doc ->
                doc.pages.mapIndexedNotNull { index, page ->
                    val layer = TextAnalyzer.analyze(page, index)
                    val run = layer.runs.firstOrNull { it.bounds.width > 20 } ?: return@mapIndexedNotNull null
                    val b = run.bounds
                    Whiteout("w$index", index, layer.displayWidth, Box(b.left - 2, b.top - 1, b.right + 2, b.bottom + 1), Whiteout.WHITE, FillSource.WHITE)
                }
            }
            val out = File(context.cacheDir, "rw-out-${file.name}")
            try {
                writer.write(file, edits, out)
            } catch (e: EditException) {
                var c: Throwable? = e.cause
                val chain = buildString { append(e.error); while (c != null) { append(" <- ${c!!.javaClass.simpleName}: ${c!!.message}"); c = c!!.cause } }
                problems += "${file.name}: $chain"
                Log.e("WhiteoutRW", "save failed ${file.name}", e)
            } catch (e: Throwable) {
                problems += "${file.name}: crashed ${e.javaClass.simpleName}: ${e.message}"
                Log.e("WhiteoutRW", "save crashed ${file.name}", e)
            }
            out.delete()
        }
        problems.forEach { Log.e("WhiteoutRW", it) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
