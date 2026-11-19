package app.folio.core.editing

import app.folio.core.editing.model.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class SignatureKind { SIGNATURE, INITIALS }

@Serializable
data class SavedSignature(
    val id: String,
    val ink: Ink,
    val colorArgb: Int,
    val createdAt: Long,
    val kind: SignatureKind = SignatureKind.SIGNATURE,
)

/** Built-in vector stamps, drawn exactly like signatures so they scale cleanly. */
object Stamps {
    val CHECK = Ink(
        strokes = listOf(app.folio.core.editing.model.Stroke(listOf(0.10f, 0.55f, 0.24f, 0.70f, 0.40f, 0.88f, 0.62f, 0.50f, 0.92f, 0.10f))),
        aspect = 1.1f,
        widthRatio = 0.13f,
    )
    val CROSS = Ink(
        strokes = listOf(
            app.folio.core.editing.model.Stroke(listOf(0.14f, 0.14f, 0.50f, 0.50f, 0.86f, 0.86f)),
            app.folio.core.editing.model.Stroke(listOf(0.86f, 0.14f, 0.50f, 0.50f, 0.14f, 0.86f)),
        ),
        aspect = 1f,
        widthRatio = 0.13f,
    )
}

/**
 * Signatures saved for reuse, stored as vector strokes in app-private storage
 * (excluded from cloud backup and device transfer by the manifest rules).
 */
class SignatureStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<SavedSignature>>(emptyList())
    val items: StateFlow<List<SavedSignature>> = _items.asStateFlow()

    private val loadJob = scope.launch {
        mutex.withLock {
            _items.value = runCatching {
                if (file.exists()) json.decodeFromString<List<SavedSignature>>(file.readText()) else emptyList()
            }.getOrElse { emptyList() }
        }
    }

    suspend fun add(sig: SavedSignature) = mutate { current ->
        // Keep up to MAX of each kind.
        val others = current.filterNot { s -> s.id == sig.id }
        listOf(sig) + others.filter { it.kind == sig.kind }.take(MAX - 1) + others.filter { it.kind != sig.kind }
    }
    suspend fun delete(id: String) = mutate { it.filterNot { s -> s.id == id } }

    private suspend fun mutate(transform: (List<SavedSignature>) -> List<SavedSignature>) = withContext(Dispatchers.IO) {
        loadJob.join()
        mutex.withLock {
            val next = transform(_items.value).take(MAX * 2)
            _items.value = next
            atomicWrite(file, json.encodeToString(next))
        }
    }

    companion object {
        const val MAX = 12
    }
}

/** Unsaved edits per document, so nothing is lost if the app is killed mid-edit. */
class DraftStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Serializable
    private data class Draft(val uri: String, val updatedAt: Long, val edits: List<app.folio.core.editing.model.PageEdit>)

    private fun fileFor(uri: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        return File(dir, digest.take(16).joinToString("") { "%02x".format(it) } + ".json")
    }

    suspend fun save(uri: String, edits: List<app.folio.core.editing.model.PageEdit>) = withContext(Dispatchers.IO) {
        val f = fileFor(uri)
        if (edits.isEmpty()) { f.delete(); return@withContext }
        dir.mkdirs()
        atomicWrite(f, json.encodeToString(Draft(uri, System.currentTimeMillis(), edits)))
    }

    suspend fun load(uri: String): List<app.folio.core.editing.model.PageEdit> = withContext(Dispatchers.IO) {
        val f = fileFor(uri)
        if (!f.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString<Draft>(f.readText()).takeIf { it.uri == uri }?.edits }.getOrNull() ?: emptyList()
    }

    suspend fun clear(uri: String) = withContext(Dispatchers.IO) { fileFor(uri).delete(); Unit }

    suspend fun move(oldUri: String, newUri: String) {
        val edits = load(oldUri)
        clear(oldUri)
        if (edits.isNotEmpty()) save(newUri, edits)
    }
}

internal fun atomicWrite(file: File, text: String) {
    runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
