package app.folio.core.tools

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Shared helpers for reading user files into PDFBox and writing validated results. */
internal object PdfLoading {

    /** Mixed memory/temp-file buffering keeps big documents from exhausting the heap. */
    fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(12L * 1024 * 1024)

    fun copyToTemp(context: Context, uri: Uri, dir: File, suffix: String = ".pdf"): File {
        dir.mkdirs()
        val file = File(dir, "src-${UUID.randomUUID()}$suffix")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw ToolException(ToolError.SourceUnavailable)
            input.use { src -> file.outputStream().use { src.copyTo(it, 64 * 1024) } }
        } catch (e: FileNotFoundException) {
            file.delete(); throw ToolException(ToolError.SourceUnavailable, e)
        } catch (e: SecurityException) {
            file.delete(); throw ToolException(ToolError.SourceUnavailable, e)
        } catch (e: IOException) {
            file.delete(); throw ToolException(if (e.isNoSpace()) ToolError.InsufficientStorage else ToolError.SourceUnavailable, e)
        }
        return file
    }

    fun load(file: File, name: String): PDDocument = try {
        PDDocument.load(file, memory())
    } catch (e: InvalidPasswordException) {
        throw ToolException(ToolError.PasswordProtected(name), e)
    } catch (e: IOException) {
        throw ToolException(ToolError.Unreadable(name), e)
    }

    fun save(doc: PDDocument, output: File) {
        try {
            output.parentFile?.mkdirs()
            app.folio.core.editing.Encryption.keepProtectionForSave(doc)
            doc.save(output)
        } catch (e: IOException) {
            output.delete()
            throw ToolException(if (e.isNoSpace()) ToolError.InsufficientStorage else ToolError.WriteFailed, e)
        } catch (e: RuntimeException) {
            output.delete()
            throw ToolException(ToolError.WriteFailed, e)
        }
    }

    /** Re-opens [file]; a file that can't be read back (or has the wrong page count) is deleted. */
    fun validate(file: File, expectedPages: Int?) {
        try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use {
                if (expectedPages != null && it.numberOfPages != expectedPages) throw IOException("page count mismatch")
            }
        } catch (e: IOException) {
            file.delete()
            throw ToolException(ToolError.WriteFailed, e)
        }
    }

    private fun IOException.isNoSpace() = message?.contains("ENOSPC") == true || message?.contains("No space") == true
}
