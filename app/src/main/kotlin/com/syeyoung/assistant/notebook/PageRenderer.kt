package com.syeyoung.assistant.notebook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.SizeF
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Renders PDF pages with Android's PdfRenderer on one background thread (it can't be used from
 * two at once). Results come back on the main thread.
 *
 * Work is done most urgent first: whole pages (what a page turn needs), then thumbnails, then the
 * sharp zoomed-in renders; a sharp render that a newer one has replaced is skipped. (In order, a
 * backlog of sharp renders left the page just turned to blank for seconds.)
 */
class PageRenderer {
    enum class Kind { PAGE, THUMBNAIL, SHARP }

    private class Job(val kind: Kind, val seq: Long, val work: () -> Unit) : Runnable, Comparable<Job> {
        override fun run() = work()
        override fun compareTo(other: Job) = compareValuesBy(this, other, { it.kind.ordinal }, { it.seq })
    }

    private val thread = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>())
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicLong()
    private var renderer: PdfRenderer? = null
    @Volatile private var generation = 0
    /** The newest sharp render asked for, per page: older ones still queued are skipped. */
    private val latestSharp = ConcurrentHashMap<Int, Long>()

    /** Opens [file] and returns its page sizes in points. */
    fun open(file: File): List<SizeF> {
        val done = java.util.concurrent.CompletableFuture<List<SizeF>>()
        thread.execute(Job(Kind.PAGE, seq.incrementAndGet()) {
            runCatching {
                renderer?.close()
                val r = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                renderer = r
                (0 until r.pageCount).map { i -> r.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) } }
            }.fold({ done.complete(it) }, { done.completeExceptionally(it) })
        })
        val sizes = done.get()
        generation++
        latestSharp.clear()
        return sizes
    }

    /**
     * Renders [page] into a [width]×[height] bitmap through [pageToBitmap] (points → pixels): the
     * whole page, or only the part a zoomed-in screen shows. Dropped if the file was reopened since.
     */
    fun render(page: Int, width: Int, height: Int, pageToBitmap: Matrix, kind: Kind = Kind.PAGE, done: (Bitmap) -> Unit) {
        val gen = generation
        val n = seq.incrementAndGet()
        if (kind == Kind.SHARP) latestSharp[page] = n
        thread.execute(Job(kind, n) {
            val r = renderer ?: return@Job
            if (gen != generation || page >= r.pageCount || width <= 0 || height <= 0) return@Job
            if (kind == Kind.SHARP && latestSharp[page] != n) return@Job
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
            bitmap.eraseColor(Color.WHITE)
            runCatching { r.openPage(page).use { it.render(bitmap, null, pageToBitmap, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
            main.post { if (gen == generation) done(bitmap) else bitmap.recycle() }
        })
    }

    fun close() {
        thread.execute(Job(Kind.PAGE, seq.incrementAndGet()) { renderer?.close(); renderer = null })
        thread.shutdown()
    }
}
