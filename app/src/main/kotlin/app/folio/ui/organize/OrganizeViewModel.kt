package app.folio.ui.organize

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.folio.AppContainer
import app.folio.core.files.FileOpException
import app.folio.core.pdf.PdfOpenError
import app.folio.core.pdf.PdfOpenException
import app.folio.core.pdf.PdfRenderSession
import app.folio.core.tools.ImageOptions
import app.folio.core.tools.ImagePageSize
import app.folio.core.tools.PageRef
import app.folio.core.tools.SourceFile
import app.folio.core.tools.SourceKind
import app.folio.core.tools.ToolException
import app.folio.core.tools.images.ImageDecoding
import app.folio.core.tools.organize.SplitMode
import app.folio.core.tools.organize.SplitPlan
import app.folio.core.tools.organize.SplitPlanner
import app.folio.ui.common.OutputFile
import app.folio.ui.common.delete
import app.folio.ui.common.newOutputFile
import app.folio.ui.common.shareUri
import app.folio.ui.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** Aspect ratio (width / height) of a page as currently shown, before the user's extra rotation. */
data class SourceInfo(val file: SourceFile, val colorIndex: Int, val pageAspects: List<Float>)

sealed interface OrganizeWork {
    data class Adding(val count: Int) : OrganizeWork
    data class Building(val title: String, val detail: String, val progress: Float) : OrganizeWork
}

data class OrganizeResult(
    val title: String,
    val summary: String,
    val files: List<OutputFile>,
    val notes: List<String>,
    /** Set once a single-file result has been saved, so it can be opened. */
    val savedUri: Uri? = null,
)

data class OrganizeUiState(
    val sources: Map<String, SourceInfo> = emptyMap(),
    val pages: List<PageRef> = emptyList(),
    val selection: Set<String> = emptySet(),
    val imageOptions: ImageOptions = ImageOptions(pageSize = defaultPageSize()),
    val work: OrganizeWork? = null,
    val result: OrganizeResult? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val loaded: Boolean = false,
) {
    val hasImages: Boolean get() = pages.any { sources[it.sourceId]?.file?.kind == SourceKind.IMAGE }
    val selectedPositions: Set<Int> get() = pages.withIndex().filter { it.value.key in selection }.map { it.index }.toSet()

    fun aspectOf(ref: PageRef): Float {
        val base = sources[ref.sourceId]?.pageAspects?.getOrNull(ref.pageIndex) ?: 0.75f
        return if (ref.normalizedRotation == 90 || ref.normalizedRotation == 270) 1f / base else base
    }
}

/** Letter in North America and the Philippines, A4 elsewhere. */
private fun defaultPageSize(): ImagePageSize =
    if (Locale.getDefault().country in setOf("US", "CA", "MX", "PH")) ImagePageSize.LETTER else ImagePageSize.A4

sealed interface OrganizeEvent {
    data class Message(val text: String, val undoable: Boolean = false) : OrganizeEvent
    data class Launch(val intent: Intent) : OrganizeEvent
}

class OrganizeViewModel(private val container: AppContainer, handoffId: String) : ViewModel() {

    private val _state = MutableStateFlow(OrganizeUiState())
    val state: StateFlow<OrganizeUiState> = _state.asStateFlow()

    private val _events = Channel<OrganizeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val sessions = HashMap<String, PdfRenderSession>()
    private val imageThumbs = LruCache<String, Bitmap>(24)
    private val undo = ArrayDeque<List<PageRef>>()
    private val redo = ArrayDeque<List<PageRef>>()
    private var workJob: Job? = null
    private var nextColor = 0

    init {
        val uris = container.handoff.take(handoffId)
        if (uris.isEmpty()) _state.update { it.copy(loaded = true) } else addFiles(uris, initial = true)
    }

    // ---- files ------------------------------------------------------------------------

    fun addFiles(uris: List<Uri>, initial: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(work = OrganizeWork.Adding(uris.size)) }
            val problems = ArrayList<String>()
            val added = ArrayList<SourceInfo>()
            for (uri in uris) {
                container.documentAccess.persistPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val info = runCatching { container.documentAccess.describe(uri) }.getOrNull()
                val name = info?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
                val isPdf = info?.mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
                val id = UUID.randomUUID().toString()
                if (isPdf) {
                    try {
                        val session = PdfRenderSession.open(container.appContext, uri, container.pageCache)
                        sessions[id] = session
                        val aspects = session.pageSizes.map { it.width.toFloat() / it.height.coerceAtLeast(1) }
                        added += SourceInfo(SourceFile(id, uri, name, SourceKind.PDF, session.pageCount), nextColor++, aspects)
                    } catch (e: PdfOpenException) {
                        problems += when (e.error) {
                            PdfOpenError.PasswordProtected -> "“$name” is password-protected, so it can't be added yet."
                            PdfOpenError.Empty -> "“$name” has no pages."
                            PdfOpenError.NotFound, PdfOpenError.AccessDenied -> "“$name” couldn't be opened."
                            else -> "“$name” can't be read. It may be damaged."
                        }
                    }
                } else {
                    try {
                        val img = withContext(Dispatchers.IO) { ImageDecoding.info(container.appContext, uri, name) }
                        val aspect = img.width.toFloat() / img.height.coerceAtLeast(1)
                        added += SourceInfo(SourceFile(id, uri, name, SourceKind.IMAGE, 1), nextColor++, listOf(aspect))
                    } catch (e: ToolException) {
                        problems += e.error.toMessage()
                    }
                }
            }
            _state.update { s ->
                val newPages = added.flatMap { src -> (0 until src.file.pageCount).map { PageRef(UUID.randomUUID().toString(), src.file.id, it) } }
                val pages = s.pages + newPages
                if (!initial && newPages.isNotEmpty()) pushUndo(s.pages)
                s.copy(
                    sources = s.sources + added.associateBy { it.file.id },
                    pages = pages,
                    work = null,
                    loaded = true,
                    canUndo = undo.isNotEmpty(),
                    canRedo = redo.isNotEmpty(),
                )
            }
            when {
                problems.size == 1 -> _events.send(OrganizeEvent.Message(problems.single()))
                problems.size > 1 -> _events.send(OrganizeEvent.Message("${problems.size} files couldn't be added. ${problems.first()}"))
                !initial && added.isNotEmpty() -> _events.send(OrganizeEvent.Message("Added ${pagesLabel(added.sumOf { it.file.pageCount })}"))
            }
        }
    }

    /** Removes a file and all its pages. */
    fun removeSource(id: String) = edit { pages -> pages.filterNot { it.sourceId == id } }

    // ---- thumbnails -------------------------------------------------------------------

    suspend fun thumbnail(ref: PageRef, widthPx: Int): Bitmap? {
        val src = _state.value.sources[ref.sourceId]?.file ?: return null
        return when (src.kind) {
            SourceKind.PDF -> runCatching { sessions[src.id]?.render(ref.pageIndex, widthPx) }.getOrNull()
            SourceKind.IMAGE -> {
                val key = "${src.id}@$widthPx"
                imageThumbs.get(key) ?: withContext(Dispatchers.IO) {
                    ImageDecoding.thumbnail(container.appContext, src.uri, (widthPx * 1.4f).toInt())
                }?.also { imageThumbs.put(key, it) }
            }
        }
    }

    // ---- editing ----------------------------------------------------------------------

    private fun pushUndo(previous: List<PageRef>) {
        undo.addLast(previous)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
    }

    private fun edit(clearSelection: Boolean = true, transform: (List<PageRef>) -> List<PageRef>) = _state.update { s ->
        val next = transform(s.pages)
        if (next == s.pages) return@update s
        pushUndo(s.pages)
        s.copy(pages = next, selection = if (clearSelection) emptySet() else s.selection.filter { k -> next.any { it.key == k } }.toSet(), canUndo = true, canRedo = false)
    }

    fun toggle(key: String) = _state.update { s -> s.copy(selection = if (key in s.selection) s.selection - key else s.selection + key) }
    fun selectAll() = _state.update { s -> s.copy(selection = s.pages.map { it.key }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
    fun selectSource(id: String) = _state.update { s -> s.copy(selection = s.pages.filter { it.sourceId == id }.map { it.key }.toSet()) }

    private var dragBase: List<PageRef>? = null
    fun beginDrag() { if (dragBase == null) dragBase = _state.value.pages }

    /** Live reorder while dragging (no undo step per move; one step is recorded at the end). */
    fun move(fromKey: Any, toKey: Any) = _state.update { s ->
        val from = s.pages.indexOfFirst { it.key == fromKey }
        val to = s.pages.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0 || from == to) return@update s
        s.copy(pages = s.pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun endDrag() {
        val base = dragBase ?: return
        dragBase = null
        if (base != _state.value.pages) {
            pushUndo(base)
            _state.update { it.copy(canUndo = true, canRedo = false) }
        }
    }

    /** Accessible alternative to dragging. */
    fun moveBy(key: String, delta: Int) = edit(clearSelection = false) { pages ->
        val i = pages.indexOfFirst { it.key == key }
        val j = (i + delta).coerceIn(0, pages.lastIndex)
        if (i < 0 || i == j) pages else pages.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun rotate(keys: Set<String>, degrees: Int) = edit(clearSelection = false) { pages ->
        pages.map { if (it.key in keys) it.copy(rotation = (it.rotation + degrees).mod(360)) else it }
    }

    fun delete(keys: Set<String>) {
        val count = keys.size
        if (count == 0) return
        edit { pages -> pages.filterNot { it.key in keys } }
        _events.trySend(OrganizeEvent.Message("Deleted ${pagesLabel(count)}", undoable = true))
    }

    fun duplicate(keys: Set<String>) = edit(clearSelection = false) { pages ->
        pages.flatMap { if (it.key in keys) listOf(it, it.copy(key = UUID.randomUUID().toString())) else listOf(it) }
    }

    fun undo() = _state.update { s ->
        val prev = undo.removeLastOrNull() ?: return@update s
        redo.addLast(s.pages)
        s.copy(pages = prev, selection = emptySet(), canUndo = undo.isNotEmpty(), canRedo = true)
    }

    fun redo() = _state.update { s ->
        val next = redo.removeLastOrNull() ?: return@update s
        undo.addLast(s.pages)
        s.copy(pages = next, selection = emptySet(), canUndo = true, canRedo = redo.isNotEmpty())
    }

    fun setImageOptions(options: ImageOptions) = _state.update { it.copy(imageOptions = options) }

    // ---- building ---------------------------------------------------------------------

    private fun baseName(): String {
        val s = _state.value
        val pdfs = s.pages.map { it.sourceId }.distinct().mapNotNull { s.sources[it]?.file }.filter { it.kind == SourceKind.PDF }
        val first = pdfs.firstOrNull()?.name?.substringBeforeLast('.')
        return when {
            pdfs.isEmpty() -> "Photos " + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
            s.pages.map { it.sourceId }.distinct().size == 1 -> first ?: "Document"
            else -> "${first ?: "Document"} (combined)"
        }
    }

    /** Builds one PDF from the current pages (or just [onlyKeys]). */
    fun createPdf(onlyKeys: Set<String>? = null) {
        val s = _state.value
        val pages = if (onlyKeys == null) s.pages else s.pages.filter { it.key in onlyKeys }
        if (pages.isEmpty()) return
        val name = if (onlyKeys == null) {
            val base = baseName()
            if (s.sources.size == 1 && s.sources.values.single().file.kind == SourceKind.PDF) "$base (organized).pdf" else "$base.pdf"
        } else {
            SplitPlanner.fileName(baseName(), app.folio.core.tools.organize.SplitPart(s.selectedPositions.sorted(), SplitPlanner.label(s.selectedPositions.sorted())))
        }
        runWork(if (onlyKeys == null) "Creating your PDF" else "Extracting pages") { onProgress ->
            val out = container.newOutputFile(name)
            val report = container.assembler.assemble(sourceFiles(), pages, s.imageOptions, out) { onProgress(it) }
            val notes = buildList {
                if (report.bookmarksDropped) add("Bookmarks were left out because the pages come from more than one file.")
                if (report.formFieldsKept > 0) add("Form fields were kept and can still be filled in.")
            }
            OrganizeResult(
                title = "Your PDF is ready",
                summary = "${pagesLabel(report.pageCount)} from ${filesLabel(pages.map { it.sourceId }.distinct().size)}",
                files = listOf(OutputFile(out, name, report.pageCount)),
                notes = notes,
            )
        }
    }

    fun planSplit(mode: SplitMode): SplitPlan = SplitPlanner.plan(mode, _state.value.pages.size)

    fun split(mode: SplitMode) {
        val plan = planSplit(mode) as? SplitPlan.Ok ?: return
        val s = _state.value
        val base = baseName()
        runWork("Splitting into ${filesLabel(plan.parts.size)}") { onProgress ->
            val files = ArrayList<OutputFile>()
            plan.parts.forEachIndexed { i, part ->
                val pages = part.positions.map { s.pages[it] }
                val name = SplitPlanner.fileName(base, part)
                val out = container.newOutputFile(name)
                container.assembler.assemble(sourceFiles(), pages, s.imageOptions, out) { p -> onProgress((i + p) / plan.parts.size) }
                files += OutputFile(out, name, pages.size)
            }
            OrganizeResult(
                title = if (files.size == 1) "Your PDF is ready" else "${files.size} PDFs are ready",
                summary = "Split ${pagesLabel(s.pages.size)}",
                files = files,
                notes = emptyList(),
            )
        }
    }

    private fun sourceFiles(): Map<String, SourceFile> = _state.value.sources.mapValues { it.value.file }

    private fun runWork(title: String, block: suspend (onProgress: (Float) -> Unit) -> OrganizeResult) {
        if (workJob?.isActive == true) return
        discardResult()
        workJob = viewModelScope.launch {
            val detail = "Pages are copied as they are, so text stays sharp and selectable."
            _state.update { it.copy(work = OrganizeWork.Building(title, detail, 0f)) }
            try {
                val result = block { p -> _state.update { it.copy(work = OrganizeWork.Building(title, detail, p.coerceIn(0f, 1f))) } }
                _state.update { it.copy(work = null, result = result) }
            } catch (e: ToolException) {
                _state.update { it.copy(work = null) }
                _events.send(OrganizeEvent.Message(e.error.toMessage()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(work = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(work = null) }
                _events.send(OrganizeEvent.Message("Something went wrong while creating the PDF. Your files weren't changed."))
            }
        }
    }

    fun cancelWork() {
        workJob?.cancel()
        _state.update { it.copy(work = null) }
    }

    /** Back to editing the page list; the finished files are discarded. */
    fun discardResult() {
        _state.value.result?.files?.forEach { it.delete() }
        _state.update { it.copy(result = null) }
    }

    fun suggestedName(): String = _state.value.result?.files?.firstOrNull()?.displayName ?: "Document.pdf"

    fun saveSingle(destination: Uri) {
        val result = _state.value.result ?: return
        val file = result.files.firstOrNull() ?: return
        viewModelScope.launch {
            try {
                container.documentAccess.export(file.file, destination)
                container.documentAccess.persistPermission(destination)
                _state.update { it.copy(result = it.result?.copy(savedUri = destination)) }
                _events.send(OrganizeEvent.Message("Saved ${file.displayName}"))
            } catch (e: FileOpException) {
                _events.send(OrganizeEvent.Message(e.error.toMessage("Saving")))
            }
        }
    }

    fun saveAllToFolder(tree: Uri) {
        val result = _state.value.result ?: return
        viewModelScope.launch {
            try {
                for (f in result.files) container.documentAccess.exportToFolder(f.file, tree, f.displayName)
                val folder = container.documentAccess.folderName(tree)
                _events.send(OrganizeEvent.Message("${filesLabel(result.files.size).replaceFirstChar { it.uppercase() }} saved to $folder"))
            } catch (e: FileOpException) {
                _events.send(OrganizeEvent.Message(e.error.toMessage("Saving")))
            }
        }
    }

    fun share() {
        val result = _state.value.result ?: return
        val uris = result.files.map { container.appContext.shareUri(it.file) }
        _events.trySend(OrganizeEvent.Launch(container.documentAccess.shareIntent(uris, "application/pdf", "Share PDF")))
    }

    override fun onCleared() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        _state.value.result?.files?.forEach { it.delete() }
    }

    companion object {
        fun pagesLabel(n: Int) = if (n == 1) "1 page" else "$n pages"
        fun filesLabel(n: Int) = if (n == 1) "1 file" else "$n files"
    }
}
