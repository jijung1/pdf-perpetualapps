package app.folio.core.files

import kotlinx.serialization.Serializable

@Serializable
data class RecentDocument(
    val uri: String,
    val name: String,
    val mimeType: String = "application/pdf",
    val sizeBytes: Long? = null,
    val pageCount: Int? = null,
    val lastOpenedAt: Long,
    val lastPage: Int = 0,
)

/** Pure list operations for the recents list (unit tested). */
object RecentDocuments {
    const val MAX_ENTRIES = 50

    /** Inserts or updates [doc] (matched by uri) and moves it to the front. */
    fun upsert(list: List<RecentDocument>, doc: RecentDocument): List<RecentDocument> =
        (listOf(doc) + list.filterNot { it.uri == doc.uri }).take(MAX_ENTRIES)

    fun remove(list: List<RecentDocument>, uri: String): List<RecentDocument> =
        list.filterNot { it.uri == uri }

    /** Updates the reading position without changing ordering. */
    fun updatePosition(list: List<RecentDocument>, uri: String, page: Int): List<RecentDocument> =
        list.map { if (it.uri == uri) it.copy(lastPage = page.coerceAtLeast(0)) else it }

    /** Replaces the uri/name after a rename while keeping position and ordering. */
    fun rename(list: List<RecentDocument>, oldUri: String, newUri: String, newName: String) =
        list.map { if (it.uri == oldUri) it.copy(uri = newUri, name = newName) else it }
}
