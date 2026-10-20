package com.syeyoung.assistant.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import com.syeyoung.assistant.core.Element
import com.syeyoung.assistant.core.NoteStyle
import com.syeyoung.assistant.core.RenderException
import com.syeyoung.assistant.core.Span
import com.syeyoung.assistant.core.renderMath

/**
 * The web editor's live preview, with Android's text spans: a note reads as rendered (headings
 * sized, emphasis and links styled with their marks hidden, tasks as checkboxes, math typeset)
 * except where the cursor is, which shows the Markdown as written. The structure comes from the
 * core (the same as on the Mac and the web), so what's hidden and how it's styled match.
 *
 * [density] turns the style's CSS pixels into pixels; [width] is the text's width, for what spans it.
 */
class LivePreview(
    private val style: NoteStyle,
    private val fonts: NoteCore.Fonts,
    private val density: Float,
    private val width: () -> Int,
) {
    /** Marks our spans, to take them off before styling again. */
    private interface Ours

    private val px = { css: Float -> css * density }
    private val body get() = px(style.fontSize)
    /** Typeset math by source, size and colour (typesetting is the slow part). */
    private val math = object : LinkedHashMap<String, Painted?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Painted?>) = size > 200
    }

    /** Styles [text] by [spans]; nothing is hidden or replaced where [selection] is (or anywhere, when [editing] all of it). */
    fun apply(text: Editable, spans: List<Span>, selection: IntRange?, source: Boolean) {
        for (s in text.getSpans(0, text.length, Ours::class.java)) text.removeSpan(s)
        val touches = { from: Int, to: Int -> selection != null && selection.first <= to && selection.last >= from }
        val lineStart = { at: Int -> text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (at == 0 || it < 0) 0 else it + 1 } }
        val lineEnd = { at: Int -> text.indexOf('\n', at).let { if (it < 0) text.length else it } }
        val lineTouched = { at: Int -> touches(lineStart(at), lineEnd(at)) }
        fun put(span: Ours, from: Int, to: Int, flags: Int = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) {
            if (to > from || flags == Spanned.SPAN_INCLUSIVE_INCLUSIVE) text.setSpan(span, from.coerceIn(0, text.length), to.coerceIn(0, text.length), flags)
        }
        fun hideMarks(s: Span) { if (!source) for (m in s.marks) put(Hidden(), m.from.toInt(), m.to.toInt()) }
        // A paragraph span covers whole lines.
        fun paragraph(span: Ours, from: Int, to: Int) = put(span, lineStart(from), lineEnd(maxOf(from, to - 1)).let { if (it < text.length) it + 1 else it }, Spanned.SPAN_PARAGRAPH)

        for (s in spans) {
            val from = s.range.from.toInt()
            val to = s.range.to.toInt().coerceAtMost(text.length)
            if (to <= from) continue
            val editing = source || touches(from, to)
            when (val e = s.element) {
                is Element.Heading -> {
                    val level = (e.level.toInt() - 1).coerceIn(0, 5)
                    put(Size(style.headingSizes[level]), from, to)
                    put(Weight(700), from, to)
                    paragraph(SpaceAbove(px(style.headingSpaceAbove[level] * style.headingSizes[level] * style.fontSize)), from, to)
                    if (!lineTouched(from) && !source) hideMarks(s)
                }
                Element.Emphasis -> { put(Italic(), from, to); if (!editing) hideMarks(s) }
                Element.Strong -> { put(Weight(700), from, to); if (!editing) hideMarks(s) }
                Element.Strikethrough -> { put(Strike(), from, to); put(Color(style.textTertiary), from, to); if (!editing) hideMarks(s) }
                Element.InlineCode -> { put(Code(fonts.code, style.codeSize), from, to); if (!editing) hideMarks(s) }
                is Element.Link -> { put(Link(e.url, style.accent), from, to); if (!editing) hideMarks(s) }
                is Element.Autolink -> put(Link(e.url, style.accent), from, to)
                is Element.Math -> {
                    if (editing) { put(Code(fonts.code, style.mathSourceSize), from, to); put(Color(style.accent), from, to); continue }
                    val drawing = typeset(e.tex, e.display) ?: continue
                    val firstEnd = minOf(to, lineEnd(from))
                    if (e.display) {
                        put(Math(drawing, display = true), from, firstEnd)
                        // The rest of a formula written over several lines takes no room.
                        if (firstEnd < to) {
                            put(Hidden(), firstEnd, to)
                            paragraph(Collapsed(), firstEnd + 1, to)
                        }
                    } else put(Math(drawing, display = false), from, to)
                }
                Element.Bullet -> if (!lineTouched(from) && !source) s.marks.firstOrNull()?.let { put(Bullet(style.textSecondary), it.from.toInt(), it.to.toInt()) }
                is Element.Task -> {
                    val mark = s.marks.firstOrNull() ?: continue
                    if (!touches(mark.from.toInt(), mark.to.toInt()) && !source) {
                        // "- [x]" and the space after it become the box.
                        val end = mark.to.toInt() + if (text.getOrNull(mark.to.toInt()) == ' ') 1 else 0
                        put(Checkbox(e.checked, style), mark.from.toInt(), end)
                        if (e.checked) { put(Strike(), end, lineEnd(from)); put(Color(style.textTertiary), end, lineEnd(from)) }
                    }
                }
                Element.Quote -> {
                    paragraph(QuoteBar(style), from, to)
                    put(Color(style.textSecondary), from, to)
                    if (!source) for (m in s.marks) if (!lineTouched(m.from.toInt())) put(Hidden(), m.from.toInt(), m.to.toInt())
                }
                Element.Rule -> if (!lineTouched(from) && !source) put(RuleLine(style.rule, width), from, to)
                is Element.CodeBlock -> {
                    paragraph(CodeBlock(style), from, to)
                    put(Code(fonts.code, style.codeBlockSize), from, to)
                    if (!editing) for (m in s.marks) { put(Size(style.fenceSize / style.codeBlockSize), m.from.toInt(), m.to.toInt()); put(Color(style.textTertiary), m.from.toInt(), m.to.toInt()) }
                }
                // Tables show as written, in code type, until they're drawn as tables.
                Element.Table -> { put(Code(fonts.code, style.codeBlockSize), from, to); put(Color(style.text), from, to) }
                is Element.Image -> {}
            }
        }
    }

    /** [tex] typeset at the body size in the text colour; null if it isn't valid TeX (it shows as written). */
    private fun typeset(tex: String, display: Boolean): Painted? {
        val key = "$display|$body|${style.text}|$tex"
        return math.getOrPut(key) {
            try { Painted(renderMath(tex, display, body, style.text)) } catch (e: RenderException) { null }
        }
    }

    // ---- spans ----

    /** Takes no room and draws nothing: Markdown syntax while it's not being edited. */
    private class Hidden : ReplacementSpan(), Ours {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    private class Size(val em: Float) : MetricAffectingSpan(), Ours {
        override fun updateMeasureState(p: TextPaint) { p.textSize *= em }
        override fun updateDrawState(p: TextPaint) { p.textSize *= em }
    }

    /** A weight of the text font (Pretendard has 400, 600 and 700). */
    private class Weight(val weight: Int) : MetricAffectingSpan(), Ours {
        override fun updateMeasureState(p: TextPaint) = apply(p)
        override fun updateDrawState(p: TextPaint) = apply(p)
        private fun apply(p: TextPaint) { p.typeface = Typeface.create(p.typeface, weight, p.typeface?.isItalic == true) }
    }

    /** Slanted as browsers slant a font without italics (Pretendard has none). */
    private class Italic : MetricAffectingSpan(), Ours {
        override fun updateMeasureState(p: TextPaint) { p.textSkewX = -0.2f }
        override fun updateDrawState(p: TextPaint) { p.textSkewX = -0.2f }
    }

    private class Strike : CharacterStyle(), Ours {
        override fun updateDrawState(p: TextPaint) { p.isStrikeThruText = true }
    }

    private class Color(val argb: UInt) : CharacterStyle(), Ours {
        override fun updateDrawState(p: TextPaint) { p.color = argb.toInt() }
    }

    private class Code(val typeface: Typeface, val em: Float) : MetricAffectingSpan(), Ours {
        override fun updateMeasureState(p: TextPaint) { p.typeface = typeface; p.textSize *= em }
        override fun updateDrawState(p: TextPaint) { p.typeface = typeface; p.textSize *= em }
    }

    /** A link: coloured and underlined; [url] is where it goes (a tap on it opens it). */
    class Link(val url: String, private val accent: UInt) : CharacterStyle(), Ours {
        override fun updateDrawState(p: TextPaint) { p.color = accent.toInt(); p.isUnderlineText = true }
    }

    /** Typeset math in place of its source: inline on the text's baseline, or a display formula centred on its line. */
    private inner class Math(val drawing: Painted, val display: Boolean) : ReplacementSpan(), Ours {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                val pad = if (display) (px(6f)).toInt() else 0
                it.ascent = minOf(paint.fontMetricsInt.ascent, -drawing.baseline.toInt() - pad)
                it.top = it.ascent
                it.descent = maxOf(paint.fontMetricsInt.descent, (drawing.height - drawing.baseline).toInt() + pad)
                it.bottom = it.descent
            }
            return if (display) width() else drawing.width.toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val left = if (display) x + ((width() - drawing.width) / 2f).coerceAtLeast(0f) else x
            drawing.paint(canvas, left, y - drawing.baseline)
        }
    }

    /** A list item's marker as a bullet, centred in 0.9em. */
    private class Bullet(val color: UInt) : ReplacementSpan(), Ours {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = (paint.textSize * 0.9f).toInt()
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = Paint(paint).apply { this.color = color.toInt(); typeface = Typeface.create(paint.typeface, 700, false) }
            canvas.drawText("•", x + (paint.textSize * 0.9f - p.measureText("•")) / 2f, y.toFloat(), p)
        }
    }

    /** A task's checkbox (1.05em, as on the web), checked or not; tapping it toggles the task. */
    class Checkbox(val checked: Boolean, private val style: NoteStyle) : ReplacementSpan(), Ours {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = (paint.textSize * 1.5f).toInt()
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val size = paint.textSize * 1.05f
            val box = RectF(x, y - size * 0.85f, x + size, y + size * 0.15f)
            val r = size * 0.2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (checked) {
                p.color = style.accent.toInt()
                canvas.drawRoundRect(box, r, r, p)
                p.color = style.background.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.12f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                val path = android.graphics.Path().apply {
                    moveTo(box.left + size * 0.25f, box.top + size * 0.52f)
                    lineTo(box.left + size * 0.43f, box.top + size * 0.7f)
                    lineTo(box.left + size * 0.76f, box.top + size * 0.32f)
                }
                canvas.drawPath(path, p)
            } else {
                p.color = style.textTertiary.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.08f
                canvas.drawRoundRect(box, r, r, p)
            }
        }
    }

    /** A thematic break as a line across. */
    private class RuleLine(val color: UInt, val width: () -> Int) : ReplacementSpan(), Ours {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = width()
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = Paint().apply { this.color = color.toInt(); strokeWidth = 1f * (paint.textSize / 16f).coerceAtLeast(1f) }
            val mid = (top + bottom) / 2f
            canvas.drawLine(x, mid, x + width(), mid, p)
        }
    }

    /** Space above a heading's line. */
    private class SpaceAbove(val space: Float) : LineHeightSpan, Ours {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (start == (text as Spanned).getSpanStart(this)) { fm.ascent -= space.toInt(); fm.top -= space.toInt() }
        }
    }

    /** Lines that take no room (the rest of a display formula written over several lines). */
    private class Collapsed : LineHeightSpan, Ours {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            fm.ascent = 0; fm.descent = 0; fm.top = 0; fm.bottom = 0
        }
    }

    /** A quote's bar at its left, and its text indented past it. */
    private inner class QuoteBar(val style: NoteStyle) : LeadingMarginSpan, Ours {
        override fun getLeadingMargin(first: Boolean) = (px(style.quoteBarWidth) + px(style.quoteIndent)).toInt()
        override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
            val barColor = style.rule.toInt()
            val bar = Paint().apply { color = barColor }
            c.drawRect(x.toFloat(), top.toFloat(), x + dir * px(style.quoteBarWidth), bottom.toFloat(), bar)
        }
    }

    /** A code block's background (rounded at its first and last lines) and side padding. */
    private inner class CodeBlock(val style: NoteStyle) : LineBackgroundSpan, LeadingMarginSpan, Ours {
        override fun getLeadingMargin(first: Boolean) = px(style.codePadding).toInt()
        override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {}
        override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            val spanned = text as Spanned
            val firstLine = start <= spanned.getSpanStart(this)
            val lastLine = end >= spanned.getSpanEnd(this)
            val r = px(style.codeRadius)
            val background = style.codeBackground.toInt()
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
            val rect = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            canvas.drawRoundRect(rect, if (firstLine || lastLine) r else 0f, if (firstLine || lastLine) r else 0f, bg)
            // Square off the corners that run on into the next or last line.
            if (!firstLine) canvas.drawRect(rect.left, rect.top, rect.right, rect.centerY(), bg)
            if (!lastLine) canvas.drawRect(rect.left, rect.centerY(), rect.right, rect.bottom, bg)
        }
    }
}
