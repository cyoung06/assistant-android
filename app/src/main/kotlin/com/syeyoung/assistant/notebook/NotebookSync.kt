package com.syeyoung.assistant.notebook

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.SizeF
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import com.syeyoung.assistant.core.NotebookChange
import com.syeyoung.assistant.core.NotebookDoc
import com.syeyoung.assistant.core.NotebookEdit
import com.syeyoung.assistant.core.NotebookImage
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.core.NotebookStroke
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.notes.LiveConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * A notebook in the drive as every device has it at once: its live document ([NotebookDoc], the
 * shared core's: pages with ids, whole strokes, pictures by content hash, bookmarks) kept in step
 * with the API's, which writes the PDF (see [LiveConnection]). What's written here goes out as it's
 * finished ([edited], page and bookmark changes); what's written elsewhere comes in through
 * [listener], in the order it came.
 *
 * The view works by page number and its own stroke ids; here they're turned into the document's
 * page and stroke ids, and back. Strokes keep the Ink encoding they were written with (so they're
 * edited here exactly as written); strokes from devices without Ink are made from their points.
 */
class NotebookSync(context: Context, val path: String, onStatus: (state: String, message: String?) -> Unit, private val listener: Listener) {
    /** What's written elsewhere, for the notebook screen to show. */
    interface Listener {
        /** The pages changed: drawn from [sources] at [sizes] (with [links]); [moved] says where each page shown before went (null: gone). */
        fun pages(sources: List<PageRenderer.Source>, sizes: List<SizeF>, links: List<PdfLink>, moved: (Int) -> Int?)
        fun remote(removedStrokes: Set<Long>, putStrokes: List<InkStroke>, removedImages: Set<Long>, putImages: List<NoteImage>)
        fun bookmarks(pages: Set<Int>)
        /** The PDF's links, once found (after the pages: finding them in a big PDF takes a while). */
        fun links(links: List<PdfLink>)
    }

    class Opened(val sources: List<PageRenderer.Source>, val sizes: List<SizeF>, val links: List<PdfLink>, val strokes: List<InkStroke>, val images: List<NoteImage>, val bookmarks: Set<Int>)

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val doc = NotebookDoc()
    private var opened: CompletableDeferred<Unit>? = null

    /** The pages as shown, by id (page number = index). */
    private var pageIds: List<String> = emptyList()
    /** The view's ids for the document's strokes and pictures, and back. */
    private val viewIds = HashMap<String, Long>()
    private val docIds = HashMap<Long, String>()
    /** Pictures' hashes (kept by the server), by the view's id. */
    private val blobOf = HashMap<Long, String>()

    /** What came from elsewhere, handled one batch at a time, in order (some need a download first). */
    private val incoming = Channel<List<NotebookChange>>(Channel.UNLIMITED)

    private val connection = LiveConnection(app, path, doc::start, ::receive) { state, message ->
        if (state == "error") opened?.completeExceptionally(IllegalStateException(message))
        if (state != "live" || opened?.isCompleted == true) onStatus(state, message)
    }

    private fun receive(bytes: ByteArray): ByteArray? {
        val got = doc.receive(bytes)
        if (opened?.isCompleted == true && got.changes.isNotEmpty()) incoming.trySend(got.changes)
        if (opened?.isCompleted == false && LiveConnection.isSyncStep2(bytes)) opened?.complete(Unit)
        return got.reply
    }

    /** The notebook as the server has it (connected, its base PDF fetched). */
    suspend fun open(): Opened {
        val first = CompletableDeferred<Unit>().also { opened = it }
        connection.connect()
        first.await()
        val pages = doc.pages()
        pageIds = pages.map { it.id }
        val (sources, links) = sourcesOf(pages)
        val strokes = doc.strokes().mapNotNull(::toView)
        val images = doc.images().mapNotNull { toView(it) }
        scope.launch { for (batch in incoming) handle(batch) }
        return Opened(sources, pages.map { SizeF(it.width, it.height) }, links, strokes, images, doc.bookmarks().mapNotNull { pageIds.indexOf(it).takeIf { i -> i >= 0 } }.toSet())
    }

    // ---- from elsewhere ----

    private suspend fun handle(batch: List<NotebookChange>) {
        if (batch.any { it is NotebookChange.Pages || it is NotebookChange.Base }) refreshPages()
        val removedStrokes = mutableSetOf<Long>()
        val putStrokes = mutableListOf<InkStroke>()
        val removedImages = mutableSetOf<Long>()
        val putImages = mutableListOf<NoteImage>()
        for (c in batch) when (c) {
            is NotebookChange.Stroke -> {
                val s = c.stroke
                if (s == null) viewIds[c.id]?.let { removedStrokes += it } else toView(s)?.let { putStrokes += it }
            }
            is NotebookChange.Image -> {
                val i = c.image
                if (i == null) viewIds[c.id]?.let { removedImages += it } else toView(i)?.let { putImages += it }
            }
            else -> {}
        }
        if (removedStrokes.isNotEmpty() || putStrokes.isNotEmpty() || removedImages.isNotEmpty() || putImages.isNotEmpty()) {
            listener.remote(removedStrokes, putStrokes, removedImages, putImages)
        }
        if (batch.any { it is NotebookChange.Bookmarks || it is NotebookChange.Pages }) listener.bookmarks(bookmarks())
    }

    private fun bookmarks() = doc.bookmarks().mapNotNull { pageIds.indexOf(it).takeIf { i -> i >= 0 } }.toSet()

    /** The pages as the document has them now, for the view (where each one shown before went). */
    private suspend fun refreshPages() {
        val before = pageIds
        val pages = doc.pages()
        val (sources, links) = sourcesOf(pages)
        pageIds = pages.map { it.id }
        val now = pageIds
        listener.pages(sources, pages.map { SizeF(it.width, it.height) }, links) { old -> before.getOrNull(old)?.let { id -> now.indexOf(id).takeIf { it >= 0 } } }
    }

    /** Where each page is drawn from: the base PDF (fetched once, kept by its hash), or a blank page of its style and size. */
    private suspend fun sourcesOf(pages: List<NotebookPage>): Pair<List<PageRenderer.Source>, List<PdfLink>> = withContext(Dispatchers.IO) {
        val hash = doc.base()
        val base = File(File(app.cacheDir, "notebook-blobs").apply { mkdirs() }, "$hash.pdf")
        if (hash.isNotEmpty() && !base.exists()) {
            val tmp = File(base.parentFile, "$hash.part")
            tmp.writeBytes(Repo.get(app).api().syncBlob(hash))
            tmp.renameTo(base)
        }
        val blanks = File(app.cacheDir, "notebook-blank").apply { mkdirs() }
        val sources = pages.map { p ->
            if (p.base >= 0) PageRenderer.Source(base, p.base)
            else {
                val style = when (p.style) { "dots" -> PageStyle.DOTS; "grid" -> PageStyle.GRID; "lines" -> PageStyle.LINES; else -> PageStyle.BLANK }
                val f = File(blanks, "${style.name}-${p.width}x${p.height}.pdf")
                if (!f.exists()) f.writeBytes(BlankPdf.notebook(1, style, p.width, p.height))
                PageRenderer.Source(f, 0)
            }
        }
        // The base PDF's links, if known already; else found meanwhile, and told when they are.
        val known = baseLinks?.takeIf { it.first == hash }?.second ?: if (hash.isNotEmpty()) readLinks(hash) else null
        if (known == null && base.exists()) scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { NotebookFile.linksIn(app, base) }.getOrDefault(emptyList()).also { writeLinks(hash, it) } }
            baseLinks = hash to found
            listener.links(linksOn(found, doc.pages()))
        }
        known?.let { baseLinks = hash to it }
        sources to linksOn(known.orEmpty(), pages)
    }

    /** The base PDF's links (by its own page numbers) and whose they are, once found. */
    private var baseLinks: Pair<String, List<PdfLink>>? = null

    /** [baseLinks] on the pages showing the base's pages. */
    private fun linksOn(baseLinks: List<PdfLink>, pages: List<NotebookPage>): List<PdfLink> {
        val where = pages.withIndex().filter { it.value.base >= 0 }.associate { it.value.base to it.index }
        return baseLinks.mapNotNull { l -> where[l.page]?.let { page -> l.copy(page = page, target = l.target?.let { where[it] }) } }
    }

    // A base PDF's links, kept beside it (found once per PDF: a textbook's take seconds to find).
    private fun linksFile(hash: String) = File(File(app.cacheDir, "notebook-blobs"), "$hash.links")

    private fun readLinks(hash: String): List<PdfLink>? = runCatching {
        val f = linksFile(hash)
        if (!f.exists()) return null
        f.readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            PdfLink(p[0].toInt(), android.graphics.RectF(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat()), p[6].ifEmpty { null }, p[5].toIntOrNull())
        }
    }.getOrNull()

    private fun writeLinks(hash: String, links: List<PdfLink>) {
        runCatching {
            linksFile(hash).writeText(links.joinToString("\n") { l ->
                listOf(l.page, l.rect.left, l.rect.top, l.rect.right, l.rect.bottom, l.target ?: "", (l.uri ?: "").replace('\t', ' ').replace('\n', ' ')).joinToString("\t")
            })
        }
    }

    // ---- written here ----

    /** What changed here (see NotebookView.onEdit), to the other devices. */
    fun edited(removedStrokes: List<InkStroke>, putStrokes: List<InkStroke>, removedImages: List<NoteImage>, putImages: List<NoteImage>) {
        val edits = mutableListOf<NotebookEdit>()
        removedStrokes.forEach { s -> docIds[s.id]?.let { edits += NotebookEdit.RemoveStroke(it) } }
        putStrokes.forEach { s -> toDoc(s)?.let { edits += NotebookEdit.PutStroke(it) } }
        removedImages.forEach { i -> docIds[i.id]?.let { edits += NotebookEdit.RemoveImage(it) } }
        if (edits.isNotEmpty()) connection.send(doc.edit(edits))
        // Pictures go once the server has their bytes.
        if (putImages.isNotEmpty()) scope.launch {
            val puts = putImages.mapNotNull { img ->
                val hash = blobOf[img.id] ?: runCatching { withContext(Dispatchers.IO) { Repo.get(app).api().putSyncBlob(img.bytes, img.mime) } }
                    .onFailure { android.util.Log.w("NotebookSync", "picture not sent: $it") }.getOrNull() ?: return@mapNotNull null
                blobOf[img.id] = hash
                toDoc(img, hash)?.let { NotebookEdit.PutImage(it) }
            }
            if (puts.isNotEmpty()) connection.send(doc.edit(puts))
        }
    }

    /** A page in [style] the size of page [ref], put after page [after] (-1: first). */
    suspend fun insertPage(after: Int, ref: Int, style: PageStyle) {
        val size = doc.pages().getOrNull(ref)
        val page = NotebookPage(UUID.randomUUID().toString(), -1, style.name.lowercase().let { if (it == "blank") "plain" else it }, size?.width ?: 595f, size?.height ?: 842f)
        connection.send(doc.edit(listOf(NotebookEdit.InsertPage((after + 1).coerceAtLeast(0).toUInt(), page))))
        refreshPages()
    }

    /** Pages [indices] removed, with what's on them. */
    suspend fun deletePages(indices: Set<Int>) {
        val ids = indices.mapNotNull { pageIds.getOrNull(it) }
        connection.send(doc.edit(ids.map { NotebookEdit.RemovePage(it) }))
        refreshPages()
    }

    /** The bookmarked pages are now [pages]. */
    fun setBookmarks(pages: Set<Int>) {
        val want = pages.mapNotNull { pageIds.getOrNull(it) }.toSet()
        val have = doc.bookmarks().toSet()
        val edits = (have - want).map { NotebookEdit.Bookmark(it, false) } + (want - have).map { NotebookEdit.Bookmark(it, true) }
        if (edits.isNotEmpty()) connection.send(doc.edit(edits))
    }

    suspend fun close() {
        connection.close()
        scope.cancel()
        doc.close()
    }

    // ---- the view's strokes and pictures, and the document's ----

    private fun viewId(docId: String) = viewIds.getOrPut(docId) { InkIds.next().also { docIds[it] = docId } }
    private fun docId(viewId: Long) = docIds.getOrPut(viewId) { UUID.randomUUID().toString().also { viewIds[it] = viewId } }

    private fun toView(s: NotebookStroke): InkStroke? {
        val page = pageIds.indexOf(s.page).takeIf { it >= 0 } ?: return null
        val tool = runCatching { Tool.valueOf(s.tool.uppercase()) }.getOrDefault(Tool.PEN)
        val inputs = s.ink?.takeIf { it.isNotEmpty() }?.let { runCatching { StrokeInputBatch.decode(it.inputStream()) }.getOrNull() } ?: fromPoints(s.points) ?: return null
        val stroke = InkStroke(viewId(s.id), page, tool, Stroke(brushFor(tool, s.color.toInt(), s.width), inputs))
        val t = s.transform ?: return stroke
        return transformStroke(stroke, Matrix().apply { setValues(floatArrayOf(t[0], t[2], t[4], t[1], t[3], t[5], 0f, 0f, 1f)) })
    }

    /** A stroke written without Ink (on the Mac or the web): its points, as pen input. */
    private fun fromPoints(points: List<Float>): StrokeInputBatch? {
        if (points.size < 3) return null
        val batch = MutableStrokeInputBatch()
        points.chunked(3).forEachIndexed { i, p ->
            if (p.size == 3) batch.add(InputToolType.STYLUS, p[0], p[1], i * 8L, StrokeInput.NO_STROKE_UNIT_LENGTH, p[2].coerceIn(0f, 1f))
        }
        return batch
    }

    private fun toDoc(s: InkStroke): NotebookStroke? {
        val page = pageIds.getOrNull(s.page) ?: return null
        val inputs = s.stroke.inputs
        val points = ArrayList<Float>(inputs.size * 3)
        for (i in 0 until inputs.size) {
            val p = inputs[i]
            points += p.x; points += p.y; points += if (p.pressure >= 0f) p.pressure else 0.5f
        }
        // The exact shape, as the PDF draws it.
        val shape = s.stroke.shape
        val v = MutableVec()
        val outline = mutableListOf<List<Float>>()
        for (group in 0 until shape.getRenderGroupCount()) for (o in 0 until shape.getOutlineCount(group)) {
            val n = shape.getOutlineVertexCount(group, o)
            if (n < 3) continue
            outline += (0 until n).flatMap { i -> shape.populateOutlinePosition(group, o, i, v); listOf(v.x, v.y) }
        }
        val ink = ByteArrayOutputStream().also { inputs.encode(it) }.toByteArray()
        val brush = s.stroke.brush
        return NotebookStroke(docId(s.id), page, s.tool.name.lowercase(), brush.colorIntArgb.toUInt(), brush.size, points, outline, ink, null)
    }

    private suspend fun toView(i: NotebookImage): NoteImage? {
        val page = pageIds.indexOf(i.page).takeIf { it >= 0 } ?: return null
        if (i.rect.size != 4) return null
        val bytes = runCatching { cachedBlob(i.blob) }.getOrNull() ?: return null
        val bitmap = withContext(Dispatchers.Default) { NoteImage.decode(bytes) } ?: return null
        val id = viewId(i.id)
        blobOf[id] = i.blob
        val (x, y, w, h) = i.rect
        val crop = i.crop.takeIf { it.size == 4 }?.let { RectF(it[0], it[1], it[2], it[3]) } ?: RectF(0f, 0f, 1f, 1f)
        return NoteImage(id, page, RectF(x, y, x + w, y + h), bytes, i.mime, bitmap, crop)
    }

    /** A picture's bytes: kept here once fetched (they never change). */
    private suspend fun cachedBlob(hash: String): ByteArray = withContext(Dispatchers.IO) {
        val f = File(File(app.cacheDir, "notebook-blobs").apply { mkdirs() }, hash)
        if (f.exists()) return@withContext f.readBytes()
        val bytes = Repo.get(app).api().syncBlob(hash)
        File(f.parentFile, "$hash.part").apply { writeBytes(bytes); renameTo(f) }
        bytes
    }

    private fun toDoc(img: NoteImage, hash: String): NotebookImage? {
        val page = pageIds.getOrNull(img.page) ?: return null
        val r = img.rect
        return NotebookImage(docId(img.id), page, listOf(r.left, r.top, r.width(), r.height()), listOf(img.crop.left, img.crop.top, img.crop.right, img.crop.bottom), hash, img.mime)
    }
}
