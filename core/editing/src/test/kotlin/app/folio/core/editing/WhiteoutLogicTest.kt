package app.folio.core.editing

import app.folio.core.editing.model.Box
import app.folio.core.editing.whiteout.BackgroundSampler
import app.folio.core.editing.whiteout.PixelRect
import app.folio.core.editing.whiteout.RedactionRules
import app.folio.core.editing.whiteout.SampleResult
import app.folio.core.editing.whiteout.SamplingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackgroundSamplerTest {

    private val w = 120
    private val h = 60
    private val exclude = PixelRect(20, 15, 100, 45)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun image(fill: Int) = IntArray(w * h) { fill }
    private fun IntArray.paint(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * w + x] = c
    }

    private fun confident(r: SampleResult): SampleResult.Confident {
        assertTrue("expected a confident result, got $r", r is SampleResult.Confident)
        return r as SampleResult.Confident
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 2) {
        for (shift in listOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue("channel $shift: expected $e got $a", kotlin.math.abs(e - a) <= tolerance)
        }
    }

    @Test
    fun plainCreamPaper() {
        val cream = rgb(245, 238, 220)
        assertColor(cream, confident(BackgroundSampler.sample(image(cream), w, h, exclude)).argb)
    }

    @Test
    fun blackTextInTheRingIsIgnored() {
        val cream = rgb(246, 240, 225)
        val px = image(cream)
        // Lines of dark text above and below the box.
        px.paint(10, 2, 110, 9, rgb(20, 20, 20))
        px.paint(10, 50, 110, 57, rgb(30, 30, 35))
        assertColor(cream, confident(BackgroundSampler.sample(px, w, h, exclude)).argb)
    }

    @Test
    fun excludedAreaNeverCounts() {
        val px = image(rgb(255, 255, 255))
        // Bright but different colour *inside* the box must not influence anything.
        px.paint(exclude.left, exclude.top, exclude.right, exclude.bottom, rgb(200, 255, 200))
        assertColor(rgb(255, 255, 255), confident(BackgroundSampler.sample(px, w, h, exclude)).argb)
    }

    @Test
    fun lightShadowOnOneSideIsRejectedByClustering() {
        val paper = rgb(250, 250, 248)
        val px = image(paper)
        // A light grey shadow (bright enough to pass the luma filter) along the right edge.
        px.paint(100, 0, 120, 60, rgb(205, 205, 205))
        val r = confident(BackgroundSampler.sample(px, w, h, exclude))
        assertColor(paper, r.argb)
        assertTrue(r.dominantShare < 1f)
    }

    @Test
    fun darkTableBorderIsDropped() {
        val paper = rgb(255, 255, 255)
        val px = image(paper)
        px.paint(0, 12, 120, 14, rgb(0, 0, 0))
        px.paint(17, 0, 19, 60, rgb(0, 0, 0))
        assertColor(paper, confident(BackgroundSampler.sample(px, w, h, exclude)).argb)
    }

    @Test
    fun speckledScanStillMatches() {
        val rnd = Random(7)
        val base = 236
        val px = IntArray(w * h) { val n = base + rnd.nextInt(-4, 5); rgb(n, n - 2, n - 8) }
        val r = confident(BackgroundSampler.sample(px, w, h, exclude))
        assertColor(rgb(base, base - 2, base - 8), r.argb, tolerance = 3)
    }

    @Test
    fun darkPageFallsBack() {
        val r = BackgroundSampler.sample(image(rgb(40, 40, 50)), w, h, exclude)
        assertTrue(r is SampleResult.LowConfidence)
        assertEquals(SampleResult.Reason.TOO_DARK, (r as SampleResult.LowConfidence).reason)
    }

    @Test
    fun noDominantColourFallsBack() {
        // Half pale yellow, half pale blue: two equal clusters, neither reaches the 55% share.
        val px = image(rgb(250, 240, 190))
        px.paint(60, 0, 120, 60, rgb(200, 220, 250))
        val r = BackgroundSampler.sample(px, w, h, exclude)
        assertTrue("$r", r is SampleResult.LowConfidence)
        assertEquals(SampleResult.Reason.NO_DOMINANT_COLOR, (r as SampleResult.LowConfidence).reason)
    }

    @Test
    fun tooFewPixelsFallsBack() {
        // A box covering the whole rendered region (e.g. a full-page whiteout) leaves nothing to sample.
        val r = BackgroundSampler.sample(image(rgb(255, 255, 255)), w, h, PixelRect(0, 0, w, h))
        assertTrue(r is SampleResult.LowConfidence)
        assertEquals(SampleResult.Reason.TOO_FEW_PIXELS, (r as SampleResult.LowConfidence).reason)
    }

    @Test
    fun lightGreyFieldNextToWhite() {
        // A form field's grey fill covers most of the ring; it's the dominant background.
        val grey = rgb(232, 236, 242)
        val px = image(grey)
        px.paint(0, 0, 120, 6, rgb(255, 255, 255))
        assertColor(grey, confident(BackgroundSampler.sample(px, w, h, exclude)).argb)
    }

    @Test
    fun thresholdsAreConfigurable() {
        val mid = rgb(170, 170, 170)
        assertTrue(BackgroundSampler.sample(image(mid), w, h, exclude) is SampleResult.LowConfidence)
        val loose = SamplingConfig(minLuma = 150)
        assertColor(mid, confident(BackgroundSampler.sample(image(mid), w, h, exclude, loose)).argb)
    }

    @Test
    fun planClipsToPageAndExcludesTheBox() {
        val cfg = SamplingConfig(ringWidthPt = 6f, innerGapPt = 1f, renderDpi = 144)
        val plan = BackgroundSampler.plan(Box(2f, 100f, 50f, 120f), pageWidth = 612f, pageHeight = 792f, config = cfg)
        assertEquals(0f, plan.region.left, 0.001f) // clipped at the page edge
        assertEquals(93f, plan.region.top, 0.001f)
        assertEquals(2f, plan.pxPerPt, 0.001f)
        assertEquals(((2f - 1f - 0f) * 2).toInt(), plan.exclude.left)
        assertTrue(plan.exclude.right <= plan.widthPx && plan.exclude.bottom <= plan.heightPx)
    }
}

class RedactionRulesTest {

    private val box = Box(100f, 100f, 200f, 120f)

    @Test
    fun glyphWithCentreInsideIsRemoved() = assertTrue(RedactionRules.removesGlyph(Box(150f, 105f, 158f, 117f), box))

    @Test
    fun glyphMostlyOutsideButOverlappingEnoughIsRemoved() =
        // 25% of the glyph lies inside the box.
        assertTrue(RedactionRules.removesGlyph(Box(194f, 105f, 218f, 117f), box))

    @Test
    fun glyphBarelyTouchingIsKept() =
        // The line above whose descender grazes the box by 1pt of 12.
        assertFalse(RedactionRules.removesGlyph(Box(150f, 89f, 158f, 101f), box))

    @Test
    fun glyphElsewhereIsKept() = assertFalse(RedactionRules.removesGlyph(Box(300f, 105f, 308f, 117f), box))

    @Test
    fun fullyCoveredNeedsOneBoxToContainIt() {
        assertTrue(RedactionRules.fullyCovered(Box(110f, 102f, 190f, 118f), listOf(box)))
        assertFalse(RedactionRules.fullyCovered(Box(90f, 102f, 190f, 118f), listOf(box)))
    }

    @Test
    fun spilloverCountsRemovedGlyphsThatStickOut() {
        val glyphs = listOf(Box(150f, 105f, 158f, 117f), Box(196f, 105f, 206f, 117f), Box(300f, 105f, 308f, 117f))
        assertEquals(1, RedactionRules.spillover(glyphs, box))
    }
}
