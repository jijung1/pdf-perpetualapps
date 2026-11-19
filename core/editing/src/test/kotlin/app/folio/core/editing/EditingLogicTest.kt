package app.folio.core.editing

import app.folio.core.editing.geometry.PageTransform
import app.folio.core.editing.ink.InkGeometry
import app.folio.core.editing.ink.PathOp
import app.folio.core.editing.model.AddedText
import app.folio.core.editing.model.Box
import app.folio.core.editing.model.GlyphKey
import app.folio.core.editing.model.TextStyle
import app.folio.core.editing.text.GlyphBox
import app.folio.core.editing.text.TextRunGrouper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTransformTest {

    private fun roundTrip(rotation: Int) {
        val t = PageTransform(10f, 20f, 622f, 812f, rotation)
        for ((u, v) in listOf(0f to 0f, 100f to 50f, t.displayWidth to t.displayHeight, 33f to 400f)) {
            val (x, y) = t.displayToUser(u, v)
            val (u2, v2) = t.userToDisplay(x, y)
            assertEquals("u rot=$rotation", u, u2, 0.01f)
            assertEquals("v rot=$rotation", v, v2, 0.01f)
        }
    }

    @Test fun roundTripsAllRotations() = listOf(0, 90, 180, 270, -90, 450).forEach(::roundTrip)

    @Test
    fun unrotatedTopLeftIsCropTopLeft() {
        val t = PageTransform(0f, 0f, 612f, 792f, 0)
        assertEquals(0f to 792f, t.displayToUser(0f, 0f))
        assertEquals(612f to 0f, t.displayToUser(612f, 792f))
    }

    @Test
    fun rotated90SwapsDimensionsAndMapsTopLeftToBottomLeft() {
        val t = PageTransform(0f, 0f, 612f, 792f, 90)
        assertEquals(792f, t.displayWidth)
        assertEquals(612f, t.displayHeight)
        // Clockwise rotation: the user-space bottom-left corner appears at the display top-left.
        val (x, y) = t.displayToUser(0f, 0f)
        assertEquals(0f, x, 0.001f); assertEquals(0f, y, 0.001f)
    }

    @Test
    fun displayUpMatrixIsAProperRotation() {
        for (r in listOf(0, 90, 180, 270)) {
            val m = PageTransform(0f, 0f, 100f, 200f, r).displayUpToUser
            assertEquals("det for $r", 1f, m[0] * m[3] - m[1] * m[2], 0.0001f) // no mirroring → upright text
        }
    }
}

class InkGeometryTest {

    @Test
    fun normalizeMapsStrokesIntoUnitBox() {
        val ink = InkGeometry.normalize(listOf(listOf(10f, 10f, 110f, 60f), listOf(60f, 30f)), penWidthPx = 4f)
        assertNotNull(ink)
        ink!!
        ink.strokes.flatMap { it.points }.forEach { assertTrue(it in 0f..1f) }
        assertEquals(104f / 54f, ink.aspect, 0.001f)
        assertEquals(4f / 54f, ink.widthRatio, 0.001f)
    }

    @Test
    fun normalizeRejectsEmptyDrawing() {
        assertNull(InkGeometry.normalize(emptyList(), 3f))
        assertNull(InkGeometry.normalize(listOf(emptyList()), 3f))
    }

    @Test
    fun singlePointBecomesDot() {
        val ops = InkGeometry.path(listOf(0.5f, 0.5f)) { x, y -> x to y }
        assertEquals(2, ops.size)
        assertTrue(ops[0] is PathOp.MoveTo && ops[1] is PathOp.LineTo)
    }

    @Test
    fun smoothPathStartsAndEndsOnStrokeEndpoints() {
        val pts = listOf(0f, 0f, 1f, 1f, 2f, 0f, 3f, 1f)
        val ops = InkGeometry.path(pts) { x, y -> x * 10 to y * 10 }
        assertEquals(PathOp.MoveTo(0f, 0f), ops.first())
        assertEquals(PathOp.LineTo(30f, 10f), ops.last())
        assertTrue(ops.any { it is PathOp.CubicTo })
    }

    @Test
    fun simplifyDropsNearbyPointsButKeepsEnds() {
        val pts = listOf(0f, 0f, 0.1f, 0f, 0.2f, 0f, 5f, 0f, 5.1f, 0f)
        val s = InkGeometry.simplify(pts, 1f)
        assertEquals(listOf(0f, 0f, 5f, 0f, 5.1f, 0f), s)
    }
}

class TextRunGrouperTest {

    private fun glyph(key: Int, ch: String, x: Float, w: Float = 6f, y: Float = 100f, size: Float = 12f, font: String = "F1") =
        GlyphBox(
            key = GlyphKey.pack(1, key), text = ch,
            box = Box(x, y - size * 0.8f, x + w, y + size * 0.2f),
            originX = x, originY = y, dirX = 1f, dirY = 0f,
            fontSize = size, fontKey = font, editable = true,
        )

    @Test
    fun contiguousGlyphsFormOneRun() {
        val runs = TextRunGrouper.group(listOf(glyph(0, "H", 10f), glyph(1, "i", 16f)))
        assertEquals(1, runs.size)
        assertEquals("Hi", runs[0].text)
    }

    @Test
    fun kernedGapInsertsSpace() {
        // No space glyph, but a word-sized gap (TJ positioning).
        val runs = TextRunGrouper.group(listOf(glyph(0, "a", 10f), glyph(1, "b", 16f + 4f)))
        assertEquals("a b", runs.single().text)
    }

    @Test
    fun differentBaselineFontOrLargeGapSplitsRuns() {
        val runs = TextRunGrouper.group(
            listOf(
                glyph(0, "a", 10f), glyph(1, "b", 16f),
                glyph(2, "c", 22f, font = "F2"),
                glyph(3, "d", 28f, font = "F2", y = 130f),
                glyph(4, "e", 200f, font = "F2", y = 130f),
            ),
        )
        assertEquals(listOf("ab", "c", "d", "e"), runs.map { it.text })
    }

    @Test
    fun hitTestFindsRunUnderPoint() {
        val runs = TextRunGrouper.group(listOf(glyph(0, "a", 10f), glyph(1, "b", 16f), glyph(2, "z", 10f, y = 200f)))
        assertEquals("ab", TextRunGrouper.hitTest(runs, 14f, 96f, 2f)?.text)
        assertNull(TextRunGrouper.hitTest(runs, 500f, 500f, 2f))
    }

    @Test
    fun glyphKeysPackAndUnpack() {
        val k = GlyphKey.pack(123456, 789)
        assertEquals(123456, GlyphKey.operator(k))
        assertEquals(789, GlyphKey.ordinal(k))
    }
}

class EditHistoryTest {

    private fun text(id: String, x: Float = 0f) = AddedText(id, 0, 612f, x, 0f, "t", TextStyle())

    @Test
    fun undoRedoRoundTrip() {
        var h = EditHistory().add(text("a")).add(text("b"))
        assertEquals(2, h.current.size)
        h = h.undo()
        assertEquals(listOf("a"), h.current.map { it.id })
        assertTrue(h.canRedo)
        h = h.redo()
        assertEquals(listOf("a", "b"), h.current.map { it.id })
        assertFalse(h.canRedo)
    }

    @Test
    fun newEditClearsRedoAndReplaceDoesNotCreateUndoStep() {
        var h = EditHistory().add(text("a")).undo()
        h = h.add(text("c"))
        assertFalse(h.canRedo)
        val before = h
        h = h.replace(listOf(text("c", x = 50f)))
        assertEquals(before.undo().current, h.undo().current)
    }

    @Test
    fun pushingIdenticalStateIsNoOp() {
        val h = EditHistory().add(text("a"))
        assertTrue(h.push(h.current) === h)
    }
}
