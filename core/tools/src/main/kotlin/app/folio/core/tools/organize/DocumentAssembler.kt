package app.folio.core.tools.organize

import android.content.Context
import android.graphics.Bitmap
import app.folio.core.editing.EditableDocument
import app.folio.core.editing.PdfBox
import app.folio.core.tools.AssembleReport
import app.folio.core.tools.ImageOptions
import app.folio.core.tools.PageRef
import app.folio.core.tools.PdfLoading
import app.folio.core.tools.SourceFile
import app.folio.core.tools.SourceKind
import app.folio.core.tools.ToolError
import app.folio.core.tools.ToolException
import app.folio.core.tools.images.ImageDecoding
import app.folio.core.tools.images.ImageLayout
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Builds a new PDF from pages of any number of PDFs and photos, in any order, rotated as asked.
 *
 * Pages are copied without re-rendering, so text stays selectable and quality is unchanged.
 * Nothing from pages that weren't chosen ends up in the output: links and form fields pointing at
 * them are dropped, because a reference would silently drag the whole page into the file.
 */
class DocumentAssembler(private val context: Context, private val workDir: () -> File) {

    suspend fun assemble(
        sources: Map<String, SourceFile>,
        pages: List<PageRef>,
        images: ImageOptions,
        output: File,
        onProgress: (Float) -> Unit = {},
    ): AssembleReport = withContext(EditableDocument.pdfboxDispatcher) {
        require(pages.isNotEmpty()) { "no pages" }
        PdfBox.init(context)
        val temps = ArrayList<File>()
        val opened = HashMap<String, PDDocument>()
        try {
            // Open every PDF that contributes a page.
            for (id in pages.map { it.sourceId }.distinct()) {
                val src = sources[id] ?: throw ToolException(ToolError.SourceUnavailable)
                if (src.kind != SourceKind.PDF) continue
                val file = PdfLoading.copyToTemp(context, src.uri, workDir()).also { temps += it }
                val doc = PdfLoading.load(file, src.name)
                opened[id] = doc
                if (doc.isEncrypted && !doc.currentAccessPermission.canAssembleDocument()) {
                    throw ToolException(ToolError.NotPermitted(src.name))
                }
            }

            PDDocument(PdfLoading.memory()).use { target ->
                // source page dict → first copy in the output (for links and bookmarks)
                val pageMap = HashMap<Pair<String, COSDictionary>, PDPage>()
                val includedWidgets = HashSet<COSDictionary>()
                val copiedOnce = HashSet<Pair<String, COSDictionary>>()

                pages.forEachIndexed { i, ref ->
                    coroutineContext.ensureActive()
                    val src = sources.getValue(ref.sourceId)
                    val page = when (src.kind) {
                        SourceKind.PDF -> {
                            val doc = opened.getValue(ref.sourceId)
                            if (ref.pageIndex !in 0 until doc.numberOfPages) throw ToolException(ToolError.Unreadable(src.name))
                            val srcPage = doc.getPage(ref.pageIndex)
                            val key = ref.sourceId to srcPage.cosObject
                            val first = copiedOnce.add(key)
                            copyPage(srcPage, ref, keepAnnotations = first).also { if (first) pageMap[key] = it }
                        }
                        SourceKind.IMAGE -> imagePage(target, src, ref, images)
                    }
                    target.addPage(page)
                    page.cosObject.getCOSArray(COSName.ANNOTS)?.let { annots ->
                        for (j in 0 until annots.size()) {
                            val a = annots.getObject(j) as? COSDictionary ?: continue
                            if (a.getCOSName(COSName.SUBTYPE) == COSName.getPDFName("Widget")) includedWidgets += a
                        }
                    }
                    onProgress((i + 1f) / (pages.size + 1))
                }

                remapLinks(target, pageMap)
                val fields = mergeFormFields(target, includedWidgets, opened.values.toList())

                val pdfSources = pages.map { it.sourceId }.distinct().filter { sources[it]?.kind == SourceKind.PDF }
                val hadOutline = opened.values.any { it.documentCatalog.documentOutline?.hasChildren() == true }
                var bookmarksDropped = hadOutline
                if (pdfSources.size == 1 && pages.all { sources[it.sourceId]?.kind == SourceKind.PDF }) {
                    val outline = opened.getValue(pdfSources.single()).documentCatalog.documentOutline
                    if (outline != null && outline.hasChildren()) {
                        val copy = PDDocumentOutline()
                        copyOutline(outline, copy, pdfSources.single(), pageMap)
                        if (copy.hasChildren()) target.documentCatalog.documentOutline = copy
                        bookmarksDropped = false
                    }
                }

                PdfLoading.save(target, output)
                PdfLoading.validate(output, pages.size)
                onProgress(1f)
                AssembleReport(pages.size, bookmarksDropped, fields)
            }
        } finally {
            opened.values.forEach { runCatching { it.close() } }
            temps.forEach { it.delete() }
        }
    }

    // ---- PDF pages ------------------------------------------------------------------

    private fun copyPage(src: PDPage, ref: PageRef, keepAnnotations: Boolean): PDPage {
        val dict = COSDictionary(src.cosObject)
        // Page-tree links and document-structure references don't carry over.
        listOf(COSName.PARENT, COSName.STRUCT_PARENTS, COSName.B, COSName.getPDFName("Thumb"), COSName.getPDFName("PieceInfo"))
            .forEach { dict.removeItem(it) }
        val page = PDPage(dict)
        // Inheritable attributes are resolved from the source tree and set explicitly.
        page.mediaBox = src.mediaBox
        page.cropBox = src.cropBox
        src.resources?.let { page.resources = it }
        page.rotation = ((src.rotation + ref.normalizedRotation) % 360 + 360) % 360

        if (!keepAnnotations) {
            dict.removeItem(COSName.ANNOTS)
        } else {
            val annots = runCatching { src.annotations }.getOrDefault(emptyList())
            val kept = ArrayList<PDAnnotation>()
            for (a in annots) {
                // A copied annotation must not point back at the source page.
                a.cosObject.setItem(COSName.P, page)
                a.cosObject.removeItem(COSName.getPDFName("IRT"))
                kept += a
            }
            page.annotations = kept
        }
        return page
    }

    private fun remapLinks(target: PDDocument, pageMap: Map<Pair<String, COSDictionary>, PDPage>) {
        val bySourceDict = pageMap.entries.associate { (k, v) -> k.second to v }
        for (page in target.pages) {
            val annots = runCatching { page.annotations }.getOrNull() ?: continue
            if (annots.isEmpty()) continue
            val kept = annots.filter { a ->
                if (a !is PDAnnotationLink) return@filter true
                val action = a.action
                val dest: PDDestination? = runCatching { a.destination }.getOrNull()
                    ?: (action as? PDActionGoTo)?.let { runCatching { it.destination }.getOrNull() }
                when {
                    dest == null -> action == null || action !is PDActionGoTo // external links (URI etc.) stay
                    dest is PDPageDestination -> {
                        val newPage = dest.cosObject.getObject(0).let { it as? COSDictionary }?.let { bySourceDict[it] }
                        if (newPage == null) {
                            false // its target page wasn't included
                        } else {
                            val arr = COSArray().apply { addAll(dest.cosObject); set(0, newPage.cosObject) }
                            val remapped = PDDestination.create(arr)
                            if (action is PDActionGoTo) action.destination = remapped else a.destination = remapped
                            true
                        }
                    }
                    else -> false // named destinations live in the source's name tree, which isn't copied
                }
            }
            if (kept.size != annots.size) page.annotations = kept
        }
    }

    /**
     * Keeps form fields whose widgets made it into the output. Fields are pruned bottom-up so no
     * kept field references a widget (and through it, a page) that was left out. Clashing names
     * from different files get a numeric suffix so values don't merge.
     */
    private fun mergeFormFields(target: PDDocument, widgets: Set<COSDictionary>, sources: List<PDDocument>): Int {
        if (widgets.isEmpty()) return 0
        fun isField(d: COSDictionary) = d.containsKey(COSName.FT) || d.containsKey(COSName.T) || d.containsKey(COSName.KIDS)

        fun prune(node: COSDictionary, seen: HashSet<COSDictionary>): Boolean {
            if (!seen.add(node)) return node in widgets
            val kids = node.getCOSArray(COSName.KIDS) ?: return node in widgets
            val keep = COSArray()
            for (i in 0 until kids.size()) {
                val kid = kids.getObject(i) as? COSDictionary ?: continue
                if (prune(kid, seen)) keep.add(kids.get(i))
            }
            node.setItem(COSName.KIDS, keep)
            return keep.size() > 0 || node in widgets
        }

        val roots = LinkedHashSet<COSDictionary>()
        for (w in widgets) {
            var node: COSDictionary = w
            var guard = 0
            while (guard++ < 64) {
                val parent = node.getDictionaryObject(COSName.PARENT) as? COSDictionary ?: break
                node = parent
            }
            if (isField(node)) roots += node
        }
        if (roots.isEmpty()) return 0
        val seen = HashSet<COSDictionary>()
        val fields = COSArray()
        val names = HashSet<String>()
        var count = 0
        for (root in roots) {
            if (!prune(root, seen)) continue
            val base = root.getString(COSName.T) ?: "Field"
            var name = base
            var n = 2
            while (!names.add(name)) name = "${base}_${n++}"
            if (name != base) root.setItem(COSName.T, COSString(name))
            fields.add(root)
            count += countTerminals(root)
        }
        if (fields.size() == 0) return 0
        val form = COSDictionary()
        form.setItem(COSName.FIELDS, fields)
        sources.firstNotNullOfOrNull { it.documentCatalog.acroForm }?.let { src ->
            src.cosObject.getDictionaryObject(COSName.DR)?.let { form.setItem(COSName.DR, it) }
            src.cosObject.getDictionaryObject(COSName.DA)?.let { form.setItem(COSName.DA, it) }
        }
        if (sources.any { it.documentCatalog.acroForm?.needAppearances == true }) form.setBoolean(COSName.NEED_APPEARANCES, true)
        target.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, form)
        return count
    }

    private fun countTerminals(node: COSDictionary): Int {
        val kids = node.getCOSArray(COSName.KIDS) ?: return 1
        var total = 0
        var hasFieldKids = false
        for (i in 0 until kids.size()) {
            val kid = kids.getObject(i) as? COSDictionary ?: continue
            if (kid.containsKey(COSName.T)) { hasFieldKids = true; total += countTerminals(kid) }
        }
        return if (hasFieldKids) total else 1
    }

    private fun copyOutline(from: PDOutlineNode, to: PDOutlineNode, sourceId: String, pageMap: Map<Pair<String, COSDictionary>, PDPage>) {
        for (item in from.children()) {
            val copy = PDOutlineItem()
            copy.title = item.title
            val dest = runCatching { item.destination }.getOrNull()
                ?: (item.action as? PDActionGoTo)?.let { runCatching { it.destination }.getOrNull() }
            var mapped: PDPage? = null
            if (dest is PDPageDestination) {
                val dict = dest.cosObject.getObject(0) as? COSDictionary
                mapped = dict?.let { pageMap[sourceId to it] }
                if (mapped != null) {
                    val arr = COSArray().apply { addAll(dest.cosObject); set(0, mapped.cosObject) }
                    copy.destination = PDDestination.create(arr)
                }
            }
            copyOutline(item, copy, sourceId, pageMap)
            if (mapped != null || copy.hasChildren()) to.addLast(copy)
        }
    }

    // ---- photo pages ----------------------------------------------------------------

    private fun imagePage(target: PDDocument, src: SourceFile, ref: PageRef, options: ImageOptions): PDPage {
        val info = ImageDecoding.info(context, src.uri, src.name)
        val placement = ImageLayout.place(info.width, info.height, options)
        val (tw, th) = ImageLayout.targetPixels(info.width, info.height, placement)
        val image: PDImageXObject = if (info.isJpeg && info.exifRotation == 0 && !info.exifMirrored && tw == info.width && th == info.height) {
            // Already the right size and upright: embed the original JPEG bytes, no recompression.
            runCatching { JPEGFactory.createFromByteArray(target, ImageDecoding.readBytes(context, src.uri)) }.getOrNull()
                ?: encode(target, src, info, tw, th)
        } else {
            encode(target, src, info, tw, th)
        }
        val page = PDPage(PDRectangle(placement.pageWidth, placement.pageHeight))
        PDPageContentStream(target, page).use { cs ->
            cs.drawImage(image, placement.x, placement.y, placement.width, placement.height)
        }
        page.rotation = ref.normalizedRotation
        return page
    }

    private fun encode(target: PDDocument, src: SourceFile, info: app.folio.core.tools.images.ImageInfo, tw: Int, th: Int): PDImageXObject {
        val bmp: Bitmap = ImageDecoding.decode(context, src.uri, src.name, info, tw, th)
        try {
            // Photos compress well as JPEG; PNGs (screenshots, scans, transparency) stay lossless.
            return if (info.isJpeg || info.isHeif) JPEGFactory.createFromImage(target, bmp, 0.9f) else LosslessFactory.createFromImage(target, bmp)
        } finally {
            bmp.recycle()
        }
    }
}
