package app.folio.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderMathTest {

    private val letter = PageSize(612, 792)

    @Test
    fun bucketRoundsUpToNextRenderScale() {
        assertEquals(1f, RenderMath.bucketFor(1f))
        assertEquals(1.5f, RenderMath.bucketFor(1.2f))
        assertEquals(2f, RenderMath.bucketFor(1.9f))
        assertEquals(3f, RenderMath.bucketFor(2.4f))
        assertEquals(4f, RenderMath.bucketFor(9f))
    }

    @Test
    fun renderWidthFollowsZoomBucket() {
        assertEquals(1000, RenderMath.renderWidth(1000, 1f, letter))
        assertEquals(2000, RenderMath.renderWidth(1000, 1.8f, letter))
    }

    @Test
    fun renderWidthIsCappedByMemoryBudget() {
        val w = RenderMath.renderWidth(1440, 4f, letter, maxBytes = 8L * 1024 * 1024)
        val bytes = w.toLong() * letter.heightForWidth(w) * 4
        assertTrue("bitmap $bytes exceeds budget", bytes <= 8L * 1024 * 1024 + w * 4)
        assertTrue(w < 1440 * 4)
    }

    @Test
    fun extremelyTallPageStillGetsPositiveWidth() {
        val strip = PageSize(10, 100_000)
        assertTrue(RenderMath.renderWidth(1080, 4f, strip) >= 1)
    }

    @Test
    fun panIsClampedToContentOverflow() {
        assertEquals(0f, RenderMath.clampPanX(300f, 1000f, 1000f))
        assertEquals(500f, RenderMath.clampPanX(900f, 1000f, 2000f))
        assertEquals(-500f, RenderMath.clampPanX(-900f, 1000f, 2000f))
    }

    @Test
    fun anchoredPanKeepsFocusPointStationary() {
        val viewport = 1000f
        val focus = 800f
        val pan0 = 0f
        val ratio = 2f
        val pan1 = RenderMath.anchoredPanX(pan0, focus, viewport, ratio)
        // Content coordinate under focus before: (focus - center - pan0); after scale it should map back to focus.
        val before = focus - viewport / 2 - pan0
        val after = viewport / 2 + pan1 + before * ratio
        assertEquals(focus, after, 0.001f)
    }

    @Test
    fun heightForWidthPreservesAspect() {
        assertEquals(1294, letter.heightForWidth(1000))
        assertEquals(1, PageSize(1000, 1).heightForWidth(10))
    }
}
