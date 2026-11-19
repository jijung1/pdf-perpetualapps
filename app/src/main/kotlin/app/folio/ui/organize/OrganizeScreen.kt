package app.folio.ui.organize

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.folio.appContainer
import app.folio.core.tools.ImageOptions
import app.folio.core.tools.ImagePageSize
import app.folio.core.tools.PageRef
import app.folio.core.tools.SourceKind
import app.folio.core.tools.organize.SplitMode
import app.folio.core.tools.organize.SplitPlan
import app.folio.core.tools.organize.SplitPlanner
import app.folio.ui.common.OutputPanel
import app.folio.ui.common.StatusMessage
import app.folio.ui.common.WorkingPanel
import app.folio.ui.theme.LocalFolioExtras
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** Colours that tell files apart on page tiles. Mid-tone so they read in light and dark themes. */
private val SourceColors = listOf(
    Color(0xFF3B5BA9), Color(0xFFD0743C), Color(0xFF2E8B6E), Color(0xFF9A4FB0),
    Color(0xFFB8913A), Color(0xFFC2485C), Color(0xFF4C8FB8), Color(0xFF6E7A3A),
)

private fun sourceColor(i: Int) = SourceColors[i.mod(SourceColors.size)]

private val PickTypes = arrayOf("application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(handoffId: String, openSplit: Boolean, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: OrganizeViewModel = viewModel(key = "organize-$handoffId", factory = viewModelFactory { initializer { OrganizeViewModel(container, handoffId) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showSplit by rememberSaveable { mutableStateOf(false) }
    var splitOpened by rememberSaveable { mutableStateOf(false) }
    var showPhotoOptions by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PageRef?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addFiles(it) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::saveSingle) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::saveAllToFolder) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is OrganizeEvent.Message -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = if (e.undoable) "Undo" else null, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undo()
                }
                is OrganizeEvent.Launch -> try {
                    context.startActivity(e.intent)
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar("No app available to share with")
                }
            }
        }
    }
    LaunchedEffect(openSplit, state.loaded) {
        if (openSplit && state.loaded && !splitOpened && state.pages.isNotEmpty()) { splitOpened = true; showSplit = true }
    }

    val selecting = state.selection.isNotEmpty()
    val leaveNeedsConfirm = state.pages.isNotEmpty() && state.result == null && state.canUndo
    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !selecting && state.result != null) { vm.discardResult() }
    BackHandler(enabled = !selecting && state.result == null && leaveNeedsConfirm) { confirmLeave = true }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.result != null -> "Done"
                            selecting -> "${state.selection.size} selected"
                            else -> "Merge & organize"
                        },
                    )
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
                    } else {
                        IconButton(onClick = {
                            when {
                                state.result != null -> vm.discardResult()
                                leaveNeedsConfirm -> confirmLeave = true
                                else -> onBack()
                            }
                        }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (state.result == null && state.pages.isNotEmpty()) {
                        IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo") }
                        IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More actions") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Outlined.SelectAll, null) }, onClick = { menu = false; vm.selectAll() })
                                if (state.hasImages) {
                                    DropdownMenuItem(text = { Text("Photo page size…") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { menu = false; showPhotoOptions = true })
                                }
                            }
                        }
                    }
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val result = state.result
                when {
                    result != null -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        OutputPanel(
                            title = result.title,
                            summary = result.summary,
                            files = result.files,
                            notes = result.notes,
                            onSave = {
                                if (result.files.size == 1) saveLauncher.launch(result.files.single().displayName) else folderLauncher.launch(null)
                            },
                            onShare = vm::share,
                            onOpen = result.savedUri?.let { uri -> { onOpenDocument(uri) } },
                            onStartOver = vm::discardResult,
                            startOverLabel = "Back to pages",
                            modifier = Modifier.widthIn(max = 560.dp),
                        )
                    }
                    !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.pages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        StatusMessage(
                            icon = Icons.Outlined.ViewModule,
                            title = "Add PDFs or photos",
                            detail = "Combine files, put pages in order, rotate or remove them, or split a PDF into parts. Photos become pages.",
                        ) {
                            Button(onClick = { addLauncher.launch(PickTypes) }) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text("Add files")
                            }
                        }
                    }
                    else -> PageGrid(
                        state = state,
                        vm = vm,
                        onPreview = { preview = it },
                        onPhotoOptions = { showPhotoOptions = true },
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }

            // Bottom actions.
            val work = state.work
            when {
                work is OrganizeWork.Building -> WorkingPanel(
                    title = work.title,
                    detail = work.detail,
                    progress = work.progress,
                    onCancel = vm::cancelWork,
                    modifier = Modifier.navigationBarsPadding().padding(12.dp),
                )
                state.result != null || state.pages.isEmpty() -> Unit
                selecting -> SelectionBar(
                    count = state.selection.size,
                    onRotateLeft = { vm.rotate(state.selection, -90) },
                    onRotateRight = { vm.rotate(state.selection, 90) },
                    onDuplicate = { vm.duplicate(state.selection) },
                    onExtract = { vm.createPdf(onlyKeys = state.selection) },
                    onDelete = { vm.delete(state.selection) },
                )
                else -> MainBar(
                    pageCount = state.pages.size,
                    adding = work is OrganizeWork.Adding,
                    onAdd = { addLauncher.launch(PickTypes) },
                    onSplit = { showSplit = true },
                    onCreate = { vm.createPdf() },
                )
            }
        }
    }

    if (showSplit) {
        SplitSheet(
            pageCount = state.pages.size,
            selected = state.selectedPositions,
            plan = vm::planSplit,
            baseName = state.sources.values.firstOrNull()?.file?.name ?: "Document",
            onSplit = { mode -> showSplit = false; vm.split(mode) },
            onDismiss = { showSplit = false },
        )
    }
    if (showPhotoOptions) {
        PhotoOptionsDialog(
            options = state.imageOptions,
            onApply = { vm.setImageOptions(it); showPhotoOptions = false },
            onDismiss = { showPhotoOptions = false },
        )
    }
    preview?.let { ref ->
        PagePreviewDialog(ref = ref, aspect = state.aspectOf(ref), load = { w -> vm.thumbnail(ref, w) }, onDismiss = { preview = null })
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without creating a PDF?") },
            text = { Text("Your page arrangement will be lost. Your original files are never changed.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep working") } },
        )
    }
}

// ---- grid ---------------------------------------------------------------------------

@Composable
private fun PageGrid(state: OrganizeUiState, vm: OrganizeViewModel, onPreview: (PageRef) -> Unit, onPhotoOptions: () -> Unit) {
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        vm.move(from.key, to.key)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header", contentType = "header") {
            Column {
                SourceChips(state, vm)
                if (state.hasImages) {
                    val o = state.imageOptions
                    AssistChip(
                        onClick = onPhotoOptions,
                        label = { Text("Photo pages: ${pageSizeLabel(o.pageSize)}${if (o.margins) ", with margins" else ""}") },
                        leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Text(
                    "Tap pages to select them. Press and hold to drag a page to a new place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
        items(state.pages, key = { it.key }) { ref ->
            val position = state.pages.indexOf(ref)
            ReorderableItem(reorderState, key = ref.key) { dragging ->
                val src = state.sources[ref.sourceId]
                val elevation by animateFloatAsState(if (dragging) 10f else 0f, label = "lift")
                PageTile(
                    ref = ref,
                    number = position + 1,
                    sourceName = src?.file?.name ?: "",
                    sourcePage = if (src?.file?.kind == SourceKind.PDF && (src.file.pageCount > 1)) ref.pageIndex + 1 else null,
                    isImage = src?.file?.kind == SourceKind.IMAGE,
                    color = sourceColor(src?.colorIndex ?: 0),
                    aspect = state.aspectOf(ref),
                    selected = ref.key in state.selection,
                    dragging = dragging,
                    load = { w -> vm.thumbnail(ref, w) },
                    onClick = { vm.toggle(ref.key) },
                    onPreview = { onPreview(ref) },
                    onMoveEarlier = { vm.moveBy(ref.key, -1) },
                    onMoveLater = { vm.moveBy(ref.key, 1) },
                    onRotate = { vm.rotate(setOf(ref.key), 90) },
                    onDelete = { vm.delete(setOf(ref.key)) },
                    modifier = Modifier
                        .graphicsLayer { shadowElevation = elevation }
                        .longPressDraggableHandle(
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.beginDrag() },
                            onDragStopped = { vm.endDrag() },
                        ),
                )
            }
        }
    }
}

@Composable
private fun SourceChips(state: OrganizeUiState, vm: OrganizeViewModel) {
    val used = state.pages.map { it.sourceId }.toSet()
    val sources = state.sources.values.filter { it.file.id in used }.sortedBy { it.colorIndex }
    if (sources.size < 2) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { s ->
            var open by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { Text(s.file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(sourceColor(s.colorIndex), CircleShape)) },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Select its pages") }, onClick = { open = false; vm.selectSource(s.file.id) })
                    DropdownMenuItem(text = { Text("Remove this file") }, onClick = { open = false; vm.removeSource(s.file.id) })
                }
            }
        }
    }
}

@Composable
private fun PageTile(
    ref: PageRef,
    number: Int,
    sourceName: String,
    sourcePage: Int?,
    isImage: Boolean,
    color: Color,
    aspect: Float,
    selected: Boolean,
    dragging: Boolean,
    load: suspend (Int) -> Bitmap?,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalFolioExtras.current.accent
    val density = LocalDensity.current
    val rotation = ref.normalizedRotation
    val description = buildString {
        append("Page $number")
        append(if (isImage) ", photo $sourceName" else ", from $sourceName")
        if (sourcePage != null) append(" page $sourcePage")
        if (rotation != 0) append(", rotated $rotation degrees")
    }
    Column(
        modifier
            .semantics {
                contentDescription = description
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
                customActions = listOf(
                    CustomAccessibilityAction("Move earlier") { onMoveEarlier(); true },
                    CustomAccessibilityAction("Move later") { onMoveLater(); true },
                    CustomAccessibilityAction("Rotate right") { onRotate(); true },
                    CustomAccessibilityAction("Delete page") { onDelete(); true },
                    CustomAccessibilityAction("Preview") { onPreview(); true },
                )
            }
            .clickable(role = Role.Checkbox, onClickLabel = if (selected) "Deselect" else "Select", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.78f), contentAlignment = Alignment.Center) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            // Fit the (rotated) page inside the square-ish cell.
            val fitW = minOf(boxW, boxH * aspect)
            val fitH = fitW / aspect
            val swap = rotation == 90 || rotation == 270
            // The bitmap is drawn unrotated, then turned; its unrotated box swaps sides.
            val drawW = if (swap) fitH else fitW
            val drawH = if (swap) fitW else fitH
            val renderWidth = (drawW * 1.0f).toInt().coerceIn(64, 480)
            val bitmap by produceState<Bitmap?>(null, ref.sourceId, ref.pageIndex, renderWidth) { value = load(renderWidth) }
            Box(
                Modifier
                    .size(with(density) { fitW.toDp() }, with(density) { fitH.toDp() })
                    .shadow(if (dragging) 8.dp else 1.dp, MaterialTheme.shapes.extraSmall)
                    .background(Color.White, MaterialTheme.shapes.extraSmall)
                    .then(if (selected) Modifier.border(3.dp, accent, MaterialTheme.shapes.extraSmall) else Modifier)
                    .clip(MaterialTheme.shapes.extraSmall),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .size(with(density) { drawW.toDp() }, with(density) { drawH.toDp() })
                            .graphicsLayer { rotationZ = rotation.toFloat() },
                    )
                } else {
                    Icon(if (isImage) Icons.Outlined.Image else Icons.Outlined.PictureAsPdf, contentDescription = null, tint = Color(0xFFBBBBBB))
                }
                // Source colour strip.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(color))
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
                // Bottom corner, clear of titles at the top of the page; 40dp touch target.
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp).size(40.dp)
                        .clickable(role = Role.Button, onClickLabel = "Preview page", onClick = onPreview),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(26.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.OpenInFull, contentDescription = "Preview page $number", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("$number", style = MaterialTheme.typography.labelMedium, color = if (selected) accent else MaterialTheme.colorScheme.onSurface)
    }
}

// ---- bars ---------------------------------------------------------------------------

@Composable
private fun MainBar(pageCount: Int, adding: Boolean, onAdd: () -> Unit, onSplit: () -> Unit, onCreate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onAdd, enabled = !adding) {
                if (adding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Add")
            }
            OutlinedButton(onClick = onSplit, enabled = pageCount > 1) {
                Icon(Icons.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Split")
            }
            Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                Text("Create PDF", maxLines = 1)
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onRotateLeft: () -> Unit, onRotateRight: () -> Unit, onDuplicate: () -> Unit, onExtract: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarAction(Icons.Outlined.RotateLeft, "Rotate left", onRotateLeft)
            BarAction(Icons.Outlined.RotateRight, "Rotate right", onRotateRight)
            BarAction(Icons.Outlined.ContentCopy, "Duplicate", onDuplicate)
            BarAction(Icons.Outlined.FileUpload, if (count == 1) "Extract page" else "Extract", onExtract)
            BarAction(Icons.Outlined.Delete, "Delete", onDelete, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

// ---- split --------------------------------------------------------------------------

private enum class SplitChoice { SELECTED, RANGES, EVERY_N, EACH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitSheet(
    pageCount: Int,
    selected: Set<Int>,
    plan: (SplitMode) -> SplitPlan,
    baseName: String,
    onSplit: (SplitMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf(if (selected.isNotEmpty()) SplitChoice.SELECTED else SplitChoice.RANGES) }
    var ranges by rememberSaveable { mutableStateOf("") }
    var every by rememberSaveable { mutableStateOf("2") }
    val mode: SplitMode = when (choice) {
        SplitChoice.SELECTED -> SplitMode.Selected(selected)
        SplitChoice.RANGES -> SplitMode.Ranges(ranges)
        SplitChoice.EVERY_N -> SplitMode.EveryN(every.toIntOrNull() ?: 0)
        SplitChoice.EACH -> SplitMode.EachPage
    }
    val result = plan(mode)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text("Split into separate PDFs", style = MaterialTheme.typography.titleLarge)
            Text("Uses the pages in their current order. Your original files aren't changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            SplitOption(choice == SplitChoice.SELECTED, "Selected pages", if (selected.isEmpty()) "Select pages first, then come back here" else "One PDF with the ${selected.size} selected", enabled = selected.isNotEmpty()) { choice = SplitChoice.SELECTED }
            SplitOption(choice == SplitChoice.RANGES, "Page ranges", "One PDF per range") { choice = SplitChoice.RANGES }
            if (choice == SplitChoice.RANGES) {
                OutlinedTextField(
                    value = ranges,
                    onValueChange = { ranges = it },
                    placeholder = { Text("e.g. 1-3, 5, 8-") },
                    singleLine = true,
                    isError = ranges.isNotBlank() && result is SplitPlan.Invalid,
                    supportingText = { Text("Pages 1 to $pageCount. “8-” means page 8 to the end.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 4.dp),
                )
            }
            SplitOption(choice == SplitChoice.EVERY_N, "Every few pages", "Equal parts of a fixed size") { choice = SplitChoice.EVERY_N }
            if (choice == SplitChoice.EVERY_N) {
                OutlinedTextField(
                    value = every,
                    onValueChange = { v -> every = v.filter(Char::isDigit).take(4) },
                    label = { Text("Pages per file") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 4.dp),
                )
            }
            SplitOption(choice == SplitChoice.EACH, "Every page separately", "$pageCount single-page PDFs") { choice = SplitChoice.EACH }

            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    when (result) {
                        is SplitPlan.Invalid -> Text(
                            if (choice == SplitChoice.RANGES && ranges.isBlank()) "Type the pages you want in each file." else result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (choice == SplitChoice.RANGES && ranges.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        is SplitPlan.Ok -> {
                            Text(
                                if (result.parts.size == 1) "Creates 1 PDF" else "Creates ${result.parts.size} PDFs",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            result.parts.take(4).forEach { part ->
                                Text(SplitPlanner.fileName(baseName, part), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            }
                            if (result.parts.size > 4) Text("…and ${result.parts.size - 4} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSplit(mode) }, enabled = result is SplitPlan.Ok) { Text("Split") }
            }
        }
    }
}

@Composable
private fun SplitOption(selected: Boolean, title: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- photo options & preview ----------------------------------------------------------

private fun pageSizeLabel(s: ImagePageSize) = when (s) {
    ImagePageSize.FIT -> "fit to photo"
    ImagePageSize.A4 -> "A4"
    ImagePageSize.LETTER -> "Letter"
}

@Composable
private fun PhotoOptionsDialog(options: ImageOptions, onApply: (ImageOptions) -> Unit, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf(options.pageSize) }
    var margins by remember { mutableStateOf(options.margins) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Photo pages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("How photos are placed when they become PDF pages.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val entries = listOf(ImagePageSize.A4, ImagePageSize.LETTER, ImagePageSize.FIT)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { i, s ->
                        SegmentedButton(selected = size == s, onClick = { size = s }, shape = SegmentedButtonDefaults.itemShape(i, entries.size)) {
                            Text(when (s) { ImagePageSize.A4 -> "A4"; ImagePageSize.LETTER -> "Letter"; ImagePageSize.FIT -> "Fit photo" })
                        }
                    }
                }
                Text(
                    if (size == ImagePageSize.FIT) "Each page takes the photo's own shape." else "Photos are centred and scaled to fit; landscape photos get landscape pages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { margins = !margins }) {
                    Text("White margin around photos", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = margins, onCheckedChange = { margins = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(ImageOptions(size, margins)) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PagePreviewDialog(ref: PageRef, aspect: Float, load: suspend (Int) -> Bitmap?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = onDismiss).padding(20.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val maxW = constraints.maxWidth.toFloat()
            val maxH = constraints.maxHeight.toFloat()
            val w = minOf(maxW, maxH * aspect)
            val h = w / aspect
            val rotation = ref.normalizedRotation
            val swap = rotation == 90 || rotation == 270
            val drawW = if (swap) h else w
            val drawH = if (swap) w else h
            val bitmap by produceState<Bitmap?>(null, ref) { value = load(drawW.toInt().coerceIn(200, 1600)) }
            Box(
                Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp == null) CircularProgressIndicator()
                else Image(
                    bmp.asImageBitmap(),
                    contentDescription = "Page preview",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(with(density) { drawW.toDp() }, with(density) { drawH.toDp() }).graphicsLayer { rotationZ = rotation.toFloat() },
                )
            }
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) { Text("Close") }
        }
    }
}
