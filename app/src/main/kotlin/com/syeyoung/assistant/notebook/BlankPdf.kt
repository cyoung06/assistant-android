package com.syeyoung.assistant.notebook

import java.io.ByteArrayOutputStream
import java.util.Locale

/** What a new page looks like. */
enum class PageStyle(val label: String) { BLANK("Blank"), DOTS("Dotted"), LINES("Lined"), GRID("Grid") }

/**
 * Blank notebook pages as a PDF, written by hand: dots and grid are PDF tiling patterns (one cell
 * described once, repeated by the reader) and lines are a few dozen strokes, all pages sharing one
 * content stream; so 20 pages are a few KB and each renders as fast as an empty page. (Android's
 * PdfDocument can't make patterns; drawing each dot made ~1,700 shapes per page.)
 */
object BlankPdf {
    /** Spacing of dots and grid, 17pt (6 mm); lines are 22pt (about 8 mm) apart. */
    private const val STEP = 17f
    private const val LINE = 22f
    private const val MARGIN = 28f

    /** [pages] pages of [width]×[height] points (A4 by default) in [style]. */
    fun notebook(pages: Int, style: PageStyle, width: Float = 595f, height: Float = 842f): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun obj(body: String) { offsets += out.size(); out.write("${offsets.size} 0 obj\n$body\nendobj\n".toByteArray(Charsets.US_ASCII)) }
        fun stream(dict: String, data: String) = "<< $dict /Length ${data.length} >>\nstream\n$data\nendstream"
        fun n(v: Float) = String.format(Locale.US, "%.2f", v)

        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        val firstPage = 6
        // 1 catalog, 2 page tree, 3 the pattern (if any), 4 the page content, 5 page resources, then the pages.
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Count $pages /Kids [${(0 until pages).joinToString(" ") { "${firstPage + it} 0 R" }}] >>")
        val inner = MARGIN - STEP / 2
        val shift = MARGIN - STEP / 2
        when (style) {
            PageStyle.DOTS -> {
                // One light-gray dot (radius 0.7pt, four Bézier arcs) centred in a cell.
                val c = STEP / 2; val r = 0.7f; val k = r * 0.5523f
                val dot = "0.78 0.78 0.8 rg ${n(c + r)} ${n(c)} m ${n(c + r)} ${n(c + k)} ${n(c + k)} ${n(c + r)} ${n(c)} ${n(c + r)} c " +
                    "${n(c - k)} ${n(c + r)} ${n(c - r)} ${n(c + k)} ${n(c - r)} ${n(c)} c ${n(c - r)} ${n(c - k)} ${n(c - k)} ${n(c - r)} ${n(c)} ${n(c - r)} c " +
                    "${n(c + k)} ${n(c - r)} ${n(c + r)} ${n(c - k)} ${n(c + r)} ${n(c)} c f"
                obj(stream("/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 ${n(STEP)} ${n(STEP)}] /XStep ${n(STEP)} /YStep ${n(STEP)} /Resources << >> /Matrix [1 0 0 1 ${n(shift)} ${n(shift)}]", dot))
                obj(stream("", "/Pattern cs /P scn ${n(inner)} ${n(inner)} ${n(width - 2 * inner)} ${n(height - 2 * inner)} re f"))
            }
            PageStyle.GRID -> {
                // A cell's bottom and left edges, so the cells tile into a grid.
                val cell = "0.85 0.85 0.88 RG 0.4 w 0 0 m ${n(STEP)} 0 l S 0 0 m 0 ${n(STEP)} l S"
                obj(stream("/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 ${n(STEP)} ${n(STEP)}] /XStep ${n(STEP)} /YStep ${n(STEP)} /Resources << >> /Matrix [1 0 0 1 ${n(MARGIN)} ${n(MARGIN)}]", cell))
                val cols = ((width - 2 * MARGIN) / STEP).toInt()
                val rows = ((height - 2 * MARGIN) / STEP).toInt()
                // Fill whole cells plus one line along the far edges, so the grid is closed.
                obj(stream("", "/Pattern cs /P scn ${n(MARGIN)} ${n(MARGIN)} ${n(cols * STEP + 0.5f)} ${n(rows * STEP + 0.5f)} re f"))
            }
            PageStyle.LINES -> {
                obj("<< >>")
                val lines = StringBuilder("0.8 0.82 0.88 RG 0.5 w\n")
                var y = height - MARGIN * 2.2f
                while (y > MARGIN) { lines.append("${n(MARGIN)} ${n(y)} m ${n(width - MARGIN)} ${n(y)} l\n"); y -= LINE }
                lines.append("S")
                obj(stream("", lines.toString()))
            }
            PageStyle.BLANK -> { obj("<< >>"); obj(stream("", "")) }
        }
        obj(if (style == PageStyle.DOTS || style == PageStyle.GRID) "<< /Pattern << /P 3 0 R >> >>" else "<< >>")
        repeat(pages) { obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(width)} ${n(height)}] /Contents 4 0 R /Resources 5 0 R >>") }

        val xref = out.size()
        val table = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { table.append("%010d 00000 n \n".format(it)) }
        table.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(table.toString().toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    fun dotGrid(pages: Int) = notebook(pages, PageStyle.DOTS)
}
