package app.folio.ui.viewer

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.folio.appContainer
import app.folio.core.pdf.PdfOpenError
import app.folio.ui.common.StatusMessage
import app.folio.ui.common.toMessage
import app.folio.ui.editor.AddedTextDialog
import app.folio.ui.editor.EditTool
import app.folio.ui.editor.EditorToolbar
import app.folio.ui.editor.PageEditsActions
import app.folio.ui.editor.PageEditsLayer
import app.folio.ui.editor.ReplaceTextDialog
import app.folio.ui.editor.SaveDialog
import app.folio.ui.editor.SaveProgressBar
import app.folio.ui.editor.SelectionToolbar
import app.folio.ui.editor.SignaturePadDialog
import app.folio.ui.editor.SignaturesSheet
import app.folio.ui.editor.TextDialogTarget
import app.folio.ui.editor.ToolHint
import app.folio.ui.editor.UnsavedChangesDialog
import app.folio.ui.editor.StampChoice
import app.folio.ui.editor.StampKind
import app.folio.ui.editor.WhiteoutColorSheet
import app.folio.core.editing.SignatureKind
import app.folio.core.editing.model.FillSource
import app.folio.core.editing.whiteout.RedactionRules
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.HorizontalDivider
import app.folio.ui.theme.LocalFolioExtras
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    uri: Uri,
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    onOrganize: (split: Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: ViewerViewModel = viewModel(
        key = uri.toString(),
        factory = viewModelFactory { initializer { ViewerViewModel(container, uri) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val edit by vm.editor.state.collectAsStateWithLifecycle()
    val savedSignatures by container.signatures.items.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showThumbnails by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showSignatures by rememberSaveable { mutableStateOf(false) }
    var showSignaturePad by rememberSaveable { mutableStateOf(false) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var leaveAfterSave by remember { mutableStateOf(false) }
    var currentPageForPlacement by remember { mutableStateOf(0) }
    var showInitials by rememberSaveable { mutableStateOf(false) }
    var showInitialsPad by rememberSaveable { mutableStateOf(false) }
    var colorSheetFor by remember { mutableStateOf<String?>(null) }

    /** Tools that work on a saved file need pending edits saved first, so nothing is silently left out. */
    fun requireSaved(action: () -> Unit) {
        if (edit.isDirty) scope.launch { snackbar.showSnackbar("Save your edits first. This uses the saved file.") } else action()
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { dest -> if (dest != null) vm.saveCopy(dest) }

    val saveEditsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { dest -> if (dest != null) vm.saveEdits(dest) else leaveAfterSave = false }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ViewerEvent.Message -> snackbar.showSnackbar(event.text)
                is ViewerEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar("No app available to handle this")
                }
                is ViewerEvent.RestoredDraft -> {
                    val r = snackbar.showSnackbar(
                        "Restored ${event.count} unsaved ${if (event.count == 1) "edit" else "edits"}",
                        actionLabel = "Discard",
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.editor.discardAll()
                }
                is ViewerEvent.Overwrote -> {
                    if (leaveAfterSave) { leaveAfterSave = false; onBack() } else snackbar.showSnackbar(event.text)
                }
                is ViewerEvent.SavedCopy -> {
                    // The edits now live in the copy; clear them from the original so it's never
                    // ambiguous which file contains them, then continue in the copy.
                    vm.editor.discardAll()
                    android.widget.Toast.makeText(context, event.text, android.widget.Toast.LENGTH_LONG).show()
                    if (leaveAfterSave) {
                        leaveAfterSave = false
                        onBack()
                    } else {
                        onOpenDocument(event.uri)
                    }
                }
            }
        }
    }

    BackHandler(enabled = edit.isDirty) { showUnsaved = true }
    BackHandler(enabled = !edit.isDirty && (edit.selectedId != null || edit.tool != EditTool.NONE)) {
        vm.editor.select(null)
        if (edit.tool != EditTool.NONE) vm.editor.setTool(edit.tool)
    }

    val canvas = LocalFolioExtras.current.pageCanvas
    val ready = state.content as? ViewerContent.Ready
    val editingActive = edit.selectedId != null || edit.tool != EditTool.NONE

    Box(Modifier.fillMaxSize().background(canvas)) {
        when (val content = state.content) {
            ViewerContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is ViewerContent.Failed -> ErrorContent(
                error = content.error,
                canRemove = state.isInRecents,
                onRetry = vm::load,
                onRemove = { vm.removeFromRecents(); onBack() },
                onBack = onBack,
            )

            is ViewerContent.Ready -> {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = content.initialPage)
                val zoom = remember { DocumentZoomState() }

                val currentPage by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                        info.visibleItemsInfo.firstOrNull { center in it.offset..(it.offset + it.size) }?.index
                            ?: listState.firstVisibleItemIndex
                    }
                }
                LaunchedEffect(Unit) {
                    snapshotFlow { currentPage }.collect { vm.onPageVisible(it); currentPageForPlacement = it }
                }

                var pillVisible by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    snapshotFlow { listState.isScrollInProgress to currentPage }.collectLatest { (scrolling, _) ->
                        pillVisible = true
                        if (!scrolling) { delay(1800); pillVisible = false }
                    }
                }

                val actions = remember(vm) {
                    object : PageEditsActions {
                        override fun onSelect(id: String) = vm.editor.select(id)
                        override fun onOpen(id: String) = vm.editor.openTextEditor(id)
                        override fun onGestureStart() { zoom.panLocked = true; vm.editor.beginGesture() }
                        override fun onMove(id: String, dxPt: Float, dyPt: Float) {
                            val size = content.pageSizes.getOrNull(vm.editor.state.value.selected?.page ?: -1) ?: return
                            vm.editor.moveBy(id, dxPt, dyPt, size.width.toFloat(), size.height.toFloat())
                        }
                        override fun onResize(id: String, dxPt: Float, textWidthPt: Float) = vm.editor.resizeBy(id, dxPt, textWidthPt)
                        override fun onResizeCorner(id: String, corner: app.folio.ui.editor.Corner, dxPt: Float, dyPt: Float) {
                            val size = content.pageSizes.getOrNull(vm.editor.state.value.selected?.page ?: -1) ?: return
                            vm.editor.resizeWhiteout(id, corner, dxPt, dyPt, size.width.toFloat(), size.height.toFloat())
                        }
                        override fun onGestureEnd() { zoom.panLocked = false; vm.editor.commitGesture() }
                        override fun onRequestTextLayer(page: Int) = vm.editor.requestTextLayer(page)
                        override fun onRequestGlyphBoxes(page: Int) = vm.editor.requestGlyphBoxes(page)
                        override fun onDrawWhiteout(page: Int, box: app.folio.core.editing.model.Box, refPageWidth: Float) =
                            vm.editor.addWhiteout(page, box, refPageWidth)
                    }
                }

                val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                PdfPageList(
                    pageSizes = content.pageSizes,
                    listState = listState,
                    zoom = zoom,
                    contentPadding = PaddingValues(top = topInset + 72.dp, bottom = bottomInset + 150.dp),
                    cachedPage = vm::cachedPage,
                    renderPage = vm::renderPage,
                    onTap = { hit ->
                        val consumed = hit != null && vm.editor.onPageTap(hit.page, hit.x, hit.y, hit.pageWidth)
                        if (!consumed) chromeVisible = !chromeVisible
                    },
                    pageOverlay = { index, widthPx, _ ->
                        val size = content.pageSizes[index]
                        val pageEdits = edit.edits.filter { it.page == index }
                        val showRuns = edit.tool == EditTool.EDIT_TEXT
                        val drawing = edit.tool == EditTool.WHITEOUT
                        if (edit.tool == EditTool.ADD_TEXT || edit.tool == EditTool.PLACE_STAMP) {
                            LaunchedEffect(index) { vm.editor.requestFieldBoxes(index) }
                        }
                        if (pageEdits.isNotEmpty() || showRuns || drawing) {
                            PageEditsLayer(
                                page = index,
                                pageWidthPt = size.width.toFloat(),
                                pageHeightPt = size.height.toFloat(),
                                pxPerPt = widthPx / size.width.toFloat(),
                                edits = pageEdits,
                                selectedId = edit.selectedId,
                                showTextRuns = showRuns,
                                textLayer = edit.textLayers[index],
                                actions = actions,
                                drawingWhiteout = drawing,
                                glyphBoxes = edit.glyphBoxes[index],
                            )
                        }
                    },
                )

                // Bottom controls: page pill, tool hint, and the editing toolbar.
                Column(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AnimatedVisibility(visible = edit.tool != EditTool.NONE && edit.selected !is app.folio.core.editing.model.Whiteout, enter = fadeIn(), exit = fadeOut()) {
                        ToolHint(edit.tool, edit.stamp)
                    }
                    (edit.selected as? app.folio.core.editing.model.Whiteout)?.let { w ->
                        if (edit.tool != EditTool.EYEDROPPER) WhiteoutHint(w, edit.glyphBoxes[w.page])
                    }
                    AnimatedVisibility(visible = (pillVisible || chromeVisible) && !editingActive, enter = fadeIn(), exit = fadeOut()) {
                        PagePill(current = currentPage + 1, total = content.pageSizes.size, onClick = { showGoTo = true })
                    }
                    AnimatedVisibility(
                        visible = chromeVisible || editingActive,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        val selected = edit.selected
                        if (selected != null) {
                            SelectionToolbar(
                                edit = selected,
                                onEdit = { vm.editor.openTextEditor(selected.id) },
                                onStyle = { t -> vm.editor.updateStyle(selected.id, t) },
                                onInkColor = { c -> vm.editor.updateSignatureColor(selected.id, c) },
                                onDelete = { vm.editor.delete(selected.id) },
                                onDone = { vm.editor.select(null) },
                                onWhiteoutColor = { colorSheetFor = selected.id },
                                onEyedropper = { vm.editor.startEyedropper(selected.id) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        } else {
                            EditorToolbar(
                                tool = edit.tool,
                                canUndo = edit.history.canUndo,
                                canRedo = edit.history.canRedo,
                                onTool = vm.editor::setTool,
                                onSign = { showSignatures = true },
                                onStamp = { choice ->
                                    when (choice) {
                                        StampChoice.CHECK -> vm.editor.armStamp(StampKind.Check)
                                        StampChoice.CROSS -> vm.editor.armStamp(StampKind.Cross)
                                        StampChoice.DATE -> vm.editor.armStamp(StampKind.Date)
                                        StampChoice.INITIALS ->
                                            if (savedSignatures.any { it.kind == SignatureKind.INITIALS }) showInitials = true else showInitialsPad = true
                                    }
                                },
                                onUndo = vm.editor::undo,
                                onRedo = vm.editor::redo,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }

                if (showThumbnails) {
                    PageThumbnailsSheet(
                        pageSizes = content.pageSizes,
                        currentPage = currentPage,
                        cachedPage = vm::cachedPage,
                        renderPage = vm::renderPage,
                        onSelect = { page ->
                            showThumbnails = false
                            scope.launch { listState.scrollToItem(page) }
                        },
                        onDismiss = { showThumbnails = false },
                    )
                }
                if (showGoTo) {
                    GoToPageDialog(
                        pageCount = content.pageSizes.size,
                        currentPage = currentPage,
                        onGo = { page -> showGoTo = false; scope.launch { listState.scrollToItem(page) } },
                        onDismiss = { showGoTo = false },
                    )
                }
                if (showInfo) {
                    DocumentInfoDialog(
                        name = state.name,
                        info = state.info,
                        pageCount = content.pageSizes.size,
                        currentPageSize = content.pageSizes.getOrNull(currentPage),
                        onDismiss = { showInfo = false },
                    )
                }

                fun placeSignature(ink: app.folio.core.editing.model.Ink, color: Int) {
                    val page = currentPageForPlacement
                    val size = content.pageSizes[page]
                    vm.editor.placeSignature(ink, color, page, size.width.toFloat(), size.height.toFloat(), centerY = null)
                }

                if (showInitials) {
                    SignaturesSheet(
                        saved = savedSignatures,
                        kind = SignatureKind.INITIALS,
                        placing = true,
                        onPick = { sig -> showInitials = false; vm.editor.armStamp(StampKind.Initials(sig.ink, sig.colorArgb)) },
                        onNew = { showInitials = false; showInitialsPad = true },
                        onDelete = { sig -> scope.launch { container.signatures.delete(sig.id) } },
                        onDismiss = { showInitials = false },
                    )
                }
                if (showInitialsPad) {
                    SignaturePadDialog(
                        kind = SignatureKind.INITIALS,
                        onDone = { ink, color, save ->
                            showInitialsPad = false
                            if (save) scope.launch { vm.editor.saveSignature(ink, color, SignatureKind.INITIALS) }
                            vm.editor.armStamp(StampKind.Initials(ink, color))
                        },
                        onDismiss = { showInitialsPad = false },
                    )
                }
                colorSheetFor?.let { id ->
                    val w = edit.edits.firstOrNull { it.id == id } as? app.folio.core.editing.model.Whiteout
                    if (w == null) {
                        colorSheetFor = null
                    } else {
                        WhiteoutColorSheet(
                            edit = w,
                            onAuto = { vm.editor.setWhiteoutFill(id, FillSource.AUTO) },
                            onColor = { c, source -> vm.editor.setWhiteoutFill(id, source, c) },
                            onEyedropper = { colorSheetFor = null; vm.editor.startEyedropper(id) },
                            onDismiss = { colorSheetFor = null },
                        )
                    }
                }

                if (showSignatures) {
                    SignaturesSheet(
                        saved = savedSignatures,
                        placing = true,
                        onPick = { sig -> showSignatures = false; placeSignature(sig.ink, sig.colorArgb) },
                        onNew = { showSignatures = false; showSignaturePad = true },
                        onDelete = { sig -> scope.launch { container.signatures.delete(sig.id) } },
                        onDismiss = { showSignatures = false },
                    )
                }
                if (showSignaturePad) {
                    SignaturePadDialog(
                        onDone = { ink, color, save ->
                            showSignaturePad = false
                            if (save) scope.launch { vm.editor.saveSignature(ink, color) }
                            placeSignature(ink, color)
                        },
                        onDismiss = { showSignaturePad = false },
                    )
                }
            }
        }

        // Top chrome floats over the document so hiding it never shifts the page.
        AnimatedVisibility(
            visible = chromeVisible || ready == null || editingActive,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column {
                TopAppBar(
                    title = {
                        Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (edit.isDirty) showUnsaved = true else onBack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (ready != null) {
                            if (edit.isDirty) {
                                FilledTonalButton(onClick = { showSave = true }, enabled = edit.saveProgress == null) { Text("Save") }
                                Spacer(Modifier.width(4.dp))
                            }
                            IconButton(onClick = { showThumbnails = true }) {
                                Icon(Icons.Outlined.GridView, contentDescription = "Page thumbnails")
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Share original") },
                                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                        onClick = { menuOpen = false; vm.share() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save a copy of original") },
                                        leadingIcon = { Icon(Icons.Outlined.SaveAs, null) },
                                        onClick = { menuOpen = false; saveCopyLauncher.launch(vm.suggestedCopyName()) },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Organize pages") },
                                        leadingIcon = { Icon(Icons.Outlined.ViewModule, null) },
                                        onClick = { menuOpen = false; requireSaved { onOrganize(false) } },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Split into files") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCut, null) },
                                        onClick = { menuOpen = false; requireSaved { onOrganize(true) } },
                                    )
                                    HorizontalDivider()
                                    if (state.info?.canRename == true && !edit.isDirty) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                            onClick = { menuOpen = false; showRename = true },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Details") },
                                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                        onClick = { menuOpen = false; showInfo = true },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    ),
                )
                state.operationProgress?.let { progress ->
                    OperationBar(progress = progress, onCancel = vm::cancelOperation)
                }
                edit.saveProgress?.let { progress ->
                    SaveProgressBar(progress = progress, onCancel = vm.editor::cancelSave)
                }
            }
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 96.dp),
        )
    }

    // ---- dialogs ---------------------------------------------------------------------
    when (val d = edit.dialog) {
        is TextDialogTarget.Added -> AddedTextDialog(
            target = d,
            onConfirm = { text, style -> vm.editor.confirmAddedText(d.edit, text, style) },
            onDelete = { vm.editor.delete(d.edit.id) },
            onDismiss = vm.editor::dismissDialog,
        )
        is TextDialogTarget.Replacement -> ReplaceTextDialog(
            target = d,
            onConfirm = { text, style -> vm.editor.confirmReplacement(d, text, style) },
            onRevert = { d.existing?.let { vm.editor.delete(it.id) } },
            onDismiss = vm.editor::dismissDialog,
        )
        null -> Unit
    }

    if (showSave) {
        LaunchedEffect(Unit) { vm.editor.loadFlattenSummary() }
        SaveDialog(
            editCount = edit.edits.size,
            whiteoutCount = edit.edits.count { it is app.folio.core.editing.model.Whiteout },
            flattenSummary = edit.flattenSummary,
            canOverwrite = vm.canOverwrite,
            onSaveCopy = { showSave = false; saveEditsLauncher.launch(vm.suggestedEditedName()) },
            onOverwrite = { showSave = false; vm.saveEdits(null) },
            onDismiss = { showSave = false; leaveAfterSave = false },
        )
    }
    if (showUnsaved) {
        UnsavedChangesDialog(
            onSave = { showUnsaved = false; leaveAfterSave = true; showSave = true },
            onKeepDraft = { showUnsaved = false; onBack() },
            onDiscard = { showUnsaved = false; vm.editor.discardAll(); onBack() },
            onDismiss = { showUnsaved = false },
        )
    }
    if (showRename) {
        RenameDialog(
            currentName = state.name,
            onRename = { name -> showRename = false; vm.rename(name) },
            onDismiss = { showRename = false },
        )
    }
}

/** Says what a selected whiteout will remove, including characters that stick out past it. */
@Composable
private fun WhiteoutHint(w: app.folio.core.editing.model.Whiteout, glyphs: List<app.folio.core.editing.model.Box>?) {
    val removed = glyphs?.count { RedactionRules.removesGlyph(it, w.box) }
    val spill = glyphs?.let { RedactionRules.spillover(it, w.box) } ?: 0
    val text = when {
        removed == null -> "Everything under the box is removed when you save"
        spill > 0 -> "Removes $removed characters. $spill ${if (spill == 1) "sticks" else "stick"} out past the box and will be removed too."
        removed > 0 -> "Removes $removed characters and anything else under the box when you save"
        else -> "Removes anything under the box when you save"
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (spill > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (spill > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun PagePill(current: Int, total: Int, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = "Go to page", onClick = onClick)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            "$current / $total",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun OperationBar(progress: Float, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        Column(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Saving copy…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorContent(
    error: PdfOpenError,
    canRemove: Boolean,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    val msg = error.toMessage()
    val icon = if (error == PdfOpenError.PasswordProtected) Icons.Outlined.Lock else Icons.Outlined.ErrorOutline
    val gone = error == PdfOpenError.NotFound || error == PdfOpenError.AccessDenied
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        StatusMessage(icon = icon, title = msg.title, detail = msg.detail, isError = error != PdfOpenError.PasswordProtected) {
            if (gone && canRemove) {
                OutlinedButton(onClick = onRemove) { Text("Remove from recents") }
            } else {
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            if (error != PdfOpenError.PasswordProtected && error != PdfOpenError.Corrupted && error != PdfOpenError.Empty) {
                Button(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}
