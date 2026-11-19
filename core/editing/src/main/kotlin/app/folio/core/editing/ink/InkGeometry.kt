package app.folio.core.editing.ink

import app.folio.core.editing.model.Ink
import app.folio.core.editing.model.Stroke
import kotlin.math.hypot
import kotlin.math.max

/** Path commands shared by the on-screen renderer and the PDF writer, so both draw identical curves. */
sealed interface PathOp {
    data class MoveTo(val x: Float, val y: Float) : PathOp
    data class LineTo(val x: Float, val y: Float) : PathOp
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp
}

object InkGeometry {

    /**
     * Normalises raw strokes (in any pixel space) to an [Ink] relative to their bounding box.
     * Returns null if there's nothing meaningful drawn.
     */
    fun normalize(rawStrokes: List<List<Float>>, penWidthPx: Float): Ink? {
        val nonEmpty = rawStrokes.filter { it.size >= 2 }
        if (nonEmpty.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in nonEmpty) for (i in s.indices step 2) {
            minX = minOf(minX, s[i]); maxX = maxOf(maxX, s[i])
            minY = minOf(minY, s[i + 1]); maxY = maxOf(maxY, s[i + 1])
        }
        // Pad by half the pen width so stroke edges aren't clipped by the box.
        val pad = penWidthPx / 2f
        minX -= pad; minY -= pad; maxX += pad; maxY += pad
        val w = max(maxX - minX, 1f)
        val h = max(maxY - minY, 1f)
        val strokes = nonEmpty.map { s ->
            Stroke(s.mapIndexed { i, v -> if (i % 2 == 0) (v - minX) / w else (v - minY) / h })
        }
        return Ink(strokes = strokes, aspect = w / h, widthRatio = penWidthPx / h)
    }

    /** Drops points closer than [minDistance] to the previous kept point (reduces jitter and size). */
    fun simplify(points: List<Float>, minDistance: Float): List<Float> {
        if (points.size <= 4) return points
        val out = ArrayList<Float>(points.size)
        out += points[0]; out += points[1]
        var lx = points[0]; var ly = points[1]
        for (i in 2 until points.size - 2 step 2) {
            val x = points[i]; val y = points[i + 1]
            if (hypot(x - lx, y - ly) >= minDistance) {
                out += x; out += y; lx = x; ly = y
            }
        }
        out += points[points.size - 2]; out += points[points.size - 1]
        return out
    }

    /**
     * Smooth path through the stroke points using midpoint quadratic curves (expressed as cubics),
     * mapped through [map] into the destination coordinate space.
     */
    fun path(points: List<Float>, map: (Float, Float) -> Pair<Float, Float>): List<PathOp> {
        val n = points.size / 2
        if (n == 0) return emptyList()
        val pts = List(n) { map(points[it * 2], points[it * 2 + 1]) }
        val (x0, y0) = pts[0]
        if (n == 1) {
            // A dot: a zero-length segment renders as a round dot with round caps.
            return listOf(PathOp.MoveTo(x0, y0), PathOp.LineTo(x0 + 0.01f, y0))
        }
        if (n == 2) return listOf(PathOp.MoveTo(x0, y0), PathOp.LineTo(pts[1].first, pts[1].second))

        val ops = ArrayList<PathOp>(n + 1)
        ops += PathOp.MoveTo(x0, y0)
        var cur = pts[0]
        for (i in 1 until n - 1) {
            val c = pts[i]
            val next = pts[i + 1]
            val mid = ((c.first + next.first) / 2f) to ((c.second + next.second) / 2f)
            ops += quadAsCubic(cur, c, mid)
            cur = mid
        }
        val last = pts[n - 1]
        ops += PathOp.LineTo(last.first, last.second)
        return ops
    }

    private fun quadAsCubic(p0: Pair<Float, Float>, c: Pair<Float, Float>, p: Pair<Float, Float>): PathOp.CubicTo {
        val c1x = p0.first + 2f / 3f * (c.first - p0.first)
        val c1y = p0.second + 2f / 3f * (c.second - p0.second)
        val c2x = p.first + 2f / 3f * (c.first - p.first)
        val c2y = p.second + 2f / 3f * (c.second - p.second)
        return PathOp.CubicTo(c1x, c1y, c2x, c2y, p.first, p.second)
    }
}
