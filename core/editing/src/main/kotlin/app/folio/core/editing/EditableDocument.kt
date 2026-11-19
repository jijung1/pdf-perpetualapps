package app.folio.core.editing

import android.content.Context
import android.net.Uri
import app.folio.core.editing.flatten.Flattener
import app.folio.core.editing.model.Box
import app.folio.core.editing.text.PageTextLayer
import app.folio.core.editing.text.TextAnalyzer
import app.folio.core.editing.whiteout.RedactionScanner
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

sealed interface EditError {
    /** The PDF's owner permissions forbid modification. */
    data object EditingNotPermitted : EditError
    data object PasswordRequired : EditError
    /** The text being replaced no longer matches the document (it changed since analysis). */
    data object DocumentChanged : EditError
    data object Corrupted : EditError
    data object InsufficientStorage : EditError
    data object SourceUnavailable : EditError
    data object WriteFailed : EditError
}

class EditException(val error: EditError, cause: Throwable? = null) : Exception(error.toString(), cause)

/**
 * A private, read-only snapshot of the opened PDF, loaded with PDFBox for text analysis.
 * The snapshot file is also the input for saving, so glyph identities found during analysis
 * are guaranteed to refer to the same content when edits are written.
 */
class EditableDocument private constructor(
    val snapshot: File,
    private val doc: PDDocument,
) : Closeable {

    val pageCount: Int = doc.numberOfPages
    val isEncrypted: Boolean = doc.isEncrypted
    val canModify: Boolean = !doc.isEncrypted || doc.currentAccessPermission.canModify()

    private val layers = HashMap<Int, PageTextLayer>()

    @Volatile private var closed = false

    /** Existing text on [pageIndex], analysed on demand and cached. */
    suspend fun textLayer(pageIndex: Int): PageTextLayer = withContext(pdfboxDispatcher) {
        check(!closed) { "closed" }
        layers.getOrPut(pageIndex) {
            try {
                TextAnalyzer.analyze(doc.getPage(pageIndex), pageIndex)
            } catch (e: IOException) {
                PageTextLayer(pageIndex, 0f, emptyList())
            }
        }
    }

    private val glyphBoxCache = HashMap<Int, List<Box>>()
    private val fieldBoxCache = HashMap<Int, List<Box>>()

    /** Bounds of every glyph drawn on [pageIndex] (including inside embedded objects), display points. */
    suspend fun glyphBoxes(pageIndex: Int): List<Box> = withContext(pdfboxDispatcher) {
        check(!closed) { "closed" }
        glyphBoxCache.getOrPut(pageIndex) {
            try {
                val page = doc.getPage(pageIndex)
                RedactionScanner(page, TextAnalyzer.pageTransform(page)).scan(page).glyphs.map { it.box }
            } catch (e: IOException) {
                emptyList()
            }
        }
    }

    /** Form-field rectangles on [pageIndex] in display points, used to line up added text and stamps. */
    suspend fun formFieldBoxes(pageIndex: Int): List<Box> = withContext(pdfboxDispatcher) {
        check(!closed) { "closed" }
        fieldBoxCache.getOrPut(pageIndex) {
            runCatching {
                val page = doc.getPage(pageIndex)
                val t = TextAnalyzer.pageTransform(page)
                page.annotations.filterIsInstance<PDAnnotationWidget>().mapNotNull { w ->
                    val r = w.rectangle ?: return@mapNotNull null
                    if (w.isHidden || w.isNoView) return@mapNotNull null
                    val a = t.userToDisplay(r.lowerLeftX, r.lowerLeftY)
                    val b = t.userToDisplay(r.upperRightX, r.upperRightY)
                    Box.of(a.first, a.second, b.first, b.second).takeIf { it.width > 2f && it.height > 2f }
                }
            }.getOrDefault(emptyList())
        }
    }

    /** (form fields, visible annotations) a save will flatten into the page. */
    suspend fun flattenSummary(): Pair<Int, Int> = withContext(pdfboxDispatcher) {
        check(!closed) { "closed" }
        runCatching { Flattener.describe(doc) }.getOrDefault(0 to 0)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { doc.close() }
        snapshot.delete()
    }

    companion object {
        /** PDFBox objects are not thread-safe; all access is serialised on one thread. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val pdfboxDispatcher = Dispatchers.IO.limitedParallelism(1)

        private fun snapshotDir(context: Context) = File(context.cacheDir, "edit-snapshots")

        fun clearStaleSnapshots(context: Context) {
            snapshotDir(context).listFiles()?.forEach { it.delete() }
        }

        suspend fun open(context: Context, uri: Uri): EditableDocument = withContext(Dispatchers.IO) {
            PdfBox.init(context)
            val dir = snapshotDir(context).apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString() + ".pdf")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw EditException(EditError.SourceUnavailable)
                input.use { src -> file.outputStream().use { src.copyTo(it, 64 * 1024) } }
            } catch (e: FileNotFoundException) {
                file.delete(); throw EditException(EditError.SourceUnavailable, e)
            } catch (e: SecurityException) {
                file.delete(); throw EditException(EditError.SourceUnavailable, e)
            } catch (e: IOException) {
                file.delete()
                throw EditException(if (e.isNoSpace()) EditError.InsufficientStorage else EditError.SourceUnavailable, e)
            }
            val doc = withContext(pdfboxDispatcher) {
                try {
                    PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024 * 1024))
                } catch (e: InvalidPasswordException) {
                    file.delete(); throw EditException(EditError.PasswordRequired, e)
                } catch (e: IOException) {
                    file.delete(); throw EditException(EditError.Corrupted, e)
                }
            }
            EditableDocument(file, doc)
        }
    }
}

internal fun IOException.isNoSpace(): Boolean = message?.contains("ENOSPC") == true || message?.contains("No space") == true
