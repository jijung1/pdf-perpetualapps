package app.folio.core.editing

import app.folio.core.editing.model.PageEdit

/** Immutable undo/redo history over the list of pending edits. */
data class EditHistory(
    val current: List<PageEdit> = emptyList(),
    private val past: List<List<PageEdit>> = emptyList(),
    private val future: List<List<PageEdit>> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(next: List<PageEdit>): EditHistory =
        if (next == current) this
        else copy(current = next, past = (past + listOf(current)).takeLast(MAX_DEPTH), future = emptyList())

    /** Replace the current state without creating an undo step (e.g. continuous drag updates). */
    fun replace(next: List<PageEdit>): EditHistory = copy(current = next)

    fun undo(): EditHistory =
        if (!canUndo) this else copy(current = past.last(), past = past.dropLast(1), future = listOf(current) + future)

    fun redo(): EditHistory =
        if (!canRedo) this else copy(current = future.first(), past = past + listOf(current), future = future.drop(1))

    fun add(edit: PageEdit) = push(current + edit)
    fun remove(id: String) = push(current.filterNot { it.id == id })
    fun update(edit: PageEdit) = push(current.map { if (it.id == edit.id) edit else it })

    companion object {
        const val MAX_DEPTH = 100
        fun restored(edits: List<PageEdit>) = EditHistory(current = edits)
    }
}
