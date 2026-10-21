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
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
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
/** A link in the PDF: on [page] at [rect] (points), to a web address ([uri]) or to page [target]. */
data class PdfLink(val page: Int, val rect: android.graphics.RectF, val uri: String?, val target: Int?)

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
        private val IMAGE = COSName.getPDFName("AssistantImage")
        private val IMAGE_TYPE = COSName.getPDFName("AssistantImageType")
        private val IMAGE_CROP = COSName.getPDFName("AssistantImageCrop")

        class Opened(val file: NotebookFile, val strokes: List<InkStroke>, val images: List<NoteImage>, val bookmarks: Set<Int>, val sizes: List<android.util.SizeF>, val links: List<PdfLink>)

        /** How many backups (the PDF as each session found it) are kept per notebook. */
        private const val BACKUPS = 5

        /** Opens [uri]: its strokes and bookmarked pages, with the base written to a working file (of this session's own). */
        fun open(context: Context, uri: Uri, nextId: () -> Long): Opened {
            PDFBoxResourceLoader.init(context.applicationContext)
            val sessions = File(context.cacheDir, "notebook").apply { mkdirs() }
            // Left behind by sessions that never closed (a crash); a day is longer than any is open.
            sessions.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.deleteRecursively() }
            val dir = File(sessions, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
            val original = File(dir, "original.pdf")
            context.contentResolver.openInputStream(uri)!!.use { input -> original.outputStream().use { input.copyTo(it) } }
            // The PDF as each of the last few sessions found it, in case a save ever goes wrong.
            File(context.filesDir, "notebook-backups").apply { mkdirs() }.let { backups ->
                val key = Integer.toHexString(uri.toString().hashCode())
                original.copyTo(File(backups, "$key-${System.currentTimeMillis()}.pdf"))
                backups.listFiles { f -> f.name.startsWith("$key-") }?.sortedByDescending { it.name }?.drop(BACKUPS)?.forEach { it.delete() }
            }
            val base = File(dir, "base.pdf")
            val strokes = mutableListOf<InkStroke>()
            val images = mutableListOf<NoteImage>()
            val sizes = mutableListOf<android.util.SizeF>()
            load(original).use { doc ->
                doc.pages.forEachIndexed { index, page ->
                    sizes += sizeOf(page)
                    val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return@forEachIndexed
                    val crop = page.cropBox
                    for (i in annots.size() - 1 downTo 0) {
                        val a = annots.getObject(i) as? COSDictionary ?: continue
                        // Our images: the bytes as imported, and where the annotation sits.
                        // Ours are taken out of the base to be edited here (and written back on save). One that
                        // can't be read back stays in the base as it is: shown, not editable, never lost.
                        (a.getDictionaryObject(IMAGE) as? com.tom_roush.pdfbox.cos.COSStream)?.let { stream ->
                            runCatching {
                                val bytes = stream.createRawInputStream().use { it.readBytes() }
                                val rect = (a.getDictionaryObject(COSName.RECT) as COSArray).toFloatArray()
                                val bitmap = NoteImage.decode(bytes) ?: error("not an image")
                                val r = android.graphics.RectF(rect[0] - crop.lowerLeftX, crop.upperRightY - rect[3], rect[2] - crop.lowerLeftX, crop.upperRightY - rect[1])
                                val c = (a.getDictionaryObject(IMAGE_CROP) as? COSArray)?.toFloatArray()
                                val crop = if (c != null && c.size == 4) android.graphics.RectF(c[0], c[1], c[2], c[3]) else android.graphics.RectF(0f, 0f, 1f, 1f)
                                NoteImage(nextId(), index, r, bytes, a.getString(IMAGE_TYPE) ?: "image/png", bitmap, crop)
                            }.onSuccess { images += it; annots.remove(i) }.onFailure { android.util.Log.w("NotebookFile", "image on page ${index + 1} kept as it is: $it") }
                            continue
                        }
                        val data = (a.getDictionaryObject(INK) as? COSString)?.bytes ?: continue
                        runCatching {
                            val tool = Tool.valueOf(a.getNameAsString(TOOL) ?: "PEN")
                            val brush = brushFor(tool, a.getInt(COLOR), a.getFloat(SIZE))
                            InkStroke(nextId(), index, tool, Stroke(brush, StrokeInputBatch.decode(data.inputStream())))
                        }.onSuccess { strokes += it; annots.remove(i) }.onFailure { android.util.Log.w("NotebookFile", "stroke on page ${index + 1} kept as it is: $it") }
                    }
                }
                val bookmarks = takeBookmarks(doc)
                val links = linksOf(doc)
                doc.save(base)
                original.delete()
                return Opened(NotebookFile(context, uri, base), strokes, images, bookmarks, sizes, links)
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

        /**
         * The PDF's links: where each is on its page (points from the crop box's top-left, as images
         * are placed), and a web address or a page of this PDF to go to. Links on turned pages are
         * placed as if unturned.
         */
        private fun linksOf(doc: PDDocument): List<PdfLink> = buildList {
            doc.pages.forEachIndexed { index, page ->
                val crop = page.cropBox
                val annotations = runCatching { page.annotations }.getOrNull() ?: return@forEachIndexed
                for (link in annotations.filterIsInstance<PDAnnotationLink>()) runCatching {
                    val r = link.rectangle ?: return@runCatching
                    val rect = android.graphics.RectF(r.lowerLeftX - crop.lowerLeftX, crop.upperRightY - r.upperRightY, r.upperRightX - crop.lowerLeftX, crop.upperRightY - r.lowerLeftY)
                    val action = link.action
                    val uri = (action as? PDActionURI)?.uri
                    val target = when (val dest = (action as? PDActionGoTo)?.destination ?: link.destination) {
                        is PDPageDestination -> dest.retrievePageNumber()
                        is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(dest)?.retrievePageNumber()
                        else -> null
                    }?.takeIf { it in 0 until doc.numberOfPages }
                    if (uri != null || target != null) add(PdfLink(index, rect, uri, target))
                }.onFailure { android.util.Log.w("NotebookFile", "a link on page ${index + 1} left out: $it") }
            }
        }

        /** A page's size in points as PdfRenderer reports it: its crop box, turned with the page. */
        private fun sizeOf(page: PDPage): android.util.SizeF {
            val box = page.cropBox
            val turned = ((page.rotation % 360) + 360) % 360 in setOf(90, 270)
            return if (turned) android.util.SizeF(box.height, box.width) else android.util.SizeF(box.width, box.height)
        }

        private fun load(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

        /** The links in the PDF [file] (a live notebook's base: by its own page numbers). */
        fun linksIn(context: Context, file: File): List<PdfLink> {
            PDFBoxResourceLoader.init(context.applicationContext)
            return load(file).use { linksOf(it) }
        }
    }

    /** Writes the base plus [strokes] and [bookmarks] to the notebook's file (checked to be a readable PDF first). */
    fun save(strokes: List<InkStroke>, images: List<NoteImage>, bookmarks: Set<Int>) {
        val out = File(base.parentFile, "save.pdf")
        val pageCount = write(strokes, images, bookmarks, out)
        PdfRenderer(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)).use {
            check(it.pageCount == pageCount) { "the saved PDF came out wrong (${it.pageCount} of $pageCount pages); the original is untouched" }
        }
        context.contentResolver.openOutputStream(uri, "wt")!!.use { o -> out.inputStream().use { it.copyTo(o) } }
        out.delete()
    }

    /** Deletes this session's working files (the notebook itself is saved already). */
    fun close() {
        base.parentFile?.deleteRecursively()
    }

    /**
     * Just [pages] as a PDF of their own, for sharing or printing: the writing drawn into the pages,
     * so every viewer shows it (it's no longer editable there).
     */
    fun export(strokes: List<InkStroke>, images: List<NoteImage>, pages: Set<Int>, out: File) {
        val annotated = File(base.parentFile, "export.pdf")
        write(strokes.filter { it.page in pages }, images.filter { it.page in pages }, emptySet(), annotated, keep = pages)
        if (!Flatten.flatten(context, annotated, out)) annotated.copyTo(out, overwrite = true)
        annotated.delete()
    }

    /** Deletes [indices] (several pages at once). */
    fun deletePages(indices: Set<Int>) = edit { doc -> indices.sortedDescending().forEach { if (it < doc.numberOfPages) doc.removePage(it) } }

    /** The base plus [strokes] and [bookmarks], to [out]; only the pages in [keep] if given. Returns the page count. */
    private fun write(strokes: List<InkStroke>, images: List<NoteImage>, bookmarks: Set<Int>, out: File, keep: Set<Int>? = null): Int {
        var pageCount = 0
        load(base).use { doc ->
            val pages = doc.pages.toList()
            // Images first, so the ink is drawn over them.
            images.groupBy { it.page }.forEach { (index, list) ->
                val page = pages.getOrNull(index) ?: return@forEach
                val annots = (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray) ?: COSArray().also { page.cosObject.setItem(COSName.ANNOTS, it) }
                list.forEach { img -> runCatching { annots.add(imageAnnotation(doc, page, img)) } }
            }
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

    /**
     * A PDF stamp annotation showing [img] (so any reader shows it), holding the bytes as imported
     * so it can be moved and resized here again.
     */
    private fun imageAnnotation(doc: PDDocument, page: PDPage, img: NoteImage): COSDictionary {
        val crop = page.cropBox
        val rect = floatArrayOf(crop.lowerLeftX + img.rect.left, crop.upperRightY - img.rect.bottom, crop.lowerLeftX + img.rect.right, crop.upperRightY - img.rect.top)
        val x = if (img.mime == "image/jpeg") JPEGFactory.createFromByteArray(doc, img.bytes)
        else LosslessFactory.createFromImage(doc, android.graphics.BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size))
        val ap = PDAppearanceStream(doc)
        ap.bBox = PDRectangle(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        ap.resources = com.tom_roush.pdfbox.pdmodel.PDResources()
        // The whole picture, clipped to the shown (cropped) part.
        val full = img.fullRect()
        PDPageContentStream(doc, ap).use {
            it.saveGraphicsState()
            it.addRect(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
            it.clip()
            it.drawImage(x, crop.lowerLeftX + full.left, crop.upperRightY - full.bottom, full.width(), full.height())
            it.restoreGraphicsState()
        }
        val raw = doc.document.createCOSStream()
        raw.createRawOutputStream().use { it.write(img.bytes) }
        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
            setItem(COSName.RECT, floats(*rect))
            setItem(COSName.F, COSInteger.get(4)) // print
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, ap.cosObject) })
            setItem(IMAGE, raw)
            setString(IMAGE_TYPE, img.mime)
            setItem(IMAGE_CROP, floats(img.crop.left, img.crop.top, img.crop.right, img.crop.bottom))
        }
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
