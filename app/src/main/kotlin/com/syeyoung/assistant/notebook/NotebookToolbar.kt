package com.syeyoung.assistant.notebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The screen edge the toolbar sits on; on the left or right it runs vertically. */
enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

private val BAR = 32.dp
private val ITEM = 28.dp

/**
 * A thin floating toolbar: drag it by its grip to any edge (it snaps there and turns to fit), tap
 * the grip to fold it to a small handle. Pen, highlighter, eraser; colours and sizes for the
 * current tool; undo and redo; the page number (tap to jump); add a page; more.
 */
@Composable
fun NotebookToolbar(
    ui: ToolbarState,
    onChange: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onJump: () -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val margin = with(density) { 10.dp.toPx() }
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        var drag by remember { mutableStateOf<Offset?>(null) }

        // Where it rests: on its edge, at a fraction along it.
        val rest = when (ui.edge) {
            Edge.TOP -> Offset((screenW - barSize.width) * ui.along, margin)
            Edge.BOTTOM -> Offset((screenW - barSize.width) * ui.along, screenH - barSize.height - margin)
            Edge.LEFT -> Offset(margin, (screenH - barSize.height) * ui.along)
            Edge.RIGHT -> Offset(screenW - barSize.width - margin, (screenH - barSize.height) * ui.along)
        }
        val at = drag ?: rest
        val vertical = ui.edge == Edge.LEFT || ui.edge == Edge.RIGHT

        Surface(
            Modifier
                .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                .onSizeChanged { barSize = it },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 3.dp,
            tonalElevation = 2.dp,
        ) {
            val grip: @Composable () -> Unit = {
                Box(
                    Modifier
                        .size(ITEM)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { drag = at },
                                onDrag = { change, amount -> change.consume(); drag = (drag ?: at) + amount },
                                onDragEnd = {
                                    val p = drag ?: return@detectDragGestures
                                    val cx = p.x + barSize.width / 2f
                                    val cy = p.y + barSize.height / 2f
                                    // Snap to the nearest edge.
                                    val distances = mapOf(Edge.TOP to cy, Edge.BOTTOM to screenH - cy, Edge.LEFT to cx, Edge.RIGHT to screenW - cx)
                                    ui.edge = distances.minBy { it.value }.key
                                    ui.along = if (ui.edge == Edge.TOP || ui.edge == Edge.BOTTOM) (cx / screenW).coerceIn(0f, 1f) else (cy / screenH).coerceIn(0f, 1f)
                                    drag = null
                                    onChange()
                                },
                                onDragCancel = { drag = null },
                            )
                        }
                        .clickable { ui.collapsed = !ui.collapsed; onChange() },
                    contentAlignment = Alignment.Center,
                ) { Grip(vertical) }
            }
            val content: @Composable () -> Unit = {
                grip()
                if (ui.collapsed) {
                    Dot(currentColor(ui), 10.dp, selected = false) { ui.collapsed = false; onChange() }
                } else {
                    Tools(ui, onChange)
                    Divider(vertical)
                    Swatches(ui, onChange)
                    Divider(vertical)
                    Sizes(ui, onChange)
                    Divider(vertical)
                    IconItem(Icons.AutoMirrored.Filled.Undo, "Undo", ui.canUndo, onUndo)
                    IconItem(Icons.AutoMirrored.Filled.Redo, "Redo", ui.canRedo, onRedo)
                    Divider(vertical)
                    Text(
                        if (ui.pages == 0) "…" else "${ui.page + 1}/${ui.pages}",
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onJump).padding(horizontal = 6.dp, vertical = 4.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconItem(Icons.Filled.Add, "Add a page after this one", true, onAddPage)
                    if (ui.status.isNotEmpty()) Text(ui.status, Modifier.padding(horizontal = 4.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    More(ui, onChange, onDeletePage, onClose)
                }
            }
            if (vertical) Column(Modifier.padding(vertical = 2.dp).width(BAR), horizontalAlignment = Alignment.CenterHorizontally) { content() }
            else Row(Modifier.padding(horizontal = 2.dp).height(BAR), verticalAlignment = Alignment.CenterVertically) { content() }
        }
    }
}

private fun currentColor(ui: ToolbarState) = Color(if (ui.tool == Tool.HIGHLIGHTER) HIGHLIGHTER_COLORS[ui.highlighterColor] else PEN_COLORS[ui.penColor])

@Composable
private fun Tools(ui: ToolbarState, onChange: () -> Unit) {
    for (t in Tool.entries) {
        val selected = ui.tool == t
        Box(
            Modifier.size(ITEM).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable { ui.tool = t; onChange() },
            contentAlignment = Alignment.Center,
        ) { ToolGlyph(t, if (t == Tool.ERASER) MaterialTheme.colorScheme.onSurface else currentColorFor(ui, t)) }
    }
}

private fun currentColorFor(ui: ToolbarState, t: Tool) =
    Color(if (t == Tool.HIGHLIGHTER) HIGHLIGHTER_COLORS[ui.highlighterColor] or (0xFF shl 24) else PEN_COLORS[ui.penColor])

/** Small drawn glyphs: a nib, a marker, an eraser. */
@Composable
private fun ToolGlyph(t: Tool, color: Color) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        when (t) {
            Tool.PEN -> {
                drawLine(outline, Offset(w * 0.2f, w * 0.8f), Offset(w * 0.8f, w * 0.2f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
                drawCircle(color, w * 0.12f, Offset(w * 0.2f, w * 0.8f))
            }
            Tool.HIGHLIGHTER -> drawLine(color, Offset(w * 0.2f, w * 0.8f), Offset(w * 0.8f, w * 0.2f), strokeWidth = w * 0.32f, cap = StrokeCap.Square)
            Tool.ERASER -> {
                drawRoundRect(outline, topLeft = Offset(w * 0.15f, w * 0.35f), size = androidx.compose.ui.geometry.Size(w * 0.7f, w * 0.34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.1f))
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
        Dot(Color(if (hl) c or (0xFF shl 24) else c), 14.dp, selected = i == (if (hl) ui.highlighterColor else ui.penColor)) {
            if (hl) ui.highlighterColor = i else ui.penColor = i
            onChange()
        }
    }
}

@Composable
private fun Sizes(ui: ToolbarState, onChange: () -> Unit) {
    if (ui.tool == Tool.ERASER) return
    val hl = ui.tool == Tool.HIGHLIGHTER
    val count = if (hl) HIGHLIGHTER_SIZES.size else PEN_SIZES.size
    val ink = MaterialTheme.colorScheme.onSurface
    repeat(count) { i ->
        val selected = i == (if (hl) ui.highlighterSize else ui.penSize)
        Box(
            Modifier.size(width = 22.dp, height = ITEM).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable { if (hl) ui.highlighterSize = i else ui.penSize = i; onChange() },
            contentAlignment = Alignment.Center,
        ) {
            // A dot as big as the line it draws, relatively: hairline to thick.
            Box(Modifier.size((2 + i * (if (hl) 4 else 2)).dp).clip(CircleShape).background(ink))
        }
    }
}

@Composable
private fun More(ui: ToolbarState, onChange: () -> Unit, onDeletePage: () -> Unit, onClose: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconItem(Icons.Filled.MoreVert, "More", true) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                { Text(if (ui.mode == ScrollMode.PAGED) "Scroll continuously" else "Turn page by page") },
                { ui.mode = if (ui.mode == ScrollMode.PAGED) ScrollMode.CONTINUOUS else ScrollMode.PAGED; open = false; onChange() },
            )
            DropdownMenuItem({ Text("Delete this page") }, { open = false; onDeletePage() }, enabled = ui.pages > 1)
            DropdownMenuItem({ Text("Close") }, { open = false; onClose() })
        }
    }
}

@Composable
private fun IconItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(ITEM).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun Dot(color: Color, size: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(width = 22.dp, height = ITEM).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
        )
    }
}

@Composable
private fun Divider(vertical: Boolean) {
    val c = MaterialTheme.colorScheme.outlineVariant
    if (vertical) Spacer(Modifier.padding(vertical = 3.dp).size(width = 16.dp, height = 1.dp).background(c))
    else Spacer(Modifier.padding(horizontal = 3.dp).size(width = 1.dp, height = 16.dp).background(c))
}

@Composable
private fun Grip(vertical: Boolean) {
    val c = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        for (a in 0..1) for (b in 0..2) {
            val x = w * (0.3f + 0.4f * a)
            val y = w * (0.2f + 0.3f * b)
            drawCircle(c, w * 0.08f, if (vertical) Offset(y, x) else Offset(x, y))
        }
    }
}

