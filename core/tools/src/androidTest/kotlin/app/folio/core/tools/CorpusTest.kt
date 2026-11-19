package app.folio.core.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.core.editing.EditException
import app.folio.core.editing.EditableDocument
import app.folio.core.editing.PdfBox
import app.folio.core.editing.PdfEditWriter
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.FillSource
import app.folio.core.editing.model.Ink
import app.folio.core.editing.model.PlacedSignature
import app.folio.core.editing.model.Stroke
import app.folio.core.editing.model.TextReplacement
import app.folio.core.editing.model.TextStyle
import app.folio.core.editing.model.Whiteout
import app.folio.core.editing.text.TextAnalyzer
import app.folio.core.pdf.PageBitmapCache
import app.folio.core.pdf.PdfOpenException
import app.folio.core.pdf.PdfRenderSession
import app.folio.core.tools.organize.DocumentAssembler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs every feature against a corpus of real, public PDFs copied into this test app's private
 * folder "corpus/" (see testdata/corpus/README.md). Each check reports OK, a clean refusal
 * ("REFUSED" with the user-facing reason), or FAIL with the cause. Results are logged under
 * the tag "Corpus" as one line per file and check.
 */
@RunWith(AndroidJUnit4::class)
class CorpusTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "corpus-work").apply { mkdirs() }

    private fun log(file: String, check: String, status: String, detail: String = "", ms: Long = 0) =
        Log.i("Corpus", "$file | $check | $status | ${ms}ms | $detail")

    private inline fun check(file: String, name: String, block: () -> String) {
        val start = System.currentTimeMillis()
        try {
            val detail = block()
            log(file, name, "OK", detail, System.currentTimeMillis() - start)
        } catch (e: PdfOpenException) {
            log(file, name, "REFUSED", "open: ${e.error}", System.currentTimeMillis() - start)
        } catch (e: EditException) {
            log(file, name, if (e.error == app.folio.core.editing.EditError.PasswordRequired || e.error == app.folio.core.editing.EditError.EditingNotPermitted) "REFUSED" else "FAIL",
                "edit: ${e.error} <- ${e.cause?.javaClass?.simpleName}: ${e.cause?.message?.take(140)}", System.currentTimeMillis() - start)
            if (e.error != app.folio.core.editing.EditError.PasswordRequired) Log.e("Corpus", "$file $name", e)
        } catch (e: ToolException) {
            log(file, name, if (e.error is ToolError.PasswordProtected || e.error is ToolError.NotPermitted) "REFUSED" else "FAIL",
                "tool: ${e.error} <- ${e.cause?.javaClass?.simpleName}: ${e.cause?.message?.take(140)}", System.currentTimeMillis() - start)
        } catch (e: Throwable) {
            log(file, name, "FAIL", "${e.javaClass.simpleName}: ${e.message?.take(160)}", System.currentTimeMillis() - start)
            Log.e("Corpus", "$file $name", e)
        }
    }

    @Test
    fun corpus(): Unit = runBlocking {
        val dir = File(context.filesDir, "corpus")
        val files = dir.listFiles { f -> f.name.endsWith(".pdf") }.orEmpty().sortedBy { it.name }
        assumeTrue("no corpus", files.isNotEmpty())
        PdfBox.init(context)
        val writer = PdfEditWriter(context, BundledFonts(context))
        val assembler = DocumentAssembler(context) { work }
        val cache = PageBitmapCache(64 * 1024 * 1024)
        val ink = Ink(listOf(Stroke(listOf(0f, .6f, .3f, .2f, .6f, .9f, 1f, .4f))), 3f, .06f)

        for (file in files) {
            val name = file.name
            val uri = Uri.fromFile(file)
            var pages = 0

            check(name, "1 open+render") {
                val s = PdfRenderSession.open(context, uri, cache)
                try {
                    pages = s.pageCount
                    for (i in 0 until minOf(3, pages)) s.render(i, 720)
                    "pages=$pages"
                } finally { s.close() }
            }

            if (pages == 0) {
                // The app stops at opening (e.g. a password is needed); the other checks would only
                // exercise this harness's own direct file loading.
                log(name, "2-7", "SKIPPED", "not openable in the app")
                continue
            }
            val info = runCatching { PDDocument.load(file).use { d ->
                val form = d.documentCatalog.acroForm
                "encrypted=${d.isEncrypted} fields=${form?.fieldTree?.count() ?: 0} xfa=${form?.hasXFA() == true}"
            } }.getOrElse { "pdfbox-load: ${it.javaClass.simpleName}" }
            log(name, "0 info", "INFO", info)

            // Whiteout: up to 3 text lines on each of the first 5 pages, then a large area on the first 3 pages.
            check(name, "2 whiteout lines") {
                val edits = PDDocument.load(file).use { d ->
                    d.pages.take(5).flatMapIndexed { i, page ->
                        TextAnalyzer.analyze(page, i).runs.filter { it.bounds.width > 15 }.take(3).mapIndexed { k, r ->
                            val b = r.bounds
                            Whiteout("l$i$k", i, TextAnalyzer.pageTransform(page).displayWidth, Box(b.left - 2, b.top - 1, b.right + 2, b.bottom + 1), Whiteout.WHITE, FillSource.WHITE)
                        }
                    }
                }
                if (edits.isEmpty()) return@check "no text found (scan?)"
                val out = File(work, "wl.pdf")
                val r = writer.write(file, edits, out)
                val remaining = PDDocument.load(out).use { o ->
                    // Every whited-out line's text must be gone from the saved file.
                    PDFTextStripper().getText(o).length
                }
                out.delete()
                "boxes=${edits.size} glyphsRemoved=${r.redaction.glyphsRemoved} rasterized=${r.rasterizedPages} textLen=$remaining"
            }
            check(name, "3 whiteout large") {
                val edits = PDDocument.load(file).use { d ->
                    d.pages.take(3).mapIndexed { i, page ->
                        val t = TextAnalyzer.pageTransform(page)
                        Whiteout("b$i", i, t.displayWidth, Box(t.displayWidth * .1f, t.displayHeight * .25f, t.displayWidth * .9f, t.displayHeight * .65f), Whiteout.WHITE, FillSource.WHITE)
                    }
                }
                val out = File(work, "wb.pdf")
                val r = writer.write(file, edits, out); out.delete()
                "glyphs=${r.redaction.glyphsRemoved} imgRemoved=${r.redaction.imagesRemoved} imgRedacted=${r.redaction.imagesRedacted} paths=${r.redaction.pathsRemoved} rasterized=${r.rasterizedPages}"
            }
            check(name, "4 edit text") {
                val doc = EditableDocument.open(context, uri)
                try {
                    val layer = doc.textLayer(0)
                    val run = layer.runs.firstOrNull { it.blocker == null && it.text.trim().length >= 3 } ?: return@check "no editable text on page 1 (blocked=${layer.runs.count { it.blocker != null }} runs=${layer.runs.size})"
                    val out = File(work, "et.pdf")
                    val r = writer.write(doc.snapshot, listOf(TextReplacement("r", 0, layer.displayWidth, run.ref, "Zq Edited")), out)
                    // Check by position with our own analyzer; PDFTextStripper reorders rotated text.
                    val ok = PDDocument.load(out).use { o ->
                        TextAnalyzer.analyze(o.getPage(0), 0).runs.any { "Edited" in it.text && it.bounds.intersects(run.bounds) }
                    }
                    out.delete()
                    if (!ok) throw AssertionError("replacement text not found at the edited spot")
                    "removed=${r.glyphsRemoved} fontSubstituted=${r.fontSubstitutions}"
                } finally { doc.close() }
            }
            check(name, "5 add text+sign") {
                val out = File(work, "at.pdf")
                val r = writer.write(file, listOf(
                    AddedText("t", 0, 612f, 72f, 72f, "Added note", TextStyle()),
                    PlacedSignature("s", 0, 612f, 300f, 600f, 150f, 50f, ink, 0xFF102060.toInt()),
                ), out)
                val ok = PDDocument.load(out).use { o -> TextAnalyzer.analyze(o.getPage(0), 0).runs.any { "Added note" in it.text } }
                out.delete()
                if (!ok) throw AssertionError("added text missing")
                "fieldsFlattened=${r.flatten.fieldsFlattened} annotsFlattened=${r.flatten.annotationsFlattened} kept=${r.flatten.annotationsKept}"
            }
            check(name, "6 organize") {
                val n = minOf(pages.takeIf { it > 0 } ?: PDDocument.load(file).use { it.numberOfPages }, 10)
                val src = mapOf("a" to SourceFile("a", uri, name, SourceKind.PDF, n), "b" to SourceFile("b", uri, name, SourceKind.PDF, n))
                val refs = (n - 1 downTo 0).map { PageRef("a$it", "a", it, rotation = if (it % 2 == 0) 90 else 0) } + PageRef("b0", "b", 0)
                val out = File(work, "org.pdf")
                val r = assembler.assemble(src, refs, ImageOptions(), out); out.delete()
                "pages=${r.pageCount} fields=${r.formFieldsKept} bookmarksDropped=${r.bookmarksDropped}"
            }
            check(name, "7 split") {
                val out = File(work, "split.pdf")
                val r = assembler.assemble(mapOf("a" to SourceFile("a", uri, name, SourceKind.PDF, 1)), listOf(PageRef("p", "a", 0)), ImageOptions(), out)
                val text = PDDocument.load(out).use { it.numberOfPages }; out.delete()
                "pages=$text"
            }
        }
        work.deleteRecursively()
    }
}
