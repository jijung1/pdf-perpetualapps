package app.folio.ui.common

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.folio.AppContainer
import app.folio.core.tools.ToolError
import java.io.File
import java.util.UUID

/** A finished file waiting to be saved or shared. Lives in the app cache until the screen closes. */
data class OutputFile(val file: File, val displayName: String, val pageCount: Int?) {
    val sizeBytes: Long get() = file.length()
}

/** A fresh, uniquely-located file in the shareable output directory, named for the user. */
fun AppContainer.newOutputFile(displayName: String): File {
    val dir = File(outputDir, UUID.randomUUID().toString()).apply { mkdirs() }
    return File(dir, displayName)
}

fun Context.shareUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

fun OutputFile.delete() {
    file.delete()
    file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
}

fun ToolError.toMessage(): String = when (this) {
    is ToolError.PasswordProtected -> "“$name” is password-protected, so it can't be used here yet."
    is ToolError.NotPermitted -> "“$name” has permissions that don't allow this change."
    is ToolError.Unreadable -> "“$name” can't be read. It may be damaged or not a real PDF."
    is ToolError.UnsupportedImage ->
        if (needsNewerAndroid) "“$name” is a HEIC photo. Opening HEIC needs Android 9 or newer."
        else "“$name” isn't an image this device can open."
    ToolError.SourceUnavailable -> "A file is no longer available. It may have been moved or deleted."
    ToolError.InsufficientStorage -> "Not enough storage space. Free up some space and try again."
    ToolError.WriteFailed -> "The new PDF couldn't be created. Your original files weren't changed."
}

/** Full-width progress strip with a cancel action, used by every tool while it works. */
@Composable
fun WorkingPanel(title: String, detail: String, progress: Float?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/**
 * The "it's ready" panel shared by Merge and Split: what was made, then Save / Share /
 * Open. Nothing is written to the user's storage until they press Save.
 */
@Composable
fun OutputPanel(
    title: String,
    files: List<OutputFile>,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    notes: List<String> = emptyList(),
    saveLabel: String = if (files.size > 1) "Save all to a folder" else "Save to device",
    onOpen: (() -> Unit)? = null,
    onStartOver: (() -> Unit)? = null,
    startOverLabel: String = "Make changes",
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                files.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(
                                listOfNotNull(Formatting.pages(f.pageCount), Formatting.fileSize(f.sizeBytes)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            notes.forEach { note ->
                Spacer(Modifier.height(10.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(saveLabel)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (files.size > 1) "Share all" else "Share")
                }
                if (onOpen != null) {
                    OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("Open")
                    }
                }
            }
            if (onStartOver != null) {
                TextButton(onClick = onStartOver, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(startOverLabel, textAlign = TextAlign.Center) }
            }
        }
    }
}
