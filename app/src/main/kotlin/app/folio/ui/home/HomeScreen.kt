package app.folio.ui.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import app.folio.core.editing.SignatureKind
import app.folio.ui.editor.SignaturePadDialog
import app.folio.ui.editor.SignaturesSheet
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.folio.appContainer
import app.folio.core.files.RecentDocument
import app.folio.ui.common.Formatting
import app.folio.ui.common.SectionHeader
import app.folio.ui.common.StatusMessage

@Composable
fun HomeScreen(onOpenDocument: (Uri) -> Unit, onOrganize: (handoffId: String) -> Unit, onAbout: () -> Unit) {
    val container = LocalContext.current.appContainer
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val recents by vm.recents.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.onDocumentPicked(uri)
            onOpenDocument(uri)
        }
    }
    val openPdf = { picker.launch(arrayOf("application/pdf")) }
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onOrganize(vm.handOff(uris))
    }
    val signatures by vm.signatures.collectAsStateWithLifecycle()
    var showSignatures by remember { mutableStateOf(false) }
    var sheetKind by remember { mutableStateOf(SignatureKind.SIGNATURE) }
    var showPad by remember { mutableStateOf(false) }

    if (showSignatures) {
        SignaturesSheet(
            saved = signatures,
            kind = sheetKind,
            placing = false,
            onKindChange = { sheetKind = it },
            onPick = {},
            onNew = { showSignatures = false; showPad = true },
            onDelete = vm::deleteSignature,
            onDismiss = { showSignatures = false },
        )
    }
    if (showPad) {
        SignaturePadDialog(
            kind = sheetKind,
            onDone = { ink, color, _ -> showPad = false; vm.addSignature(ink, color, sheetKind); showSignatures = true },
            onDismiss = { showPad = false },
            showSaveOption = false,
        )
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Header(onAbout = onAbout, modifier = Modifier.widthIn(max = 720.dp)) }
            item { OpenCard(onOpenPdf = openPdf, modifier = Modifier.widthIn(max = 720.dp)) }
            item {
                ToolsGrid(
                    onMerge = { mergePicker.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif")) },
                    onFillSign = openPdf,
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
            item {
                SignaturesRow(
                    count = signatures.size,
                    onClick = { sheetKind = SignatureKind.SIGNATURE; showSignatures = true },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
            if (recents.isEmpty()) {
                item {
                    StatusMessage(
                        icon = Icons.Outlined.Description,
                        title = "No recent documents",
                        detail = "PDFs you open appear here. They're read where they are — nothing is imported or uploaded.",
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                item { SectionHeader("Recent", Modifier.widthIn(max = 720.dp)) }
                items(recents, key = { it.uri }) { doc ->
                    RecentRow(
                        doc = doc,
                        loadThumbnail = { vm.thumbnail(doc) },
                        onClick = { onOpenDocument(Uri.parse(doc.uri)) },
                        onRemove = { vm.remove(doc) },
                        modifier = Modifier.widthIn(max = 720.dp).animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onAbout: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 28.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PDF Editor", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                Text("by Perpetual Apps", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onAbout) {
                Icon(Icons.Outlined.Info, contentDescription = "About this app, privacy and licenses", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Everything stays on this device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OpenCard(onOpenPdf: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(16.dp))
            Text("Open a PDF", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "From your device, SD card or any installed storage app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onOpenPdf,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse files")
            }
        }
    }
}

@Composable
private fun ToolsGrid(onMerge: () -> Unit, onFillSign: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolCard(Icons.Outlined.ViewModule, "Merge & organize", "Combine PDFs and photos, reorder, rotate, split", onMerge, Modifier.weight(1f).fillMaxHeight())
        ToolCard(Icons.Outlined.Draw, "Fill & sign", "Type on forms, sign, white out", onFillSign, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ToolCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onClick).padding(16.dp).heightIn(min = 112.dp)) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SignaturesRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Gesture, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Signatures & initials", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (count == 0) "Draw them once, reuse on any document" else "$count saved on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentRow(
    doc: RecentDocument,
    loadThumbnail: suspend () -> Bitmap?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumb by produceState<Bitmap?>(null, doc.uri, doc.lastOpenedAt) { value = loadThumbnail() }
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 58.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PictureAsPdf, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                Formatting.pages(doc.pageCount),
                Formatting.fileSize(doc.sizeBytes),
                Formatting.relativeTime(doc.lastOpenedAt),
            ).joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${doc.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from recents") },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}
