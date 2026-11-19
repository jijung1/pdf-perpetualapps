package app.folio

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import app.folio.core.editing.DraftStore
import app.folio.core.editing.EditableDocument
import app.folio.core.editing.PdfEditWriter
import app.folio.core.editing.SignatureStore
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.files.DocumentAccess
import app.folio.core.files.RecentDocumentsStore
import app.folio.core.files.ThumbnailStore
import app.folio.core.pdf.PageBitmapCache
import app.folio.core.pdf.PdfRenderSession
import app.folio.core.tools.organize.DocumentAssembler
import java.io.File

/** Manual dependency container — the graph is small enough that a DI framework would be overhead. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val documentAccess = DocumentAccess(context)
    val recents = RecentDocumentsStore(File(context.filesDir, "recents.json"))
    val thumbnails = ThumbnailStore(File(context.cacheDir, "thumbnails"))
    val pageCache = PageBitmapCache(PageBitmapCache.defaultBudgetBytes(context))
    val fonts = BundledFonts(context)
    val editWriter = PdfEditWriter(context, fonts)
    val signatures = SignatureStore(File(context.filesDir, "signatures.json"))
    val drafts = DraftStore(File(context.filesDir, "drafts"))
    val workDir: File get() = File(appContext.cacheDir, "work").apply { mkdirs() }

    /** Finished tool outputs; the only shareable directory (see res/xml/file_paths.xml). */
    val outputDir: File get() = File(workDir, "out").apply { mkdirs() }
    val assembler = DocumentAssembler(context) { workDir }

    /** Files handed between screens (e.g. picked on Home, opened in Organize). Lost on process death by design. */
    val handoff = FileHandoff()
}

/** Short-lived hand-off of picked file lists to the next screen, keyed by a random id. */
class FileHandoff {
    private val pending = java.util.concurrent.ConcurrentHashMap<String, List<android.net.Uri>>()
    fun put(uris: List<android.net.Uri>): String = java.util.UUID.randomUUID().toString().also { pending[it] = uris }
    fun take(id: String): List<android.net.Uri> = pending.remove(id).orEmpty()
}

class FolioApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PdfRenderSession.clearStaleStaging(this)
        EditableDocument.clearStaleSnapshots(this)
        container.workDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> container.pageCache.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> container.pageCache.trimTo(0.25f)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> container.pageCache.trimTo(0.5f)
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> container.pageCache.trimTo(0.5f)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        container.pageCache.clear()
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as FolioApplication).container
