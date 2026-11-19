package app.folio.core.tools

import app.folio.core.tools.images.ImageLayout
import app.folio.core.tools.organize.SplitMode
import app.folio.core.tools.organize.SplitPlan
import app.folio.core.tools.organize.SplitPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPlannerTest {

    private fun ok(r: SplitPlanner.RangeParse) = (r as SplitPlanner.RangeParse.Ok).ranges
    private fun bad(r: SplitPlanner.RangeParse) = (r as SplitPlanner.RangeParse.Invalid).message

    @Test
    fun parsesTheUsualForms() {
        assertEquals(listOf(0..2, 4..4, 7..9), ok(SplitPlanner.parseRanges("1-3, 5, 8-", 10)))
        assertEquals(listOf(0..2), ok(SplitPlanner.parseRanges("-3", 10)))
        assertEquals(listOf(1..3, 5..5), ok(SplitPlanner.parseRanges(" 2 – 4 ; 6 ", 10)))
        assertEquals(listOf(0..0, 2..2), ok(SplitPlanner.parseRanges("1 3", 10)))
    }

    @Test
    fun explainsMistakes() {
        assertTrue(bad(SplitPlanner.parseRanges("12", 10)).contains("no page 12"))
        assertTrue(bad(SplitPlanner.parseRanges("5-2", 10)).contains("2-5"))
        assertTrue(bad(SplitPlanner.parseRanges("abc", 10)).contains("isn't a page"))
        assertTrue(bad(SplitPlanner.parseRanges("0", 10)).contains("start at 1"))
        assertTrue(bad(SplitPlanner.parseRanges("  ", 10)).contains("for example"))
        assertTrue(bad(SplitPlanner.parseRanges("-", 10)).contains("isn't a page"))
    }

    @Test
    fun plansEveryMode() {
        val each = SplitPlanner.plan(SplitMode.EachPage, 3) as SplitPlan.Ok
        assertEquals(3, each.parts.size)
        val every = SplitPlanner.plan(SplitMode.EveryN(2), 5) as SplitPlan.Ok
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), every.parts.map { it.positions })
        assertEquals("part 3 of 3", every.parts.last().label)
        val sel = SplitPlanner.plan(SplitMode.Selected(setOf(4, 1, 2)), 6) as SplitPlan.Ok
        assertEquals(listOf(1, 2, 4), sel.parts.single().positions)
        assertTrue(SplitPlanner.plan(SplitMode.EveryN(5), 5) is SplitPlan.Invalid)
        assertTrue(SplitPlanner.plan(SplitMode.Selected(emptySet()), 5) is SplitPlan.Invalid)
    }

    @Test
    fun labelsAndFileNames() {
        assertEquals("page 4", SplitPlanner.label(listOf(3)))
        assertEquals("pages 1-3, 5, 7-8", SplitPlanner.label(listOf(0, 1, 2, 4, 6, 7)))
        val part = (SplitPlanner.plan(SplitMode.Ranges("1-2"), 4) as SplitPlan.Ok).parts.single()
        assertEquals("Report (pages 1-2).pdf", SplitPlanner.fileName("Report.pdf", part))
    }
}

class ImageLayoutTest {

    @Test
    fun portraitPhotoOnA4IsCentredAndFits() {
        val p = ImageLayout.place(3000, 4000, ImageOptions(ImagePageSize.A4, margins = false))
        assertEquals(ImageLayout.A4_W, p.pageWidth, 0.01f)
        assertEquals(ImageLayout.A4_H, p.pageHeight, 0.01f)
        assertTrue(p.width <= p.pageWidth + 0.01f && p.height <= p.pageHeight + 0.01f)
        assertEquals((p.pageWidth - p.width) / 2f, p.x, 0.01f)
        assertEquals(3000f / 4000f, p.width / p.height, 0.001f)
    }

    @Test
    fun landscapePhotoGetsLandscapeLetterPageWithMargins() {
        val p = ImageLayout.place(4000, 3000, ImageOptions(ImagePageSize.LETTER, margins = true))
        assertEquals(ImageLayout.LETTER_H, p.pageWidth, 0.01f)
        assertEquals(ImageLayout.LETTER_W, p.pageHeight, 0.01f)
        assertTrue(p.x >= ImageLayout.MARGIN - 0.01f && p.y >= ImageLayout.MARGIN - 0.01f)
    }

    @Test
    fun fitPageTakesThePhotosShape() {
        val p = ImageLayout.place(1000, 500, ImageOptions(ImagePageSize.FIT, margins = false))
        assertEquals(2f, p.pageWidth / p.pageHeight, 0.001f)
        assertEquals(0f, p.x, 0.001f)
    }

    @Test
    fun hugePhotosAreCappedAt300Dpi() {
        val p = ImageLayout.place(8000, 6000, ImageOptions(ImagePageSize.A4))
        val (w, h) = ImageLayout.targetPixels(8000, 6000, p)
        assertTrue(w <= (p.width / 72f * 300f).toInt() + 1)
        assertEquals(8000f / 6000f, w.toFloat() / h, 0.01f)
        // Small images are never enlarged.
        assertEquals(400 to 300, ImageLayout.targetPixels(400, 300, ImageLayout.place(400, 300, ImageOptions())))
    }

    @Test
    fun decodeSubsamplingNeverUndershoots() {
        assertEquals(4, ImageLayout.inSampleSize(8000, 6000, 1900, 1400))
        assertEquals(1, ImageLayout.inSampleSize(1000, 800, 900, 700))
    }
}
