package app.folio.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.folio.AppContainer
import app.folio.core.files.DocumentInfo
import app.folio.core.files.FileNames
import app.folio.core.files.FileOpException
import app.folio.core.files.RecentDocument
import app.folio.core.pdf.PageSize
import app.folio.core.pdf.PdfOpenError
import app.folio.core.pdf.PdfOpenException
import app.folio.core.pdf.PdfRenderSession
import app.folio.ui.common.toMessage
import app.folio.ui.editor.EditSession
import app.folio.ui.editor.SaveOutcome
import app.folio.ui.editor.notes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ViewerContent {
    data object Loading : ViewerContent
    data class Failed(val error: PdfOpenError) : ViewerContent
    data class Ready(val pageSizes: List<PageSize>, val initialPage: Int) : ViewerContent
}

data class ViewerUiState(
    val uri: Uri,
    val name: String = "",
    val info: DocumentInfo? = null,
    val content: ViewerContent = ViewerContent.Loading,
    /** Progress of a running file operation (0..1), or null when idle. */
    val operationProgress: Float? = null,
    val isInRecents: Boolean = false,
)

sealed interface ViewerEvent {
    data class Message(val text: String) : ViewerEvent
    data class Launch(val intent: Intent) : ViewerEvent
    data class RestoredDraft(val count: Int) : ViewerEvent
    data class SavedCopy(val uri: Uri, val text: String) : ViewerEvent
    data class Overwrote(val text: String) : ViewerEvent
}

@OptIn(FlowPreview::class)
class ViewerViewModel(private val container: AppContainer, uri: Uri) : ViewModel() {

    private val _state = MutableStateFlow(ViewerUiState(uri = uri))
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var session: PdfRenderSession? = null
    private var loadJob: Job? = null
    private var operationJob: Job? = null

    private val currentPage = MutableStateFlow(0)
    private var draftChecked = false

    val editor = EditSession(
        container = container,
        scope = viewModelScope,
        currentUri = { _state.value.uri },
        onMessage = { _events.trySend(ViewerEvent.Message(it)) },
        renderRegion = { page, region, pxPerPt ->
            runCatching { session?.renderRegion(page, region.left, region.top, region.right, region.bottom, pxPerPt) }.getOrNull()
        },
        pageSize = { page -> session?.pageSizes?.getOrNull(page)?.let { it.width.toFloat() to it.height.toFloat() } },
    )

    init {
        load()
        viewModelScope.launch {
            currentPage.drop(1).distinctUntilChanged().debounce(400).collect { page ->
                container.recents.updatePosition(_state.value.uri.toString(), page)
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        session?.close(); session = null
        _state.update { it.copy(content = ViewerContent.Loading) }
        loadJob = viewModelScope.launch {
            val uri = _state.value.uri
            val info = runCatching { container.documentAccess.describe(uri) }.getOrNull()
            _state.update { it.copy(name = info?.name ?: it.name.ifEmpty { "Document" }, info = info) }
            try {
                val opened = PdfRenderSession.open(container.appContext, uri, container.pageCache)
                session = opened
                val previous = container.recents.get(uri.toString())
                val startPage = (previous?.lastPage ?: 0).coerceIn(0, opened.pageCount - 1)
                container.recents.upsert(
                    RecentDocument(
                        uri = uri.toString(),
                        name = info?.name ?: previous?.name ?: "Document.pdf",
                        sizeBytes = info?.sizeBytes ?: previous?.sizeBytes,
                        pageCount = opened.pageCount,
                        lastOpenedAt = System.currentTimeMillis(),
                        lastPage = startPage,
                    ),
                )
                currentPage.value = startPage
                _state.update {
                    it.copy(content = ViewerContent.Ready(opened.pageSizes, startPage), isInRecents = true)
                }
                saveThumbnail(opened, uri)
                if (!draftChecked) {
                    draftChecked = true
                    val restored = editor.restoreDraft()
                    if (restored > 0) _events.send(ViewerEvent.RestoredDraft(restored))
                }
            } catch (e: PdfOpenException) {
                _state.update { it.copy(content = ViewerContent.Failed(e.error), isInRecents = container.recents.find(uri.toString()) != null) }
            }
        }
    }

    private fun saveThumbnail(session: PdfRenderSession, uri: Uri) {
        viewModelScope.launch {
            runCatching { session.render(0, THUMBNAIL_WIDTH) }.getOrNull()?.let {
                container.thumbnails.save(uri.toString(), it)
            }
        }
    }

    fun cachedPage(index: Int, widthPx: Int): Bitmap? = session?.cached(index, widthPx)

    /** Renders a page; returns null if the session is gone (closed / reloading) or the page fails. */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? {
        val s = session ?: return null
        return try {
            s.render(index, widthPx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    fun onPageVisible(index: Int) {
        currentPage.value = index
    }

    fun share() {
        val s = _state.value
        val intent = container.documentAccess.shareIntent(s.uri, "application/pdf", "Share ${s.name}")
        _events.trySend(ViewerEvent.Launch(intent))
    }

    fun suggestedCopyName(): String = FileNames.copyName(_state.value.name.ifEmpty { "Document.pdf" })

    fun saveCopy(destination: Uri) {
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            _state.update { it.copy(operationProgress = 0f) }
            try {
                container.documentAccess.copy(_state.value.uri, destination) { p ->
                    _state.update { it.copy(operationProgress = p) }
                }
                _events.send(ViewerEvent.Message("Copy saved"))
            } catch (e: FileOpException) {
                _events.send(ViewerEvent.Message(e.error.toMessage("Saving a copy")))
            } finally {
                _state.update { it.copy(operationProgress = null) }
            }
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    val canOverwrite: Boolean
        get() = container.documentAccess.hasWriteAccess(_state.value.uri, _state.value.info)

    fun suggestedEditedName(): String {
        val name = _state.value.name.ifEmpty { "Document.pdf" }
        return name.substringBeforeLast('.', name) + " (edited).pdf"
    }

    /** Saves pending edits as a new file at [destination], or over the original when null. */
    fun saveEdits(destination: Uri?) {
        editor.save(
            destination = destination,
            beforeOverwrite = {
                // Release the renderer's handle on the file before its contents are replaced.
                _state.update { it.copy(content = ViewerContent.Loading) }
                session?.close(); session = null
            },
        ) { outcome ->
            when (outcome) {
                is SaveOutcome.SavedCopy -> {
                    container.documentAccess.persistPermission(outcome.uri)
                    val note = outcome.report.notes().let { if (it.isEmpty()) "" else " $it" }
                    _events.trySend(ViewerEvent.SavedCopy(outcome.uri, "Edited copy saved.$note"))
                }
                is SaveOutcome.Overwritten -> {
                    container.pageCache.evictPrefix("")
                    load()
                    val note = outcome.report.notes().let { if (it.isEmpty()) "" else " $it" }
                    _events.trySend(ViewerEvent.Overwrote("Changes saved to the original file.$note"))
                }
                is SaveOutcome.Failed -> {
                    if (_state.value.content !is ViewerContent.Ready) load()
                    _events.trySend(ViewerEvent.Message(outcome.message))
                }
            }
        }
    }

    fun rename(input: String) {
        val newName = FileNames.sanitize(input) ?: run {
            _events.trySend(ViewerEvent.Message("Enter a file name"))
            return
        }
        val current = _state.value
        if (newName == current.name) return
        viewModelScope.launch {
            try {
                val newUri = container.documentAccess.rename(current.uri, newName)
                container.documentAccess.persistPermission(newUri)
                container.recents.rename(current.uri.toString(), newUri.toString(), newName)
                if (newUri != current.uri) {
                    container.thumbnails.delete(current.uri.toString())
                    session?.let { saveThumbnail(it, newUri) }
                }
                val info = runCatching { container.documentAccess.describe(newUri) }.getOrNull()
                _state.update { it.copy(uri = newUri, name = info?.name ?: newName, info = info ?: it.info) }
                _events.send(ViewerEvent.Message("Renamed to ${info?.name ?: newName}"))
            } catch (e: FileOpException) {
                _events.send(ViewerEvent.Message(e.error.toMessage("Renaming")))
            }
        }
    }

    fun removeFromRecents() {
        viewModelScope.launch {
            val uri = _state.value.uri
            container.recents.remove(uri.toString())
            container.thumbnails.delete(uri.toString())
            container.documentAccess.releasePermission(uri)
            _state.update { it.copy(isInRecents = false) }
        }
    }

    override fun onCleared() {
        if (_state.value.content is ViewerContent.Ready) {
            container.recents.savePositionAsync(_state.value.uri.toString(), currentPage.value)
        }
        session?.close()
        session = null
        editor.close()
    }

    private companion object {
        const val THUMBNAIL_WIDTH = 176
    }
}
