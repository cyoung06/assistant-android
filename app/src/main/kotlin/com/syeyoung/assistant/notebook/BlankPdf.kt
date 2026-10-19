package com.syeyoung.assistant.notebook

import java.io.ByteArrayOutputStream
import java.util.Locale

/** What a new page looks like. */
enum class PageStyle(val label: String) { BLANK("Blank"), DOTS("Dotted"), LINES("Lined"), GRID("Grid") }

/**
 * Blank notebook pages as a PDF, written by hand. Dots, lines and grid are plain vector paths, one
 * path per page, compressed (FlateDecode), all pages sharing one content stream; a 20-page
 * notebook is a few KB.
 *
 * Not tiling patterns: Android's PDF renderer draws a pattern by rasterising one cell to whole
 * pixels and repeating it, so the grid drifted against the (exact) ink, differently at every zoom.
 */
object BlankPdf {
    /** Spacing of dots and grid, 17pt (6 mm); lines are 22pt (about 8 mm) apart. */
    private const val STEP = 17f
    private const val LINE = 22f
    private const val MARGIN = 28f

    private fun n(v: Float) = String.format(Locale.US, "%.2f", v)

    /** The drawing of a page in [style] of [width]×[height] points, as PDF content. */
    fun content(style: PageStyle, width: Float, height: Float): String {
        val out = StringBuilder()
        when (style) {
            PageStyle.DOTS -> {
                // Each dot a zero-length segment with a round cap: one stroked path for the page.
                out.append("0.75 0.75 0.78 RG 1.4 w 1 J\n")
                var y = MARGIN
                while (y <= height - MARGIN + 0.01f) {
                    var x = MARGIN
                    while (x <= width - MARGIN + 0.01f) { out.append("${n(x)} ${n(y)} m ${n(x)} ${n(y)} l\n"); x += STEP }
                    y += STEP
                }
                out.append("S")
            }
            PageStyle.GRID -> {
                val cols = ((width - 2 * MARGIN) / STEP).toInt()
                val rows = ((height - 2 * MARGIN) / STEP).toInt()
                val right = MARGIN + cols * STEP
                val top = MARGIN + rows * STEP
                out.append("0.85 0.85 0.88 RG 0.4 w\n")
                for (c in 0..cols) { val x = MARGIN + c * STEP; out.append("${n(x)} ${n(MARGIN)} m ${n(x)} ${n(top)} l\n") }
                for (r in 0..rows) { val y = MARGIN + r * STEP; out.append("${n(MARGIN)} ${n(y)} m ${n(right)} ${n(y)} l\n") }
                out.append("S")
            }
            PageStyle.LINES -> {
                out.append("0.8 0.82 0.88 RG 0.5 w\n")
                var y = height - MARGIN * 2.2f
                while (y > MARGIN) { out.append("${n(MARGIN)} ${n(y)} m ${n(width - MARGIN)} ${n(y)} l\n"); y -= LINE }
                out.append("S")
            }
            PageStyle.BLANK -> {}
        }
        return out.toString()
    }

    /** [pages] pages of [width]×[height] points (A4 by default) in [style]. */
    fun notebook(pages: Int, style: PageStyle, width: Float = 595f, height: Float = 842f): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun text(t: String) = out.write(t.toByteArray(Charsets.US_ASCII))
        fun obj(body: String) { offsets += out.size(); text("${offsets.size} 0 obj\n$body\nendobj\n") }

        text("%PDF-1.4\n")
        val firstPage = 4
        // 1 catalog, 2 page tree, 3 the page drawing (shared, compressed), then the pages.
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Count $pages /Kids [${(0 until pages).joinToString(" ") { "${firstPage + it} 0 R" }}] >>")
        val drawing = deflate(content(style, width, height).toByteArray(Charsets.US_ASCII))
        offsets += out.size()
        text("${offsets.size} 0 obj\n<< /Length ${drawing.size} /Filter /FlateDecode >>\nstream\n")
        out.write(drawing)
        text("\nendstream\nendobj\n")
        repeat(pages) { obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(width)} ${n(height)}] /Contents 3 0 R /Resources << >> >>") }

        val xref = out.size()
        val table = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { table.append("%010d 00000 n \n".format(it)) }
        table.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        text(table.toString())
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
