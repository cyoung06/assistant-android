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
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Renders PDF pages with Android's PdfRenderer (pdfium) on a few background threads at once.
 * A PdfRenderer can only be used from one thread, so each thread opens the file itself. Results
 * come back on the main thread.
 *
 * Work is done most urgent first (by [Kind], then [priority], lower first), and a job whose result
 * is no longer wanted when its turn comes is skipped (its callback gets null).
 */
class PageRenderer(threads: Int = 3) {
    enum class Kind { TILE, THUMBNAIL }

    private class Job(val kind: Kind, val priority: Float, val seq: Long, val work: () -> Unit) : Runnable, Comparable<Job> {
        override fun run() = work()
        override fun compareTo(other: Job) = compareValuesBy(this, other, { it.kind.ordinal }, { it.priority }, { it.seq })
    }

    /** One thread's open renderer, for the file of [generation]. */
    private class Open(val generation: Int, val renderer: PdfRenderer)

    private val pool = ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>())
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicLong()
    private val local = ThreadLocal<Open?>()
    private val opened = java.util.Collections.synchronizedList(mutableListOf<PdfRenderer>())
    @Volatile private var file: File? = null
    @Volatile private var generation = 0

    /** Opens [file] (for every thread) and returns its page sizes in points. */
    fun open(file: File): List<SizeF> {
        val sizes = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            (0 until r.pageCount).map { i -> r.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) } }
        }
        this.file = file
        generation++
        return sizes
    }

    /** This thread's renderer for the current file, opened (or reopened) as needed. */
    private fun renderer(): PdfRenderer? {
        val gen = generation
        local.get()?.let { if (it.generation == gen) return it.renderer; runCatching { it.renderer.close() }; opened.remove(it.renderer) }
        val f = file ?: return null
        return runCatching { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
            ?.also { local.set(Open(gen, it)); opened += it }
    }

    /**
     * Renders [page] into a [width]×[height] bitmap through [pageToBitmap] (points → pixels): a
     * tile of a zoomed page, or a whole page small. [wanted] is asked just before rendering; if it
     * says no, or the file was reopened since, [done] gets null.
     */
    fun render(
        page: Int, width: Int, height: Int, pageToBitmap: Matrix,
        kind: Kind = Kind.TILE, priority: Float = 0f, wanted: () -> Boolean = { true }, done: (Bitmap?) -> Unit,
    ) {
        val gen = generation
        pool.execute(Job(kind, priority, seq.incrementAndGet()) {
            val bitmap = run {
                if (gen != generation || width <= 0 || height <= 0 || !wanted()) return@run null
                val r = renderer() ?: return@run null
                if (page >= r.pageCount) return@run null
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                    eraseColor(Color.WHITE)
                    runCatching { r.openPage(page).use { it.render(this, null, pageToBitmap, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
                }
            }
            main.post { if (bitmap != null && gen != generation) { bitmap.recycle(); done(null) } else done(bitmap) }
        })
    }

    fun close() {
        pool.shutdownNow()
        synchronized(opened) { opened.forEach { runCatching { it.close() } }; opened.clear() }
    }
}
