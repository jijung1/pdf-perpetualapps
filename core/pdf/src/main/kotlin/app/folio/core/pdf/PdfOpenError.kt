package app.folio.core.pdf

/** Typed reasons a document could not be opened, so the UI can explain them without exposing raw exceptions. */
sealed interface PdfOpenError {
    /** The file is encrypted and requires a password to open. */
    data object PasswordProtected : PdfOpenError

    /** The file is not a valid PDF or is damaged. */
    data object Corrupted : PdfOpenError

    /** The file no longer exists at its location. */
    data object NotFound : PdfOpenError

    /** The app lost (or never had) permission to read the file. */
    data object AccessDenied : PdfOpenError

    /** The document has zero pages. */
    data object Empty : PdfOpenError

    /** Device storage is full (e.g. while staging a non-seekable stream). */
    data object InsufficientStorage : PdfOpenError

    data object Unknown : PdfOpenError
}

class PdfOpenException(val error: PdfOpenError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
