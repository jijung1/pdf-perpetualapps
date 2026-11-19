package app.folio.core.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** First-page thumbnails for the recents list, stored in the cache dir (safe for the OS to clear). */
class ThumbnailStore(private val dir: File) {

    private fun fileFor(uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        val name = digest.take(16).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.webp")
    }

    suspend fun save(uri: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val target = fileFor(uri)
            val tmp = File(dir, target.name + ".tmp")
            tmp.outputStream().use {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 82, it)
            }
            tmp.renameTo(target)
        }
    }

    suspend fun load(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = fileFor(uri)
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    suspend fun delete(uri: String) = withContext(Dispatchers.IO) { fileFor(uri).delete() }
}
