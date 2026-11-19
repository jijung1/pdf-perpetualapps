package app.folio.core.editing.geometry

/**
 * Maps between PDF user space (origin bottom-left, y up, unrotated) and display space
 * (the page as shown after /Rotate, origin top-left, y down), using the page crop box.
 */
class PageTransform(
    private val llx: Float,
    private val lly: Float,
    private val urx: Float,
    private val ury: Float,
    rotation: Int,
) {
    val rotation: Int = ((rotation % 360) + 360) % 360

    private val cropW = urx - llx
    private val cropH = ury - lly

    val displayWidth: Float = if (this.rotation == 90 || this.rotation == 270) cropH else cropW
    val displayHeight: Float = if (this.rotation == 90 || this.rotation == 270) cropW else cropH

    /**
     * Affine matrix [a b c d e f] mapping "display-up" coordinates (origin bottom-left of the
     * displayed page, y up) to user space: x = a*p + c*q + e, y = b*p + d*q + f.
     * Drawing in display-up space makes text appear upright regardless of page rotation.
     */
    val displayUpToUser: FloatArray = when (this.rotation) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, urx, lly)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, urx, ury)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, ury)
        else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
    }

    /** Display (top-left, y down) → user space. */
    fun displayToUser(u: Float, v: Float): Pair<Float, Float> {
        val p = u
        val q = displayHeight - v
        val m = displayUpToUser
        return (m[0] * p + m[2] * q + m[4]) to (m[1] * p + m[3] * q + m[5])
    }

    /** User space → display (top-left, y down). Inverse of [displayToUser]. */
    fun userToDisplay(x: Float, y: Float): Pair<Float, Float> {
        val m = displayUpToUser
        // Inverse of a rotation-only affine: transpose the 2x2 part.
        val dx = x - m[4]
        val dy = y - m[5]
        val p = m[0] * dx + m[1] * dy
        val q = m[2] * dx + m[3] * dy
        return p to (displayHeight - q)
    }
}
