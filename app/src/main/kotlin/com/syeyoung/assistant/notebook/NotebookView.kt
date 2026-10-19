package com.syeyoung.assistant.notebook

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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
    var penSize = PEN_SIZES[1]
    var highlighterColor = HIGHLIGHTER_COLORS[0]
    var highlighterSize = HIGHLIGHTER_SIZES[1]
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
    private val undo = ArrayDeque<InkChange>()
    private val redo = ArrayDeque<InkChange>()
    fun strokesSnapshot(): List<InkStroke> = strokes.toList()

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

    fun setDocument(pageSizes: List<SizeF>, inkStrokes: List<InkStroke>) {
        sizes = pageSizes
        strokes.clear(); strokes += inkStrokes
        undo.clear(); redo.clear(); notifyUndo()
        dropBitmaps()
        layoutPages()
        goTo(currentPage.coerceAtMost(pageCount - 1).coerceAtLeast(0), animate = false)
    }

    /** After pages were added or removed: new sizes, and where each old page went (null = deleted). */
    fun pagesChanged(pageSizes: List<SizeF>, moved: (Int) -> Int?) {
        sizes = pageSizes
        val kept = strokes.mapNotNull { s -> moved(s.page)?.let { s.onPage(it) } }
        strokes.clear(); strokes += kept
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
                scales = sizes.map { s -> min((w - 2 * pad) / s.width, (h - 2 * pad) / s.height) }
                rects = sizes.mapIndexed { i, s ->
                    val pw = s.width * scales[i]
                    val ph = s.height * scales[i]
                    RectF(i * w + (w - pw) / 2, (h - ph) / 2, i * w + (w + pw) / 2, (h + ph) / 2)
                }
                contentW = w * sizes.size
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

    fun goTo(page: Int, animate: Boolean = true) {
        if (rects.isEmpty()) return
        val p = page.coerceIn(0, rects.size - 1)
        if (mode == ScrollMode.PAGED) zoom = 1f
        val x = if (mode == ScrollMode.PAGED) p * width.toFloat() else scrollXf
        val y = if (mode == ScrollMode.PAGED) 0f else (rects[p].top * zoom - gap).coerceIn(0f, max(0f, contentH * zoom - height))
        if (animate) animateTo(x, y) else { scrollXf = x; scrollYf = y; clampScroll(); afterMove() }
    }

    /**
     * How far the screen can scroll: to just past the page edges (a gap's width), never into the
     * empty space beside a page; along an axis where the zoomed page fits on screen, it stays centred.
     */
    private fun scrollRange(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        fun axis(from: Float, to: Float, view: Float, pad: Float): Pair<Float, Float> {
            val lo = from * zoom - pad
            val hi = to * zoom + pad - view
            return if (hi >= lo) lo to hi else ((from + to) / 2 * zoom - view / 2).let { it to it }
        }
        return when {
            rects.isEmpty() -> RectF()
            mode == ScrollMode.PAGED && zoom <= 1.01f -> RectF(0f, 0f, max(0f, contentW - w), 0f)
            mode == ScrollMode.PAGED -> {
                val r = rects[currentPage]
                val (x0, x1) = axis(r.left, r.right, w, gap)
                val (y0, y1) = axis(r.top, r.bottom, h, gap)
                RectF(x0, y0, x1, y1)
            }
            else -> {
                val (x0, x1) = axis(rects.minOf { it.left }, rects.maxOf { it.right }, w, gap)
                RectF(x0, 0f, x1, max(0f, contentH * zoom - h))
            }
        }
    }

    private fun clampScroll() {
        val r = scrollRange()
        scrollXf = scrollXf.coerceIn(r.left, r.right)
        scrollYf = scrollYf.coerceIn(r.top, r.bottom)
    }

    private fun afterMove() {
        val before = currentPage
        currentPage = when (mode) {
            ScrollMode.PAGED -> ((scrollXf + width / 2f) / zoom / width).toInt().coerceIn(0, max(0, pageCount - 1))
            ScrollMode.CONTINUOUS -> {
                val mid = (scrollYf + height / 2f) / zoom
                rects.indexOfFirst { mid <= it.bottom + gap / 2 }.let { if (it < 0) pageCount - 1 else it }.coerceAtLeast(0)
            }
        }
        if (before != currentPage) onPageChanged(currentPage, pageCount)
        scheduleSharp()
        invalidate()
    }

    // ---- drawing ----

    private val strokeRenderer = CanvasStrokeRenderer.create()
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint().apply { color = 0x22000000 }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()

    /** Each page once, at the size it has on screen at zoom 1. */
    private val pageBitmaps = HashMap<Int, Bitmap>()
    private val pending = HashSet<Int>()
    /** The visible part of a page, rendered sharp at the current zoom. */
    private class Sharp(val page: Int, val bitmap: Bitmap, val zoom: Float, val left: Float, val top: Float, val scrollX: Float, val scrollY: Float)
    private val sharp = HashMap<Int, Sharp>()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFE8E8EC.toInt())
        if (rects.isEmpty()) return
        val visible = rects.indices.filter { RectF.intersects(screenRect(it), RectF(0f, 0f, width.toFloat(), height.toFloat())) }
        for (i in visible) {
            val r = screenRect(i)
            canvas.drawRect(r.left + 2, r.top + 3, r.right + 2, r.bottom + 3, shadowPaint)
            canvas.drawRect(r, pagePaint)
            val base = pageBitmaps[i]
            if (base != null) canvas.drawBitmap(base, null, r, bitmapPaint) else requestPage(i)
            sharp[i]?.takeIf { abs(it.zoom - zoom) < 0.001f }?.let { s ->
                canvas.drawBitmap(s.bitmap, s.left - (scrollXf - s.scrollX), s.top - (scrollYf - s.scrollY), null)
            }
            canvas.save()
            canvas.clipRect(r)
            // The renderer doesn't place strokes itself: the canvas maps page points to the screen,
            // and the same matrix is passed so it draws at the right resolution.
            pageMatrix(i, drawMatrix)
            canvas.concat(drawMatrix)
            for (s in strokes) if (s.page == i) strokeRenderer.draw(canvas, s.stroke, drawMatrix)
            canvas.restore()
        }
        // Keep the neighbours ready for a page turn.
        (visible.flatMap { listOf(it - 1, it + 1) } - visible.toSet()).filter { it in rects.indices }.forEach { if (pageBitmaps[it] == null) requestPage(it) }
        // Forget pages far from the screen.
        pageBitmaps.keys.filter { k -> visible.none { abs(it - k) <= 2 } }.forEach { pageBitmaps.remove(it)?.recycle() }
    }

    private fun requestPage(i: Int) {
        if (!pending.add(i)) return
        val w = (rects[i].width()).roundToInt()
        val h = (rects[i].height()).roundToInt()
        val m = Matrix().apply { setScale(scales[i], scales[i]) }
        renderer.render(i, w, h, m) { bmp ->
            pending.remove(i)
            if (i in rects.indices) { pageBitmaps.put(i, bmp)?.recycle(); invalidate() } else bmp.recycle()
        }
    }

    private val sharpRequest = Runnable { renderSharp() }
    private fun scheduleSharp() {
        removeCallbacks(sharpRequest)
        if (zoom > 1.05f) postDelayed(sharpRequest, 140) else if (sharp.isNotEmpty()) { sharp.values.forEach { it.bitmap.recycle() }; sharp.clear() }
    }

    private fun renderSharp() {
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        for (i in rects.indices) {
            val r = screenRect(i)
            val part = RectF(r)
            if (!part.intersect(screen)) continue
            val m = pageMatrix(i).apply { postTranslate(-part.left, -part.top) }
            val z = zoom; val sx = scrollXf; val sy = scrollYf
            renderer.render(i, part.width().roundToInt(), part.height().roundToInt(), m) { bmp ->
                sharp.put(i, Sharp(i, bmp, z, part.left, part.top, sx, sy))?.bitmap?.recycle()
                invalidate()
            }
        }
    }

    fun dropBitmaps() {
        pageBitmaps.values.forEach { it.recycle() }; pageBitmaps.clear(); pending.clear()
        sharp.values.forEach { it.bitmap.recycle() }; sharp.clear()
    }

    // ---- touch ----

    private enum class Gesture { NONE, PEN, ERASE, FINGERS }
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
            gesture = when {
                isPen(e, 0) -> if (erasing(e)) Gesture.ERASE else Gesture.PEN
                // A hand resting while the pen is near (or just lifted) isn't a gesture.
                e.eventTime - lastPenTime < 600 -> Gesture.NONE
                else -> Gesture.FINGERS
            }
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
            Gesture.PEN -> pen(e)
            Gesture.ERASE -> erase(e)
            Gesture.FINGERS -> fingers(e)
            Gesture.NONE -> {}
        }
        if (isPen(e, e.actionIndex)) lastPenTime = e.eventTime
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) gesture = Gesture.NONE
        return true
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
                val radius = 10f * resources.displayMetrics.density / (scales[page] * zoom)
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
    private var dragStartX = 0f
    private var scaled = false

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            val f = next / zoom
            scrollXf = (scrollXf + d.focusX) * f - d.focusX
            scrollYf = (scrollYf + d.focusY) * f - d.focusY
            zoom = next
            scaled = true
            clampScroll(); afterMove()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true); settle?.cancel()
            dragStartX = scrollXf
            scaled = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollXf += dx
            if (!(mode == ScrollMode.PAGED && zoom <= 1.01f)) scrollYf += dy
            if (mode == ScrollMode.PAGED && zoom <= 1.01f) scrollXf = scrollXf.coerceIn(0f, max(0f, contentW - width)) else clampScroll()
            afterMove()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (mode == ScrollMode.PAGED && zoom <= 1.01f) {
                val target = when {
                    vx < -800 -> currentPageFrom(dragStartX) + 1
                    vx > 800 -> currentPageFrom(dragStartX) - 1
                    else -> nearestPage()
                }
                flingHandled = true
                turnTo(target)
                return true
            }
            val (minX, maxX, minY, maxY) = scrollBounds()
            scroller.fling(scrollXf.toInt(), scrollYf.toInt(), -vx.toInt(), -vy.toInt(), minX, maxX, minY, maxY)
            postInvalidateOnAnimation()
            return true
        }
    })
    private var flingHandled = false

    private fun fingers(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) flingHandled = false
        scale.onTouchEvent(e)
        if (!scale.isInProgress) gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP && mode == ScrollMode.PAGED && zoom <= 1.01f && !flingHandled) turnTo(nearestPage())
        if (e.actionMasked == MotionEvent.ACTION_UP && mode == ScrollMode.PAGED && zoom < 1.01f && scaled) turnTo(currentPage)
    }

    private fun currentPageFrom(x: Float) = ((x + width / 2f) / width).toInt().coerceIn(0, max(0, pageCount - 1))
    private fun nearestPage() = ((scrollXf + width / 2f) / width).toInt().coerceIn(0, max(0, pageCount - 1))

    private fun turnTo(page: Int) {
        val p = page.coerceIn(0, max(0, pageCount - 1))
        animateTo(p * width.toFloat(), 0f)
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
        val (add, remove) = when (c) {
            is InkChange.Added -> if (back) emptyList<InkStroke>() to c.strokes else c.strokes to emptyList()
            is InkChange.Erased -> if (back) c.strokes to emptyList() else emptyList<InkStroke>() to c.strokes
        }
        val ids = remove.map { it.id }.toSet()
        strokes.removeAll { it.id in ids }
        strokes += add
        invalidate()
    }

    private fun notifyUndo() = onUndoChanged(undo.isNotEmpty(), redo.isNotEmpty())
}
