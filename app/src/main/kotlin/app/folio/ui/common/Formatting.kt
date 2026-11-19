package app.folio.ui.common

import app.folio.core.files.FileOpError
import app.folio.core.pdf.PdfOpenError
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Formatting {

    fun fileSize(bytes: Long?): String? {
        if (bytes == null || bytes < 0) return null
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val value = bytes / 1024.0.pow(exp)
        return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[exp - 1])
    }

    fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - epochMillis) / 60_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}d ago"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
        }
    }

    fun pages(count: Int?): String? = when (count) {
        null -> null
        1 -> "1 page"
        else -> "$count pages"
    }
}

data class UserMessage(val title: String, val detail: String)

fun PdfOpenError.toMessage(): UserMessage = when (this) {
    PdfOpenError.PasswordProtected -> UserMessage(
        "This PDF is password-protected",
        "Opening protected documents isn't available in this build yet. The file was not changed.",
    )
    PdfOpenError.Corrupted -> UserMessage(
        "This file can't be read",
        "It may be damaged or not a valid PDF. The original file was not changed.",
    )
    PdfOpenError.NotFound -> UserMessage(
        "File not found",
        "It may have been moved, renamed or deleted in another app.",
    )
    PdfOpenError.AccessDenied -> UserMessage(
        "Access to this file was lost",
        "Open it again from the file picker to grant access.",
    )
    PdfOpenError.Empty -> UserMessage("This PDF has no pages", "There is nothing to display.")
    PdfOpenError.InsufficientStorage -> UserMessage(
        "Not enough storage",
        "Free up some space on your device and try again.",
    )
    PdfOpenError.Unknown -> UserMessage("Couldn't open this document", "Please try again.")
}

fun FileOpError.toMessage(action: String): String = when (this) {
    FileOpError.NotFound -> "$action failed: the file no longer exists."
    FileOpError.AccessDenied -> "$action failed: permission to this location was denied."
    FileOpError.InsufficientStorage -> "$action failed: not enough storage space."
    FileOpError.NotSupported -> "$action isn't supported by this file's storage provider."
    FileOpError.Unknown -> "$action failed. Please try again."
}
