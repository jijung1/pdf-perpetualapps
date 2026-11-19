package app.folio.ui.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.folio.AppContainer
import app.folio.core.editing.SavedSignature
import app.folio.core.editing.SignatureKind
import app.folio.core.editing.model.Ink
import app.folio.core.files.RecentDocument
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val recents: StateFlow<List<RecentDocument>> = container.recents.items

    /** Called with the uri returned by the system document picker. */
    fun onDocumentPicked(uri: Uri) {
        container.documentAccess.persistPermission(uri)
    }

    fun remove(doc: RecentDocument) {
        viewModelScope.launch {
            container.recents.remove(doc.uri)
            container.thumbnails.delete(doc.uri)
            container.documentAccess.releasePermission(Uri.parse(doc.uri))
        }
    }

    suspend fun thumbnail(doc: RecentDocument): Bitmap? = container.thumbnails.load(doc.uri)

    val signatures: StateFlow<List<SavedSignature>> = container.signatures.items

    fun addSignature(ink: Ink, color: Int, kind: SignatureKind) {
        viewModelScope.launch {
            container.signatures.add(SavedSignature(java.util.UUID.randomUUID().toString(), ink, color, System.currentTimeMillis(), kind))
        }
    }

    /** Files picked for Merge & organize, handed to the next screen. */
    fun handOff(uris: List<Uri>): String {
        uris.forEach { container.documentAccess.persistPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return container.handoff.put(uris)
    }

    fun deleteSignature(sig: SavedSignature) {
        viewModelScope.launch { container.signatures.delete(sig.id) }
    }
}
