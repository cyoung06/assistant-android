package com.syeyoung.assistant.notebook

import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** A page's text, with where each character sits (page points from the top-left). */
class PageText(val text: String, val boxes: List<RectF?>)

/** One match: its page, the boxes to highlight (one per line it spans), and a line of context. */
class SearchHit(val page: Int, val boxes: List<RectF>, val snippet: String)

/**
 * Search over the PDF's own text (slides, course material), not handwriting. The text is pulled
 * out of the base file once, page by page, with each character's position.
 */
object TextSearch {
    fun extract(base: File): List<PageText> =
        PDDocument.load(base, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            (0 until doc.numberOfPages).map { i ->
                val sb = StringBuilder()
                val boxes = mutableListOf<RectF?>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
                        // One position per character when they line up; otherwise the run's box for each.
                        if (positions.size == text.length) {
                            text.forEachIndexed { k, ch -> sb.append(ch); boxes += box(positions[k]) }
                        } else {
                            val run = positions.map(::box).reduceOrNull { a, b -> RectF(a).apply { union(b) } }
                            text.forEach { ch -> sb.append(ch); boxes += run }
                        }
                    }
                    override fun writeLineSeparator() { sb.append('\n'); boxes += null }
                    override fun writeWordSeparator() { sb.append(' '); boxes += null }
                }
                stripper.sortByPosition = true
                stripper.startPage = i + 1
                stripper.endPage = i + 1
                runCatching { stripper.getText(doc) }
                PageText(sb.toString(), boxes)
            }
        }

    private fun box(p: TextPosition) = RectF(p.xDirAdj, p.yDirAdj - p.heightDir, p.xDirAdj + p.widthDirAdj, p.yDirAdj)

    /** Every case-insensitive match of [query], page by page. */
    fun find(pages: List<PageText>, query: String): List<SearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        pages.forEachIndexed { page, t ->
            val lower = t.text.lowercase()
            var from = 0
            while (true) {
                val at = lower.indexOf(q, from)
                if (at < 0) break
                from = at + q.length
                // Merge the characters' boxes into one box per line.
                val lines = mutableListOf<RectF>()
                for (k in at until (at + q.length).coerceAtMost(t.boxes.size)) {
                    val b = t.boxes[k] ?: continue
                    val last = lines.lastOrNull()
                    if (last != null && b.top < last.bottom && b.bottom > last.top) last.union(b) else lines += RectF(b)
                }
                val start = (t.text.lastIndexOf('\n', at).takeIf { it >= 0 } ?: -1) + 1
                val end = t.text.indexOf('\n', at + q.length).takeIf { it >= 0 } ?: t.text.length
                val line = t.text.substring(maxOf(start, at - 40), minOf(end, at + q.length + 60)).trim()
                hits += SearchHit(page, lines, line)
            }
        }
        return hits
    }
}
