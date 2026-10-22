package com.syeyoung.assistant.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/** What [NoteEditor]'s math preview shows: the math being written, typeset, or a message (a TeX error in red). */
class MathTipView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val pad = 10 * density
    private var painted: Painted? = null
    private var text: StaticLayout? = null
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * context.resources.displayMetrics.scaledDensity; typeface = android.graphics.Typeface.MONOSPACE }
    private val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun show(painted: Painted) {
        this.painted = painted
        text = null
        requestLayout(); invalidate()
    }

    fun show(message: String, error: Boolean) {
        painted = null
        words.color = if (error) 0xFFD93025.toInt() else 0xFF8A8A94.toInt()
        text = StaticLayout.Builder.obtain(message, 0, message.length, words, (320 * density).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val p = painted
        val t = text
        val w = p?.width ?: t?.let { (0 until it.lineCount).maxOfOrNull { l -> it.getLineWidth(l) } } ?: 0f
        val h = p?.height ?: t?.height?.toFloat() ?: 0f
        setMeasuredDimension((w + pad * 2).toInt(), (h + pad * 2).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        box.color = if (night) 0xFF22232A.toInt() else 0xFFFFFFFF.toInt()
        border.color = if (night) 0xFF3A3B45.toInt() else 0xFFD8D8DE.toInt()
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(r, 8 * density, 8 * density, box)
        canvas.drawRoundRect(r.apply { inset(density / 2, density / 2) }, 8 * density, 8 * density, border)
        painted?.paint(canvas, pad, pad)
        text?.let { canvas.save(); canvas.translate(pad, pad); it.draw(canvas); canvas.restore() }
    }
}
