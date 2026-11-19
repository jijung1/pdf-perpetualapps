package app.folio.core.tools.organize

/** How to split the current page list into several files. */
sealed interface SplitMode {
    /** One file containing only the selected pages (positions in the current list). */
    data class Selected(val positions: Set<Int>) : SplitMode
    /** One file per range, e.g. "1-3, 5, 8-". */
    data class Ranges(val text: String) : SplitMode
    data class EveryN(val n: Int) : SplitMode
    data object EachPage : SplitMode
}

/** One output file: page positions (0-based, in the current order) and a human label. */
data class SplitPart(val positions: List<Int>, val label: String)

sealed interface SplitPlan {
    data class Ok(val parts: List<SplitPart>) : SplitPlan
    data class Invalid(val message: String) : SplitPlan
}

object SplitPlanner {

    fun plan(mode: SplitMode, pageCount: Int): SplitPlan {
        if (pageCount <= 0) return SplitPlan.Invalid("There are no pages to split.")
        return when (mode) {
            is SplitMode.Selected -> {
                val pos = mode.positions.filter { it in 0 until pageCount }.sorted()
                if (pos.isEmpty()) SplitPlan.Invalid("Select the pages to extract first.")
                else SplitPlan.Ok(listOf(SplitPart(pos, label(pos))))
            }
            is SplitMode.EachPage -> SplitPlan.Ok((0 until pageCount).map { SplitPart(listOf(it), "page ${it + 1}") })
            is SplitMode.EveryN -> {
                if (mode.n < 1) return SplitPlan.Invalid("Enter a number of pages of at least 1.")
                if (mode.n >= pageCount) return SplitPlan.Invalid("That would make a single file. Choose fewer than $pageCount pages.")
                val chunks = (0 until pageCount).chunked(mode.n)
                SplitPlan.Ok(chunks.mapIndexed { i, c -> SplitPart(c, "part ${i + 1} of ${chunks.size}") })
            }
            is SplitMode.Ranges -> when (val r = parseRanges(mode.text, pageCount)) {
                is RangeParse.Invalid -> SplitPlan.Invalid(r.message)
                is RangeParse.Ok -> SplitPlan.Ok(r.ranges.map { range -> range.toList().let { SplitPart(it, label(it)) } })
            }
        }
    }

    sealed interface RangeParse {
        /** 0-based inclusive ranges, in the order typed. */
        data class Ok(val ranges: List<IntRange>) : RangeParse
        data class Invalid(val message: String) : RangeParse
    }

    /**
     * Parses page ranges as people type them: "1-3, 5, 8-" ("8-" means to the end, "-3" from the
     * start). Accepts commas, semicolons or spaces, and en/em dashes. Page numbers are 1-based.
     */
    fun parseRanges(text: String, pageCount: Int): RangeParse {
        val normalized = text.replace('–', '-').replace('—', '-').replace(Regex("\\s*-\\s*"), "-")
        val tokens = normalized.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return RangeParse.Invalid("Enter pages or ranges, for example 1-3, 5, 8-")
        val out = ArrayList<IntRange>()
        for (t in tokens) {
            val m = Regex("^(\\d*)-(\\d*)$").find(t)
            val (start, end) = when {
                m != null -> {
                    val a = m.groupValues[1]; val b = m.groupValues[2]
                    if (a.isEmpty() && b.isEmpty()) return RangeParse.Invalid("“$t” isn't a page or range.")
                    (a.toIntOrNull() ?: 1) to (b.toIntOrNull() ?: pageCount)
                }
                t.all { it.isDigit() } -> t.toInt().let { it to it }
                else -> return RangeParse.Invalid("“$t” isn't a page or range.")
            }
            if (start < 1 || end < 1) return RangeParse.Invalid("Page numbers start at 1.")
            if (start > pageCount || end > pageCount) {
                val bad = maxOf(start, end)
                return RangeParse.Invalid("There's no page $bad. This document has ${pages(pageCount)}.")
            }
            if (end < start) return RangeParse.Invalid("“$t” goes backwards. Did you mean $end-$start?")
            out += (start - 1)..(end - 1)
        }
        return RangeParse.Ok(out)
    }

    /** "page 4", "pages 1-3", "pages 1, 3, 5-7". */
    fun label(positions: List<Int>): String {
        if (positions.size == 1) return "page ${positions[0] + 1}"
        val runs = ArrayList<String>()
        var i = 0
        while (i < positions.size) {
            var j = i
            while (j + 1 < positions.size && positions[j + 1] == positions[j] + 1) j++
            runs += if (j == i) "${positions[i] + 1}" else "${positions[i] + 1}-${positions[j] + 1}"
            i = j + 1
        }
        return "pages " + runs.joinToString(", ")
    }

    /** File name for one part: "Report (pages 1-3).pdf". */
    fun fileName(baseName: String, part: SplitPart): String {
        val base = baseName.substringBeforeLast('.', baseName).ifBlank { "Document" }
        return "$base (${part.label}).pdf"
    }

    private fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"
}
