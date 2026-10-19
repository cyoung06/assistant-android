package com.syeyoung.assistant.notebook

import java.io.ByteArrayOutputStream

/**
 * A blank notebook as a PDF with a dot grid, written by hand: the dot is a PDF tiling pattern,
 * described once and repeated by the reader, and every page shares one content stream. So 20 pages
 * are a few KB and each page renders as fast as an empty one. (Android's PdfDocument can't make
 * patterns; drawing each dot made ~1,700 shapes per page.)
 */
object BlankPdf {
    /** A4 in points; dots every 17pt (6 mm), starting 28pt in. */
    private const val W = 595
    private const val H = 842
    private const val STEP = 17
    private const val MARGIN = 28

    fun dotGrid(pages: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun obj(body: String) { offsets += out.size(); out.write("${offsets.size} 0 obj\n$body\nendobj\n".toByteArray(Charsets.US_ASCII)) }
        fun stream(dict: String, data: String) = "<< $dict /Length ${data.length} >>\nstream\n$data\nendstream"

        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        val firstPage = 6
        // 1 catalog, 2 page tree, 3 the dot pattern, 4 the page content, 5 page resources, then the pages.
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Count $pages /Kids [${(0 until pages).joinToString(" ") { "${firstPage + it} 0 R" }}] >>")
        // One light-gray dot (radius 0.7pt, as four Bézier arcs) centred in a STEP×STEP cell.
        val c = STEP / 2.0
        val r = 0.7
        val k = r * 0.5523
        val dot = "0.78 0.78 0.8 rg ${c + r} $c m ${c + r} ${c + k} ${c + k} ${c + r} $c ${c + r} c " +
            "${c - k} ${c + r} ${c - r} ${c + k} ${c - r} $c c ${c - r} ${c - k} ${c - k} ${c - r} $c ${c - r} c " +
            "${c + k} ${c - r} ${c + r} ${c - k} ${c + r} $c c f"
        // The pattern's cell is placed so the first dot sits MARGIN in from the bottom-left corner.
        val shift = MARGIN - c
        obj(stream("/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 $STEP $STEP] /XStep $STEP /YStep $STEP /Resources << >> /Matrix [1 0 0 1 $shift $shift]", dot))
        // Fill the area inside the margins with the pattern.
        val inner = MARGIN - STEP / 2
        obj(stream("", "/Pattern cs /Dots scn $inner $inner ${W - 2 * inner} ${H - 2 * inner} re f"))
        obj("<< /Pattern << /Dots 3 0 R >> >>")
        repeat(pages) { obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $W $H] /Contents 4 0 R /Resources 5 0 R >>") }

        val xref = out.size()
        val table = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { table.append("%010d 00000 n \n".format(it)) }
        table.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(table.toString().toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}
