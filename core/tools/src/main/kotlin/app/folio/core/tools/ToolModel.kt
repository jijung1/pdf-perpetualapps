package app.folio.core.tools

import android.net.Uri

/** Why a merge / split couldn't be done. Every case maps to a plain-language message. */
sealed interface ToolError {
    data class PasswordProtected(val name: String) : ToolError
    /** The PDF's owner permissions forbid this change. */
    data class NotPermitted(val name: String) : ToolError
    data class Unreadable(val name: String) : ToolError
    data class UnsupportedImage(val name: String, val needsNewerAndroid: Boolean) : ToolError
    data object SourceUnavailable : ToolError
    data object InsufficientStorage : ToolError
    data object WriteFailed : ToolError
}

class ToolException(val error: ToolError, cause: Throwable? = null) : Exception(error.toString(), cause)

enum class SourceKind { PDF, IMAGE }

/** A file the user added: a PDF or a photo/screenshot. */
data class SourceFile(
    val id: String,
    val uri: Uri,
    val name: String,
    val kind: SourceKind,
    /** PDF page count; 1 for images. */
    val pageCount: Int,
)

/** One page of the output: page [pageIndex] of [sourceId] (0 for images), turned [rotation]° clockwise. */
data class PageRef(val key: String, val sourceId: String, val pageIndex: Int, val rotation: Int = 0) {
    val normalizedRotation: Int get() = ((rotation % 360) + 360) % 360
}

enum class ImagePageSize { FIT, A4, LETTER }

/** How photos become pages. */
data class ImageOptions(val pageSize: ImagePageSize = ImagePageSize.A4, val margins: Boolean = false)

data class AssembleReport(
    val pageCount: Int,
    /** Bookmarks existed but couldn't be kept (they're only kept when the output comes from one PDF). */
    val bookmarksDropped: Boolean,
    val formFieldsKept: Int,
)
