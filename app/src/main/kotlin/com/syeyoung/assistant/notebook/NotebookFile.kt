package com.syeyoung.assistant.notebook

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.ink.geometry.MutableVec
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * The PDF behind a notebook. On open, our ink is read out of the PDF's annotations and the rest
 * is kept as the "base" PDF (what's drawn under the ink); saving writes the base plus the ink back
 * as standard PDF ink annotations, drawn from the stroke's exact shape so any reader shows them as
 * written. Adding and deleting pages changes the base.
 */
class NotebookFile private constructor(private val context: Context, var uri: Uri, val base: File) {

    companion object {
        private val INK = COSName.getPDFName("AssistantInk")
        private val TOOL = COSName.getPDFName("AssistantTool")
        private val COLOR = COSName.getPDFName("AssistantColor")
        private val SIZE = COSName.getPDFName("AssistantSize")
        private val INK_LIST = COSName.getPDFName("InkList")
        private val CA = COSName.getPDFName("CA")
        private val LOWER_CA = COSName.getPDFName("ca")
        private val BOOKMARK = COSName.getPDFName("AssistantBookmark")

        class Opened(val file: NotebookFile, val strokes: List<InkStroke>, val bookmarks: Set<Int>)

        /** Opens [uri]: its strokes and bookmarked pages, with the base written to a working file. */
        fun open(context: Context, uri: Uri, nextId: () -> Long): Opened {
            PDFBoxResourceLoader.init(context.applicationContext)
            val dir = File(context.cacheDir, "notebook").apply { mkdirs() }
            val original = File(dir, "original.pdf")
            context.contentResolver.openInputStream(uri)!!.use { input -> original.outputStream().use { input.copyTo(it) } }
            // The PDF as it was before this session, in case a save ever goes wrong.
            File(context.filesDir, "notebook-backups").apply { mkdirs() }.let { dir ->
                original.copyTo(File(dir, "${Integer.toHexString(uri.toString().hashCode())}.pdf"), overwrite = true)
            }
            val base = File(dir, "base.pdf")
            val strokes = mutableListOf<InkStroke>()
            load(original).use { doc ->
                doc.pages.forEachIndexed { index, page ->
                    val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return@forEachIndexed
                    for (i in annots.size() - 1 downTo 0) {
                        val a = annots.getObject(i) as? COSDictionary ?: continue
                        val data = (a.getDictionaryObject(INK) as? COSString)?.bytes ?: continue
                        annots.remove(i)
                        runCatching {
                            val tool = Tool.valueOf(a.getNameAsString(TOOL) ?: "PEN")
                            val brush = brushFor(tool, a.getInt(COLOR), a.getFloat(SIZE))
                            val inputs = StrokeInputBatch.decode(data.inputStream())
                            strokes += InkStroke(nextId(), index, tool, Stroke(brush, inputs))
                        }
                        // (A stroke that can't be read back stays out of the base, so it isn't drawn twice.)
                    }
                }
                val bookmarks = takeBookmarks(doc)
                doc.save(base)
                original.delete()
                return Opened(NotebookFile(context, uri, base), strokes, bookmarks)
            }
        }

        /**
         * Our bookmarks are entries in the PDF's outline (what readers list as bookmarks), marked as
         * ours; they're taken out of the base (and written back on save), the PDF's own stay.
         */
        private fun takeBookmarks(doc: PDDocument): Set<Int> {
            val outline = doc.documentCatalog.documentOutline ?: return emptySet()
            val items = outline.children().toList()
            val ours = items.filter { it.cosObject.getBoolean(BOOKMARK, false) }
            if (ours.isEmpty()) return emptySet()
            val pages = ours.mapNotNull { item -> runCatching { item.findDestinationPage(doc) }.getOrNull()?.let { doc.pages.indexOf(it) }?.takeIf { it >= 0 } }.toSet()
            // Rebuild the outline without them (items can only be added when unlinked).
            val kept = items - ours.toSet()
            val fresh = PDDocumentOutline()
            kept.forEach { item ->
                listOf(COSName.PREV, COSName.NEXT, COSName.PARENT).forEach { item.cosObject.removeItem(it) }
                fresh.addLast(item)
            }
            doc.documentCatalog.documentOutline = if (kept.isEmpty()) null else fresh
            return pages
        }

        private fun load(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
    }

    /** Writes the base plus [strokes] and [bookmarks] to the notebook's file (checked to be a readable PDF first). */
    fun save(strokes: List<InkStroke>, bookmarks: Set<Int>) {
        val out = File(base.parentFile, "save.pdf")
        val pageCount = write(strokes, bookmarks, out)
        PdfRenderer(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)).use {
            check(it.pageCount == pageCount) { "the saved PDF came out wrong (${it.pageCount} of $pageCount pages); the original is untouched" }
        }
        context.contentResolver.openOutputStream(uri, "wt")!!.use { o -> out.inputStream().use { it.copyTo(o) } }
        out.delete()
    }

    /** Just [pages] (with their writing) as a PDF of their own, for sharing or printing. */
    fun export(strokes: List<InkStroke>, pages: Set<Int>, out: File) {
        write(strokes.filter { it.page in pages }, emptySet(), out, keep = pages)
    }

    /** Deletes [indices] (several pages at once). */
    fun deletePages(indices: Set<Int>) = edit { doc -> indices.sortedDescending().forEach { if (it < doc.numberOfPages) doc.removePage(it) } }

    /** The base plus [strokes] and [bookmarks], to [out]; only the pages in [keep] if given. Returns the page count. */
    private fun write(strokes: List<InkStroke>, bookmarks: Set<Int>, out: File, keep: Set<Int>? = null): Int {
        var pageCount = 0
        load(base).use { doc ->
            val pages = doc.pages.toList()
            strokes.groupBy { it.page }.forEach { (index, list) ->
                val page = pages.getOrNull(index) ?: return@forEach
                val annots = (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray) ?: COSArray().also { page.cosObject.setItem(COSName.ANNOTS, it) }
                list.forEach { s -> annotation(doc, page, s)?.let { annots.add(it) } }
            }
            if (bookmarks.isNotEmpty()) {
                val outline = doc.documentCatalog.documentOutline ?: PDDocumentOutline().also { doc.documentCatalog.documentOutline = it }
                bookmarks.sorted().forEach { i ->
                    val page = pages.getOrNull(i) ?: return@forEach
                    outline.addLast(PDOutlineItem().apply {
                        title = "Page ${i + 1}"
                        destination = PDPageFitDestination().apply { setPage(page) }
                        cosObject.setBoolean(BOOKMARK, true)
                    })
                }
            }
            if (keep != null) (pages.size - 1 downTo 0).forEach { if (it !in keep) doc.removePage(it) }
            pageCount = doc.numberOfPages
            doc.save(out)
        }
        return pageCount
    }

    /** Inserts a page in [style], the size of page [ref], after page [after] (-1 puts it first). */
    fun insertPage(after: Int, ref: Int, style: PageStyle) = edit { doc ->
        val size = doc.getPage(ref.coerceIn(0, doc.numberOfPages - 1)).cropBox
        PDDocument.load(BlankPdf.notebook(1, style, size.width, size.height)).use { template ->
            val page = doc.importPage(template.getPage(0))
            // importPage adds it at the end; move it into place.
            if (after < doc.numberOfPages - 2) {
                doc.pages.remove(page)
                if (after < 0) doc.pages.insertBefore(page, doc.getPage(0)) else doc.pages.insertAfter(page, doc.getPage(after))
            }
            doc.save(File(base.parentFile, "edit.pdf"))
        }
    }

    fun deletePage(index: Int) = edit { doc -> doc.removePage(index) }

    /** Applies [change] to the base; it may save to edit.pdf itself (while what it imported is still open). */
    private fun edit(change: (PDDocument) -> Unit) {
        val out = File(base.parentFile, "edit.pdf")
        out.delete()
        load(base).use { doc -> change(doc); if (!out.exists()) doc.save(out) }
        out.renameTo(base)
    }

    /** A PDF ink annotation for [s]: the input points as its InkList, and its exact outline as its appearance. */
    private fun annotation(doc: PDDocument, page: PDPage, s: InkStroke): COSDictionary? {
        val crop = page.cropBox
        fun px(x: Float) = crop.lowerLeftX + x
        fun py(y: Float) = crop.upperRightY - y
        val box = s.stroke.shape.computeBoundingBox() ?: return null
        val rect = floatArrayOf(px(box.xMin) - 1, py(box.yMax) - 1, px(box.xMax) + 1, py(box.yMin) + 1)
        val color = s.stroke.brush.colorIntArgb
        val alpha = (color ushr 24) / 255f
        val (r, g, b) = listOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF).map { it / 255f }

        val drawing = StringBuilder()
        drawing.append(String.format(Locale.US, "%.3f %.3f %.3f rg\n", r, g, b))
        if (alpha < 1f) drawing.append("/GS0 gs\n")
        val shape = s.stroke.shape
        val v = MutableVec()
        for (group in 0 until shape.getRenderGroupCount()) {
            for (o in 0 until shape.getOutlineCount(group)) {
                val n = shape.getOutlineVertexCount(group, o)
                if (n < 3) continue
                for (i in 0 until n) {
                    shape.populateOutlinePosition(group, o, i, v)
                    drawing.append(String.format(Locale.US, "%.2f %.2f %s\n", px(v.x), py(v.y), if (i == 0) "m" else "l"))
                }
                drawing.append("h\n")
            }
            drawing.append("f\n")
        }
        val appearance = doc.document.createCOSStream()
        appearance.setItem(COSName.TYPE, COSName.XOBJECT)
        appearance.setItem(COSName.SUBTYPE, COSName.FORM)
        appearance.setItem(COSName.BBOX, floats(*rect))
        if (alpha < 1f) {
            val gs = COSDictionary().apply {
                setItem(LOWER_CA, COSFloat(alpha))
                setItem(COSName.getPDFName("BM"), COSName.getPDFName("Multiply"))
            }
            appearance.setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(COSName.getPDFName("GS0"), gs) }) })
        }
        appearance.createOutputStream(COSName.FLATE_DECODE).use { it.write(drawing.toString().toByteArray(Charsets.US_ASCII)) }

        val inkList = COSArray()
        val points = COSArray()
        val inputs = s.stroke.inputs
        for (i in 0 until inputs.size) { val p = inputs[i]; points.add(COSFloat(px(p.x))); points.add(COSFloat(py(p.y))) }
        inkList.add(points)
        val encoded = ByteArrayOutputStream().also { inputs.encode(it) }.toByteArray()

        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("Ink"))
            setItem(COSName.RECT, floats(*rect))
            setItem(COSName.F, COSInteger.get(4)) // print
            setItem(COSName.C, floats(r, g, b))
            if (alpha < 1f) setItem(CA, COSFloat(alpha))
            setItem(COSName.BS, COSDictionary().apply { setItem(COSName.W, COSFloat(s.stroke.brush.size)) })
            setItem(INK_LIST, inkList)
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
            // How to edit it again here: the exact pen inputs and brush.
            setItem(INK, COSString(encoded))
            setItem(TOOL, COSName.getPDFName(s.tool.name))
            setInt(COLOR, color)
            setFloat(SIZE, s.stroke.brush.size)
        }
    }

    private fun floats(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
}
