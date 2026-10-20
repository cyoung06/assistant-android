package com.syeyoung.assistant.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import com.syeyoung.assistant.core.Drawing
import com.syeyoung.assistant.core.registerFont

/**
 * What notes are drawn with, shared with the Mac and the web through the core: its fonts (text in
 * Pretendard, code in JetBrains Mono, from the core's fonts/), registered with the core once so math
 * and diagrams are set in them too.
 */
object NoteCore {
    @Volatile private var fonts: Fonts? = null

    class Fonts(val text: Typeface, val code: Typeface)

    /** The fonts (registered with the core the first time). Reads font files: not on the main thread. */
    fun fonts(context: Context): Fonts = fonts ?: synchronized(this) {
        fonts ?: run {
            val assets = context.applicationContext.assets
            val text = listOf("Pretendard-Regular.otf" to 400, "Pretendard-SemiBold.otf" to 600, "Pretendard-Bold.otf" to 700)
            // The core sets text in the first font registered: Pretendard's regular.
            for ((file, _) in text) registerFont(assets.open(file).use { it.readBytes() })
            registerFont(assets.open("JetBrainsMono-Regular.ttf").use { it.readBytes() })
            val family = text.drop(1).fold(FontFamily.Builder(Font.Builder(assets, text[0].first).setWeight(text[0].second).build())) { b, (file, weight) ->
                b.addFont(Font.Builder(assets, file).setWeight(weight).build())
            }.build()
            Fonts(
                text = Typeface.CustomFallbackBuilder(family).build(),
                // Hangul in code falls back to Pretendard, as on the web.
                code = Typeface.CustomFallbackBuilder(FontFamily.Builder(Font.Builder(assets, "JetBrainsMono-Regular.ttf").build()).build()).addCustomFallback(family).build(),
            ).also { fonts = it }
        }
    }
}

/** A drawing from the core, ready to paint: its shapes as paths. */
class Painted(val drawing: Drawing) {
    private val shapes: List<Pair<Path, Paint>> = drawing.shapes.map { s ->
        val path = Path()
        var i = 0
        for (verb in s.verbs) {
            val p = s.points
            when (verb.toInt()) {
                0 -> { path.moveTo(p[i], p[i + 1]); i += 2 }
                1 -> { path.lineTo(p[i], p[i + 1]); i += 2 }
                2 -> { path.quadTo(p[i], p[i + 1], p[i + 2], p[i + 3]); i += 4 }
                3 -> { path.cubicTo(p[i], p[i + 1], p[i + 2], p[i + 3], p[i + 4], p[i + 5]); i += 6 }
                4 -> path.close()
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color.toInt()
            if (s.strokeWidth > 0f) { style = Paint.Style.STROKE; strokeWidth = s.strokeWidth } else style = Paint.Style.FILL
        }
        path to paint
    }

    val width get() = drawing.width
    val height get() = drawing.height
    val baseline get() = drawing.baseline

    /** Paints it with its top-left at [x], [y], [scale] times its size. */
    fun paint(canvas: Canvas, x: Float, y: Float, scale: Float = 1f) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        for ((path, paint) in shapes) canvas.drawPath(path, paint)
        canvas.restore()
    }
}
