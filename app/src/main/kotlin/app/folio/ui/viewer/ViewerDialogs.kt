package app.folio.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.folio.core.files.DocumentInfo
import app.folio.core.pdf.PageSize
import app.folio.ui.common.Formatting
import app.folio.ui.theme.LocalFolioExtras
import java.util.Locale

private const val THUMB_RENDER_WIDTH = 240

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageThumbnailsSheet(
    pageSizes: List<PageSize>,
    currentPage: Int,
    cachedPage: (Int, Int) -> Bitmap?,
    renderPage: suspend (Int, Int) -> Bitmap?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (currentPage - 3).coerceAtLeast(0))
    val accent = LocalFolioExtras.current.accent

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            "Pages",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            state = gridState,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().height(520.dp),
        ) {
            items(pageSizes.size, key = { it }) { index ->
                val selected = index == currentPage
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Go to page ${index + 1}") { onSelect(index) },
                ) {
                    var image by remember(index) { mutableStateOf<ImageBitmap?>(cachedPage(index, THUMB_RENDER_WIDTH)?.asImageBitmap()) }
                    LaunchedEffect(index) {
                        if (image == null) renderPage(index, THUMB_RENDER_WIDTH)?.let { image = it.asImageBitmap() }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(pageSizes[index].aspectRatio.coerceIn(0.2f, 5f))
                            .shadow(if (selected) 0.dp else 1.dp, RectangleShape)
                            .background(Color.White)
                            .then(if (selected) Modifier.border(2.dp, accent) else Modifier),
                    ) {
                        image?.let {
                            Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun GoToPageDialog(pageCount: Int, currentPage: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val number = text.toIntOrNull()
    val valid = number != null && number in 1..pageCount
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                singleLine = true,
                placeholder = { Text("${currentPage + 1}") },
                supportingText = { Text(if (text.isNotEmpty() && !valid) "Enter 1–$pageCount" else "1–$pageCount") },
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(number!! - 1) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onGo(number!! - 1) }, enabled = valid) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenameDialog(currentName: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val base = currentName.substringBeforeLast('.', currentName)
    var value by remember { mutableStateOf(TextFieldValue(base, selection = TextRange(0, base.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                suffix = { Text(".pdf") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(value.text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(value.text) }, enabled = value.text.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DocumentInfoDialog(
    name: String,
    info: DocumentInfo?,
    pageCount: Int,
    currentPageSize: PageSize?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Document details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow("Name", name)
                InfoRow("Pages", pageCount.toString())
                Formatting.fileSize(info?.sizeBytes)?.let { InfoRow("Size", it) }
                currentPageSize?.let { InfoRow("Page size", describePageSize(it)) }
                InfoRow("Processing", "On this device only")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.62f))
    }
}

/** "8.5 × 11 in (Letter)" / "210 × 297 mm (A4)" depending on locale. */
internal fun describePageSize(size: PageSize, locale: Locale = Locale.getDefault()): String {
    val w = size.width / 72.0
    val h = size.height / 72.0
    val shortMm = minOf(w, h) * 25.4
    val longMm = maxOf(w, h) * 25.4
    fun isSize(a: Double, b: Double) = kotlin.math.abs(shortMm - a) < 2.0 && kotlin.math.abs(longMm - b) < 2.0
    val name = when {
        isSize(215.9, 279.4) -> "Letter"
        isSize(215.9, 355.6) -> "Legal"
        isSize(210.0, 297.0) -> "A4"
        isSize(148.0, 210.0) -> "A5"
        isSize(297.0, 420.0) -> "A3"
        else -> null
    }
    val metric = locale.country !in setOf("US", "LR", "MM")
    val dims = if (metric) {
        String.format(locale, "%.0f × %.0f mm", w * 25.4, h * 25.4)
    } else {
        String.format(locale, "%.1f × %.1f in", w, h)
    }
    return if (name != null) "$dims ($name)" else dims
}
