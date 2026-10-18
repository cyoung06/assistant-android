package com.syeyoung.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.syeyoung.assistant.api.models.TaskResponse.Priority
import com.syeyoung.assistant.api.models.TaskResponse.Status

/** Linear-style status circle, like the web app's. */
@Composable
fun StatusIcon(status: Status, size: Dp = 18.dp) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2 * 0.8f
        val w = this.size.minDimension * 0.11f
        when (status) {
            Status.backlog -> drawCircle(muted, r, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 1.6f, w * 1.2f))))
            Status.in_progress -> {
                drawCircle(Warn, r, style = Stroke(w))
                drawArc(Warn, -90f, 180f, true, topLeft = Offset(center.x - r / 2, center.y - r / 2), size = Size(r, r))
            }
            Status.done, Status.canceled -> {
                drawCircle(if (status == Status.done) accent else muted, r * 1.1f)
                if (status == Status.done) {
                    drawLine(surface, Offset(center.x - r * .45f, center.y), Offset(center.x - r * .1f, center.y + r * .35f), w * 1.2f, StrokeCap.Round)
                    drawLine(surface, Offset(center.x - r * .1f, center.y + r * .35f), Offset(center.x + r * .5f, center.y - r * .35f), w * 1.2f, StrokeCap.Round)
                } else {
                    drawLine(surface, Offset(center.x - r * .4f, center.y - r * .4f), Offset(center.x + r * .4f, center.y + r * .4f), w * 1.2f, StrokeCap.Round)
                    drawLine(surface, Offset(center.x + r * .4f, center.y - r * .4f), Offset(center.x - r * .4f, center.y + r * .4f), w * 1.2f, StrokeCap.Round)
                }
            }
            else -> drawCircle(muted, r, style = Stroke(w))
        }
    }
}

@Composable
fun PriorityIcon(priority: Priority, size: Dp = 16.dp) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val danger = MaterialTheme.colorScheme.error
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        when (priority) {
            Priority.urgent -> {
                drawRoundRect(danger, cornerRadius = CornerRadius(s * .2f))
                drawLine(Color.White, Offset(s / 2, s * .25f), Offset(s / 2, s * .58f), s * .13f, StrokeCap.Round)
                drawCircle(Color.White, s * .07f, Offset(s / 2, s * .75f))
            }
            Priority.high, Priority.medium, Priority.low -> {
                val filled = when (priority) { Priority.high -> 3; Priority.medium -> 2; else -> 1 }
                for (i in 0 until 3) {
                    val h = s * (0.3f + i * 0.25f)
                    drawRoundRect(muted.copy(alpha = if (i < filled) 1f else .3f), Offset(s * (.08f + i * .32f), s - h), Size(s * .22f, h), CornerRadius(s * .05f))
                }
            }
            else -> for (i in 0 until 3) drawRoundRect(muted.copy(alpha = .6f), Offset(s * (.08f + i * .32f), s * .45f), Size(s * .2f, s * .1f))
        }
    }
}
