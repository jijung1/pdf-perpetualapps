package app.folio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.folio.ui.FolioNavHost
import app.folio.ui.IncomingDocument
import app.folio.ui.theme.FolioTheme

class MainActivity : ComponentActivity() {

    /** A document handed to us by another app (ACTION_VIEW), consumed once by the nav host. */
    private val incomingDocument = mutableStateOf<IncomingDocument?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FolioTheme {
                FolioNavHost(
                    incomingDocument = incomingDocument.value,
                    onIncomingConsumed = { incomingDocument.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val type = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
                if (type?.startsWith("image/") == true) {
                    // A photo opened with Folio becomes a PDF in Merge & organize.
                    incomingDocument.value = IncomingDocument.Organize(listOf(uri))
                } else {
                    appContainer.documentAccess.persistPermission(uri, intent.flags)
                    incomingDocument.value = IncomingDocument.View(uri)
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = sharedUris(intent)
                if (uris.isEmpty()) return
                val single = uris.singleOrNull()
                val singleType = single?.let { runCatching { contentResolver.getType(it) }.getOrNull() ?: intent.type }
                incomingDocument.value = if (single != null && singleType == "application/pdf") {
                    IncomingDocument.View(single)
                } else {
                    IncomingDocument.Organize(uris)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
}
