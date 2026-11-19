package app.folio.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import app.folio.AppContainer
import app.folio.core.editing.EditError
import app.folio.core.editing.EditException
import app.folio.core.editing.EditHistory
import app.folio.core.editing.EditableDocument
import app.folio.core.editing.SavedSignature
import app.folio.core.editing.SignatureKind
import app.folio.core.editing.Stamps
import app.folio.core.editing.WriteReport
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.FillSource
import app.folio.core.editing.model.Ink
import app.folio.core.editing.model.PageEdit
import app.folio.core.editing.model.PlacedSignature
import app.folio.core.editing.model.TextReplacement
import app.folio.core.editing.model.TextStyle
import app.folio.core.editing.model.Whiteout
import app.folio.core.editing.text.EditableTextRun
import app.folio.core.editing.text.PageTextLayer
import app.folio.core.editing.whiteout.BackgroundSampler
import app.folio.core.editing.whiteout.SampleResult
import app.folio.core.editing.whiteout.SamplingConfig
import app.folio.core.files.FileOpException
import app.folio.ui.common.toMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

enum class EditTool { NONE, ADD_TEXT, EDIT_TEXT, WHITEOUT, PLACE_STAMP, EYEDROPPER }

/** What a tap places while [EditTool.PLACE_STAMP] is active. */
sealed interface StampKind {
    data object Check : StampKind
    data object Cross : StampKind
    data object Date : StampKind
    data class Initials(val ink: Ink, val color: Int) : StampKind
}

/** What the text dialog is currently editing, if anything. */
sealed interface TextDialogTarget {
    data class Added(val edit: AddedText, val isNew: Boolean) : TextDialogTarget
    data class Replacement(val page: Int, val refPageWidth: Float, val run: EditableTextRun, val existing: TextReplacement?) :
        TextDialogTarget
}

data class EditUiState(
    val history: EditHistory = EditHistory(),
    val savedEdits: List<PageEdit> = emptyList(),
    val selectedId: String? = null,
    val tool: EditTool = EditTool.NONE,
    val stamp: StampKind? = null,
    /** Whiteout whose colour the eyedropper will set. */
    val eyedropperFor: String? = null,
    val dialog: TextDialogTarget? = null,
    val textLayers: Map<Int, PageTextLayer> = emptyMap(),
    /** Glyph bounds per page, for showing what a whiteout will remove. */
    val glyphBoxes: Map<Int, List<Box>> = emptyMap(),
    /** Form-field rectangles per page, for lining up text and stamps. */
    val fieldBoxes: Map<Int, List<Box>> = emptyMap(),
    /** (form fields, annotations) that saving will flatten; null until known. */
    val flattenSummary: Pair<Int, Int>? = null,
    /** null = not yet known (snapshot not opened). */
    val canModify: Boolean? = null,
    val saveProgress: Float? = null,
) {
    val edits: List<PageEdit> get() = history.current
    val isDirty: Boolean get() = history.current.isNotEmpty() && history.current != savedEdits
    val selected: PageEdit? get() = selectedId?.let { id -> edits.firstOrNull { it.id == id } }
}

/** Outcome of a save, reported to the screen. */
sealed interface SaveOutcome {
    data class SavedCopy(val uri: Uri, val report: WriteReport) : SaveOutcome
    data class Overwritten(val report: WriteReport) : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/** Which corner of a whiteout is being dragged. */
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Pending edits for one open document plus everything needed to apply them.
 * Edits stay in memory (and in a crash-safe draft) until the user saves; the original file is
 * never touched unless they explicitly choose to overwrite it.
 */
@OptIn(FlowPreview::class)
class EditSession(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val currentUri: () -> Uri,
    private val onMessage: (String) -> Unit,
    /** Renders part of the original page (display points) for colour sampling. */
    private val renderRegion: suspend (page: Int, region: Box, pxPerPt: Float) -> Bitmap?,
    /** Display size of a page in points. */
    private val pageSize: (page: Int) -> Pair<Float, Float>?,
) {
    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    private var editable: EditableDocument? = null
    private val editableMutex = Mutex()
    private var saveJob: Job? = null
    private val layerJobs = HashMap<Int, Job>()
    private val glyphJobs = HashMap<Int, Job>()
    private val fieldJobs = HashMap<Int, Job>()
    private val sampleJobs = HashMap<String, Job>()
    private val samplingConfig = SamplingConfig()

    init {
        // Crash-safe drafts: persist pending edits shortly after every change.
        scope.launch {
            state.map { it.edits to it.savedEdits }.distinctUntilChanged().drop(1).debounce(600).collect { (edits, saved) ->
                container.drafts.save(currentUri().toString(), if (edits == saved) emptyList() else edits)
            }
        }
    }

    /** Restores unsaved edits from a previous session. Returns how many were restored. */
    suspend fun restoreDraft(): Int {
        val edits = container.drafts.load(currentUri().toString())
        if (edits.isNotEmpty()) _state.update { it.copy(history = EditHistory.restored(edits)) }
        return edits.size
    }

    fun discardAll() {
        _state.update { it.copy(history = EditHistory(), selectedId = null, dialog = null, savedEdits = emptyList()) }
        scope.launch { container.drafts.clear(currentUri().toString()) }
    }

    // ---- snapshot (PDFBox) -------------------------------------------------------------

    private suspend fun snapshot(): EditableDocument? = editableMutex.withLock {
        editable?.let { return it }
        try {
            EditableDocument.open(container.appContext, currentUri()).also { doc ->
                editable = doc
                _state.update { it.copy(canModify = doc.canModify) }
            }
        } catch (e: EditException) {
            onMessage(e.error.message())
            null
        }
    }

    fun close() {
        editable?.close(); editable = null
        (layerJobs.values + glyphJobs.values + fieldJobs.values).forEach { it.cancel() }
        layerJobs.clear(); glyphJobs.clear(); fieldJobs.clear()
        _state.update { it.copy(textLayers = emptyMap(), glyphBoxes = emptyMap(), fieldBoxes = emptyMap()) }
    }

    /** Called by visible pages while the edit-text tool is active. */
    fun requestTextLayer(page: Int) {
        if (_state.value.textLayers.containsKey(page) || layerJobs[page]?.isActive == true) return
        layerJobs[page] = scope.launch {
            val doc = snapshot() ?: return@launch
            val layer = doc.textLayer(page)
            _state.update { it.copy(textLayers = it.textLayers + (page to layer)) }
        }
    }

    /** Called by visible pages that have whiteouts (or while the whiteout tool is active). */
    fun requestGlyphBoxes(page: Int) {
        if (_state.value.glyphBoxes.containsKey(page) || glyphJobs[page]?.isActive == true) return
        glyphJobs[page] = scope.launch {
            val doc = snapshot() ?: return@launch
            val boxes = doc.glyphBoxes(page)
            _state.update { it.copy(glyphBoxes = it.glyphBoxes + (page to boxes)) }
        }
    }

    private suspend fun fieldBoxes(page: Int): List<Box> {
        _state.value.fieldBoxes[page]?.let { return it }
        val doc = snapshot() ?: return emptyList()
        val boxes = doc.formFieldBoxes(page)
        _state.update { it.copy(fieldBoxes = it.fieldBoxes + (page to boxes)) }
        return boxes
    }

    /** Prefetch field boxes so taps can snap instantly. */
    fun requestFieldBoxes(page: Int) {
        if (_state.value.fieldBoxes.containsKey(page) || fieldJobs[page]?.isActive == true) return
        fieldJobs[page] = scope.launch { fieldBoxes(page) }
    }

    /** Loads what a save will flatten, for the save dialog. */
    fun loadFlattenSummary() {
        if (_state.value.flattenSummary != null) return
        scope.launch {
            val doc = snapshot() ?: return@launch
            val summary = doc.flattenSummary()
            _state.update { it.copy(flattenSummary = summary) }
        }
    }

    // ---- tools & selection ------------------------------------------------------------

    fun setTool(tool: EditTool) {
        _state.update {
            val next = if (it.tool == tool) EditTool.NONE else tool
            it.copy(tool = next, selectedId = null, stamp = if (next == EditTool.PLACE_STAMP) it.stamp else null, eyedropperFor = null)
        }
        if (tool == EditTool.EDIT_TEXT) scope.launch {
            val doc = snapshot()
            if (doc != null && !doc.canModify) {
                onMessage("This PDF's permissions don't allow editing its text.")
                _state.update { it.copy(tool = EditTool.NONE) }
            }
        }
    }

    fun armStamp(kind: StampKind) = _state.update { it.copy(tool = EditTool.PLACE_STAMP, stamp = kind, selectedId = null) }

    fun select(id: String?) = _state.update { it.copy(selectedId = id, eyedropperFor = null, tool = if (it.tool == EditTool.EYEDROPPER) EditTool.NONE else it.tool) }

    /**
     * A tap on empty page space. Returns true if it was consumed by an editing action
     * (false lets the screen toggle its chrome).
     */
    fun onPageTap(page: Int, x: Float, y: Float, refPageWidth: Float): Boolean {
        val s = _state.value
        return when (s.tool) {
            EditTool.ADD_TEXT -> {
                scope.launch { placeTextAt(page, x, y, refPageWidth) }
                true
            }
            EditTool.EDIT_TEXT -> {
                editExistingTextAt(page, x, y, refPageWidth); true
            }
            EditTool.WHITEOUT -> {
                // A tap makes a line-sized box centred on the finger; drag to draw any size.
                val w = 120f; val h = 22f
                addWhiteout(page, Box(x - w / 2, y - h / 2, x + w / 2, y + h / 2), refPageWidth)
                true
            }
            EditTool.PLACE_STAMP -> {
                s.stamp?.let { stamp -> scope.launch { placeStamp(stamp, page, x, y, refPageWidth) } }
                true
            }
            EditTool.EYEDROPPER -> {
                pickColorAt(page, x, y)
                true
            }
            EditTool.NONE -> if (s.selectedId != null) { select(null); true } else false
        }
    }

    private suspend fun placeTextAt(page: Int, x: Float, y: Float, refPageWidth: Float) {
        var style = TextStyle()
        var px = x
        var py = (y - style.sizePt * 0.7f).coerceAtLeast(0f)
        // Inside a form field: line the text up with it and size it to fit.
        fieldBoxes(page).filter { it.contains(x, y, slop = 2f) }.minByOrNull { it.area }?.let { f ->
            style = style.copy(sizePt = (f.height * 0.62f).coerceIn(6f, 14f).let { kotlin.math.round(it) })
            val lineH = container.fonts.layout("Ag", style).height
            px = f.left + 2f
            py = f.top + (f.height - lineH) / 2f
        }
        val edit = AddedText(newId(), page, refPageWidth, px, py, "", style)
        _state.update { it.copy(tool = EditTool.NONE, dialog = TextDialogTarget.Added(edit, isNew = true)) }
    }

    private suspend fun placeStamp(stamp: StampKind, page: Int, x: Float, y: Float, refPageWidth: Float) {
        val field = fieldBoxes(page).filter { it.contains(x, y, slop = 2f) }.minByOrNull { it.area }
        when (stamp) {
            StampKind.Date -> {
                val text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
                var style = TextStyle(sizePt = 12f)
                var px = x
                var py = y - style.sizePt * 0.7f
                if (field != null) {
                    style = style.copy(sizePt = (field.height * 0.62f).coerceIn(6f, 14f).let { kotlin.math.round(it) })
                    px = field.left + 2f
                    py = field.top + (field.height - container.fonts.layout("Ag", style).height) / 2f
                }
                val edit = AddedText(newId(), page, refPageWidth, px, py.coerceAtLeast(0f), text, style)
                // One placement per pick, like Add text and Sign: the next tap never drops a surprise stamp.
                _state.update { it.copy(history = it.history.add(edit), selectedId = edit.id, tool = EditTool.NONE, stamp = null) }
            }
            else -> {
                val (ink, color, size) = when (stamp) {
                    StampKind.Check -> Triple(Stamps.CHECK, 0xFF111318.toInt(), 18f)
                    StampKind.Cross -> Triple(Stamps.CROSS, 0xFF111318.toInt(), 16f)
                    is StampKind.Initials -> Triple(stamp.ink, stamp.color, 26f)
                    StampKind.Date -> error("handled above")
                }
                var h = size
                var cx = x; var cy = y
                if (field != null) {
                    h = (field.height * 0.8f).coerceIn(8f, 40f)
                    cx = if (stamp is StampKind.Initials) field.left + h * ink.aspect / 2f + 2f else field.centerX
                    cy = field.centerY
                }
                val w = h * ink.aspect
                val sig = PlacedSignature(newId(), page, refPageWidth, cx - w / 2, cy - h / 2, w, h, ink, color)
                _state.update { it.copy(history = it.history.add(sig), selectedId = sig.id, tool = EditTool.NONE, stamp = null) }
            }
        }
    }

    private fun editExistingTextAt(page: Int, x: Float, y: Float, refPageWidth: Float) {
        scope.launch {
            val doc = snapshot() ?: return@launch
            val layer = doc.textLayer(page)
            _state.update { it.copy(textLayers = it.textLayers + (page to layer)) }
            if (layer.isEmpty) {
                onMessage("This page has no selectable text. It may be a scanned image.")
                return@launch
            }
            // Taps arrive in renderer points; runs are in PDFBox display points (normally identical).
            val k = if (refPageWidth > 0f && layer.displayWidth > 0f) layer.displayWidth / refPageWidth else 1f
            val run = hit(layer, x * k, y * k) ?: return@launch
            run.blocker?.let { blocker ->
                onMessage(blockerMessage(blocker))
                return@launch
            }
            val existing = _state.value.edits.filterIsInstance<TextReplacement>()
                .firstOrNull { it.page == page && it.target.glyphKeys == run.ref.glyphKeys }
            _state.update { it.copy(dialog = TextDialogTarget.Replacement(page, layer.displayWidth, run, existing)) }
        }
    }

    /** Smallest run whose box (plus a finger-sized slop) contains the point. */
    private fun hit(layer: PageTextLayer, x: Float, y: Float): EditableTextRun? =
        layer.runs.filter { it.bounds.contains(x, y, slop = 4f) }.minByOrNull { it.bounds.width * it.bounds.height }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun openTextEditor(id: String) {
        val e = _state.value.edits.firstOrNull { it.id == id } ?: return
        when (e) {
            is AddedText -> _state.update { it.copy(dialog = TextDialogTarget.Added(e, isNew = false)) }
            is TextReplacement -> {
                val run = EditableTextRun(e.target, null)
                _state.update { it.copy(dialog = TextDialogTarget.Replacement(e.page, e.refPageWidth, run, e)) }
            }
            is PlacedSignature, is Whiteout -> select(id)
        }
    }

    // ---- mutations ------------------------------------------------------------------

    fun confirmAddedText(edit: AddedText, text: String, style: TextStyle) {
        val updated = edit.copy(text = text.trimEnd(), style = style)
        _state.update { s ->
            val exists = s.edits.any { it.id == edit.id }
            val history = when {
                updated.text.isBlank() && exists -> s.history.remove(edit.id)
                updated.text.isBlank() -> s.history
                exists -> s.history.update(updated)
                else -> s.history.add(updated)
            }
            s.copy(history = history, dialog = null, selectedId = if (updated.text.isBlank()) null else updated.id)
        }
    }

    fun confirmReplacement(target: TextDialogTarget.Replacement, newText: String, style: TextStyle?) {
        val edit = TextReplacement(
            id = target.existing?.id ?: newId(),
            page = target.page,
            refPageWidth = target.refPageWidth,
            target = target.run.ref,
            newText = newText.replace('\n', ' '),
            style = style,
        )
        _state.update { s ->
            val history = if (target.existing != null) s.history.update(edit) else s.history.add(edit)
            s.copy(history = history, dialog = null, selectedId = null)
        }
    }

    fun delete(id: String) = _state.update { it.copy(history = it.history.remove(id), selectedId = null, dialog = null, eyedropperFor = null) }

    fun updateStyle(id: String, transform: (TextStyle) -> TextStyle) = _state.update { s ->
        val e = s.edits.firstOrNull { it.id == id } as? AddedText ?: return@update s
        s.copy(history = s.history.update(e.copy(style = transform(e.style))))
    }

    fun updateSignatureColor(id: String, color: Int) = _state.update { s ->
        val e = s.edits.firstOrNull { it.id == id } as? PlacedSignature ?: return@update s
        s.copy(history = s.history.update(e.copy(colorArgb = color)))
    }

    /** Live drag: updates without creating undo steps; [commitGesture] records one step at the end. */
    private var gestureBase: EditHistory? = null

    fun beginGesture() { gestureBase = _state.value.history }

    fun moveBy(id: String, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float) = _state.update { s ->
        val next = s.edits.map { e ->
            if (e.id != id) e else when (e) {
                is AddedText -> e.copy(x = (e.x + dx).coerceIn(-20f, pageWidth - 8f), y = (e.y + dy).coerceIn(-20f, pageHeight - 8f))
                is PlacedSignature -> e.copy(x = (e.x + dx).coerceIn(-e.width / 2, pageWidth - e.width / 2), y = (e.y + dy).coerceIn(-e.height / 2, pageHeight - e.height / 2))
                is Whiteout -> {
                    val b = e.box
                    val mx = dx.coerceIn(-b.left, pageWidth - b.right)
                    val my = dy.coerceIn(-b.top, pageHeight - b.bottom)
                    e.copy(box = Box(b.left + mx, b.top + my, b.right + mx, b.bottom + my))
                }
                is TextReplacement -> e
            }
        }
        s.copy(history = s.history.replace(next))
    }

    /** Resize from the bottom-right handle: signatures keep their aspect; text scales its font size. */
    fun resizeBy(id: String, dx: Float, textWidth: Float) = _state.update { s ->
        val next = s.edits.map { e ->
            if (e.id != id) e else when (e) {
                is PlacedSignature -> {
                    val w = (e.width + dx).coerceIn(12f, 600f)
                    e.copy(width = w, height = w / e.ink.aspect.coerceAtLeast(0.05f))
                }
                is AddedText -> {
                    val factor = ((textWidth + dx) / textWidth.coerceAtLeast(1f)).coerceIn(0.5f, 2f)
                    e.copy(style = e.style.copy(sizePt = (e.style.sizePt * factor).coerceIn(MIN_TEXT, MAX_TEXT)))
                }
                is Whiteout, is TextReplacement -> e
            }
        }
        s.copy(history = s.history.replace(next))
    }

    /** Drag one corner of a whiteout; it never flips and stays on the page. */
    fun resizeWhiteout(id: String, corner: Corner, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float) = _state.update { s ->
        val e = s.edits.firstOrNull { it.id == id } as? Whiteout ?: return@update s
        var (l, t, r, b) = e.box
        when (corner) {
            Corner.TOP_LEFT -> { l += dx; t += dy }
            Corner.TOP_RIGHT -> { r += dx; t += dy }
            Corner.BOTTOM_LEFT -> { l += dx; b += dy }
            Corner.BOTTOM_RIGHT -> { r += dx; b += dy }
        }
        l = l.coerceIn(0f, r - MIN_WHITEOUT); r = r.coerceIn(l + MIN_WHITEOUT, pageWidth)
        t = t.coerceIn(0f, b - MIN_WHITEOUT); b = b.coerceIn(t + MIN_WHITEOUT, pageHeight)
        s.copy(history = s.history.replace(s.edits.map { if (it.id == id) e.copy(box = Box(l, t, r, b)) else it }))
    }

    fun commitGesture() {
        val base = gestureBase ?: return
        gestureBase = null
        _state.update { s ->
            if (s.history.current == base.current) s else s.copy(history = base.push(s.history.current))
        }
        when (val e = _state.value.selected) {
            is Whiteout -> if (e.fillSource == FillSource.AUTO) resample(e.id, delayMs = 250)
            is AddedText -> scope.launch { snapToField(e) }
            else -> Unit
        }
    }

    /** Text dropped inside a form field lines up with it (no undo step of its own). */
    private suspend fun snapToField(e: AddedText) {
        val layout = container.fonts.layout(e.text, e.style)
        val cx = e.x + layout.width / 2f
        val cy = e.y + layout.height / 2f
        val f = fieldBoxes(e.page).filter { it.contains(cx, cy) }.minByOrNull { it.area } ?: return
        if (layout.lines.size > 1 || layout.height > f.height * 1.2f) return
        val x = f.left + 2f
        val y = f.top + (f.height - layout.height) / 2f
        if (kotlin.math.abs(x - e.x) < 0.5f && kotlin.math.abs(y - e.y) < 0.5f) return
        _state.update { s ->
            val cur = s.edits.firstOrNull { it.id == e.id } as? AddedText ?: return@update s
            s.copy(history = s.history.replace(s.edits.map { if (it.id == e.id) cur.copy(x = x, y = y) else it }))
        }
    }

    fun placeSignature(ink: Ink, color: Int, page: Int, pageWidth: Float, pageHeight: Float, centerY: Float?) {
        val width = (pageWidth * 0.34f).coerceAtMost(220f)
        val height = width / ink.aspect.coerceAtLeast(0.05f)
        val cy = (centerY ?: (pageHeight * 0.6f)).coerceIn(height / 2, pageHeight - height / 2)
        val sig = PlacedSignature(newId(), page, pageWidth, (pageWidth - width) / 2, cy - height / 2, width, height, ink, color)
        _state.update { it.copy(history = it.history.add(sig), selectedId = sig.id, tool = EditTool.NONE) }
    }

    fun undo() = _state.update { it.copy(history = it.history.undo(), selectedId = null) }
    fun redo() = _state.update { it.copy(history = it.history.redo(), selectedId = null) }

    suspend fun saveSignature(ink: Ink, color: Int, kind: SignatureKind = SignatureKind.SIGNATURE) =
        container.signatures.add(SavedSignature(newId(), ink, color, System.currentTimeMillis(), kind))

    // ---- whiteout -------------------------------------------------------------------

    /** Adds a whiteout over [box] (display points) and starts matching the background colour. */
    fun addWhiteout(page: Int, box: Box, refPageWidth: Float) {
        val (pw, ph) = pageSize(page) ?: (refPageWidth to Float.MAX_VALUE)
        val clipped = Box(box.left.coerceIn(0f, pw), box.top.coerceIn(0f, ph), box.right.coerceIn(0f, pw), box.bottom.coerceIn(0f, ph))
        if (clipped.width < MIN_WHITEOUT || clipped.height < MIN_WHITEOUT) return
        val w = Whiteout(newId(), page, refPageWidth, clipped)
        _state.update { it.copy(history = it.history.add(w), selectedId = w.id) }
        requestGlyphBoxes(page)
        resample(w.id, delayMs = 0, announceFailure = true)
    }

    /** Re-matches the fill to the background around the box (only for automatic colours). */
    fun resample(id: String, delayMs: Long, announceFailure: Boolean = false) {
        sampleJobs[id]?.cancel()
        sampleJobs[id] = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val w = _state.value.edits.firstOrNull { it.id == id } as? Whiteout ?: return@launch
            if (w.fillSource != FillSource.AUTO) return@launch
            val (pw, ph) = pageSize(w.page) ?: return@launch
            val k = if (w.refPageWidth > 0f) pw / w.refPageWidth else 1f
            val box = Box(w.box.left * k, w.box.top * k, w.box.right * k, w.box.bottom * k)
            val plan = BackgroundSampler.plan(box, pw, ph, samplingConfig)
            val bitmap = renderRegion(w.page, plan.region, plan.pxPerPt) ?: return@launch
            val result = withContext(Dispatchers.Default) {
                val px = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val exclude = plan.exclude.copy(
                    right = plan.exclude.right.coerceAtMost(bitmap.width),
                    bottom = plan.exclude.bottom.coerceAtMost(bitmap.height),
                )
                BackgroundSampler.sample(px, bitmap.width, bitmap.height, exclude, samplingConfig).also { bitmap.recycle() }
            }
            val (color, matched) = when (result) {
                is SampleResult.Confident -> result.argb to true
                is SampleResult.LowConfidence -> Whiteout.WHITE to false
            }
            _state.update { s ->
                val cur = s.edits.firstOrNull { it.id == id } as? Whiteout ?: return@update s
                if (cur.fillSource != FillSource.AUTO || cur.box != w.box) return@update s
                // Colour sampling isn't a user action: replace in place, no undo step.
                s.copy(history = s.history.replace(s.edits.map { if (it.id == id) cur.copy(fillArgb = color, autoMatched = matched) else it }))
            }
            if (!matched && announceFailure) onMessage("Couldn't match the background here, so white is used. Tap the colour to pick one.")
        }
    }

    fun setWhiteoutFill(id: String, source: FillSource, color: Int = Whiteout.WHITE) {
        _state.update { s ->
            val e = s.edits.firstOrNull { it.id == id } as? Whiteout ?: return@update s
            s.copy(history = s.history.update(e.copy(fillSource = source, fillArgb = if (source == FillSource.AUTO) e.fillArgb else color)), eyedropperFor = null)
        }
        if (source == FillSource.AUTO) resample(id, delayMs = 0, announceFailure = true)
    }

    fun startEyedropper(id: String) = _state.update { it.copy(tool = EditTool.EYEDROPPER, eyedropperFor = id) }

    private fun pickColorAt(page: Int, x: Float, y: Float) {
        val target = _state.value.eyedropperFor ?: return
        scope.launch {
            val r = 1.5f
            val bitmap = renderRegion(page, Box(x - r, y - r, x + r, y + r), 4f)
            if (bitmap == null) {
                onMessage("Couldn't read the colour there.")
                return@launch
            }
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            fun med(shift: Int) = px.map { (it shr shift) and 0xFF }.sorted()[px.size / 2]
            val color = (0xFF shl 24) or (med(16) shl 16) or (med(8) shl 8) or med(0)
            _state.update { it.copy(tool = EditTool.NONE, selectedId = target) }
            setWhiteoutFill(target, FillSource.MANUAL, color)
        }
    }

    // ---- saving -----------------------------------------------------------------------

    /**
     * Writes all edits into a new PDF. With [destination] the result is exported there (a copy);
     * otherwise it replaces the original via [overwrite].
     */
    fun save(destination: Uri?, beforeOverwrite: suspend () -> Unit = {}, onDone: (SaveOutcome) -> Unit) {
        if (saveJob?.isActive == true) return
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        saveJob = scope.launch {
            _state.update { it.copy(saveProgress = 0f, selectedId = null, tool = EditTool.NONE) }
            val out = File(container.workDir, "out-${UUID.randomUUID()}.pdf")
            try {
                val doc = snapshot() ?: run { onDone(SaveOutcome.Failed("Couldn't prepare the document for saving.")); return@launch }
                val report = container.editWriter.write(doc.snapshot, edits, out) { p ->
                    _state.update { it.copy(saveProgress = p * 0.9f) }
                }
                if (destination != null) {
                    container.documentAccess.export(out, destination)
                    _state.update { it.copy(savedEdits = edits) }
                    container.drafts.clear(currentUri().toString())
                    onDone(SaveOutcome.SavedCopy(destination, report))
                } else {
                    beforeOverwrite()
                    close()
                    container.documentAccess.overwrite(currentUri(), out, container.workDir)
                    container.drafts.clear(currentUri().toString())
                    _state.update { EditUiState() }
                    onDone(SaveOutcome.Overwritten(report))
                }
            } catch (e: EditException) {
                logSaveFailure(e)
                onDone(SaveOutcome.Failed(e.error.message()))
            } catch (e: FileOpException) {
                logSaveFailure(e)
                onDone(SaveOutcome.Failed(e.error.toMessage("Saving")))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected from the PDF library: report it rather than crash the app.
                logSaveFailure(e)
                onDone(SaveOutcome.Failed(EditError.WriteFailed.message()))
            } finally {
                out.delete()
                _state.update { it.copy(saveProgress = null) }
            }
        }
    }

    fun cancelSave() { saveJob?.cancel() }

    /** Debug builds only: the cause of a failed save, for diagnosing problem documents. */
    private fun logSaveFailure(e: Throwable) {
        val debuggable = (container.appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) android.util.Log.e("PdfEditor", "save failed", e)
    }

    companion object {
        const val MIN_TEXT = 4f
        const val MAX_TEXT = 144f
        const val MIN_WHITEOUT = 4f
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Extra sentences for the "saved" message, from what the writer reports. */
fun WriteReport.notes(): String = buildList {
    if (fontSubstitutions > 0) add("A similar font was used where the original lacked characters.")
    val f = flatten
    if (f.fieldsFlattened > 0) add("Form fields are now part of the page.")
    if (f.annotationsKept > 0) add("${f.annotationsKept} comment${if (f.annotationsKept == 1) "" else "s"} couldn't be flattened and were kept as is.")
    if (rasterizedPages.isNotEmpty()) {
        val pages = rasterizedPages.joinToString(", ") { (it + 1).toString() }
        add("Page${if (rasterizedPages.size == 1) "" else "s"} $pages became an image so the content under the whiteout could be removed.")
    }
}.joinToString(" ")

fun EditError.message(): String = when (this) {
    EditError.EditingNotPermitted -> "This PDF's permissions don't allow it to be modified."
    EditError.PasswordRequired -> "This PDF is password-protected."
    EditError.DocumentChanged -> "The document changed since it was opened. Reopen it and try again."
    EditError.Corrupted -> "This PDF is damaged and can't be edited."
    EditError.InsufficientStorage -> "Not enough storage space to save."
    EditError.SourceUnavailable -> "The original file is no longer available."
    EditError.WriteFailed -> "Saving failed. Your original file was not changed."
}

fun blockerMessage(b: app.folio.core.editing.text.EditBlocker): String = when (b) {
    app.folio.core.editing.text.EditBlocker.EMBEDDED_OBJECT ->
        "This text is inside an embedded object and can't be edited in place. Use Whiteout and Add text instead."
    app.folio.core.editing.text.EditBlocker.UNSUPPORTED_FONT ->
        "This text uses vertical writing, which can't be edited yet."
    app.folio.core.editing.text.EditBlocker.CLIPPING_TEXT ->
        "This text is used as a clipping shape and can't be edited safely."
}
