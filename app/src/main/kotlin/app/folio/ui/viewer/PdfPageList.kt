package app.folio.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import app.folio.core.pdf.PageSize
import app.folio.core.pdf.RenderMath
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Zoom/pan state shared by all pages so the document zooms as one continuous surface. */
@Stable
class DocumentZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var panX by mutableFloatStateOf(0f)
        private set

    /** Scale used to choose render resolution; only updated when a gesture ends. */
    var settledScale by mutableFloatStateOf(1f)
        private set

    internal fun zoomBy(factor: Float, focus: Offset, viewportWidth: Float, baseWidth: Float, list: LazyListState) {
        val newScale = (scale * factor).coerceIn(RenderMath.MIN_ZOOM, RenderMath.MAX_ZOOM)
        val ratio = newScale / scale
        if (ratio == 1f) return
        panX = RenderMath.clampPanX(
            RenderMath.anchoredPanX(panX, focus.x, viewportWidth, ratio),
            viewportWidth, baseWidth * newScale,
        )
        // Keep the point under the fingers vertically fixed: items grow by `ratio` from the first visible item's top.
        list.dispatchRawDelta((list.firstVisibleItemScrollOffset + focus.y) * (ratio - 1f))
        scale = newScale
    }

    internal fun panBy(dx: Float, viewportWidth: Float, baseWidth: Float) {
        panX = RenderMath.clampPanX(panX + dx, viewportWidth, baseWidth * scale)
    }

    internal fun settle() {
        settledScale = scale
    }

    val isZoomed: Boolean get() = scale > 1.01f

    /** Set while an overlay element is being dragged so one-finger pans don't move the page too. */
    var panLocked: Boolean = false
}

/** A tap resolved to a page and a position in page points (display space, top-left origin). */
data class PageHit(val page: Int, val x: Float, val y: Float, val pageWidth: Float, val pageHeight: Float)

@Composable
fun PdfPageList(
    pageSizes: List<PageSize>,
    listState: LazyListState,
    zoom: DocumentZoomState,
    contentPadding: PaddingValues,
    cachedPage: (index: Int, widthPx: Int) -> Bitmap?,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    onTap: (PageHit?) -> Unit,
    modifier: Modifier = Modifier,
    pageOverlay: @Composable (index: Int, widthPx: Int, heightPx: Int) -> Unit = { _, _, _ -> },
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val sideGutter = with(density) { 12.dp.toPx() }
        val maxPageWidth = with(density) { 960.dp.toPx() }
        val baseWidth = min(viewportWidth - 2 * sideGutter, maxPageWidth).coerceAtLeast(1f)

        fun hitTest(offset: Offset): PageHit? {
            val info = listState.layoutInfo
            // Item offsets are measured from the content-padding edge (viewportStartOffset = -padding).
            val listY = offset.y + info.viewportStartOffset
            val item = info.visibleItemsInfo.firstOrNull { listY >= it.offset && listY < it.offset + it.size } ?: return null
            val size = pageSizes[item.index]
            val pageW = baseWidth * zoom.scale
            val left = (viewportWidth - pageW) / 2f + zoom.panX
            val lx = offset.x - left
            val ly = listY - item.offset
            if (lx < 0f || lx > pageW) return null
            val ptPerPx = size.width / pageW
            return PageHit(item.index, lx * ptPerPx, ly * ptPerPx, size.width.toFloat(), size.height.toFloat())
        }

        val gestures = Modifier
            .pointerInput(viewportWidth, baseWidth) {
                // Pinch-zoom and horizontal pan. Runs in the Initial pass so two-finger gestures
                // take priority over list scrolling; single-finger vertical drags fall through to the list.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                pinched = true
                                zoom.zoomBy(event.calculateZoom(), event.calculateCentroid(), viewportWidth, baseWidth, listState)
                                val pan = event.calculatePan()
                                zoom.panBy(pan.x, viewportWidth, baseWidth)
                                listState.dispatchRawDelta(-pan.y)
                                event.changes.forEach { it.consume() }
                            }
                            pressed.size == 1 -> {
                                val change = pressed.first()
                                if (zoom.isZoomed && !zoom.panLocked) zoom.panBy(change.position.x - change.previousPosition.x, viewportWidth, baseWidth)
                                if (pinched) change.consume() // avoid a jump when one finger lifts after a pinch
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    zoom.settle()
                }
            }
            .pointerInput(viewportWidth, baseWidth) {
                detectTapGestures(
                    onTap = { offset -> currentOnTap(hitTest(offset)) },
                    onDoubleTap = { focus ->
                        scope.launch {
                            val target = if (zoom.isZoomed) 1f else 2.5f
                            animate(zoom.scale, target, animationSpec = spring(stiffness = 500f)) { value, _ ->
                                zoom.zoomBy(value / zoom.scale, focus, viewportWidth, baseWidth, listState)
                            }
                            zoom.settle()
                        }
                    },
                )
            }

        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().then(gestures),
        ) {
            items(count = pageSizes.size, key = { it }) { index ->
                val size = pageSizes[index]
                val widthPx = (baseWidth * zoom.scale).roundToInt().coerceAtLeast(1)
                val heightPx = size.heightForWidth(widthPx)
                val renderWidth = RenderMath.renderWidth(baseWidth.roundToInt(), zoom.settledScale, size)
                val widthDp = with(density) { widthPx.toDp() }
                val heightDp = with(density) { heightPx.toDp() }

                Box(Modifier.fillMaxWidth().height(heightDp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .requiredSize(widthDp, heightDp)
                            .graphicsLayer { translationX = zoom.panX },
                    ) {
                        PdfPage(
                            index = index,
                            renderWidth = renderWidth,
                            cachedPage = cachedPage,
                            renderPage = renderPage,
                            modifier = Modifier.fillMaxSize(),
                        )
                        pageOverlay(index, widthPx, heightPx)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    index: Int,
    renderWidth: Int,
    cachedPage: (Int, Int) -> Bitmap?,
    renderPage: suspend (Int, Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    // Keep the last bitmap while a sharper one renders, so zooming never flashes blank.
    var image by remember(index) { mutableStateOf<ImageBitmap?>(cachedPage(index, renderWidth)?.asImageBitmap()) }
    var failed by remember(index) { mutableStateOf(false) }

    LaunchedEffect(index, renderWidth) {
        val cached = cachedPage(index, renderWidth)
        if (cached != null) {
            image = cached.asImageBitmap()
            return@LaunchedEffect
        }
        val bmp = renderPage(index, renderWidth)
        if (bmp != null) {
            image = bmp.asImageBitmap(); failed = false
        } else if (image == null) {
            failed = true
        }
    }

    Box(
        modifier
            .shadow(2.dp, RectangleShape, clip = false)
            .background(Color.White)
            .semantics { contentDescription = "Page ${index + 1}" },
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        when {
            img != null -> Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Icon(Icons.Outlined.BrokenImage, contentDescription = "Page couldn't be displayed", tint = Color.Gray)
            // While rendering: a clean blank sheet reads better than a spinner on every page.
        }
    }
}
