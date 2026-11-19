package app.folio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Approval
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import app.folio.core.editing.model.FillSource
import app.folio.core.editing.model.Whiteout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.FontFamilyId
import app.folio.core.editing.model.PageEdit
import app.folio.core.editing.model.PlacedSignature
import app.folio.core.editing.model.TextReplacement
import app.folio.core.editing.model.TextStyle
import app.folio.ui.theme.LocalFolioExtras
import kotlin.math.roundToInt

val TextColors = listOf(
    0xFF1A1C21.toInt(), 0xFF1D3F8F.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFF6B6E76.toInt(), 0xFFFFFFFF.toInt(),
)
val InkColors = listOf(0xFF111318.toInt(), 0xFF1F3FA8.toInt(), 0xFF8E1B1B.toInt())

/** Main editing toolbar shown at the bottom of the viewer. */
@Composable
fun EditorToolbar(
    tool: EditTool,
    canUndo: Boolean,
    canRedo: Boolean,
    onTool: (EditTool) -> Unit,
    onSign: () -> Unit,
    onStamp: (StampChoice) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stampMenu by remember { mutableStateOf(false) }
    ToolbarSurface(modifier) {
        ToolButton(Icons.Outlined.TextFields, "Add text", tool == EditTool.ADD_TEXT) { onTool(EditTool.ADD_TEXT) }
        ToolButton(Icons.Outlined.EditNote, "Edit text", tool == EditTool.EDIT_TEXT) { onTool(EditTool.EDIT_TEXT) }
        ToolButton(Icons.Outlined.Draw, "Sign", false, onSign)
        ToolButton(Icons.Outlined.Rectangle, "Whiteout", tool == EditTool.WHITEOUT) { onTool(EditTool.WHITEOUT) }
        Box {
            ToolButton(Icons.Outlined.Approval, "Stamp", tool == EditTool.PLACE_STAMP) {
                if (tool == EditTool.PLACE_STAMP) onTool(EditTool.PLACE_STAMP) else stampMenu = true
            }
            DropdownMenu(expanded = stampMenu, onDismissRequest = { stampMenu = false }) {
                DropdownMenuItem(text = { Text("Check mark  ✓") }, leadingIcon = { Icon(Icons.Outlined.Check, null) }, onClick = { stampMenu = false; onStamp(StampChoice.CHECK) })
                DropdownMenuItem(text = { Text("Cross  ✗") }, leadingIcon = { Icon(Icons.Outlined.Close, null) }, onClick = { stampMenu = false; onStamp(StampChoice.CROSS) })
                DropdownMenuItem(text = { Text("Today's date") }, leadingIcon = { Icon(Icons.Outlined.Today, null) }, onClick = { stampMenu = false; onStamp(StampChoice.DATE) })
                DropdownMenuItem(text = { Text("Initials") }, leadingIcon = { Icon(Icons.Outlined.Gesture, null) }, onClick = { stampMenu = false; onStamp(StampChoice.INITIALS) })
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 28.dp).background(MaterialTheme.colorScheme.outlineVariant))
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo") }
    }
}

enum class StampChoice { CHECK, CROSS, DATE, INITIALS }

/** A hint strip shown while a placement tool is armed. */
@Composable
fun ToolHint(tool: EditTool, stamp: StampKind?, modifier: Modifier = Modifier) {
    val text = when (tool) {
        EditTool.ADD_TEXT -> "Tap where the new text should go. Inside a form box, it lines up for you."
        EditTool.EDIT_TEXT -> "Tap highlighted text to change or delete it"
        EditTool.WHITEOUT -> "Drag over what to remove. Scroll with two fingers."
        EditTool.PLACE_STAMP -> when (stamp) {
            StampKind.Check -> "Tap where the check mark goes"
            StampKind.Cross -> "Tap where the cross goes"
            StampKind.Date -> "Tap where the date goes"
            is StampKind.Initials -> "Tap where your initials go"
            null -> return
        }
        EditTool.EYEDROPPER -> "Tap the page to pick the whiteout colour"
        EditTool.NONE -> return
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.padding(horizontal = 24.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** Contextual actions for the selected edit. */
@Composable
fun SelectionToolbar(
    edit: PageEdit,
    onEdit: () -> Unit,
    onStyle: ((TextStyle) -> TextStyle) -> Unit,
    onInkColor: (Int) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onWhiteoutColor: () -> Unit = {},
    onEyedropper: () -> Unit = {},
) {
    ToolbarSurface(modifier) {
        when (edit) {
            is Whiteout -> {
                Row(
                    Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = "Change colour", onClick = onWhiteoutColor).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).background(Color(edit.fillArgb), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (edit.fillSource) {
                            FillSource.AUTO -> if (edit.autoMatched) "Matches page" else "White"
                            FillSource.WHITE -> "White"
                            FillSource.MANUAL -> "Custom"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                IconButton(onClick = onEyedropper) { Icon(Icons.Outlined.Colorize, contentDescription = "Pick colour from page") }
            }
            is AddedText -> {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit text") }
                IconButton(onClick = { onStyle { it.copy(sizePt = (it.sizePt - 2f).coerceAtLeast(EditSession.MIN_TEXT)) } }) {
                    Icon(Icons.Outlined.TextDecrease, contentDescription = "Smaller")
                }
                Text("${edit.style.sizePt.roundToInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp))
                IconButton(onClick = { onStyle { it.copy(sizePt = (it.sizePt + 2f).coerceAtMost(EditSession.MAX_TEXT)) } }) {
                    Icon(Icons.Outlined.TextIncrease, contentDescription = "Larger")
                }
                ColorDot(edit.style.colorArgb) { onStyle { s -> s.copy(colorArgb = next(TextColors, s.colorArgb)) } }
                IconToggleButton(checked = edit.style.bold, onCheckedChange = { b -> onStyle { it.copy(bold = b) } }) {
                    Icon(Icons.Outlined.FormatBold, contentDescription = "Bold")
                }
                IconToggleButton(checked = edit.style.italic, onCheckedChange = { i -> onStyle { it.copy(italic = i) } }) {
                    Icon(Icons.Outlined.FormatItalic, contentDescription = "Italic")
                }
            }
            is PlacedSignature -> {
                InkColors.forEach { c -> ColorDot(c, selected = c == edit.colorArgb) { onInkColor(c) } }
            }
            is TextReplacement -> {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit replacement") }
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
        // Text items already fill the bar; tapping the page (or Back) deselects them.
        if (edit !is AddedText) IconButton(onClick = onDone) { Icon(Icons.Outlined.Check, contentDescription = "Done") }
    }
}

private fun next(list: List<Int>, current: Int): Int = list[(list.indexOf(current) + 1).mod(list.size)]

@Composable
private fun ToolbarSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val accent = LocalFolioExtras.current.accent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (active) Modifier.background(accent.copy(alpha = 0.14f), MaterialTheme.shapes.medium) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { selected = active },
    ) {
        Icon(icon, contentDescription = null, tint = if (active) accent else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ColorDot(color: Int, selected: Boolean = false, onClick: () -> Unit) {
    val accent = LocalFolioExtras.current.accent
    Box(
        Modifier
            .padding(6.dp)
            .size(28.dp)
            .then(if (selected) Modifier.border(2.dp, accent, CircleShape) else Modifier)
            .padding(3.dp)
            .background(Color(color), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Colour" },
    )
}

@Composable
fun StyleControls(style: TextStyle, onChange: (TextStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
            Slider(
                value = style.sizePt,
                onValueChange = { onChange(style.copy(sizePt = it.roundToInt().toFloat())) },
                valueRange = 6f..72f,
                modifier = Modifier.weight(1f),
            )
            Text("${style.sizePt.roundToInt()} pt", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FontFamilyId.entries.forEachIndexed { i, f ->
                SegmentedButton(
                    selected = style.family == f,
                    onClick = { onChange(style.copy(family = f)) },
                    shape = SegmentedButtonDefaults.itemShape(i, FontFamilyId.entries.size),
                ) { Text(if (f == FontFamilyId.SANS) "Sans" else "Serif") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = style.bold, onClick = { onChange(style.copy(bold = !style.bold)) }, label = { Text("Bold") })
            FilterChip(selected = style.italic, onClick = { onChange(style.copy(italic = !style.italic)) }, label = { Text("Italic") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextColors.forEach { c -> ColorDot(c, selected = c == style.colorArgb) { onChange(style.copy(colorArgb = c)) } }
        }
    }
}

/** Create or edit added text. */
@Composable
fun AddedTextDialog(
    target: TextDialogTarget.Added,
    onConfirm: (String, TextStyle) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(target.edit.text, TextRange(target.edit.text.length))) }
    var style by remember { mutableStateOf(target.edit.style) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.isNew) "Add text" else "Edit added text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    minLines = 2,
                    maxLines = 6,
                    placeholder = { Text("Type text") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                StyleControls(style) { style = it }
                Text(
                    "Added text is placed on top of the page. Use Edit text to change words already in the document.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text, style) }, enabled = value.text.isNotBlank()) { Text(if (target.isNew) "Add" else "Apply") } },
        dismissButton = {
            Row {
                if (!target.isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Change or delete a run of the document's existing text. */
@Composable
fun ReplaceTextDialog(
    target: TextDialogTarget.Replacement,
    onConfirm: (String, TextStyle?) -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ref = target.run.ref
    val initial = target.existing?.newText ?: ref.originalText
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var keepOriginal by remember { mutableStateOf(target.existing?.style == null) }
    var style by remember {
        mutableStateOf(
            target.existing?.style ?: TextStyle(
                family = if (ref.serifHint) FontFamilyId.SERIF else FontFamilyId.SANS,
                sizePt = ref.fontSizePt.roundToInt().toFloat().coerceIn(6f, 72f),
                colorArgb = ref.colorArgb,
                bold = ref.boldHint,
                italic = ref.italicHint,
            ),
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit document text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Original: “${ref.originalText}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = v.copy(text = v.text.replace('\n', ' ')) },
                    singleLine = true,
                    placeholder = { Text("Leave empty to delete") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep original style", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Uses the document's font (${ref.fontName}) when it contains every character you type; otherwise a similar font is used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = keepOriginal, onCheckedChange = { keepOriginal = it })
                }
                if (!keepOriginal) StyleControls(style) { style = it }
                HorizontalDivider()
                Text(
                    "The original words are removed from the PDF and the new text is written in their place on this line. Surrounding text does not reflow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim(), if (keepOriginal) null else style) }) {
                Text(if (value.text.isBlank()) "Delete text" else "Apply")
            }
        },
        dismissButton = {
            Row {
                if (target.existing != null) TextButton(onClick = onRevert) { Text("Revert") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Pick how a whiteout is filled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteoutColorSheet(
    edit: Whiteout,
    onAuto: () -> Unit,
    onColor: (Int, FillSource) -> Unit,
    onEyedropper: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Whiteout colour", style = MaterialTheme.typography.titleLarge)
            ColorChoiceRow(
                selected = edit.fillSource == FillSource.AUTO,
                swatch = if (edit.fillSource == FillSource.AUTO) edit.fillArgb else null,
                title = "Match the page",
                detail = when {
                    edit.fillSource != FillSource.AUTO -> "Samples the paper colour around the box"
                    edit.autoMatched -> "Matched the paper colour around the box"
                    else -> "Couldn't find one clear background colour here, so white is used"
                },
                onClick = onAuto,
            )
            ColorChoiceRow(
                selected = edit.fillSource == FillSource.WHITE,
                swatch = Whiteout.WHITE,
                title = "White",
                detail = "Plain white",
                onClick = { onColor(Whiteout.WHITE, FillSource.WHITE) },
            )
            Text("Other colours", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                WhiteoutSwatches.forEach { c ->
                    ColorDot(c, selected = edit.fillSource == FillSource.MANUAL && edit.fillArgb == c) { onColor(c, FillSource.MANUAL) }
                }
            }
            androidx.compose.material3.OutlinedButton(onClick = onEyedropper, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Colorize, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Pick a colour from the page")
            }
            Text(
                "Whatever is under the box is permanently removed when you save — not just covered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Paper tones plus black (for redaction bars). */
val WhiteoutSwatches = listOf(
    0xFFFFFFFF.toInt(), 0xFFFAF8F2.toInt(), 0xFFF5EFDF.toInt(), 0xFFFFF8D6.toInt(),
    0xFFEDEDED.toInt(), 0xFFDCDCDC.toInt(), 0xFF000000.toInt(),
)

@Composable
private fun ColorChoiceRow(selected: Boolean, swatch: Int?, title: String, detail: String, onClick: () -> Unit) {
    val accent = LocalFolioExtras.current.accent
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.border(2.dp, accent, MaterialTheme.shapes.medium) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(swatch?.let { Color(it) } ?: Color.Transparent, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (swatch == null) Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = accent)
    }
}

/** Choose how to save edits. */
@Composable
fun SaveDialog(
    editCount: Int,
    canOverwrite: Boolean,
    onSaveCopy: () -> Unit,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit,
    whiteoutCount: Int = 0,
    flattenSummary: Pair<Int, Int>? = null,
) {
    var confirmOverwrite by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmOverwrite) "Replace the original?" else "Save changes") },
        text = {
            Column(Modifier.heightIn(min = 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmOverwrite) {
                    Text("The original file will be replaced with the edited version. This can't be undone.")
                } else {
                    Text("$editCount ${if (editCount == 1) "change" else "changes"} will be written into a new PDF. Your original stays exactly as it is.")
                    if (whiteoutCount > 0) {
                        SaveNote("Whiteout permanently removes everything under ${if (whiteoutCount == 1) "the box" else "the $whiteoutCount boxes"}: text, images and drawings.")
                    }
                    val (fields, annots) = flattenSummary ?: (0 to 0)
                    if (fields > 0) {
                        SaveNote("Form fields will be flattened: the PDF will look filled in, but it won't be a fillable form any more.")
                    }
                    if (annots > 0) {
                        SaveNote("Comments and highlights become part of the page.")
                    }
                    if (!canOverwrite) {
                        Text(
                            "This file's storage location doesn't allow overwriting, so a copy is the only option.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (confirmOverwrite) {
                    androidx.compose.material3.Button(
                        onClick = onOverwrite,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("Replace original") }
                } else {
                    androidx.compose.material3.Button(onClick = onSaveCopy) { Text("Save as new copy") }
                    if (canOverwrite) TextButton(onClick = { confirmOverwrite = true }) { Text("Overwrite original…") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SaveNote(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown on back with unsaved edits. Drafts are kept automatically, so leaving is safe. */
@Composable
fun UnsavedChangesDialog(onSave: () -> Unit, onKeepDraft: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unsaved changes") },
        text = { Text("Your edits haven't been saved to a PDF yet. Keep them as a draft to continue later, or save now.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save…") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onKeepDraft) { Text("Keep draft") }
            }
        },
    )
}

@Composable
fun SaveProgressBar(progress: Float, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Saving edits…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}
