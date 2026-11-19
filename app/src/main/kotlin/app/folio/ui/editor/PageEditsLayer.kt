package app.folio.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.folio.appContainer
import app.folio.core.editing.fonts.BundledFonts
import app.folio.core.editing.ink.InkGeometry
import app.folio.core.editing.ink.PathOp
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.FontFamilyId
import app.folio.core.editing.model.Ink
import app.folio.core.editing.model.PageEdit
import app.folio.core.editing.model.PlacedSignature
import app.folio.core.editing.model.TextReplacement
import app.folio.core.editing.model.TextStyle
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.Whiteout
import app.folio.core.editing.text.PageTextLayer
import app.folio.core.editing.whiteout.RedactionRules
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.folio.ui.theme.LocalFolioExtras
import kotlin.math.roundToInt

/** Callbacks from the overlay back into the [EditSession]. */
interface PageEditsActions {
    fun onSelect(id: String)
    fun onOpen(id: String)
    fun onGestureStart()
    fun onMove(id: String, dxPt: Float, dyPt: Float)
    fun onResize(id: String, dxPt: Float, textWidthPt: Float)
    fun onResizeCorner(id: String, corner: Corner, dxPt: Float, dyPt: Float)
    fun onGestureEnd()
    fun onRequestTextLayer(page: Int)
    fun onRequestGlyphBoxes(page: Int)
    fun onDrawWhiteout(page: Int, box: Box, refPageWidth: Float)
}

/**
 * Edits for one page, drawn over the rendered bitmap. Sizes are in page points scaled by
 * [pxPerPt], so overlays track zoom exactly.
 */
@Composable
fun PageEditsLayer(
    page: Int,
    pageWidthPt: Float,
    pageHeightPt: Float,
    pxPerPt: Float,
    edits: List<PageEdit>,
    selectedId: String?,
    showTextRuns: Boolean,
    textLayer: PageTextLayer?,
    actions: PageEditsActions,
    drawingWhiteout: Boolean = false,
    glyphBoxes: List<Box>? = null,
) {
    val fonts = LocalContext.current.appContainer.fonts
    val accent = LocalFolioExtras.current.accent
    val density = LocalDensity.current

    if (showTextRuns) {
        LaunchedEffect(page) { actions.onRequestTextLayer(page) }
    }
    val whiteouts = edits.filterIsInstance<Whiteout>()
    if (whiteouts.isNotEmpty() || drawingWhiteout) {
        LaunchedEffect(page) { actions.onRequestGlyphBoxes(page) }
    }

    Box(Modifier.fillMaxSize()) {
        // Drawing surface for new whiteouts (below existing items, so those can still be dragged).
        if (drawingWhiteout) WhiteoutDrawSurface(page, pxPerPt, pageWidthPt, accent, actions)

        // Whiteouts sit under every other edit, as they do in the saved file.
        for (w in whiteouts) {
            val s = pxPerPt * (pageWidthPt / w.refPageWidth.coerceAtLeast(1f))
            WhiteoutBox(
                edit = w,
                selected = w.id == selectedId,
                scale = s,
                glyphs = glyphBoxes,
                glyphScale = pxPerPt,
                accent = accent,
                actions = actions,
            )
        }

        // Replacement previews and (optionally) outlines of editable text are drawn in one canvas.
        val replacements = edits.filterIsInstance<TextReplacement>()
        if (replacements.isNotEmpty() || (showTextRuns && textLayer != null)) {
            Canvas(Modifier.fillMaxSize()) {
                val layerScale = textLayer?.displayWidth?.takeIf { it > 0f }?.let { pageWidthPt / it } ?: 1f
                if (showTextRuns && textLayer != null) {
                    val editedKeys = replacements.map { it.target.glyphKeys }.toSet()
                    for (run in textLayer.runs) {
                        if (run.ref.glyphKeys in editedKeys) continue
                        val b = run.bounds
                        val tl = Offset(b.left * layerScale * pxPerPt, b.top * layerScale * pxPerPt)
                        val sz = Size(b.width * layerScale * pxPerPt, b.height * layerScale * pxPerPt)
                        val blocked = run.blocker != null
                        drawRect(
                            color = if (blocked) Color.Gray.copy(alpha = 0.10f) else accent.copy(alpha = 0.10f),
                            topLeft = tl, size = sz,
                        )
                        drawRect(
                            color = if (blocked) Color.Gray.copy(alpha = 0.5f) else accent.copy(alpha = 0.7f),
                            topLeft = tl, size = sz,
                            style = Stroke(width = 1.dp.toPx(), pathEffect = if (blocked) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null),
                        )
                    }
                }
                for (r in replacements) drawReplacementPreview(r, fonts, pxPerPt * (pageWidthPt / r.refPageWidth.coerceAtLeast(1f)), accent, showTextRuns)
            }
        }

        for (edit in edits) {
            val s = pxPerPt * (pageWidthPt / edit.refPageWidth.coerceAtLeast(1f))
            when (edit) {
                is AddedText -> {
                    val layout = remember(edit.text, edit.style) { fonts.layout(edit.text, edit.style) }
                    EditableBox(
                        id = edit.id,
                        selected = edit.id == selectedId,
                        xPx = edit.x * s, yPx = edit.y * s,
                        widthPx = layout.width.coerceAtLeast(edit.style.sizePt) * s,
                        heightPx = layout.height * s,
                        pageWidthPt = pageWidthPt, pageHeightPt = pageHeightPt, pxPerPt = s,
                        resizeTextWidthPt = layout.width,
                        label = "Added text: ${edit.text}",
                        accent = accent,
                        actions = actions,
                    ) {
                        Canvas(Modifier.fillMaxSize()) { drawAddedText(edit, fonts, s) }
                    }
                }
                is PlacedSignature -> EditableBox(
                    id = edit.id,
                    selected = edit.id == selectedId,
                    xPx = edit.x * s, yPx = edit.y * s,
                    widthPx = edit.width * s, heightPx = edit.height * s,
                    pageWidthPt = pageWidthPt, pageHeightPt = pageHeightPt, pxPerPt = s,
                    resizeTextWidthPt = 0f,
                    label = "Signature",
                    accent = accent,
                    actions = actions,
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawInk(edit.ink, Color(edit.colorArgb), size) }
                }
                is TextReplacement -> {
                    // Tap target for re-editing a replacement.
                    val b = edit.target.bounds
                    Box(
                        Modifier
                            .offset { IntOffset((b.left * s).roundToInt(), (b.top * s).roundToInt()) }
                            .size(with(density) { (b.width * s).toDp() }, with(density) { (b.height * s).toDp() })
                            .pointerInput(edit.id) { detectTapGestures { actions.onOpen(edit.id) } }
                            .semantics { contentDescription = "Edited text: ${edit.newText.ifEmpty { "deleted" }}" },
                    )
                }
                is Whiteout -> Unit // drawn above, under everything else
            }
        }
    }
}

@Composable
private fun WhiteoutDrawSurface(page: Int, pxPerPt: Float, pageWidthPt: Float, accent: Color, actions: PageEditsActions) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val currentScale by rememberUpdatedState(pxPerPt)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(page) {
                // The box starts where the finger went down, not where the drag was recognised
                // (detectDragGestures reports the position after touch slop, which is ~10dp off).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    start = down.position
                    end = drag.position
                    val completed = drag(drag.id) { change ->
                        change.consume()
                        end = change.position
                    }
                    val a = start; val b = end
                    if (completed && a != null && b != null) {
                        val k = currentScale
                        actions.onDrawWhiteout(page, Box.of(a.x / k, a.y / k, b.x / k, b.y / k), pageWidthPt)
                    }
                    start = null; end = null
                }
            }
            .semantics { contentDescription = "Drag to draw a whiteout box" },
    ) {
        val a = start; val b = end
        if (a != null && b != null) {
            Canvas(Modifier.fillMaxSize()) {
                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val sz = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                drawRect(Color.White.copy(alpha = 0.85f), topLeft = tl, size = sz)
                drawRect(accent, topLeft = tl, size = sz, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
        }
    }
}

/**
 * A whiteout on the page. When selected it turns see-through and outlines the characters that
 * will be removed, so there are no surprises; corner handles resize it and dragging moves it.
 */
@Composable
private fun WhiteoutBox(
    edit: Whiteout,
    selected: Boolean,
    scale: Float,
    glyphs: List<Box>?,
    glyphScale: Float,
    accent: Color,
    actions: PageEditsActions,
) {
    val density = LocalDensity.current
    val currentScale by rememberUpdatedState(scale)
    val b = edit.box
    // Smaller handles on thin boxes (a single text line) so they don't swallow the box.
    val handle = with(density) { (if (b.height * scale < 40.dp.toPx()) 18.dp else 24.dp).toPx() }
    val removed = remember(glyphs, b, selected) {
        if (!selected || glyphs == null) emptyList() else glyphs.filter { RedactionRules.removesGlyph(it, b) }
    }
    Box(
        Modifier
            .offset { IntOffset((b.left * scale).roundToInt(), (b.top * scale).roundToInt()) }
            .size(with(density) { (b.width * scale).toDp() }, with(density) { (b.height * scale).toDp() })
            .pointerInput(edit.id, selected) {
                detectTapGestures(onTap = { actions.onSelect(edit.id) })
            }
            .pointerInput(edit.id, selected) {
                if (!selected) return@pointerInput
                detectDragGestures(
                    onDragStart = { actions.onGestureStart() },
                    onDragEnd = { actions.onGestureEnd() },
                    onDragCancel = { actions.onGestureEnd() },
                ) { change, amount ->
                    change.consume()
                    actions.onMove(edit.id, amount.x / currentScale, amount.y / currentScale)
                }
            }
            .semantics { contentDescription = if (selected) "Whiteout, selected. Content under it will be removed when saved." else "Whiteout" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(edit.fillArgb).copy(alpha = if (selected) 0.55f else 1f))
            if (selected) {
                // Characters that will be deleted, in page coordinates relative to this box.
                for (g in removed) {
                    drawRect(
                        Color(0xFFD32F2F).copy(alpha = 0.8f),
                        topLeft = Offset((g.left - b.left) * glyphScale, (g.top - b.top) * glyphScale),
                        size = Size(g.width * glyphScale, g.height * glyphScale),
                        style = Stroke(width = 1f),
                    )
                }
                drawRect(accent, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
        }
    }
    if (selected) {
        for (corner in Corner.entries) {
            val cx = when (corner) { Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> b.left; else -> b.right } * scale
            val cy = when (corner) { Corner.TOP_LEFT, Corner.TOP_RIGHT -> b.top; else -> b.bottom } * scale
            Box(
                Modifier
                    .offset { IntOffset((cx - handle / 2).roundToInt(), (cy - handle / 2).roundToInt()) }
                    .size(with(density) { handle.toDp() })
                    .background(accent, CircleShape)
                    .border(2.dp, Color.White, CircleShape)
                    .pointerInput(edit.id, corner) {
                        detectDragGestures(
                            onDragStart = { actions.onGestureStart() },
                            onDragEnd = { actions.onGestureEnd() },
                            onDragCancel = { actions.onGestureEnd() },
                        ) { change, amount ->
                            change.consume()
                            actions.onResizeCorner(edit.id, corner, amount.x / currentScale, amount.y / currentScale)
                        }
                    }
                    .semantics { contentDescription = "Resize whiteout" },
            )
        }
    }
}

@Composable
private fun EditableBox(
    id: String,
    selected: Boolean,
    xPx: Float,
    yPx: Float,
    widthPx: Float,
    heightPx: Float,
    pageWidthPt: Float,
    pageHeightPt: Float,
    pxPerPt: Float,
    resizeTextWidthPt: Float,
    label: String,
    accent: Color,
    actions: PageEditsActions,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val currentScale by rememberUpdatedState(pxPerPt)
    val currentTextWidth by rememberUpdatedState(resizeTextWidthPt)
    val pad = with(density) { 6.dp.toPx() }
    val handle = with(density) { 22.dp.toPx() }

    Box(
        Modifier
            .offset { IntOffset((xPx - pad).roundToInt(), (yPx - pad).roundToInt()) }
            .size(with(density) { (widthPx + 2 * pad).toDp() }, with(density) { (heightPx + 2 * pad).toDp() })
            .then(
                if (selected) Modifier.border(1.5.dp, accent.copy(alpha = 0.9f)) else Modifier,
            )
            .pointerInput(id, selected) {
                detectTapGestures(
                    onTap = { if (selected) actions.onOpen(id) else actions.onSelect(id) },
                    onDoubleTap = { actions.onOpen(id) },
                )
            }
            .pointerInput(id, selected) {
                if (!selected) return@pointerInput
                detectDragGestures(
                    onDragStart = { actions.onGestureStart() },
                    onDragEnd = { actions.onGestureEnd() },
                    onDragCancel = { actions.onGestureEnd() },
                ) { change, amount ->
                    change.consume()
                    actions.onMove(id, amount.x / currentScale, amount.y / currentScale)
                }
            }
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.offset { IntOffset(pad.roundToInt(), pad.roundToInt()) }.size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })) {
            content()
        }
        if (selected) {
            Box(
                Modifier
                    .offset { IntOffset((widthPx + 2 * pad - handle / 2f).roundToInt(), (heightPx + 2 * pad - handle / 2f).roundToInt()) }
                    .size(with(density) { handle.toDp() })
                    .background(accent, CircleShape)
                    .border(2.dp, Color.White, CircleShape)
                    .pointerInput(id) {
                        detectDragGestures(
                            onDragStart = { actions.onGestureStart() },
                            onDragEnd = { actions.onGestureEnd() },
                            onDragCancel = { actions.onGestureEnd() },
                        ) { change, amount ->
                            change.consume()
                            actions.onResize(id, amount.x / currentScale, currentTextWidth)
                        }
                    }
                    .semantics { contentDescription = "Resize" },
            )
        }
    }
}

private fun DrawScope.drawAddedText(edit: AddedText, fonts: BundledFonts, scale: Float) {
    val layout = fonts.layout(edit.text, edit.style)
    val paint = fonts.paint(edit.style, edit.style.sizePt * scale)
    drawIntoCanvas { canvas ->
        layout.lines.forEachIndexed { i, line ->
            canvas.nativeCanvas.drawText(line, 0f, (layout.firstBaseline + i * layout.lineHeight) * scale, paint)
        }
    }
}

/**
 * On-screen preview of a replacement: the original run is masked and the new text drawn on top.
 * In the saved file the original glyphs are actually removed (see ContentStreamEditor).
 */
private fun DrawScope.drawReplacementPreview(r: TextReplacement, fonts: BundledFonts, scale: Float, accent: Color, outline: Boolean) {
    val b = r.target.bounds
    val tl = Offset(b.left * scale, b.top * scale)
    val sz = Size(b.width * scale, b.height * scale)
    drawRect(Color.White, topLeft = tl - Offset(1f, 1f), size = Size(sz.width + 2f, sz.height + 2f))
    if (r.newText.isNotBlank()) {
        val style = r.style ?: TextStyle(
            family = if (r.target.serifHint) FontFamilyId.SERIF else FontFamilyId.SANS,
            sizePt = r.target.fontSizePt,
            colorArgb = r.target.colorArgb,
            bold = r.target.boldHint,
            italic = r.target.italicHint,
        )
        val paint = fonts.paint(style, style.sizePt * scale)
        val baseline = (b.bottom - r.target.fontSizePt * 0.22f) * scale
        drawIntoCanvas { it.nativeCanvas.drawText(r.newText, b.left * scale, baseline, paint) }
    }
    if (outline) {
        drawRect(accent, topLeft = tl, size = sz, style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))))
    }
}

/** Draws normalised ink into [size] using the same smoothing as the PDF writer. */
fun DrawScope.drawInk(ink: Ink, color: Color, size: Size) {
    val stroke = Stroke(
        width = (ink.widthRatio * size.height).coerceAtLeast(1f),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    for (s in ink.strokes) {
        val ops = InkGeometry.path(s.points) { x, y -> x * size.width to y * size.height }
        if (ops.isEmpty()) continue
        val path = Path()
        for (op in ops) when (op) {
            is PathOp.MoveTo -> path.moveTo(op.x, op.y)
            is PathOp.LineTo -> path.lineTo(op.x, op.y)
            is PathOp.CubicTo -> path.cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
        }
        drawPath(path, color, style = stroke)
    }
}
