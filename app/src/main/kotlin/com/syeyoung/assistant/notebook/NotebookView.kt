package com.syeyoung.assistant.notebook

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.SizeF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TILE = 512
private const val MAX_LEVEL = 3
/** About 160 MB of 512×512 tiles. */
private const val MAX_TILES = 160

/** How fingers move through pages. */
enum class ScrollMode { PAGED, CONTINUOUS }

/**
 * Pages of a PDF with ink on them. The pen writes (or erases); fingers scroll, flip pages and
 * pinch-zoom, and are ignored while the pen is near the screen (palm rejection). Wet ink is drawn
 * by [ink], a low-latency layer laid exactly over this view; finished strokes are drawn here.
 */
@SuppressLint("ViewConstructor")
class NotebookView(context: Context, private val ink: InProgressStrokesView, private val renderer: PageRenderer) : View(context) {

    // ---- what the host sets and hears ----
    var tool = Tool.PEN
    var penColor = PEN_COLORS[0]
    var penSize = 0.45f
    var highlighterColor = HIGHLIGHTER_COLORS[0]
    var highlighterSize = 9f
    /** How far the eraser reaches, in dp on screen. */
    var eraserRadius = 10f
    var mode = ScrollMode.PAGED
        set(value) { if (field != value) { val p = currentPage; field = value; layoutPages(); goTo(p, animate = false) } }
    /** Called after any change to the ink (to save it). */
    var onInkChanged: () -> Unit = {}
    var onPageChanged: (page: Int, count: Int) -> Unit = { _, _ -> }
    var onUndoChanged: (canUndo: Boolean, canRedo: Boolean) -> Unit = { _, _ -> }

    // ---- content ----
    private var sizes: List<SizeF> = emptyList()
    val pageCount get() = sizes.size
    fun pageSize(i: Int): SizeF? = sizes.getOrNull(i)
    private val strokes = mutableListOf<InkStroke>()
    private val images = mutableListOf<NoteImage>()
    /** The image picked with the select tool (moved, resized, copied or deleted). */
    var selectedImage: NoteImage? = null
        private set(value) { field = value; onSelectionChanged(value); invalidate() }
    var onSelectionChanged: (NoteImage?) -> Unit = {}
    private val undo = ArrayDeque<InkChange>()
    private val redo = ArrayDeque<InkChange>()
    fun strokesSnapshot(): List<InkStroke> = strokes.toList()
    fun imagesSnapshot(): List<NoteImage> = images.toList()

    // ---- layout: each page's rect at zoom 1 in "content" coordinates; screen = content × zoom − scroll ----
    private var rects: List<RectF> = emptyList()
    private var scales: List<Float> = emptyList()
    private var contentW = 0f
    private var contentH = 0f
    private var zoom = 1f
    private var scrollXf = 0f
    private var scrollYf = 0f
    var currentPage = 0
        private set

    private val gap get() = 12f * resources.displayMetrics.density

    fun setDocument(pageSizes: List<SizeF>, inkStrokes: List<InkStroke>, pageImages: List<NoteImage>) {
        sizes = pageSizes
        strokes.clear(); strokes += inkStrokes
        images.clear(); images += pageImages
        selectedImage = null
        group = null
        undo.clear(); redo.clear(); notifyUndo()
        dropBitmaps()
        layoutPages()
        goTo(currentPage.coerceAtMost(pageCount - 1).coerceAtLeast(0), animate = false)
        onPageChanged(currentPage, pageCount)
    }

    /** After pages were added or removed: new sizes, and where each old page went (null = deleted). */
    fun pagesChanged(pageSizes: List<SizeF>, moved: (Int) -> Int?) {
        sizes = pageSizes
        val kept = strokes.mapNotNull { s -> moved(s.page)?.let { s.onPage(it) } }
        strokes.clear(); strokes += kept
        val keptImages = images.mapNotNull { img -> moved(img.page)?.let { img.with(page = it) } }
        images.clear(); images += keptImages
        selectedImage = null
        group = null
        // Undo history refers to old page numbers: start it over.
        undo.clear(); redo.clear(); notifyUndo()
        dropBitmaps()
        layoutPages()
        clampScroll()
        invalidate()
        onPageChanged(currentPage, pageCount)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val p = currentPage
        layoutPages()
        dropBitmaps()
        goTo(p, animate = false)
    }

    private fun layoutPages() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || sizes.isEmpty()) { rects = emptyList(); return }
        val pad = gap
        when (mode) {
            ScrollMode.CONTINUOUS -> {
                val fit = (w - 2 * pad) / sizes.maxOf { it.width }
                var top = pad
                rects = sizes.map { s ->
                    val pw = s.width * fit
                    RectF((w - pw) / 2, top, (w + pw) / 2, top + s.height * fit).also { top += s.height * fit + gap }
                }
                scales = sizes.map { fit }
                contentW = w
                contentH = top - gap + pad
            }
            ScrollMode.PAGED -> {
                // Pages side by side, each fitted to the screen. They're spaced so that while one
                // rests centred, its neighbours start just past the screen edges (no grey to cross
                // before the next page shows); the spacing depends on the zoom, so this is redone
                // when it changes.
                scales = sizes.map { s -> min((w - 2 * pad) / s.width, (h - 2 * pad) / s.height) }
                val widths = sizes.mapIndexed { i, s -> s.width * scales[i] }
                val free = widths.map { max(0f, (w / zoom - it) / 2) }
                var left = 0f
                rects = sizes.mapIndexed { i, s ->
                    if (i > 0) left += max(free[i - 1], free[i]) + gap / zoom
                    val ph = s.height * scales[i]
                    RectF(left, (h - ph) / 2, left + widths[i], (h + ph) / 2).also { left += widths[i] }
                }
                contentW = left
                contentH = h
            }
        }
    }

    /** Points on page [i] → screen pixels. */
    private fun pageMatrix(i: Int, out: Matrix = Matrix()): Matrix {
        val r = rects[i]
        val s = scales[i] * zoom
        out.setScale(s, s)
        out.postTranslate(r.left * zoom - scrollXf, r.top * zoom - scrollYf)
        return out
    }

    private fun screenRect(i: Int) = RectF(rects[i]).apply {
        left = left * zoom - scrollXf; right = right * zoom - scrollXf; top = top * zoom - scrollYf; bottom = bottom * zoom - scrollYf
    }

    private fun pageAt(x: Float, y: Float): Int? {
        if (rects.isEmpty()) return null
        rects.indices.firstOrNull { screenRect(it).contains(x, y) }?.let { return it }
        // Just outside a page (a stroke starting in the margin): the nearest one.
        return rects.indices.minByOrNull { val r = screenRect(it); abs(r.centerX() - x) + abs(r.centerY() - y) }
    }

    /** Shows page [page] from its top-left, at the current zoom. */
    fun goTo(page: Int, animate: Boolean = true) {
        if (rects.isEmpty()) return
        val p = page.coerceIn(0, rects.size - 1)
        if (mode == ScrollMode.PAGED) layoutPages()
        val x = if (mode == ScrollMode.PAGED) rangeFor(p).left else scrollXf
        val y = if (mode == ScrollMode.PAGED) rangeFor(p).top else (rects[p].top * zoom - gap).coerceIn(0f, max(0f, contentH * zoom - height))
        if (animate) animateTo(x, y) else { scrollXf = x; scrollYf = y; clampScroll(); afterMove() }
    }

    /**
     * How far the screen can scroll: to just past the page edges (a gap's width), never into the
     * empty space beside a page; along an axis where the zoomed page fits on screen, it stays centred.
     */
    private fun scrollRange(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return when {
            rects.isEmpty() -> RectF()
            mode == ScrollMode.PAGED -> rangeFor(currentPage)
            else -> {
                val (x0, x1) = axis(rects.minOf { it.left }, rects.maxOf { it.right }, w, gap)
                RectF(x0, 0f, x1, max(0f, contentH * zoom - h))
            }
        }
    }

    /** Page by page: where the screen may rest on page [i] (just past its edges, centred where it fits). */
    private fun rangeFor(i: Int): RectF {
        val r = rects[i.coerceIn(rects.indices)]
        val (x0, x1) = axis(r.left, r.right, width.toFloat(), gap)
        val (y0, y1) = axis(r.top, r.bottom, height.toFloat(), gap)
        return RectF(x0, y0, x1, y1)
    }

    private fun axis(from: Float, to: Float, view: Float, pad: Float): Pair<Float, Float> {
        val lo = from * zoom - pad
        val hi = to * zoom + pad - view
        return if (hi >= lo) lo to hi else ((from + to) / 2 * zoom - view / 2).let { it to it }
    }

    /** Diagnostics: zoom about the screen's centre, as a pinch there would. */
    fun zoomForTest(z: Float) {
        if (rects.isEmpty()) return
        val page = currentPage
        val pt = floatArrayOf(width / 2f, height / 2f)
        Matrix().also { pageMatrix(page).invert(it) }.mapPoints(pt)
        zoom = z
        if (mode == ScrollMode.PAGED) layoutPages()
        val r = rects[page]
        scrollXf = r.left * zoom + pt[0] * scales[page] * zoom - width / 2f
        scrollYf = r.top * zoom + pt[1] * scales[page] * zoom - height / 2f
        clampScroll(); afterMove()
    }

    /** Search matches to mark, and the one in focus (marked stronger). */
    var hits: List<SearchHit> = emptyList()
        set(value) { field = value; invalidate() }
    var focusedHit: SearchHit? = null
        set(value) { field = value; invalidate() }

    /** Brings [hit] into the middle of the screen, at the current zoom. */
    fun show(hit: SearchHit) {
        if (rects.isEmpty() || hit.page !in rects.indices) return
        focusedHit = hit
        val box = hit.boxes.firstOrNull() ?: return goTo(hit.page)
        val r = rects[hit.page]
        val s = scales[hit.page] * zoom
        val cx = r.left * zoom + box.centerX() * s
        val cy = r.top * zoom + box.centerY() * s
        val range = if (mode == ScrollMode.PAGED) rangeFor(hit.page) else scrollRange()
        animateTo((cx - width / 2f).coerceIn(range.left, range.right), (cy - height / 2f).coerceIn(range.top, range.bottom))
    }

    private fun clampScroll() {
        val r = scrollRange()
        scrollXf = scrollXf.coerceIn(r.left, r.right)
        scrollYf = scrollYf.coerceIn(r.top, r.bottom)
    }

    private fun afterMove() {
        val before = currentPage
        currentPage = when (mode) {
            ScrollMode.PAGED -> {
                val mid = (scrollXf + width / 2f) / zoom
                rects.indices.minByOrNull { abs(rects[it].centerX() - mid) } ?: 0
            }
            ScrollMode.CONTINUOUS -> {
                val mid = (scrollYf + height / 2f) / zoom
                rects.indexOfFirst { mid <= it.bottom + gap / 2 }.let { if (it < 0) pageCount - 1 else it }.coerceAtLeast(0)
            }
        }
        if (before != currentPage) onPageChanged(currentPage, pageCount)
        invalidate()
    }

    // ---- drawing ----

    private val strokeRenderer = CanvasStrokeRenderer.create()
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint().apply { color = 0x22000000 }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()
    private val hitRect = RectF()
    private val hitPaint = Paint().apply { color = 0x55FFD600 }
    private val focusPaint = Paint().apply { color = 0x99FF9800.toInt() }

    /*
     * Page images are tiles, as map apps and PDF viewers do it: each page is cut into 512px squares
     * rendered at a zoom level (1×, 2×, 4×, 8× of the fitted page; the level just above the current
     * zoom, so tiles are at least as sharp as the screen). Only tiles on screen are rendered, nearest
     * the middle first, several at once; they're kept (about 160 MB) so scrolling back is instant.
     * Coarser tiles (and the whole page at 1×) are drawn under finer ones, so something is always there.
     */
    private data class TileKey(val page: Int, val level: Int, val tx: Int, val ty: Int)

    private val tiles = object : LinkedHashMap<TileKey, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, Bitmap>): Boolean {
            if (size <= MAX_TILES) return false
            eldest.value.recycle()
            return true
        }
    }
    private val pendingTiles = HashSet<TileKey>()
    /** The tiles the screen needs now; a queued tile not in here is skipped. */
    @Volatile private var wanted: Set<TileKey> = emptySet()
    private val tileSrc = android.graphics.Rect()
    private val tileDst = RectF()

    private fun levelFor(z: Float) = when { z <= 1.01f -> 0; z <= 2.01f -> 1; z <= 4.01f -> 2; else -> 3 }

    override fun onDraw(canvas: Canvas) = drawPages(canvas)

    private fun drawPages(canvas: Canvas) {
        canvas.drawColor(0xFFE8E8EC.toInt())
        if (rects.isEmpty()) return
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val visible = rects.indices.filter { RectF.intersects(screenRect(it), screen) }
        val level = levelFor(zoom)
        val want = HashSet<TileKey>()
        val center = screen.centerX() to screen.centerY()
        for (i in visible) {
            val r = screenRect(i)
            canvas.drawRect(r.left + 2, r.top + 3, r.right + 2, r.bottom + 3, shadowPaint)
            canvas.drawRect(r, pagePaint)
            canvas.save()
            canvas.clipRect(r)
            // Coarse to fine: the whole page at 1× underneath, then each level up to the one wanted (and one above, when zooming out).
            for (lvl in 0..minOf(level + 1, MAX_LEVEL)) drawTiles(canvas, i, lvl, r, screen, request = false, want, center)
            canvas.restore()
            // What to render: this level's tiles on screen, and the page's 1× tiles to fall back on.
            drawTiles(null, i, level, r, screen, request = true, want, center)
            if (level > 0) drawTiles(null, i, 0, r, r, request = true, want, center)

            pageMatrix(i, drawMatrix)
            for (h in hits) if (h.page == i) for (b in h.boxes) {
                hitRect.set(b); drawMatrix.mapRect(hitRect)
                canvas.drawRect(hitRect, if (h === focusedHit) focusPaint else hitPaint)
            }
            canvas.save()
            canvas.clipRect(r)
            // The renderer doesn't place strokes itself: the canvas maps page points to the screen,
            // and the same matrix is passed so it draws at the right resolution.
            canvas.concat(drawMatrix)
            val g = group?.takeIf { it.page == i }
            val gm = groupMatrix
            for (img in images) if (img.page == i) {
                val moving = gm != null && g != null && img.id in g.imageIds
                if (moving) { canvas.save(); canvas.concat(gm) }
                if (cropping && img.id == selectedImage?.id) canvas.drawBitmap(img.bitmap, null, img.fullRect(), dimPaint)
                canvas.drawBitmap(img.bitmap, img.source(), img.rect, bitmapPaint)
                if (moving) canvas.restore()
            }
            for (s in strokes) if (s.page == i) {
                val moving = gm != null && g != null && s.id in g.strokeIds
                if (moving) { canvas.save(); canvas.concat(gm) }
                strokeRenderer.draw(canvas, s.stroke, drawMatrix)
                if (moving) canvas.restore()
            }
            canvas.restore()
            g?.let { grp -> groupOnScreen(grp)?.let { r -> canvas.drawRect(r, lassoPaint); for (h in handles(r).take(4)) { canvas.drawCircle(h.x, h.y, handleR, handlePaint); canvas.drawCircle(h.x, h.y, handleR, handleRing) } } }
            selectedImage?.takeIf { it.page == i }?.let { drawSelection(canvas, it) }
        }
        // Keep the neighbours' 1× tiles ready for a page turn.
        for (i in (visible.flatMap { listOf(it - 1, it + 1) } - visible.toSet()).filter { it in rects.indices }) {
            val r = screenRect(i)
            drawTiles(null, i, 0, r, r, request = true, want, center, priority = 1e7f)
        }
        wanted = want
        lasso?.let { canvas.drawPath(it, lassoPaint) }
        onSelectionFrame(selectedImage?.let { selectionOnScreen(it) } ?: group?.let { groupOnScreen(it) })
    }

    /**
     * Page [i]'s tiles at [lvl] that fall in [area] (screen coordinates): drawn on [canvas] if it's
     * given and they're ready, or (with [request]) asked for when missing and noted in [want].
     */
    private fun drawTiles(
        canvas: Canvas?, i: Int, lvl: Int, r: RectF, area: RectF, request: Boolean,
        want: MutableSet<TileKey>, center: Pair<Float, Float>, priority: Float = 0f,
    ) {
        val pxPerPoint = scales[i] * (1 shl lvl) // tile pixels per page point
        val f = zoom / (1 shl lvl) // screen pixels per tile pixel
        val pageW = sizes[i].width * pxPerPoint
        val pageH = sizes[i].height * pxPerPoint
        val part = RectF(r)
        if (!part.intersect(area)) return
        val x0 = ((part.left - r.left) / f / TILE).toInt().coerceAtLeast(0)
        val y0 = ((part.top - r.top) / f / TILE).toInt().coerceAtLeast(0)
        val x1 = ((part.right - r.left) / f / TILE).toInt().coerceAtMost(((pageW - 1) / TILE).toInt())
        val y1 = ((part.bottom - r.top) / f / TILE).toInt().coerceAtMost(((pageH - 1) / TILE).toInt())
        for (ty in y0..y1) for (tx in x0..x1) {
            val key = TileKey(i, lvl, tx, ty)
            val w = minOf(TILE.toFloat(), pageW - tx * TILE).toInt().coerceAtLeast(1)
            val h = minOf(TILE.toFloat(), pageH - ty * TILE).toInt().coerceAtLeast(1)
            tileDst.set(r.left + tx * TILE * f, r.top + ty * TILE * f, r.left + (tx * TILE + w) * f, r.top + (ty * TILE + h) * f)
            if (canvas != null) {
                tiles[key]?.let { b -> tileSrc.set(0, 0, w, h); canvas.drawBitmap(b, tileSrc, tileDst, bitmapPaint) }
                continue
            }
            if (!request) continue
            want += key
            if (key in tiles || !pendingTiles.add(key)) continue
            val dx = tileDst.centerX() - center.first
            val dy = tileDst.centerY() - center.second
            val m = Matrix().apply { setScale(pxPerPoint, pxPerPoint); postTranslate(-tx * TILE.toFloat(), -ty * TILE.toFloat()) }
            renderer.render(i, w, h, m, priority = priority + dx * dx + dy * dy, wanted = { key in wanted }) { bmp ->
                pendingTiles.remove(key)
                if (bmp == null) return@render
                if (i !in rects.indices) { bmp.recycle(); return@render }
                tiles.put(key, bmp)?.recycle()
                invalidate()
            }
        }
    }

    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density; color = 0xFF1E5BD8.toInt() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E5BD8.toInt() }
    private val handleR get() = 9f * resources.displayMetrics.density

    private val dimPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 80 }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density; color = Color.WHITE }

    /** While true, the selected image's handles crop it instead of resizing it. */
    var cropping = false
        set(value) { field = value; invalidate() }

    /** Where the selection is on screen, after each frame (for the bar that floats above it). */
    var onSelectionFrame: (RectF?) -> Unit = {}

    /** The selected image's frame on screen. */
    private fun selectionOnScreen(img: NoteImage) = RectF(img.rect).also { pageMatrix(img.page).mapRect(it) }

    /** The handles, on screen: corners (0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left), then, when cropping, edges (4 top, 5 right, 6 bottom, 7 left). */
    private fun handles(r: RectF): List<PointF> {
        val corners = listOf(PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.right, r.bottom), PointF(r.left, r.bottom))
        return if (!cropping) corners else corners + listOf(PointF(r.centerX(), r.top), PointF(r.right, r.centerY()), PointF(r.centerX(), r.bottom), PointF(r.left, r.centerY()))
    }

    private fun drawSelection(canvas: Canvas, img: NoteImage) {
        val r = selectionOnScreen(img)
        canvas.drawRect(r, selectPaint)
        for (h in handles(r)) { canvas.drawCircle(h.x, h.y, handleR, handlePaint); canvas.drawCircle(h.x, h.y, handleR, handleRing) }
    }

    /**
     * Places an image (as imported: PNG or JPEG bytes) on the page in view, centred on the screen
     * point ([x], [y]) or the middle of the screen, at its natural size (capped to most of the page).
     */
    fun addImage(bytes: ByteArray, mime: String, x: Float = width / 2f, y: Float = height / 2f): Boolean {
        if (rects.isEmpty()) return false
        val bitmap = NoteImage.decode(bytes) ?: return false
        val page = pageAt(x, y) ?: return false
        val pt = floatArrayOf(x, y).also { Matrix().apply { pageMatrix(page).invert(this) }.mapPoints(it) }
        val size = sizes[page]
        // 96 dpi pixels to points, then no more than 70% of the page either way.
        var w = bitmap.width * 0.75f
        var h = bitmap.height * 0.75f
        val fit = minOf(1f, size.width * 0.7f / w, size.height * 0.7f / h)
        w *= fit; h *= fit
        val left = (pt[0] - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
        val top = (pt[1] - h / 2).coerceIn(0f, (size.height - h).coerceAtLeast(0f))
        val img = NoteImage(InkIds.next(), page, RectF(left, top, left + w, top + h), bytes, mime, bitmap)
        images += img
        record(InkChange.ImageAdded(img))
        group = null
        selectedImage = img
        return true
    }

    fun deleteSelected() {
        val img = selectedImage ?: return
        images.removeAll { it.id == img.id }
        selectedImage = null
        record(InkChange.ImageRemoved(img))
    }

    fun clearSelection() { cropping = false; selectedImage = null; group = null }

    /** A copy of the selected image, a little below and to the right; the copy is selected. */
    fun duplicateSelected() {
        val img = selectedImage ?: return
        val copy = img.with(id = InkIds.next(), rect = RectF(img.rect).apply { offset(12f, 12f) })
        images += copy
        record(InkChange.ImageAdded(copy))
        selectedImage = copy
    }

    /** Shows the whole picture again. */
    fun resetCrop() {
        val img = selectedImage ?: return
        val full = img.with(rect = img.fullRect(), crop = RectF(0f, 0f, 1f, 1f))
        images.replaceAll { if (it.id == full.id) full else it }
        record(InkChange.ImageChanged(img, full))
        selectedImage = full
    }

    /** Places an image (bytes as imported) with its top-left at the screen point. */
    fun pasteImageAt(bytes: ByteArray, mime: String, x: Float, y: Float) = addImage(bytes, mime, x, y)

    fun dropBitmaps() {
        tiles.values.forEach { it.recycle() }
        tiles.clear()
        pendingTiles.clear()
        wanted = emptySet()
    }

    // ---- touch ----

    private enum class Gesture { NONE, PEN, ERASE, FINGERS, SELECT }
    private var gesture = Gesture.NONE
    private var lastPenTime = 0L
    private var penPointer = -1
    private var strokeId: InProgressStrokeId? = null
    private val strokePages = HashMap<InProgressStrokeId, Pair<Int, Tool>>()
    private val erasedNow = mutableListOf<InkStroke>()

    init {
        ink.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                val added = strokes.mapNotNull { (id, s) -> strokePages.remove(id)?.let { (page, tool) -> InkStroke(InkIds.next(), page, tool, s) } }
                if (added.isNotEmpty()) {
                    this@NotebookView.strokes += added
                    record(InkChange.Added(added))
                }
                invalidate()
                // Hand off from the wet layer once this view has drawn them.
                postOnAnimation { ink.removeFinishedStrokes(strokes.keys) }
            }
        })
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isPen(event, 0)) lastPenTime = event.eventTime
        return super.onHoverEvent(event)
    }

    private fun isPen(e: MotionEvent, index: Int) = e.getToolType(index).let { it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            onTouchStart()
            gesture = when {
                // What's selected can be moved or resized with any tool; only the select tool picks
                // things (the pen: tap or lasso; a finger: tap an image, or long-press to paste).
                onSelection(e.x, e.y) -> Gesture.SELECT
                tool == Tool.SELECT && (isPen(e, 0) || imageAt(e.x, e.y) != null) -> Gesture.SELECT
                isPen(e, 0) -> if (erasing(e)) Gesture.ERASE else Gesture.PEN
                // A hand resting while the pen is near (or just lifted) isn't a gesture.
                e.eventTime - lastPenTime < 600 -> Gesture.NONE
                else -> Gesture.FINGERS
            }
            if (gesture == Gesture.FINGERS && tool == Tool.SELECT) {
                val x = e.x; val y = e.y
                fingerHold = Runnable { onLongPressEmpty(x, y) }.also { postDelayed(it, 500) }
                grabAt.set(x, y)
            }
            if ((gesture == Gesture.PEN || gesture == Gesture.ERASE) && hasSelection) { clearSelection(); gesture = Gesture.NONE }
            if (gesture == Gesture.PEN || gesture == Gesture.ERASE) requestUnbufferedDispatch(e)
        }
        // Pressing the S Pen's button mid-stroke ends the stroke and erases from there.
        if (gesture == Gesture.PEN && e.actionMasked == MotionEvent.ACTION_MOVE && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            strokeId?.let { id -> if (e.findPointerIndex(penPointer) >= 0) ink.finishStroke(e, penPointer, id) }
            strokeId = null
            gesture = Gesture.ERASE
            erasedNow.clear()
        }
        when (gesture) {
            Gesture.SELECT -> select(e)
            Gesture.PEN -> pen(e)
            Gesture.ERASE -> erase(e)
            Gesture.FINGERS -> fingers(e)
            Gesture.NONE -> {}
        }
        fingerHold?.let { h ->
            val moved = e.actionMasked == MotionEvent.ACTION_MOVE && kotlin.math.hypot(e.x - grabAt.x, e.y - grabAt.y) > 12 * resources.displayMetrics.density
            if (moved || e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL || e.pointerCount > 1) { removeCallbacks(h); fingerHold = null }
        }
        if (isPen(e, e.actionIndex)) lastPenTime = e.eventTime
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) gesture = Gesture.NONE
        return true
    }

    // ---- select: move and resize images ----

    private var grabbed: NoteImage? = null
    private val grabAt = PointF()

    private fun toPage(page: Int, x: Float, y: Float) = floatArrayOf(x, y).also { Matrix().apply { pageMatrix(page).invert(this) }.mapPoints(it) }

    /** The topmost image under a screen point. */
    private fun imageAt(x: Float, y: Float): NoteImage? {
        val page = pageAt(x, y) ?: return null
        val pt = toPage(page, x, y)
        return images.lastOrNull { it.page == page && it.rect.contains(pt[0], pt[1]) }
    }

    /** Which of the selected image's handles is under a screen point, if any. */
    private fun handleAt(x: Float, y: Float): Int? {
        val r = selectedImage?.let { selectionOnScreen(it) } ?: group?.let { groupOnScreen(it) } ?: return null
        val reach = handleR * 2.4f
        val corners = if (group != null) handles(r).take(4) else handles(r)
        return corners.indexOfFirst { (x - it.x) * (x - it.x) + (y - it.y) * (y - it.y) <= reach * reach }.takeIf { it >= 0 }
    }

    private fun handleHit(x: Float, y: Float) = handleAt(x, y) != null

    private var handle: Int? = null
    private var longPress: Runnable? = null
    /** Long-press on an empty spot with the select tool: offer to paste there. */
    var onLongPressEmpty: (x: Float, y: Float) -> Unit = { _, _ -> }
    /** Any touch on the page (closes a paste bubble). */
    var onTouchStart: () -> Unit = {}

    private var fingerHold: Runnable? = null

    // ---- groups: strokes (and images) picked with the lasso, or a tapped stroke ----

    /** A selection of several things on one page. */
    private class Group(val page: Int, val strokeIds: Set<Long>, val imageIds: Set<Long>)
    private var group: Group? = null
        set(value) { field = value; onSelectionChanged(selectedImage); invalidate() }

    val hasSelection get() = selectedImage != null || group != null
    val selectionIsGroup get() = group != null

    /** While a group is dragged: how it's moved or scaled (page points), drawn as a preview until the fingers lift. */
    private var groupMatrix: Matrix? = null
    private var lasso: android.graphics.Path? = null
    private val lassoPoints = mutableListOf<PointF>()
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density; color = 0xFF1E5BD8.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private fun groupBounds(g: Group): RectF? {
        var r: RectF? = null
        fun add(b: RectF) { r = r?.apply { union(b) } ?: RectF(b) }
        strokes.filter { it.id in g.strokeIds }.forEach { s -> s.stroke.shape.computeBoundingBox()?.let { add(RectF(it.xMin, it.yMin, it.xMax, it.yMax)) } }
        images.filter { it.id in g.imageIds }.forEach { add(it.rect) }
        return r
    }

    private fun groupOnScreen(g: Group): RectF? = groupBounds(g)?.let { b ->
        RectF(b).also { groupMatrix?.mapRect(it); pageMatrix(g.page).mapRect(it); it.inset(-6f, -6f) }
    }

    /** On the selection (an image, a group) or one of its handles. */
    private fun onSelection(x: Float, y: Float): Boolean {
        if (handleAt(x, y) != null) return true
        selectedImage?.let { if (selectionOnScreen(it).contains(x, y)) return true }
        group?.let { g -> groupOnScreen(g)?.let { if (it.contains(x, y)) return true } }
        return false
    }

    /** The stroke under a screen point on its page (within a few pixels). */
    private fun strokeAt(x: Float, y: Float): InkStroke? {
        val page = pageAt(x, y) ?: return null
        val pt = toPage(page, x, y)
        val radius = 8f * resources.displayMetrics.density / (scales[page] * zoom)
        return strokes.lastOrNull { it.page == page && it.hits(pt[0], pt[1], radius) }
    }

    private enum class Drag { NONE, IMAGE, GROUP_MOVE, GROUP_SCALE, LASSO }
    private var drag = Drag.NONE
    private val anchor = PointF()

    private fun select(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = handleAt(e.x, e.y)
                val g = group
                val gScreen = g?.let { groupOnScreen(it) }
                when {
                    // A handle or the inside of what's selected: move or resize it.
                    selectedImage != null && (handle != null || selectionOnScreen(selectedImage!!).contains(e.x, e.y)) -> startImageDrag(selectedImage!!, e)
                    g != null && gScreen != null && handle != null -> {
                        drag = Drag.GROUP_SCALE
                        val b = groupBounds(g)!!
                        // The opposite corner stays put.
                        anchor.set(if (handle == 0 || handle == 3) b.right else b.left, if (handle == 0 || handle == 1) b.bottom else b.top)
                    }
                    g != null && gScreen != null && gScreen.contains(e.x, e.y) -> {
                        drag = Drag.GROUP_MOVE
                        toPage(g.page, e.x, e.y).let { grabAt.set(it[0], it[1]) }
                    }
                    else -> {
                        // Picking something new (select tool only).
                        cropping = false
                        group = null
                        val img = imageAt(e.x, e.y)
                        if (img != null) { selectedImage = img; startImageDrag(img, e); return }
                        selectedImage = null
                        if (!isPen(e, 0)) { drag = Drag.NONE; return }
                        // The pen on an empty spot: a lasso (or, held still, paste here).
                        drag = Drag.LASSO
                        lassoPoints.clear(); lassoPoints += PointF(e.x, e.y)
                        lasso = android.graphics.Path().apply { moveTo(e.x, e.y) }
                        val x = e.x; val y = e.y
                        grabAt.set(x, y)
                        longPress = Runnable { lasso = null; drag = Drag.NONE; onLongPressEmpty(x, y); invalidate() }.also { postDelayed(it, 500) }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                longPress?.let { if (kotlin.math.hypot(e.x - grabAt.x, e.y - grabAt.y) > 12 * resources.displayMetrics.density) { removeCallbacks(it); longPress = null } }
                when (drag) {
                    Drag.IMAGE -> moveImage(e)
                    Drag.LASSO -> { lassoPoints += PointF(e.x, e.y); lasso?.lineTo(e.x, e.y); invalidate() }
                    Drag.GROUP_MOVE -> {
                        val g = group ?: return
                        val pt = toPage(g.page, e.x, e.y)
                        var dx = pt[0] - grabAt.x
                        var dy = pt[1] - grabAt.y
                        // Keep it on the page.
                        groupBounds(g)?.let { b ->
                            val size = sizes[g.page]
                            dx = dx.coerceIn(-b.left, size.width - b.right)
                            dy = dy.coerceIn(-b.top, size.height - b.bottom)
                        }
                        groupMatrix = Matrix().apply { setTranslate(dx, dy) }
                        invalidate()
                    }
                    Drag.GROUP_SCALE -> {
                        val g = group ?: return
                        val b = groupBounds(g) ?: return
                        val pt = toPage(g.page, e.x, e.y)
                        // Uniform scale about the opposite corner, following the farther-moved axis.
                        val sx = abs(pt[0] - anchor.x) / b.width().coerceAtLeast(1f)
                        val sy = abs(pt[1] - anchor.y) / b.height().coerceAtLeast(1f)
                        val k = maxOf(sx, sy).coerceIn(0.1f, 10f)
                        groupMatrix = Matrix().apply { setScale(k, k, anchor.x, anchor.y) }
                        invalidate()
                    }
                    Drag.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPress?.let { removeCallbacks(it) }; longPress = null
                when (drag) {
                    Drag.IMAGE -> endImageDrag()
                    Drag.LASSO -> finishLasso(e)
                    Drag.GROUP_MOVE, Drag.GROUP_SCALE -> group?.let { g -> groupMatrix?.let { m -> applyToGroup(g, m) } }
                    Drag.NONE -> {}
                }
                groupMatrix = null
                drag = Drag.NONE
                handle = null
                invalidate()
            }
        }
    }

    private fun startImageDrag(img: NoteImage, e: MotionEvent) {
        drag = Drag.IMAGE
        grabbed = img
        toPage(img.page, e.x, e.y).let { grabAt.set(it[0], it[1]) }
    }

    private fun moveImage(e: MotionEvent) {
        val start = grabbed ?: return
        val cur = selectedImage ?: return
        val pt = toPage(start.page, e.x, e.y)
        val h = handle
        val moved = when {
            h == null -> {
                // Keep it on the page.
                val size = sizes[start.page]
                val dx = (pt[0] - grabAt.x).coerceIn(-start.rect.left, size.width - start.rect.right)
                val dy = (pt[1] - grabAt.y).coerceIn(-start.rect.top, size.height - start.rect.bottom)
                cur.with(rect = RectF(start.rect).apply { offset(dx, dy) })
            }
            cropping -> cropTo(start, h, pt[0], pt[1])
            else -> resizeTo(start, h, pt[0], pt[1])
        }
        images.replaceAll { if (it.id == moved.id) moved else it }
        selectedImage = moved
    }

    private fun endImageDrag() {
        val start = grabbed
        val end = selectedImage
        if (start != null && end != null && (start.rect != end.rect || start.crop != end.crop)) record(InkChange.ImageChanged(start, end))
        grabbed = null
    }

    /** What the lasso touched (went around or crossed); a pen tap picks the stroke there. */
    private fun finishLasso(e: MotionEvent) {
        val pts = lassoPoints.toList()
        lasso = null
        val first = pts.firstOrNull() ?: return
        val page = pageAt(first.x, first.y) ?: return
        val inv = Matrix().also { pageMatrix(page).invert(it) }
        val poly = pts.map { p -> floatArrayOf(p.x, p.y).also { inv.mapPoints(it) } }
        val span = pts.maxOf { kotlin.math.hypot(it.x - first.x, it.y - first.y) }
        if (span < 12 * resources.displayMetrics.density) {
            // A tap: the stroke under it.
            strokeAt(e.x, e.y)?.let { group = Group(page, setOf(it.id), emptySet()) }
            return
        }
        fun inside(x: Float, y: Float): Boolean {
            var c = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val (xi, yi) = poly[i][0] to poly[i][1]
                val (xj, yj) = poly[j][0] to poly[j][1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i
            }
            return c
        }
        // Whether segment a-b crosses any edge of the lasso.
        fun crosses(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
            fun side(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
            var j = poly.size - 1
            for (i in poly.indices) {
                val cx = poly[j][0]; val cy = poly[j][1]; val dx = poly[i][0]; val dy = poly[i][1]
                if (side(ax, ay, bx, by, cx, cy) * side(ax, ay, bx, by, dx, dy) < 0 && side(cx, cy, dx, dy, ax, ay) * side(cx, cy, dx, dy, bx, by) < 0) return true
                j = i
            }
            return false
        }
        // Anything the lasso touches: a stroke with any point inside it or crossed by it; an image overlapping it.
        val picked = strokes.filter { s ->
            s.page == page && run {
                val inputs = s.stroke.inputs
                var px = Float.NaN; var py = Float.NaN
                for (k in 0 until inputs.size) {
                    val p = inputs[k]
                    if (inside(p.x, p.y) || (!px.isNaN() && crosses(px, py, p.x, p.y))) return@run true
                    px = p.x; py = p.y
                }
                false
            }
        }.map { it.id }.toSet()
        val pickedImages = images.filter { img ->
            val r = img.rect
            img.page == page && (
                inside(r.left, r.top) || inside(r.right, r.top) || inside(r.right, r.bottom) || inside(r.left, r.bottom) ||
                    poly.any { r.contains(it[0], it[1]) } ||
                    crosses(r.left, r.top, r.right, r.top) || crosses(r.right, r.top, r.right, r.bottom) ||
                    crosses(r.right, r.bottom, r.left, r.bottom) || crosses(r.left, r.bottom, r.left, r.top)
                )
        }.map { it.id }.toSet()
        when {
            picked.isEmpty() && pickedImages.size == 1 -> selectedImage = images.first { it.id in pickedImages }
            picked.isNotEmpty() || pickedImages.isNotEmpty() -> group = Group(page, picked, pickedImages)
        }
    }

    /** Applies [m] to the group's strokes and images, as one undoable change. */
    private fun applyToGroup(g: Group, m: Matrix) {
        val oldStrokes = strokes.filter { it.id in g.strokeIds }
        val oldImages = images.filter { it.id in g.imageIds }
        val newStrokes = oldStrokes.map { transformStroke(it, m) }
        val newImages = oldImages.map { img -> img.with(rect = RectF(img.rect).also { m.mapRect(it) }) }
        replace(oldStrokes, newStrokes, oldImages, newImages)
    }

    private fun replace(oldStrokes: List<InkStroke>, newStrokes: List<InkStroke>, oldImages: List<NoteImage>, newImages: List<NoteImage>) {
        val so = oldStrokes.map { it.id }.toSet()
        val io = oldImages.map { it.id }.toSet()
        strokes.removeAll { it.id in so }; strokes += newStrokes
        images.removeAll { it.id in io }; images += newImages
        record(InkChange.Replaced(oldStrokes, newStrokes, oldImages, newImages))
        invalidate()
    }

    /** The selection (a group, or the image) as strokes and images, for copying. */
    fun selectionContents(): Pair<List<InkStroke>, List<NoteImage>> {
        group?.let { g -> return strokes.filter { it.id in g.strokeIds } to images.filter { it.id in g.imageIds } }
        selectedImage?.let { return emptyList<InkStroke>() to listOf(it) }
        return emptyList<InkStroke>() to emptyList()
    }

    /** A picture of what's selected (white behind it), for pasting into other apps. */
    fun renderSelection(): Bitmap? {
        val (st, im) = selectionContents()
        var b: RectF? = null
        st.forEach { s -> s.stroke.shape.computeBoundingBox()?.let { bb -> val r = RectF(bb.xMin, bb.yMin, bb.xMax, bb.yMax); b = b?.apply { union(r) } ?: r } }
        im.forEach { b = b?.apply { union(it.rect) } ?: RectF(it.rect) }
        val bounds = b?.apply { inset(-4f, -4f) } ?: return null
        val k = (2048f / maxOf(bounds.width(), bounds.height())).coerceAtMost(4f)
        val bmp = Bitmap.createBitmap((bounds.width() * k).toInt().coerceAtLeast(1), (bounds.height() * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val m = Matrix().apply { setTranslate(-bounds.left, -bounds.top); postScale(k, k) }
        c.concat(m)
        im.forEach { c.drawBitmap(it.bitmap, it.source(), it.rect, bitmapPaint) }
        st.forEach { strokeRenderer.draw(c, it.stroke, m) }
        return bmp
    }

    /** Deletes what's selected. */
    fun deleteSelection() {
        group?.let { g ->
            replace(strokes.filter { it.id in g.strokeIds }, emptyList(), images.filter { it.id in g.imageIds }, emptyList())
            group = null
            return
        }
        deleteSelected()
    }

    /** A copy of what's selected, a little below and to the right; the copy is selected. */
    fun duplicateSelection() {
        val g = group ?: return duplicateSelected()
        val (st, im) = selectionContents()
        val m = Matrix().apply { setTranslate(12f, 12f) }
        pasteContents(st, im, g.page, m)
    }

    /**
     * Pastes copied strokes and images onto the page at the screen point ([x], [y]) (or the middle
     * of the screen), centred there; they arrive selected.
     */
    fun pasteContents(st: List<InkStroke>, im: List<NoteImage>, x: Float = width / 2f, y: Float = height / 2f) {
        val page = pageAt(x, y) ?: return
        var b: RectF? = null
        st.forEach { s -> s.stroke.shape.computeBoundingBox()?.let { bb -> val r = RectF(bb.xMin, bb.yMin, bb.xMax, bb.yMax); b = b?.apply { union(r) } ?: r } }
        im.forEach { b = b?.apply { union(it.rect) } ?: RectF(it.rect) }
        val bounds = b ?: return
        val pt = toPage(page, x, y)
        val size = sizes[page]
        val dx = (pt[0] - bounds.centerX()).coerceIn(-bounds.left, (size.width - bounds.right).coerceAtLeast(-bounds.left))
        val dy = (pt[1] - bounds.centerY()).coerceIn(-bounds.top, (size.height - bounds.bottom).coerceAtLeast(-bounds.top))
        pasteContents(st, im, page, Matrix().apply { setTranslate(dx, dy) })
    }

    private fun pasteContents(st: List<InkStroke>, im: List<NoteImage>, page: Int, m: Matrix) {
        val newStrokes = st.map { transformStroke(it, m, id = InkIds.next(), page = page) }
        val newImages = im.map { it.with(id = InkIds.next(), page = page, rect = RectF(it.rect).also { r -> m.mapRect(r) }) }
        replace(emptyList(), newStrokes, emptyList(), newImages)
        selectedImage = null
        group = Group(page, newStrokes.map { it.id }.toSet(), newImages.map { it.id }.toSet())
    }

    /** Resizing from corner [h]: the opposite corner stays put, the proportions are kept. */
    private fun resizeTo(start: NoteImage, h: Int, x: Float, y: Float): NoteImage {
        val r = start.rect
        val (ax, ay) = when (h) { 0 -> r.right to r.bottom; 1 -> r.left to r.bottom; 2 -> r.left to r.top; else -> r.right to r.top }
        val aspect = r.height() / r.width()
        val w = maxOf(abs(x - ax), abs(y - ay) / aspect, 12f)
        val hgt = w * aspect
        val left = if (x < ax) ax - w else ax
        val top = if (y < ay) ay - hgt else ay
        return start.with(rect = RectF(left, top, left + w, top + hgt))
    }

    /** Cropping with handle [h]: that side (or corner) of the shown part moves, within the whole picture. */
    private fun cropTo(start: NoteImage, h: Int, x: Float, y: Float): NoteImage {
        val full = start.fullRect()
        val r = RectF(start.rect)
        val min = 8f
        val moveLeft = h == 0 || h == 3 || h == 7
        val moveRight = h == 1 || h == 2 || h == 5
        val moveTop = h == 0 || h == 1 || h == 4
        val moveBottom = h == 2 || h == 3 || h == 6
        if (moveLeft) r.left = x.coerceIn(full.left, r.right - min)
        if (moveRight) r.right = x.coerceIn(r.left + min, full.right)
        if (moveTop) r.top = y.coerceIn(full.top, r.bottom - min)
        if (moveBottom) r.bottom = y.coerceIn(r.top + min, full.bottom)
        val crop = RectF((r.left - full.left) / full.width(), (r.top - full.top) / full.height(), (r.right - full.left) / full.width(), (r.bottom - full.top) / full.height())
        return start.with(rect = r, crop = crop)
    }

    /** The eraser end of the pen, the pen's side button, or the eraser tool. */
    private fun erasing(e: MotionEvent) =
        tool == Tool.ERASER || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    private fun pen(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val page = pageAt(e.x, e.y) ?: return
                penPointer = e.getPointerId(0)
                val toPage = Matrix().also { pageMatrix(page).invert(it) }
                val brush = if (tool == Tool.HIGHLIGHTER) brushFor(Tool.HIGHLIGHTER, highlighterColor, highlighterSize) else brushFor(Tool.PEN, penColor, penSize)
                val id = ink.startStroke(e, penPointer, brush, toPage)
                strokePages[id] = page to (if (tool == Tool.HIGHLIGHTER) Tool.HIGHLIGHTER else Tool.PEN)
                strokeId = id
            }
            MotionEvent.ACTION_MOVE -> strokeId?.let { id -> if (e.findPointerIndex(penPointer) >= 0) ink.addToStroke(e, penPointer, id) }
            MotionEvent.ACTION_UP -> strokeId?.let { id -> ink.finishStroke(e, penPointer, id) }.also { strokeId = null }
            MotionEvent.ACTION_CANCEL -> strokeId?.let { id -> ink.cancelStroke(id, e); strokePages.remove(id) }.also { strokeId = null }
        }
    }

    private fun erase(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) erasedNow.clear()
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            for (h in 0..e.historySize) {
                val x = if (h < e.historySize) e.getHistoricalX(h) else e.x
                val y = if (h < e.historySize) e.getHistoricalY(h) else e.y
                val page = pageAt(x, y) ?: continue
                val pt = floatArrayOf(x, y)
                Matrix().also { pageMatrix(page).invert(it) }.mapPoints(pt)
                val radius = eraserRadius * resources.displayMetrics.density / (scales[page] * zoom)
                val hit = strokes.filter { it.page == page && it.hits(pt[0], pt[1], radius) }
                if (hit.isNotEmpty()) { strokes.removeAll(hit.toSet()); erasedNow += hit; invalidate() }
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (erasedNow.isNotEmpty()) record(InkChange.Erased(erasedNow.toList()))
            erasedNow.clear()
        }
    }

    // ---- fingers: scroll, fling, pinch, page turns ----

    private val scroller = OverScroller(context)
    private var settle: ValueAnimator? = null

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (rects.isEmpty()) return true
            val next = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            // The point under the fingers, on its page, stays under them.
            val page = currentPage
            val pt = floatArrayOf(d.focusX, d.focusY)
            Matrix().also { pageMatrix(page).invert(it) }.mapPoints(pt)
            zoom = next
            if (mode == ScrollMode.PAGED) layoutPages()
            val r = rects[page]
            scrollXf = r.left * zoom + pt[0] * scales[page] * zoom - d.focusX
            scrollYf = r.top * zoom + pt[1] * scales[page] * zoom - d.focusY
            // No clamping while pinching (it pulled the page from under the fingers, a jump on
            // every frame near an edge); it eases back in bounds when the fingers lift.
            pinched = true
            afterMove()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) { dragPage = currentPage }
    }).apply {
        // Double-tap-and-drag zoom: quick successive flicks set it off, and the page jumped mid-scroll.
        isQuickScaleEnabled = false
    }

    /*
     * Page by page works the same at every zoom: the page follows the finger 1:1 both ways. Past
     * its left or right edge the neighbouring page slides in, and on release it turns if pulled far
     * enough (or flicked while already at that edge). Up and down only move within the page: past
     * its top or bottom it stretches (at half speed) and springs back.
     */
    private var dragPage = 0
    private var atLeft = false
    private var atRight = false
    /** The whole width of the page fits on screen: only then does a drag turn pages (zoomed in further, it only pans). */
    private var canTurn = false
    private var flingHandled = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Tapping anywhere off the selected image is done with it.
            if (hasSelection && !onSelection(e.x, e.y)) clearSelection()
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true); settle?.cancel()
            if (rects.isNotEmpty()) {
                dragPage = currentPage
                val r = rangeFor(dragPage)
                atLeft = scrollXf <= r.left + 1; atRight = scrollXf >= r.right - 1
                canTurn = rects[dragPage].width() * zoom <= width + 1f
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (rects.isEmpty()) return true
            if (mode == ScrollMode.CONTINUOUS) {
                scrollXf += dx; scrollYf += dy
                clampScroll(); afterMove()
                return true
            }
            val r = rangeFor(dragPage)
            // Zoomed in past the page's width, a drag only pans, and stops at the page's edge.
            scrollXf = if (canTurn) (scrollXf + dx).coerceIn(rangeFor(0).left - width * 0.25f, rangeFor(pageCount - 1).right + width * 0.25f)
            else (scrollXf + dx).coerceIn(r.left, r.right)
            val y = scrollYf + dy
            scrollYf = if ((y < r.top && dy < 0) || (y > r.bottom && dy > 0)) scrollYf + dy * 0.45f else y
            afterMove()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (rects.isEmpty()) return true
            if (mode == ScrollMode.CONTINUOUS) {
                val (minX, maxX, minY, maxY) = scrollBounds()
                scroller.fling(scrollXf.toInt(), scrollYf.toInt(), -vx.toInt(), -vy.toInt(), minX, maxX, minY, maxY)
                postInvalidateOnAnimation()
                return true
            }
            flingHandled = true
            val r = rangeFor(dragPage)
            if (abs(vx) > abs(vy)) {
                val dir = if (vx < 0) 1 else -1
                val atEdge = if (dir > 0) atRight || scrollXf > r.right + 1 else atLeft || scrollXf < r.left - 1
                if (canTurn && abs(vx) > 700 && atEdge && dragPage + dir in rects.indices) { turnHorizontal(dragPage + dir); return true }
            }
            // Otherwise a fling moves within the page.
            if (scrollXf in r.left..r.right && scrollYf in r.top..r.bottom) {
                scroller.fling(scrollXf.toInt(), scrollYf.toInt(), -vx.toInt(), -vy.toInt(), r.left.toInt(), r.right.toInt(), r.top.toInt(), r.bottom.toInt())
                postInvalidateOnAnimation()
            } else settlePaged()
            return true
        }
    })

    private var pinched = false

    private fun fingers(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { flingHandled = false; pinched = false }
        scale.onTouchEvent(e)
        // A pinch isn't also a scroll or fling.
        if (!scale.isInProgress && !pinched) gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            when {
                mode == ScrollMode.PAGED && (pinched || !flingHandled) -> settlePaged()
                pinched -> { val r = scrollRange(); animateTo(scrollXf.coerceIn(r.left, r.right), scrollYf.coerceIn(r.top, r.bottom)) }
            }
        }
    }

    /** After a drag (page by page): turn if pulled far enough onto a neighbour, else spring back. */
    private fun settlePaged() {
        if (rects.isEmpty()) return
        val r = rangeFor(dragPage)
        val overX = scrollXf - scrollXf.coerceIn(r.left, r.right)
        when {
            !pinched && canTurn && abs(overX) > width * 0.18f && dragPage + sign(overX) in rects.indices -> turnHorizontal(dragPage + sign(overX))
            else -> animateTo(scrollXf.coerceIn(r.left, r.right), scrollYf.coerceIn(r.top, r.bottom))
        }
    }

    private fun sign(v: Float) = if (v > 0) 1 else -1

    /** Slides sideways to page [target], at the same zoom, to its near edge. */
    private fun turnHorizontal(target: Int) {
        val t = rangeFor(target)
        val forward = target > dragPage
        animateTo(if (forward) t.left else t.right, scrollYf.coerceIn(t.top, t.bottom))
    }

    private fun animateTo(x: Float, y: Float) {
        settle?.cancel()
        val sx = scrollXf; val sy = scrollYf
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a -> val t = a.animatedValue as Float; scrollXf = sx + (x - sx) * t; scrollYf = sy + (y - sy) * t; afterMove() }
            start()
        }
    }

    private data class Bounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)
    private fun scrollBounds(): Bounds = scrollRange().let { Bounds(it.left.toInt(), it.right.toInt(), it.top.toInt(), it.bottom.toInt()) }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXf = scroller.currX.toFloat(); scrollYf = scroller.currY.toFloat()
            clampScroll(); afterMove()
            postInvalidateOnAnimation()
        }
    }

    // ---- undo ----

    private fun record(change: InkChange) {
        undo.addLast(change)
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
        notifyUndo()
        onInkChanged()
    }

    fun undo() { undo.removeLastOrNull()?.let { apply(it, back = true); redo.addLast(it); notifyUndo(); onInkChanged() } }
    fun redo() { redo.removeLastOrNull()?.let { apply(it, back = false); undo.addLast(it); notifyUndo(); onInkChanged() } }

    private fun apply(c: InkChange, back: Boolean) {
        when (c) {
            is InkChange.ImageAdded -> { if (back) images.removeAll { it.id == c.image.id } else images += c.image; selectedImage = null; invalidate(); return }
            is InkChange.ImageRemoved -> { if (back) images += c.image else images.removeAll { it.id == c.image.id }; selectedImage = null; invalidate(); return }
            is InkChange.ImageChanged -> {
                val to = if (back) c.before else c.after
                images.replaceAll { if (it.id == to.id) to else it }
                if (selectedImage?.id == to.id) selectedImage = to
                invalidate(); return
            }
            is InkChange.Replaced -> {
                val (rs, ast, ri, ai) = if (back) listOf(c.addedStrokes, c.removedStrokes, c.addedImages, c.removedImages) else listOf(c.removedStrokes, c.addedStrokes, c.removedImages, c.addedImages)
                @Suppress("UNCHECKED_CAST") run {
                    val removeS = (rs as List<InkStroke>).map { it.id }.toSet()
                    val removeI = (ri as List<NoteImage>).map { it.id }.toSet()
                    strokes.removeAll { it.id in removeS }; strokes += ast as List<InkStroke>
                    images.removeAll { it.id in removeI }; images += ai as List<NoteImage>
                }
                selectedImage = null; group = null; invalidate(); return
            }
            else -> {}
        }
        val (add, remove) = when (c) {
            is InkChange.Added -> if (back) emptyList<InkStroke>() to c.strokes else c.strokes to emptyList()
            is InkChange.Erased -> if (back) c.strokes to emptyList() else emptyList<InkStroke>() to c.strokes
            else -> emptyList<InkStroke>() to emptyList()
        }
        val ids = remove.map { it.id }.toSet()
        strokes.removeAll { it.id in ids }
        strokes += add
        invalidate()
    }

    private fun notifyUndo() = onUndoChanged(undo.isNotEmpty(), redo.isNotEmpty())
}
