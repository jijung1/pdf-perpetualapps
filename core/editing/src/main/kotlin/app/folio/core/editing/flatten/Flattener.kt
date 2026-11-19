package app.folio.core.editing.flatten

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDVariableText
import com.tom_roush.pdfbox.util.Matrix

data class FlattenReport(
    val fieldsFlattened: Int = 0,
    val annotationsFlattened: Int = 0,
    /** Annotations that had no drawable appearance; they were kept as annotations. */
    val annotationsKept: Int = 0,
)

/**
 * Makes form fields and annotations part of the page content, so the saved PDF looks the same in
 * every viewer and nothing interactive is left behind. Links stay clickable.
 */
object Flattener {

    /** [fallbackFont] draws field values whose appearance couldn't be generated. */
    fun flatten(doc: PDDocument, fallbackFont: () -> PDFont): FlattenReport {
        val fields = flattenForm(doc, fallbackFont)
        var flattened = 0
        var kept = 0
        for (page in doc.pages) {
            val (f, k) = flattenAnnotations(doc, page)
            flattened += f; kept += k
        }
        return FlattenReport(fields, flattened, kept)
    }

    /** True when the document has fields or annotations a save would flatten. */
    fun describe(doc: PDDocument): Pair<Int, Int> {
        val fields = runCatching { doc.documentCatalog.acroForm?.fieldTree?.count { it is PDTerminalField } ?: 0 }.getOrDefault(0)
        var annots = 0
        for (page in doc.pages) {
            annots += runCatching {
                page.annotations.count { it !is PDAnnotationLink && it !is PDAnnotationPopup && it !is PDAnnotationWidget && !it.isHidden && !it.isNoView }
            }.getOrDefault(0)
        }
        return fields to annots
    }

    // ---- form fields -----------------------------------------------------------------

    private class PendingValue(val page: PDPage, val rect: PDRectangle, val text: String)

    private fun flattenForm(doc: PDDocument, fallbackFont: () -> PDFont): Int {
        val form = doc.documentCatalog.acroForm ?: return 0
        val terminals = runCatching { form.fieldTree.filterIsInstance<PDTerminalField>() }.getOrDefault(emptyList())
        if (terminals.isEmpty()) {
            doc.documentCatalog.cosObject.removeItem(COSName.ACRO_FORM)
            return 0
        }
        // Appearances first: many forms rely on the viewer to draw values (NeedAppearances).
        runCatching { form.refreshAppearances() }

        // Values whose widgets still have no appearance would silently disappear when flattened,
        // so they are drawn by hand afterwards.
        val pending = ArrayList<PendingValue>()
        val pageOf = HashMap<Any, PDPage>()
        for (page in doc.pages) runCatching { page.annotations }.getOrDefault(emptyList()).forEach { pageOf[it.cosObject] = page }
        for (field in terminals) {
            val value = fieldText(field) ?: continue
            for (w in field.widgets) {
                if (w.normalAppearanceStream != null || w.isHidden || w.isNoView) continue
                val page = w.page ?: pageOf[w.cosObject] ?: continue
                pending += PendingValue(page, w.rectangle ?: continue, value)
            }
        }

        val count = terminals.size
        val ok = runCatching { form.flatten(form.fields.toList(), false) }.isSuccess
        if (!ok) {
            // Widgets are then flattened like any other annotation below.
        }
        doc.documentCatalog.cosObject.removeItem(COSName.ACRO_FORM)

        if (pending.isNotEmpty()) {
            val font = fallbackFont()
            pending.groupBy { it.page }.forEach { (page, values) ->
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (v in values) {
                        val size = (v.rect.height * 0.62f).coerceIn(6f, 12f)
                        cs.beginText()
                        cs.setFont(font, size)
                        cs.newLineAtOffset(v.rect.lowerLeftX + 2f, v.rect.lowerLeftY + (v.rect.height - size) / 2f + size * 0.22f)
                        runCatching { cs.showText(v.text.replace('\n', ' ').replace('\r', ' ')) }
                        cs.endText()
                    }
                }
            }
        }
        return count
    }

    private fun fieldText(field: PDField): String? = when (field) {
        is PDCheckBox, is PDRadioButton -> null // drawn by their on/off appearances
        is PDVariableText -> field.valueAsString.takeIf { it.isNotBlank() }
        else -> null
    }

    // ---- annotations -----------------------------------------------------------------

    /** Returns (flattened, kept). */
    private fun flattenAnnotations(doc: PDDocument, page: PDPage): Pair<Int, Int> {
        val annotations = runCatching { page.annotations }.getOrDefault(emptyList())
        if (annotations.isEmpty()) return 0 to 0
        val keep = ArrayList<PDAnnotation>()
        val draw = ArrayList<Pair<PDAnnotation, PDAppearanceStream>>()
        var kept = 0
        for (a in annotations) {
            when {
                a is PDAnnotationLink -> keep += a
                a is PDAnnotationPopup -> Unit // goes with its parent
                a.isHidden || a.isNoView -> Unit // never visible
                else -> {
                    var ap = appearance(a)
                    if (ap == null && a !is PDAnnotationWidget) {
                        runCatching { a.constructAppearances(doc) }
                        ap = appearance(a)
                    }
                    when {
                        ap != null -> draw += a to ap
                        a is PDAnnotationWidget -> Unit // an empty field: nothing to show
                        else -> { keep += a; kept++ }
                    }
                }
            }
        }
        if (draw.isNotEmpty()) {
            if (page.resources == null) page.resources = PDResources()
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for ((a, ap) in draw) {
                    val m = placement(a, ap) ?: continue
                    cs.saveGraphicsState()
                    cs.transform(m)
                    cs.drawForm(PDFormXObject(ap.cosObject))
                    cs.restoreGraphicsState()
                }
            }
        }
        page.annotations = keep
        return draw.size to kept
    }

    private fun appearance(a: PDAnnotation): PDAppearanceStream? = runCatching {
        val entry = a.appearance?.normalAppearance ?: return@runCatching null
        if (entry.isSubDictionary) {
            val state = a.appearanceState ?: return@runCatching null
            entry.subDictionary[state]
        } else {
            entry.appearanceStream
        }
    }.getOrNull()?.takeIf { it.bBox != null }

    /**
     * PDF 32000 §12.5.5: the appearance BBox, transformed by its Matrix, is mapped onto the
     * annotation Rect. Returns the matrix to apply before drawing the form (whose own /Matrix
     * is applied by the Do operator).
     */
    private fun placement(a: PDAnnotation, ap: PDAppearanceStream): Matrix? {
        val rect = a.rectangle ?: return null
        val bbox = ap.bBox ?: return null
        val m = ap.matrix
        val pts = listOf(
            bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY,
            bbox.upperRightX to bbox.upperRightY, bbox.lowerLeftX to bbox.upperRightY,
        ).map { (x, y) -> (m.scaleX * x + m.shearX * y + m.translateX) to (m.shearY * x + m.scaleY * y + m.translateY) }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val tw = maxX - minX; val th = maxY - minY
        if (tw <= 0f || th <= 0f || rect.width <= 0f || rect.height <= 0f) return null
        val sx = rect.width / tw
        val sy = rect.height / th
        return Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - minX * sx, rect.lowerLeftY - minY * sy)
    }
}
