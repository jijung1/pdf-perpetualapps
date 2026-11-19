package app.folio.core.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class DocumentInfo(
    val name: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    val canRename: Boolean,
    val canWrite: Boolean,
)

sealed interface FileOpError {
    data object NotFound : FileOpError
    data object AccessDenied : FileOpError
    data object InsufficientStorage : FileOpError
    data object NotSupported : FileOpError
    data object Unknown : FileOpError
}

class FileOpException(val error: FileOpError, cause: Throwable? = null) : Exception(error.toString(), cause)

/**
 * All file access goes through the Storage Access Framework — no broad storage permissions.
 */
class DocumentAccess(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Keeps read (and write, if granted) access across restarts so recents can reopen files. */
    fun persistPermission(uri: Uri, intentFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val flags = intentFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // Providers that don't support persistable grants simply fall back to the session grant.
    }

    fun releasePermission(uri: Uri) {
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    suspend fun describe(uri: Uri): DocumentInfo = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        var flags = 0
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS).takeIf { it >= 0 }?.let { flags = c.getInt(it) }
                }
            }
        } catch (e: SecurityException) {
            throw FileOpException(FileOpError.AccessDenied, e)
        } catch (_: Exception) {
            // Metadata is best-effort; fall through to defaults.
        }
        val isDocUri = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)
        DocumentInfo(
            name = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf",
            sizeBytes = size,
            mimeType = resolver.getType(uri),
            canRename = isDocUri && flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0,
            canWrite = isDocUri && flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
        )
    }

    /**
     * Streams [source] into [destination] (a uri from ACTION_CREATE_DOCUMENT).
     * On failure the partially written destination is deleted so no corrupt file is left behind.
     */
    suspend fun copy(source: Uri, destination: Uri, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val total = runCatching { describe(source).sizeBytes }.getOrNull()?.takeIf { it > 0 }
        try {
            val input = resolver.openInputStream(source) ?: throw FileOpException(FileOpError.NotFound)
            input.use { src ->
                val out = resolver.openOutputStream(destination, "wt") ?: throw FileOpException(FileOpError.AccessDenied)
                out.use { dst ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        copied += n
                        if (total != null) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw when (t) {
                is FileOpException -> t
                is kotlinx.coroutines.CancellationException -> t
                is FileNotFoundException -> FileOpException(FileOpError.NotFound, t)
                is SecurityException -> FileOpException(FileOpError.AccessDenied, t)
                is IOException -> FileOpException(
                    if (t.message?.contains("ENOSPC") == true) FileOpError.InsufficientStorage else FileOpError.Unknown, t,
                )
                else -> FileOpException(FileOpError.Unknown, t)
            }
        }
    }

    /** Whether we currently hold write access to [uri] (persisted grant or provider-declared writability). */
    fun hasWriteAccess(uri: Uri, info: DocumentInfo?): Boolean =
        info?.canWrite == true || resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /**
     * Replaces the contents of [target] with [replacement], protecting the original:
     * the current contents are first backed up to [workDir]; if writing fails part-way,
     * the backup is written back so the original is never left truncated or corrupt.
     */
    suspend fun overwrite(target: Uri, replacement: java.io.File, workDir: java.io.File) = withContext(Dispatchers.IO) {
        val backup = java.io.File(workDir, "backup-${System.nanoTime()}.pdf")
        try {
            val input = resolver.openInputStream(target) ?: throw FileOpException(FileOpError.NotFound)
            input.use { src -> backup.outputStream().use { src.copyTo(it, 64 * 1024) } }
        } catch (e: FileOpException) {
            backup.delete(); throw e
        } catch (e: SecurityException) {
            backup.delete(); throw FileOpException(FileOpError.AccessDenied, e)
        } catch (e: IOException) {
            backup.delete()
            throw FileOpException(if (e.message?.contains("ENOSPC") == true) FileOpError.InsufficientStorage else FileOpError.NotFound, e)
        }

        try {
            writeFileTo(replacement, target)
        } catch (t: Throwable) {
            // Restore the original bytes; if even that fails the backup file is kept for recovery.
            val restored = runCatching { writeFileTo(backup, target) }.isSuccess
            if (restored) backup.delete()
            throw when (t) {
                is FileOpException -> t
                is SecurityException -> FileOpException(FileOpError.AccessDenied, t)
                is IOException -> FileOpException(
                    if (t.message?.contains("ENOSPC") == true) FileOpError.InsufficientStorage else FileOpError.Unknown, t,
                )
                else -> FileOpException(FileOpError.Unknown, t)
            }
        }
        backup.delete()
    }

    private fun writeFileTo(file: java.io.File, target: Uri) {
        val out = resolver.openOutputStream(target, "wt") ?: throw FileOpException(FileOpError.AccessDenied)
        out.use { dst -> file.inputStream().use { it.copyTo(dst, 64 * 1024) } }
    }

    /** Copies a local file (e.g. a freshly written PDF) to a SAF destination. */
    suspend fun export(file: java.io.File, destination: Uri) = copy(Uri.fromFile(file), destination)

    /**
     * Creates [displayName] in a folder the user picked (ACTION_OPEN_DOCUMENT_TREE) and copies
     * [file] into it. Providers add " (1)" etc. themselves when the name already exists.
     */
    suspend fun exportToFolder(file: java.io.File, treeUri: Uri, displayName: String, mimeType: String = "application/pdf"): Uri =
        withContext(Dispatchers.IO) {
            val parent = try {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            } catch (e: IllegalArgumentException) {
                throw FileOpException(FileOpError.NotFound, e)
            }
            val created = try {
                DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
            } catch (e: SecurityException) {
                throw FileOpException(FileOpError.AccessDenied, e)
            } catch (e: FileNotFoundException) {
                throw FileOpException(FileOpError.NotFound, e)
            } catch (e: Exception) {
                throw FileOpException(FileOpError.Unknown, e)
            } ?: throw FileOpException(FileOpError.AccessDenied)
            copy(Uri.fromFile(file), created)
            created
        }

    /** A readable name for a picked folder, e.g. "Download". */
    fun folderName(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return "the chosen folder"
        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { id.substringBefore(':').ifBlank { "the chosen folder" } }
    }

    /** Share several files at once. */
    fun shareIntent(uris: List<Uri>, mimeType: String, title: String?): Intent {
        if (uris.size == 1) return shareIntent(uris.single(), mimeType, title)
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Renames via the document provider. Returns the (possibly new) uri. */
    suspend fun rename(uri: Uri, newName: String): Uri = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.renameDocument(resolver, uri, newName) ?: uri
        } catch (e: UnsupportedOperationException) {
            throw FileOpException(FileOpError.NotSupported, e)
        } catch (e: SecurityException) {
            throw FileOpException(FileOpError.AccessDenied, e)
        } catch (e: FileNotFoundException) {
            throw FileOpException(FileOpError.NotFound, e)
        } catch (e: Exception) {
            throw FileOpException(FileOpError.Unknown, e)
        }
    }

    /** A native share-sheet intent. The read grant is forwarded to the receiving app only. */
    fun shareIntent(uri: Uri, mimeType: String = "application/pdf", title: String? = null): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

/** Ensures a user-entered file name keeps a .pdf extension and contains no path separators. */
object FileNames {
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun sanitize(input: String, extension: String = "pdf"): String? {
        val trimmed = input.trim().replace(illegal, "_").trim('.', ' ')
        if (trimmed.isEmpty()) return null
        val ext = ".$extension"
        return if (trimmed.endsWith(ext, ignoreCase = true)) trimmed else trimmed + ext
    }

    fun copyName(original: String): String {
        val base = original.substringBeforeLast('.', original)
        val ext = original.substringAfterLast('.', "pdf")
        return "$base (copy).$ext"
    }
}
