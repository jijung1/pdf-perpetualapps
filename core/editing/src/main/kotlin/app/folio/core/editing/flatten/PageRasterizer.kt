package app.folio.core.editing.flatten

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.folio.core.editing.text.TextAnalyzer
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.roundToInt

/**
 * Replaces whole pages with an image of themselves. Used only when content under a whiteout can't
 * be removed in place (images in formats this device can't decode). The image is rendered from the
 * already-redacted output, so the whiteout areas are covered in it; the original content is gone.
 */
object PageRasterizer {

    const val DPI = 200
    /** Longest side of a rendered page. 3000 px ≈ 200 dpi on Letter/A4 and ~27 MB while rendering. */
    private const val MAX_SIDE_PX = 3000

    /** Rewrites [file] in place with [pages] rasterised. Returns the pages actually converted. */
    fun rasterize(file: File, pages: Set<Int>, workDir: File): List<Int> {
        if (pages.isEmpty()) return emptyList()
        val renders = HashMap<Int, File>()
        // The platform renderer can't open encrypted files before Android 15, so render from a
        // decrypted temporary copy (private cache, deleted straight after). The output keeps its encryption.
        val renderSource = decryptedCopyIfNeeded(file, workDir)
        try {
            // 1. Render with the platform renderer (PDFium decodes every image format, CMYK included).
            ParcelFileDescriptor.open(renderSource, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (index in pages.sorted()) {
                        if (index !in 0 until renderer.pageCount) continue
                        renderer.openPage(index).use { page ->
                            renders[index] = try {
                                renderPage(page, MAX_SIDE_PX, index, workDir)
                            } catch (_: OutOfMemoryError) {
                                renderPage(page, MAX_SIDE_PX / 2, index, workDir)
                            }
                        }
                    }
                }
            }
        } finally {
            if (renderSource != file) renderSource.delete()
        }
        // 2. Swap each page's content for its image, keeping links.
        val tmp = File(workDir, "raster-out-${System.nanoTime()}.pdf")
        try {
            PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024 * 1024)).use { doc ->
                for ((index, jpg) in renders) {
                    val page = doc.getPage(index)
                    val t = TextAnalyzer.pageTransform(page)
                    val image = JPEGFactory.createFromByteArray(doc, jpg.readBytes())
                    page.resources = PDResources()
                    page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.CONTENTS)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                        val m = t.displayUpToUser
                        cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                        cs.drawImage(image, 0f, 0f, t.displayWidth, t.displayHeight)
                    }
                    page.annotations = page.annotations.filterIsInstance<PDAnnotationLink>()
                }
                app.folio.core.editing.Encryption.keepProtectionForSave(doc)
                doc.save(tmp)
            }
            tmp.copyTo(file, overwrite = true)
        } finally {
            tmp.delete()
            renders.values.forEach { it.delete() }
        }
        return renders.keys.sorted()
    }

    private fun renderPage(page: PdfRenderer.Page, maxSide: Int, index: Int, workDir: File): File {
        var scale = DPI / 72f
        val longest = maxOf(page.width, page.height) * scale
        if (longest > maxSide) scale *= maxSide / longest
        val w = (page.width * scale).roundToInt().coerceAtLeast(1)
        val h = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val out = File(workDir, "raster-$index-${System.nanoTime()}.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            return out
        } finally {
            bmp.recycle()
        }
    }

    private fun decryptedCopyIfNeeded(file: File, workDir: File): File {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (!doc.isEncrypted) return file
            val copy = File(workDir, "raster-src-${System.nanoTime()}.pdf")
            doc.isAllSecurityToBeRemoved = true
            doc.save(copy)
            return copy
        }
    }
}
