package com.syeyoung.assistant.notebook

import androidx.ink.brush.Brush
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke

/** What the pen does. */
enum class Tool { PEN, HIGHLIGHTER, ERASER }

/** Sizes: pen and highlighter widths in points (1/72 inch), the pen down to a hairline for small math; the eraser's reach in dp on screen. */
val SIZE_RANGES = mapOf(Tool.PEN to 0.15f..4f, Tool.HIGHLIGHTER to 3f..24f, Tool.ERASER to 4f..48f)
val PEN_COLORS = listOf(0xFF1B1B1F.toInt(), 0xFF1E5BD8.toInt(), 0xFFD93025.toInt(), 0xFF188038.toInt())
val HIGHLIGHTER_COLORS = listOf(0x66FFD600, 0x6634C759, 0x66FF7EB6, 0x6640A9FF)

/** Strokes are in page coordinates: PDF points from the page's top-left corner. */
fun brushFor(tool: Tool, color: Int, size: Float): Brush = when (tool) {
    // DISCARD keeps a stroke's own overlaps from darkening, so it looks the same once saved into the PDF.
    Tool.HIGHLIGHTER -> Brush.createWithColorIntArgb(StockBrushes.highlighter(SelfOverlap.DISCARD), color, size, 0.05f)
    else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), color, size, 0.02f)
}

object InkIds {
    private val counter = java.util.concurrent.atomic.AtomicLong()
    fun next() = counter.incrementAndGet()
}

/** A finished stroke on a page; [id] is stable for undo. */
class InkStroke(val id: Long, val page: Int, val tool: Tool, val stroke: Stroke) {
    fun onPage(page: Int) = InkStroke(id, page, tool, stroke)

    /** Whether a circle of [radius] at ([x], [y]) touches the stroke's centre line. */
    fun hits(x: Float, y: Float, radius: Float): Boolean {
        val box = stroke.shape.computeBoundingBox() ?: return false
        val pad = radius + stroke.brush.size
        if (x < box.xMin - pad || x > box.xMax + pad || y < box.yMin - pad || y > box.yMax + pad) return false
        val inputs = stroke.inputs
        val reach = radius + stroke.brush.size / 2
        var px = Float.NaN
        var py = Float.NaN
        for (i in 0 until inputs.size) {
            val p = inputs[i]
            if (!px.isNaN() && segmentDistance(x, y, px, py, p.x, p.y) <= reach) return true
            if (px.isNaN() && (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y) <= reach * reach) return true
            px = p.x
            py = p.y
        }
        return false
    }
}

private fun segmentDistance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len = dx * dx + dy * dy
    val t = if (len == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len).coerceIn(0f, 1f)
    val cx = ax + t * dx - x
    val cy = ay + t * dy - y
    return kotlin.math.sqrt(cx * cx + cy * cy)
}

/** Undoable changes to the ink (page changes aren't undoable; deleting a page asks first). */
sealed interface InkChange {
    data class Added(val strokes: List<InkStroke>) : InkChange
    data class Erased(val strokes: List<InkStroke>) : InkChange
}
