package com.syeyoung.assistant.notebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** The screen edge the toolbar sits on; on the left or right it runs vertically. */
enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

private val BAR = 42.dp
private val ITEM = 36.dp

/**
 * A slim floating toolbar: drag it by its grip to any edge (it snaps there and turns to fit), tap
 * the grip to fold it to a small handle. Pen, highlighter, eraser (tap the one in use for its size,
 * as a slider); colours for the current tool; undo and redo. (Pages and the rest are in the header.)
 */
@Composable
fun NotebookToolbar(ui: ToolbarState, onChange: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val margin = with(density) { 10.dp.toPx() }
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        var drag by remember { mutableStateOf<Offset?>(null) }

        // Where it rests (on its edge, a fraction along it), kept fully on screen whatever its size.
        fun onScreen(p: Offset) = Offset(
            p.x.coerceIn(margin, (screenW - barSize.width - margin).coerceAtLeast(margin)),
            p.y.coerceIn(margin, (screenH - barSize.height - margin).coerceAtLeast(margin)),
        )
        val rest = onScreen(
            when (ui.edge) {
                Edge.TOP -> Offset((screenW - barSize.width) * ui.along, margin)
                Edge.BOTTOM -> Offset((screenW - barSize.width) * ui.along, screenH - barSize.height - margin)
                Edge.LEFT -> Offset(margin, (screenH - barSize.height) * ui.along)
                Edge.RIGHT -> Offset(screenW - barSize.width - margin, (screenH - barSize.height) * ui.along)
            },
        )
        val at = drag ?: rest
        val vertical = ui.edge == Edge.LEFT || ui.edge == Edge.RIGHT
        // The drag handler below is set up once; it reads where the bar is now through this
        // (reading `at` directly kept the spot from when it was set up, so a drag jumped back there).
        val current by rememberUpdatedState(at)

        Surface(
            Modifier
                .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                .onSizeChanged { barSize = it }
                // Touches anywhere on the bar (between buttons too) stay here: none reach the page.
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); while (awaitPointerEvent().changes.any { it.pressed }) Unit } },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            shadowElevation = 4.dp,
            tonalElevation = 2.dp,
        ) {
            val content: @Composable () -> Unit = {
                Box(
                    Modifier
                        .size(ITEM)
                        .pointerInput(screenW, screenH, barSize) {
                            detectDragGestures(
                                onDragStart = { drag = current },
                                onDrag = { change, amount -> change.consume(); drag = onScreen((drag ?: current) + amount) },
                                onDragEnd = {
                                    val p = drag ?: return@detectDragGestures
                                    val cx = p.x + barSize.width / 2f
                                    val cy = p.y + barSize.height / 2f
                                    // Snap to the nearest edge.
                                    val distances = mapOf(Edge.TOP to cy, Edge.BOTTOM to screenH - cy, Edge.LEFT to cx, Edge.RIGHT to screenW - cx)
                                    ui.edge = distances.minBy { it.value }.key
                                    ui.along = if (ui.edge == Edge.TOP || ui.edge == Edge.BOTTOM) {
                                        (p.x / (screenW - barSize.width).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    } else {
                                        (p.y / (screenH - barSize.height).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    }
                                    drag = null
                                    onChange()
                                },
                                onDragCancel = { drag = null },
                            )
                        }
                        .clickable { ui.collapsed = !ui.collapsed; onChange() },
                    contentAlignment = Alignment.Center,
                ) { Grip(vertical) }
                if (ui.collapsed) {
                    Dot(currentColor(ui), 14.dp, selected = false) { ui.collapsed = false; onChange() }
                } else {
                    Tools(ui, onChange)
                    Divider(vertical)
                    Swatches(ui, onChange)
                    Divider(vertical)
                    IconItem(Icons.AutoMirrored.Filled.Undo, "Undo", ui.canUndo, onUndo)
                    IconItem(Icons.AutoMirrored.Filled.Redo, "Redo", ui.canRedo, onRedo)
                }
            }
            if (vertical) Column(Modifier.padding(vertical = 3.dp).width(BAR), horizontalAlignment = Alignment.CenterHorizontally) { content() }
            else Row(Modifier.padding(horizontal = 3.dp).height(BAR), verticalAlignment = Alignment.CenterVertically) { content() }
        }
    }
}

private fun opaque(c: Int) = c or (0xFF shl 24)
private fun currentColor(ui: ToolbarState) = Color(if (ui.tool == Tool.HIGHLIGHTER) opaque(HIGHLIGHTER_COLORS[ui.highlighterColor]) else PEN_COLORS[ui.penColor])

/** The tools; tapping the one already in use opens its size. */
@Composable
private fun Tools(ui: ToolbarState, onChange: () -> Unit) {
    for (t in Tool.entries) {
        val selected = ui.tool == t
        var sizing by remember { mutableStateOf(false) }
        Box {
            Box(
                Modifier.size(ITEM).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { if (selected) sizing = true else { ui.tool = t; onChange() } },
                contentAlignment = Alignment.Center,
            ) { ToolGlyph(t, if (t == Tool.ERASER) MaterialTheme.colorScheme.onSurface else colorFor(ui, t)) }
            DropdownMenu(sizing, { sizing = false }) { SizePanel(ui, t, onChange) }
        }
    }
}

private fun colorFor(ui: ToolbarState, t: Tool) = Color(if (t == Tool.HIGHLIGHTER) opaque(HIGHLIGHTER_COLORS[ui.highlighterColor]) else PEN_COLORS[ui.penColor])

/** A size slider (logarithmic, so the fine end has room) with a preview of the line it draws. */
@Composable
private fun SizePanel(ui: ToolbarState, t: Tool, onChange: () -> Unit) {
    val range = SIZE_RANGES.getValue(t)
    val value = when (t) { Tool.PEN -> ui.penWidth; Tool.HIGHLIGHTER -> ui.highlighterWidth; Tool.ERASER -> ui.eraserRadius }
    fun toSlider(v: Float) = (ln(v / range.start) / ln(range.endInclusive / range.start)).coerceIn(0f, 1f)
    fun fromSlider(s: Float) = range.start * (range.endInclusive / range.start).pow(s)
    Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (t) { Tool.PEN -> "Pen"; Tool.HIGHLIGHTER -> "Highlighter"; Tool.ERASER -> "Eraser" }, Modifier.weight(1f), fontSize = 14.sp)
            Text(if (t == Tool.ERASER) "${value.roundToInt()} dp" else String.format(Locale.US, "%.2f pt", value), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Roughly how it looks on the page at normal zoom, on white like the page.
        val preview = colorFor(ui, t)
        Canvas(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
            if (t == Tool.ERASER) {
                drawCircle(Color.Gray, value.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            } else {
                val px = (value * 1.6f * density).coerceAtLeast(1f)
                drawLine(preview.copy(alpha = if (t == Tool.HIGHLIGHTER) 0.45f else 1f), Offset(8.dp.toPx(), center.y), Offset(size.width - 8.dp.toPx(), center.y), strokeWidth = px, cap = StrokeCap.Round)
            }
        }
        Slider(toSlider(value), { s ->
            val v = fromSlider(s)
            when (t) { Tool.PEN -> ui.penWidth = v; Tool.HIGHLIGHTER -> ui.highlighterWidth = v; Tool.ERASER -> ui.eraserRadius = v }
            onChange()
        })
    }
}

/** Small drawn glyphs: a nib, a marker, an eraser. */
@Composable
private fun ToolGlyph(t: Tool, color: Color) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(21.dp)) {
        val w = size.width
        when (t) {
            Tool.PEN -> {
                drawLine(outline, Offset(w * 0.2f, w * 0.8f), Offset(w * 0.8f, w * 0.2f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
                drawCircle(color, w * 0.12f, Offset(w * 0.2f, w * 0.8f))
            }
            Tool.HIGHLIGHTER -> drawLine(color, Offset(w * 0.2f, w * 0.8f), Offset(w * 0.8f, w * 0.2f), strokeWidth = w * 0.32f, cap = StrokeCap.Square)
            Tool.ERASER -> {
                drawRoundRect(outline, topLeft = Offset(w * 0.15f, w * 0.35f), size = Size(w * 0.7f, w * 0.34f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(w * 0.1f))
                drawLine(outline, Offset(w * 0.45f, w * 0.35f), Offset(w * 0.45f, w * 0.69f), strokeWidth = w * 0.1f)
            }
        }
    }
}

@Composable
private fun Swatches(ui: ToolbarState, onChange: () -> Unit) {
    if (ui.tool == Tool.ERASER) return
    val hl = ui.tool == Tool.HIGHLIGHTER
    val colors = if (hl) HIGHLIGHTER_COLORS else PEN_COLORS
    colors.forEachIndexed { i, c ->
        Dot(Color(if (hl) opaque(c) else c), 18.dp, selected = i == (if (hl) ui.highlighterColor else ui.penColor)) {
            if (hl) ui.highlighterColor = i else ui.penColor = i
            onChange()
        }
    }
}

@Composable
private fun IconItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(ITEM).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun Dot(color: Color, size: Dp, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(width = 29.dp, height = ITEM).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
        )
    }
}

@Composable
private fun Divider(vertical: Boolean) {
    val c = MaterialTheme.colorScheme.outlineVariant
    if (vertical) Spacer(Modifier.padding(vertical = 4.dp).size(width = 21.dp, height = 1.dp).background(c))
    else Spacer(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 21.dp).background(c))
}

@Composable
private fun Grip(vertical: Boolean) {
    val c = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        for (a in 0..1) for (b in 0..2) {
            val x = w * (0.3f + 0.4f * a)
            val y = w * (0.2f + 0.3f * b)
            drawCircle(c, w * 0.08f, if (vertical) Offset(y, x) else Offset(x, y))
        }
    }
}
