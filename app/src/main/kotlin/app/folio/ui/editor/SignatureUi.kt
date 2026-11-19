package app.folio.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.folio.core.editing.SavedSignature
import app.folio.core.editing.SignatureKind
import app.folio.core.editing.ink.InkGeometry
import app.folio.core.editing.model.Ink

/**
 * Full-width drawing pad. Strokes are captured as points and saved as vector ink —
 * this is a handwritten signature image, not a cryptographic digital signature.
 */
@Composable
fun SignaturePadDialog(
    onDone: (ink: Ink, color: Int, saveForReuse: Boolean) -> Unit,
    onDismiss: () -> Unit,
    showSaveOption: Boolean = true,
    kind: SignatureKind = SignatureKind.SIGNATURE,
) {
    val initials = kind == SignatureKind.INITIALS
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableIntStateOf(InkColors.first()) }
    var saveForReuse by remember { mutableStateOf(true) }
    val penWidth = with(LocalDensity.current) { 3.dp.toPx() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (initials) "Draw your initials" else "Draw your signature", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (initials) 1.6f else 2.2f)
                        .background(Color.White, MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                        .semantics { contentDescription = "Signature drawing area" },
                ) {
                    // Baseline guide.
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
                            .fillMaxWidth().height(1.dp).background(Color(0xFFCCCCCC)),
                    )
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Text(if (initials) "Initials here" else "Sign here", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Center))
                    }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val pts = mutableListOf(down.position)
                                current = pts.toList()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    // Use historical samples for smooth fast strokes.
                                    change.historical.forEach { pts += it.position }
                                    pts += change.position
                                    change.consume()
                                    current = pts.toList()
                                }
                                strokes += pts.toList()
                                current = emptyList()
                            }
                        },
                    ) {
                        val style = Stroke(width = penWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        (strokes + listOf(current)).forEach { s ->
                            if (s.isEmpty()) return@forEach
                            val p = Path().apply {
                                moveTo(s[0].x, s[0].y)
                                if (s.size == 1) lineTo(s[0].x + 0.1f, s[0].y) else s.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(p, Color(color), style = style)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkColors.forEach { c -> ColorDot(c, selected = c == color) { color = c } }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Text("Undo") }
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
                }
                if (showSaveOption) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { saveForReuse = !saveForReuse }) {
                        Checkbox(checked = saveForReuse, onCheckedChange = { saveForReuse = it })
                        Text("Save on this device for reuse", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        enabled = strokes.isNotEmpty(),
                        onClick = {
                            val raw = strokes.map { s -> InkGeometry.simplify(s.flatMap { listOf(it.x, it.y) }, penWidth * 0.35f) }
                            InkGeometry.normalize(raw, penWidth)?.let { onDone(it, color, saveForReuse) }
                        },
                    ) { Text(if (!showSaveOption) "Save" else if (initials) "Use initials" else "Use signature") }
                }
            }
        }
    }
}

@Composable
fun SignatureThumb(ink: Ink, color: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color.White, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(ink.aspect.coerceIn(0.5f, 8f))) {
            Canvas(Modifier.fillMaxSize()) { drawInk(ink, Color(color), size) }
        }
    }
}

/** Pick a saved signature or draw a new one. Also used from the home screen to manage signatures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturesSheet(
    saved: List<SavedSignature>,
    placing: Boolean,
    onPick: (SavedSignature) -> Unit,
    onNew: () -> Unit,
    onDelete: (SavedSignature) -> Unit,
    onDismiss: () -> Unit,
    kind: SignatureKind = SignatureKind.SIGNATURE,
    /** Non-null shows Signatures / Initials tabs (used on the home screen). */
    onKindChange: ((SignatureKind) -> Unit)? = null,
) {
    val initials = kind == SignatureKind.INITIALS
    val items = saved.filter { it.kind == kind }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            placing && initials -> "Add your initials"
                            placing -> "Add a signature"
                            else -> "Signatures & initials"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Handwritten, stored only on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onNew) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("New")
                }
            }
            if (onKindChange != null) {
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    SignatureKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = k == kind,
                            onClick = { onKindChange(k) },
                            shape = SegmentedButtonDefaults.itemShape(i, SignatureKind.entries.size),
                        ) { Text(if (k == SignatureKind.SIGNATURE) "Signatures" else "Initials") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Text(
                    if (initials) "No saved initials yet. Draw them once to reuse on any document."
                    else "No saved signatures yet. Draw one to reuse it on any document.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().height((items.size.coerceAtMost(4) * 96).dp)) {
                    items(items, key = { it.id }) { sig ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (placing) Modifier.clickable(role = Role.Button, onClickLabel = if (initials) "Place initials" else "Place signature") { onPick(sig) } else Modifier)
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SignatureThumb(sig.ink, sig.colorArgb, Modifier.weight(1f).height(72.dp))
                            IconButton(onClick = { onDelete(sig) }) { Icon(Icons.Outlined.Delete, contentDescription = if (initials) "Delete saved initials" else "Delete saved signature") }
                        }
                    }
                }
            }
        }
    }
}
