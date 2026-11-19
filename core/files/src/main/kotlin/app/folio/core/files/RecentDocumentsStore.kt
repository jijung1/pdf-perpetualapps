package app.folio.core.files

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
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Recents persisted as a small JSON file in app-private storage.
 * Only metadata is stored (uri, name, size, position) — never document content.
 * Writes are atomic: write to a temp file, then rename over the original.
 */
class RecentDocumentsStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<RecentDocument>>(emptyList())
    val items: StateFlow<List<RecentDocument>> = _items.asStateFlow()

    private val loadJob = scope.launch { mutex.withLock { _items.value = read() } }

    fun find(uri: String): RecentDocument? = _items.value.firstOrNull { it.uri == uri }

    /** Like [find], but waits for the initial load from disk to finish. */
    suspend fun get(uri: String): RecentDocument? {
        loadJob.join()
        return find(uri)
    }

    suspend fun upsert(doc: RecentDocument) = mutate { RecentDocuments.upsert(it, doc) }
    suspend fun remove(uri: String) = mutate { RecentDocuments.remove(it, uri) }
    suspend fun updatePosition(uri: String, page: Int) = mutate { RecentDocuments.updatePosition(it, uri, page) }
    /** Fire-and-forget position save that outlives the caller (e.g. a ViewModel being cleared). */
    fun savePositionAsync(uri: String, page: Int) {
        scope.launch { updatePosition(uri, page) }
    }

    suspend fun rename(oldUri: String, newUri: String, newName: String) =
        mutate { RecentDocuments.rename(it, oldUri, newUri, newName) }

    private suspend fun mutate(transform: (List<RecentDocument>) -> List<RecentDocument>) =
        withContext(Dispatchers.IO) {
            loadJob.join() // never let a write race (and get clobbered by) the initial load
            mutex.withLock {
                val updated = transform(_items.value)
                if (updated != _items.value) {
                    _items.value = updated
                    write(updated)
                }
            }
        }

    private fun read(): List<RecentDocument> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<RecentDocument>>(file.readText())
    }.getOrElse { emptyList() } // A damaged recents file must never block the app.

    private fun write(list: List<RecentDocument>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
