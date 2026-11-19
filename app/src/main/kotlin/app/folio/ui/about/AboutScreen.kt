package app.folio.ui.about

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PAGE_MAIN = -1
private const val PAGE_PRIVACY = -2

/** About Folio: privacy promises, things worth knowing before sharing a document, and licenses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    // PAGE_MAIN, PAGE_PRIVACY, or the index of a component whose license is open.
    var page by rememberSaveable { mutableIntStateOf(PAGE_MAIN) }
    BackHandler(enabled = page != PAGE_MAIN) { page = PAGE_MAIN }

    val component = OpenSourceLicenses.components.getOrNull(page)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        when {
                            component != null -> component.name
                            page == PAGE_PRIVACY -> "Privacy policy"
                            else -> "About"
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (page == PAGE_MAIN) onBack() else page = PAGE_MAIN }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
            when {
                component != null -> LegalText(
                    header = "${component.name}\n${component.owner}\n${component.license} · ${component.url}",
                    assets = component.texts,
                )
                page == PAGE_PRIVACY -> LegalText(header = null, assets = listOf(OpenSourceLicenses.PRIVACY_POLICY))
                else -> AboutMain(onPrivacy = { page = PAGE_PRIVACY }, onLicense = { page = it })
            }
        }
    }
}

@Composable
private fun AboutMain(onPrivacy: () -> Unit, onLicense: (Int) -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { appVersion(context) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("PDF Editor", style = MaterialTheme.typography.headlineSmall)
                        Text("by Perpetual Apps · Version $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "A PDF editor that works entirely on your phone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        item { Section("Privacy") }
        item {
            Card {
                Fact(Icons.Outlined.CloudOff, "No internet access", "This app doesn't have Android's Internet permission, so it can't send your documents anywhere.")
                Fact(Icons.Outlined.VisibilityOff, "No accounts, ads or tracking", "No analytics, crash reporting or advertising. Nothing about you or your files is collected.")
                Fact(Icons.Outlined.PhonelinkLock, "Stays on this device", "Recent files, saved signatures and drafts are kept in the app's private storage and left out of cloud backups. Uninstalling the app deletes them.")
                LinkRow("Read the privacy policy", onPrivacy)
            }
        }

        item { Section("Good to know") }
        item {
            Card {
                Fact(Icons.Outlined.Gesture, "Signatures are handwritten images", "Signatures and initials are pictures of your handwriting placed on the page. They aren't certificate-based digital signatures, and the app doesn't verify who signed.")
                Fact(Icons.Outlined.Rectangle, "Whiteout removes, not just covers", "When you save, everything under a whiteout box is deleted from the file. For sensitive documents, open the saved copy and check it before you share it.")
                Fact(Icons.Outlined.Description, "Saving flattens forms", "Filled-in form fields and comments become part of the page. The saved PDF looks the same everywhere, but its fields can't be edited any more.")
                Fact(Icons.Outlined.Lock, "Your originals are safe", "The app saves a new copy unless you choose to overwrite the original, and it checks every file it writes before telling you it's done.")
            }
        }

        item { Section("Open-source licenses") }
        item {
            Text(
                "This app is built with these open-source components. Tap one to read its license.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        item {
            Card {
                OpenSourceLicenses.components.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Read license") { onLicense(i) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${c.license} · ${c.usage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Fact(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(38.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Verbatim legal text from assets, selectable, in a monospaced face so notices keep their layout. */
@Composable
private fun LegalText(header: String?, assets: List<String>) {
    val context = LocalContext.current
    val text by produceState<String?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            assets.joinToString("\n\n" + "—".repeat(24) + "\n\n") { path ->
                runCatching { reflow(context.assets.open(path).bufferedReader().use { it.readText() }) }.getOrDefault("($path is missing)")
            }
        }
    }
    // Wrapped, not scrolled sideways: easier to read on a phone, and the wording is unchanged.
    SelectionContainer {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                if (header != null) {
                    Text(header, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                }
                Text(text ?: "", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp))
            }
        }
    }
}

/**
 * Joins lines that were hard-wrapped for an 80-column terminal so text wraps naturally on a phone.
 * Only a line of 60+ characters is joined with the next, so short notice lines and headings keep
 * their own lines. The words themselves are never changed.
 */
internal fun reflow(text: String): String {
    val out = StringBuilder()
    val lines = text.replace("\r\n", "\n").split('\n')
    for ((i, raw) in lines.withIndex()) {
        val line = raw.trimEnd()
        val next = lines.getOrNull(i + 1)?.trim().orEmpty()
        out.append(line.trim())
        val joinWithNext = line.length >= 60 && next.isNotEmpty() && !next.first().isDigit() && !next.startsWith("(") && !next.startsWith("-")
        out.append(if (joinWithNext) " " else "\n")
    }
    return out.toString().trimEnd()
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"
