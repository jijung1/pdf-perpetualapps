package app.folio.core.editing

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.FillSource
import app.folio.core.editing.model.PageEdit
import app.folio.core.editing.model.TextStyle
import app.folio.core.editing.model.Whiteout
import app.folio.core.editing.text.TextAnalyzer
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs whiteout and save checks against PDFs placed (with adb run-as) in this test app's private
 * files folder, under "local-pdfs/". Nothing is bundled with the code, and only structure and outcomes
 * are logged, never document text. Skipped when the folder is empty.
 */
@RunWith(AndroidJUnit4::class)
class LocalFileWhiteoutTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Why does in-place removal leave glyphs behind? Structure only, never text. */
    @Test
    fun diagnoseLeftovers() {
        val dir = File(context.filesDir, "local-pdfs")
        val files = dir.listFiles { f -> f.name.endsWith(".pdf", ignoreCase = true) }.orEmpty().toList()
        assumeTrue("no local PDFs", files.isNotEmpty())
        PdfBox.init(context)
        val fonts = BundledFonts(context)
        for (file in files) PDDocument.load(file).use { doc ->
            app.folio.core.editing.flatten.Flattener.flatten(doc) {
                fonts.open(app.folio.core.editing.model.FontFamilyId.SANS, false).use { com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, it, true) }
            }
            val page = doc.getPage(0)
            val t = TextAnalyzer.pageTransform(page)
            val box = Box(t.displayWidth * .1f, t.displayHeight * .3f, t.displayWidth * .9f, t.displayHeight * .6f)
            val before = app.folio.core.editing.whiteout.RedactionScanner(page, t).scan(page)
            val targeted = before.glyphs.filter { app.folio.core.editing.whiteout.RedactionRules.removesGlyph(it.box, box) }
            Log.i("LocalPdf", "diag: contexts=${before.contexts.size} glyphs=${before.glyphs.size} targeted=${targeted.size} " +
                "targetedInForms=${targeted.count { it.ctx.form != null }} formDepths=${targeted.map { depth(it.ctx) }.groupingBy { it }.eachCount()}")
            val r = app.folio.core.editing.whiteout.Redactor(doc, page, listOf(app.folio.core.editing.whiteout.RedactionArea(box, Whiteout.WHITE))).redact()
            val after = app.folio.core.editing.whiteout.RedactionScanner(page, t).scan(page)
            val left = after.glyphs.filter { app.folio.core.editing.whiteout.RedactionRules.removesGlyph(it.box, box) }
            Log.i("LocalPdf", "diag: report=$r leftover=${left.size}")
            left.groupBy { Triple(it.ctx.id, depth(it.ctx), it.record.font.name) }.entries.take(15).forEach { (k, g) ->
                val form = g.first().ctx.form
                Log.i("LocalPdf", "diag: leftover ctx=${k.first} depth=${k.second} font=${k.third}/${g.first().record.font.javaClass.simpleName} n=${g.size} " +
                    "ops=${g.map { it.record.operatorIndex }.distinct().take(6)} mode=${g.first().record.renderingMode} " +
                    "formHasRes=${form?.cosObject?.containsKey(COSName.RESOURCES)} formIsGroup=${form is com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup}")
            }
            // Same contexts before: were the leftover glyph positions targeted, and in which kind of stream?
            val beforeByCtx = targeted.groupBy { it.ctx.id }.mapValues { it.value.size }
            Log.i("LocalPdf", "diag: targeted per ctx (first 15)=${beforeByCtx.entries.take(15)}")
        }
    }

    private fun depth(c: app.folio.core.editing.whiteout.RedactionScanner.Ctx): Int { var d = 0; var x = c.parent; while (x != null) { d++; x = x.parent }; return d }

    @Test
    fun localPdfsSaveWithWhiteout() = runTest {
        val dir = File(context.filesDir, "local-pdfs")
        val files = dir.listFiles { f -> f.name.endsWith(".pdf", ignoreCase = true) }.orEmpty().toList()
        assumeTrue("no local PDFs", files.isNotEmpty())
        PdfBox.init(context)
        val writer = PdfEditWriter(context, BundledFonts(context))
        val problems = ArrayList<String>()

        files.forEachIndexed { n, file ->
            val tag = "file${n + 1}"
            // Structure only.
            PDDocument.load(file).use { d ->
                val form = d.documentCatalog.acroForm
                val terminals = form?.fieldTree?.count() ?: 0
                val annots = d.pages.sumOf { runCatching { it.annotations.size }.getOrDefault(0) }
                val images = ArrayList<String>()
                for (page in d.pages) {
                    val res = page.resources ?: continue
                    for (name in res.xObjectNames) {
                        val s = res.getXObject(name)?.cosObject as? COSStream ?: continue
                        if (s.getCOSName(COSName.SUBTYPE) == COSName.IMAGE) images += "${s.getDictionaryObject(COSName.COLORSPACE)}/${s.filters}"
                    }
                }
                Log.i("LocalPdf", "$tag: pages=${d.numberOfPages} encrypted=${d.isEncrypted} canModify=${d.currentAccessPermission.canModify()} " +
                    "acroForm=${form != null} fields=$terminals xfa=${form?.hasXFA()} needAppearances=${form?.needAppearances} annotations=$annots images=$images")
            }

            suspend fun attempt(label: String, edits: List<PageEdit>) {
                val out = File(context.cacheDir, "local-out-$label.pdf")
                try {
                    val r = writer.write(file, edits, out)
                    val pages = PDDocument.load(out).use { it.numberOfPages }
                    Log.i("LocalPdf", "$tag [$label] OK pages=$pages fields=${r.flatten.fieldsFlattened} annotsFlattened=${r.flatten.annotationsFlattened} " +
                        "kept=${r.flatten.annotationsKept} glyphsRemoved=${r.redaction.glyphsRemoved} imagesRedacted=${r.redaction.imagesRedacted} " +
                        "imagesRemoved=${r.redaction.imagesRemoved} rasterized=${r.rasterizedPages}")
                } catch (e: Throwable) {
                    var c: Throwable? = e
                    val chain = buildString { while (c != null) { append(" <- ${c!!.javaClass.simpleName}: ${c!!.message?.take(160)}"); c = c!!.cause } }
                    problems += "$tag [$label]:$chain"
                    Log.e("LocalPdf", "$tag [$label] FAILED$chain", e)
                } finally {
                    out.delete()
                }
            }

            val (lineBoxes, pageBoxes) = PDDocument.load(file).use { d ->
                val lines = ArrayList<Whiteout>()
                val big = ArrayList<Whiteout>()
                d.pages.forEachIndexed { i, page ->
                    val t = TextAnalyzer.pageTransform(page)
                    TextAnalyzer.analyze(page, i).runs.filter { it.bounds.width > 20 }.take(3).forEachIndexed { k, run ->
                        val b = run.bounds
                        lines += Whiteout("l$i-$k", i, t.displayWidth, Box(b.left - 2, b.top - 1, b.right + 2, b.bottom + 1), Whiteout.WHITE, FillSource.WHITE)
                    }
                    big += Whiteout("b$i", i, t.displayWidth, Box(t.displayWidth * .1f, t.displayHeight * .3f, t.displayWidth * .9f, t.displayHeight * .6f), Whiteout.WHITE, FillSource.WHITE)
                }
                lines to big
            }
            attempt("add-text-only", listOf(AddedText("t", 0, 612f, 72f, 72f, "Test note", TextStyle())))
            attempt("whiteout-text-lines", lineBoxes)
            attempt("whiteout-large-areas", pageBoxes)
            attempt("whiteout-one-line", lineBoxes.take(1))
            val leftover = File(context.cacheDir, "local-check.pdf")
            runCatching {
                writer.write(file, lineBoxes.take(1), leftover)
                val before = PDDocument.load(file).use { PDFTextStripper().getText(it).length }
                val after = PDDocument.load(leftover).use { PDFTextStripper().getText(it).length }
                Log.i("LocalPdf", "$tag text length before=$before after one-line whiteout=$after")
            }
            leftover.delete()
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
