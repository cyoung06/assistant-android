package com.syeyoung.assistant.notebook

import androidx.ink.brush.Brush
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke

/** What the pen does; SELECT picks images to move, resize, copy or delete. */
enum class Tool { PEN, HIGHLIGHTER, ERASER, SELECT }

/** Sizes: pen and highlighter widths in points (1/72 inch), the pen down to a hairline for small math; the eraser's reach in dp on screen. */
val SIZE_RANGES = mapOf(Tool.PEN to 0.15f..4f, Tool.HIGHLIGHTER to 3f..24f, Tool.ERASER to 4f..48f, Tool.SELECT to 1f..1f)
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

/**
 * An image placed on a page: [rect] in page points from the top-left; [bytes] as imported (PNG or
 * JPEG, kept to write back), [bitmap] decoded for the screen (at most 2048px).
 */
class NoteImage(
    val id: Long, val page: Int, val rect: android.graphics.RectF, val bytes: ByteArray, val mime: String, val bitmap: android.graphics.Bitmap,
    /** The part of the picture shown, as fractions of it (0..1); the whole picture is kept, so a crop can be undone. */
    val crop: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f),
) {
    fun with(page: Int = this.page, rect: android.graphics.RectF = this.rect, crop: android.graphics.RectF = this.crop, id: Long = this.id) =
        NoteImage(id, page, android.graphics.RectF(rect), bytes, mime, bitmap, android.graphics.RectF(crop))

    /** Where the whole (uncropped) picture would be, in page points. */
    fun fullRect(): android.graphics.RectF {
        val w = rect.width() / crop.width()
        val h = rect.height() / crop.height()
        return android.graphics.RectF(rect.left - crop.left * w, rect.top - crop.top * h, rect.left - crop.left * w + w, rect.top - crop.top * h + h)
    }

    /** The shown part of [bitmap], in its pixels. */
    fun source() = android.graphics.Rect(
        (crop.left * bitmap.width).toInt(), (crop.top * bitmap.height).toInt(),
        (crop.right * bitmap.width).toInt().coerceAtLeast(1), (crop.bottom * bitmap.height).toInt().coerceAtLeast(1),
    )

    companion object {
        /** Decodes [bytes] for the screen; null if it isn't an image Android can read. */
        fun decode(bytes: ByteArray): android.graphics.Bitmap? {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/** Undoable changes to the ink and images (page changes aren't undoable; deleting a page asks first). */
sealed interface InkChange {
    data class Added(val strokes: List<InkStroke>) : InkChange
    data class Erased(val strokes: List<InkStroke>) : InkChange
    data class ImageAdded(val image: NoteImage) : InkChange
    data class ImageRemoved(val image: NoteImage) : InkChange
    data class ImageChanged(val before: NoteImage, val after: NoteImage) : InkChange
    /** Several strokes and images at once (a selection moved, resized, deleted, duplicated or pasted): ids may repeat across the two sides. */
    data class Replaced(val removedStrokes: List<InkStroke>, val addedStrokes: List<InkStroke>, val removedImages: List<NoteImage>, val addedImages: List<NoteImage>) : InkChange
}

/** [s] moved and scaled by [m] (in page points): its inputs mapped, its brush scaled with it. */
fun transformStroke(s: InkStroke, m: android.graphics.Matrix, id: Long = s.id, page: Int = s.page): InkStroke {
    val values = FloatArray(9).also { m.getValues(it) }
    val scale = kotlin.math.sqrt(values[0] * values[0] + values[3] * values[3])
    val batch = androidx.ink.strokes.MutableStrokeInputBatch()
    val pt = FloatArray(2)
    val inputs = s.stroke.inputs
    for (i in 0 until inputs.size) {
        val p = inputs[i]
        pt[0] = p.x; pt[1] = p.y
        m.mapPoints(pt)
        batch.add(p.toolType, pt[0], pt[1], p.elapsedTimeMillis, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
    }
    val b = s.stroke.brush
    val brush = androidx.ink.brush.Brush.createWithColorIntArgb(b.family, b.colorIntArgb, b.size * scale, b.epsilon)
    return InkStroke(id, page, s.tool, androidx.ink.strokes.Stroke(brush, batch))
}

/** What Copy keeps inside the app: strokes and images (page points), to paste back as they were. */
object NoteClipboard {
    var strokes: List<InkStroke> = emptyList()
    var images: List<NoteImage> = emptyList()
    /** The system clipboard item made alongside (a picture of the same), to tell whether it's still ours. */
    var systemUri: android.net.Uri? = null
}
