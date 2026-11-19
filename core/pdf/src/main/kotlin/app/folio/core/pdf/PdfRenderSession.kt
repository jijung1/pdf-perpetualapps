package app.folio.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * An open PDF document backed by the platform [PdfRenderer] (PDFium).
 *
 * PdfRenderer allows only one open page at a time and is not thread-safe, so every native call
 * runs on a dedicated single-threaded dispatcher. Pages are rendered on demand and cached in a
 * shared, byte-bounded [PageBitmapCache]; the whole document is never held in memory.
 */
class PdfRenderSession private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageSizes: List<PageSize>,
    private val cache: PageBitmapCache,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val stagedCopy: File?,
) : Closeable {

    val id: String = UUID.randomUUID().toString()
    val pageCount: Int get() = pageSizes.size

    @Volatile
    private var closed = false
    private val closeScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Returns a cached render if one exists at exactly this width. */
    fun cached(pageIndex: Int, widthPx: Int): Bitmap? =
        cache.get(PageBitmapCache.key(id, pageIndex, widthPx))

    /**
     * Renders [pageIndex] at [widthPx] (height follows the page aspect ratio).
     * Cancellation is honoured before the native call; the native render itself is short.
     */
    suspend fun render(pageIndex: Int, widthPx: Int): Bitmap {
        require(pageIndex in pageSizes.indices) { "Page $pageIndex out of range" }
        val key = PageBitmapCache.key(id, pageIndex, widthPx)
        cache.get(key)?.let { return it }

        return withContext(dispatcher) {
            cache.get(key)?.let { return@withContext it }
            coroutineContext.ensureActive()
            check(!closed) { "Session closed" }
            val height = pageSizes[pageIndex].heightForWidth(widthPx)
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE) // PDF pages are transparent by default.
            renderer.openPage(pageIndex).use { page ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            cache.put(key, bitmap)
            bitmap
        }
    }

    /**
     * Renders only the region [left, top, right, bottom] (page points, display orientation) of
     * [pageIndex] at [pxPerPt] pixels per point. Not cached — used for colour sampling.
     */
    suspend fun renderRegion(pageIndex: Int, left: Float, top: Float, right: Float, bottom: Float, pxPerPt: Float): Bitmap? {
        require(pageIndex in pageSizes.indices) { "Page $pageIndex out of range" }
        val w = kotlin.math.ceil((right - left) * pxPerPt).toInt()
        val h = kotlin.math.ceil((bottom - top) * pxPerPt).toInt()
        if (w <= 0 || h <= 0 || w.toLong() * h > 16_000_000L) return null
        return withContext(dispatcher) {
            coroutineContext.ensureActive()
            if (closed) return@withContext null
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val m = android.graphics.Matrix().apply {
                postScale(pxPerPt, pxPerPt)
                postTranslate(-left * pxPerPt, -top * pxPerPt)
            }
            renderer.openPage(pageIndex).use { page -> page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            bitmap
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cache.evictPrefix("$id:")
        // Close on the render thread so we never race an in-flight render, and never block the caller.
        closeScope.launch {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            stagedCopy?.delete()
            closeScope.cancel()
        }
    }

    companion object {

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun newRenderDispatcher() = Dispatchers.IO.limitedParallelism(1)

        /**
         * Opens [uri] for rendering. Throws [PdfOpenException] with a typed reason on failure.
         * Must not be called on the main thread (it is a suspend function and switches to IO).
         */
        suspend fun open(context: Context, uri: Uri, cache: PageBitmapCache): PdfRenderSession =
            withContext(Dispatchers.IO) {
                val dispatcher = newRenderDispatcher()
                var staged: File? = null
                var pfd = openDescriptor(context, uri)
                if (!pfd.isSeekable()) {
                    // Some providers hand out pipes; PdfRenderer needs random access.
                    runCatching { pfd.close() }
                    staged = stageToCache(context, uri)
                    pfd = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
                }

                val renderer = try {
                    PdfRenderer(pfd)
                } catch (e: SecurityException) {
                    pfd.closeQuietly(); staged?.delete()
                    throw PdfOpenException(PdfOpenError.PasswordProtected, e)
                } catch (e: IOException) {
                    pfd.closeQuietly(); staged?.delete()
                    throw PdfOpenException(PdfOpenError.Corrupted, e)
                } catch (e: RuntimeException) {
                    pfd.closeQuietly(); staged?.delete()
                    throw PdfOpenException(PdfOpenError.Corrupted, e)
                }

                try {
                    if (renderer.pageCount <= 0) throw PdfOpenException(PdfOpenError.Empty)
                    // Reading page sizes is cheap (no rasterization) and needed for stable layout.
                    val sizes = withContext(dispatcher) {
                        List(renderer.pageCount) { i ->
                            coroutineContext.ensureActive()
                            renderer.openPage(i).use { PageSize(it.width, it.height) }
                        }
                    }
                    PdfRenderSession(pfd, renderer, sizes, cache, dispatcher, staged)
                } catch (t: Throwable) {
                    runCatching { renderer.close() }
                    pfd.closeQuietly(); staged?.delete()
                    if (t is PdfOpenException) throw t
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    throw PdfOpenException(PdfOpenError.Corrupted, t)
                }
            }

        private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw PdfOpenException(PdfOpenError.NotFound)
        } catch (e: FileNotFoundException) {
            throw PdfOpenException(PdfOpenError.NotFound, e)
        } catch (e: SecurityException) {
            throw PdfOpenException(PdfOpenError.AccessDenied, e)
        } catch (e: IllegalArgumentException) {
            throw PdfOpenException(PdfOpenError.NotFound, e)
        }

        private fun ParcelFileDescriptor.isSeekable(): Boolean = try {
            Os.lseek(fileDescriptor, 0, OsConstants.SEEK_CUR)
            true
        } catch (_: ErrnoException) {
            false
        }

        private fun stageToCache(context: Context, uri: Uri): File {
            val dir = File(context.cacheDir, "render-staging").apply { mkdirs() }
            val file = File.createTempFile("doc", ".pdf", dir)
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw PdfOpenException(PdfOpenError.NotFound)
                input.use { src -> file.outputStream().use { src.copyTo(it, 64 * 1024) } }
                return file
            } catch (e: IOException) {
                file.delete()
                val full = e.message?.contains("ENOSPC") == true
                throw PdfOpenException(if (full) PdfOpenError.InsufficientStorage else PdfOpenError.Unknown, e)
            }
        }

        private fun ParcelFileDescriptor.closeQuietly() {
            runCatching { close() }
        }

        /** Removes staged copies left behind by a process death. */
        fun clearStaleStaging(context: Context) {
            File(context.cacheDir, "render-staging").listFiles()?.forEach { it.delete() }
        }
    }
}
