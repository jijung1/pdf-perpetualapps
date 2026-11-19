package app.folio.core.editing.text

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Deletes specific glyphs from a page's content stream — a real removal, not a cover-up.
 *
 * Each affected text-showing operator (Tj, TJ, ', ") is rewritten as a TJ array in which removed
 * glyphs are replaced by an equal positioning adjustment. Remaining glyphs therefore keep their exact
 * positions and the rest of the page is byte-for-byte unaffected in meaning.
 */
object ContentStreamEditor {

    /** Returns the number of glyphs removed. */
    fun removeGlyphs(doc: PDDocument, page: PDPage, remove: Set<Long>, records: List<GlyphRecord>): Int {
        if (remove.isEmpty()) return 0
        val topLevel = records.filter { !it.inForm }
        val glyphsByOp = topLevel.groupBy { it.operatorIndex }
        val affectedOps = topLevel.filter { it.key in remove }.groupBy({ it.operatorIndex }, { it.ordinal })

        val parser = PDFStreamParser(page)
        parser.parse()
        var removed = 0
        val out = rewriteTokens(parser.tokens) { opIndex, op, operands ->
            val targets = affectedOps[opIndex] ?: return@rewriteTokens null
            val glyphs = glyphsByOp.getValue(opIndex).sortedBy { it.ordinal }
            val result = removeFromOperator(op, operands, glyphs, targets.toSet())
            removed += result.second
            result.first
        }
        page.setContents(toStream(doc, out))
        return removed
    }

    /**
     * Walks [tokens] operator by operator (operator indices count every operator, matching the
     * order PDFBox's stream engine processes them). [rewrite] returns the replacement for an
     * operator together with its operands, or null to keep it unchanged.
     */
    fun rewriteTokens(tokens: List<Any>, rewrite: (opIndex: Int, op: Operator, operands: List<Any>) -> List<Any>?): List<Any> {
        val out = ArrayList<Any>(tokens.size + 16)
        val operands = ArrayList<Any>()
        var opIndex = -1
        for (token in tokens) {
            if (token is Operator) {
                opIndex++
                val replacement = rewrite(opIndex, token, operands)
                if (replacement == null) {
                    out.addAll(operands); out.add(token)
                } else {
                    out.addAll(replacement)
                }
                operands.clear()
            } else {
                operands.add(token)
            }
        }
        out.addAll(operands)
        return out
    }

    fun toStream(doc: PDDocument, tokens: List<Any>): PDStream {
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        return stream
    }

    /**
     * Removes glyphs with the given ordinals from one text-showing operator. [glyphs] are all glyphs
     * the operator shows, in order. Returns the replacement tokens and the number removed.
     */
    fun removeFromOperator(op: Operator, operands: List<Any>, glyphs: List<GlyphRecord>, remove: Set<Int>): Pair<List<Any>, Int> {
        if (glyphs.isEmpty()) return (operands + op) to 0
        val counter = intArrayOf(0, 0) // [next ordinal, removed count]
        val tj = Operator.getOperator("TJ")
        fun arrayOf(vararg parts: COSBase) = buildArray(parts.toList(), glyphs, remove, counter)

        val tokens: List<Any> = when (op.name) {
            "Tj" -> {
                val s = operands.lastOrNull() as? COSString ?: return (operands + op) to 0
                listOf(arrayOf(s), tj)
            }
            "TJ" -> {
                val a = operands.lastOrNull() as? COSArray ?: return (operands + op) to 0
                listOf(arrayOf(*a.toList().toTypedArray()), tj)
            }
            "'" -> {
                val s = operands.lastOrNull() as? COSString ?: return (operands + op) to 0
                listOf(Operator.getOperator("T*"), arrayOf(s), tj)
            }
            "\"" -> {
                if (operands.size < 3) return (operands + op) to 0
                val aw = operands[0]; val ac = operands[1]
                val s = operands[2] as? COSString ?: return (operands + op) to 0
                listOf(aw, Operator.getOperator("Tw"), ac, Operator.getOperator("Tc"), Operator.getOperator("T*"), arrayOf(s), tj)
            }
            else -> return (operands + op) to 0
        }
        return tokens to counter[1]
    }

    private fun buildArray(parts: List<COSBase>, glyphs: List<GlyphRecord>, remove: Set<Int>, counter: IntArray): COSArray {
        val result = COSArray()
        val font = glyphs.first().font
        val buffer = ByteArrayOutputStream()
        fun flush() {
            if (buffer.size() > 0) { result.add(COSString(buffer.toByteArray())); buffer.reset() }
        }
        for (part in parts) {
            when (part) {
                is COSNumber -> { flush(); result.add(part) }
                is COSString -> {
                    val bytes = part.bytes
                    val input = ByteArrayInputStream(bytes)
                    var pos = 0
                    while (input.available() > 0) {
                        val before = input.available()
                        val code = font.readCode(input)
                        val len = before - input.available()
                        val ordinal = counter[0]++
                        if (ordinal in remove) {
                            flush()
                            val g = glyphs.getOrNull(ordinal)
                            if (g != null) result.add(COSFloat(adjustmentFor(g, code, len)))
                            counter[1]++
                        } else {
                            buffer.write(bytes, pos, len)
                        }
                        pos += len
                    }
                    flush()
                }
                else -> { flush(); result.add(part) }
            }
        }
        flush()
        return result
    }

    /**
     * TJ adjustment that advances exactly as far as the removed glyph did:
     * tx = (w0·Tfs + Tc + Tw)·Th  and  tx = −(n/1000)·Tfs·Th  ⇒  n = −(w0 + (Tc + Tw)/Tfs)·1000.
     */
    internal fun adjustmentFor(g: GlyphRecord, code: Int, codeLength: Int): Float {
        val wordSpacing = if (code == 32 && codeLength == 1) g.wordSpacing else 0f
        val tfs = g.fontSize
        if (tfs == 0f) return -g.advance * 1000f
        return -(g.advance + (g.charSpacing + wordSpacing) / tfs) * 1000f
    }
}
