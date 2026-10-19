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
import java.util.concurrent.Executors

/**
 * Renders PDF pages with Android's PdfRenderer on one background thread (it can't be used from
 * two at once). Results come back on the main thread.
 */
class PageRenderer {
    private val thread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var renderer: PdfRenderer? = null
    @Volatile private var generation = 0

    /** Opens [file] and returns its page sizes in points. */
    fun open(file: File): List<SizeF> {
        val result = thread.submit<List<SizeF>> {
            renderer?.close()
            val r = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            renderer = r
            (0 until r.pageCount).map { i -> r.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) } }
        }.get()
        generation++
        return result
    }

    /**
     * Renders [page] into a [width]×[height] bitmap through [pageToBitmap] (points → pixels): the
     * whole page, or only the part a zoomed-in screen shows. Dropped if the file was reopened since.
     */
    fun render(page: Int, width: Int, height: Int, pageToBitmap: Matrix, done: (Bitmap) -> Unit) {
        val gen = generation
        thread.execute {
            val r = renderer ?: return@execute
            if (gen != generation || page >= r.pageCount || width <= 0 || height <= 0) return@execute
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
            bitmap.eraseColor(Color.WHITE)
            runCatching { r.openPage(page).use { it.render(bitmap, null, pageToBitmap, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
            main.post { if (gen == generation) done(bitmap) else bitmap.recycle() }
        }
    }

    fun close() {
        thread.execute { renderer?.close(); renderer = null }
        thread.shutdown()
    }
}
