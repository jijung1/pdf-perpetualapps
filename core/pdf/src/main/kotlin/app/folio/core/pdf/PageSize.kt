package app.folio.core.pdf

/** Page dimensions in PDF points (1/72 inch), as reported by the renderer. */
data class PageSize(val width: Int, val height: Int) {
    val aspectRatio: Float get() = if (height == 0) 1f else width.toFloat() / height.toFloat()

    /** Height in pixels when the page is laid out [widthPx] wide. */
    fun heightForWidth(widthPx: Int): Int =
        if (width == 0) widthPx else (widthPx.toLong() * height / width).toInt().coerceAtLeast(1)
}
