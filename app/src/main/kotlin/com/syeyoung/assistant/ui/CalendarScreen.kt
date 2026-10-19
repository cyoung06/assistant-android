package com.syeyoung.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syeyoung.assistant.api.models.EventOccurrence
import com.syeyoung.assistant.data.CalItem
import com.syeyoung.assistant.data.Placed
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.allDayOn
import com.syeyoung.assistant.data.calItems
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.dayLayout
import com.syeyoung.assistant.data.monthDayItems
import com.syeyoung.assistant.data.monthGrid
import com.syeyoung.assistant.data.rangeStart
import com.syeyoung.assistant.data.visibleDays
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

private enum class CalMode { Week, Month }

private val HOUR = 48.dp
private val AXIS = 44.dp

/**
 * Week and month grids of events and deadlines (events are edited on the web or the Mac). The week
 * shows all seven days when there's room, three on a phone, one when very narrow; swipe to move.
 */
@Composable
fun CalendarScreen(onOpenTask: (UUID) -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    var mode by remember { mutableStateOf(CalMode.Week) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var shown by remember { mutableStateOf<CalItem?>(null) }
    val now by produceState(LocalDateTime.now()) { while (true) { delay(60_000); value = LocalDateTime.now() } }
    val today = now.toLocalDate()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val days = visibleDays(maxWidth.value)
        val narrow = maxWidth < 480.dp
        val month = YearMonth.from(anchor)
        val (from, to) = when (mode) {
            CalMode.Week -> rangeStart(anchor, days).let { it to it.plusDays(days - 1L) }
            CalMode.Month -> monthGrid(month).let { it.first() to it.last() }
        }
        var events by remember { mutableStateOf<List<EventOccurrence>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(from, to) {
            runCatching { repo.events(from.toString(), to.toString()) }.onSuccess { events = it; error = null }.onFailure { error = it.message }
        }
        val projectColors = remember(state.data.projects) { state.data.projects.associate { it.id to it.color } }
        val items = remember(events, state.data.tasks, projectColors) { calItems(events, state.data.tasks, projectColors) }
        fun move(by: Int) {
            anchor = when (mode) {
                CalMode.Week -> rangeStart(anchor, days).plusDays(by.toLong() * days)
                CalMode.Month -> month.plusMonths(by.toLong()).atDay(1)
            }
        }
        val open = { i: CalItem -> i.task?.let { onOpenTask(it.id) } ?: run { shown = i } }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mode == CalMode.Month) month.atDay(1).format(MONTH) else rangeTitle(from, to),
                    Modifier.weight(1f), style = if (narrow) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton({ move(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                TextButton({ anchor = today }) { Text("Today") }
                IconButton({ move(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
                SingleChoiceSegmentedButtonRow(Modifier.padding(start = 4.dp)) {
                    CalMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(mode == m, { mode = m }, SegmentedButtonDefaults.itemShape(i, CalMode.entries.size), icon = {}) { Text(m.name) }
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val swipe = Modifier.pointerInput(mode, days, anchor) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (abs(dx) > 64.dp.toPx()) move(if (dx < 0) 1 else -1) }) { _, d -> dx += d }
            }
            when (mode) {
                CalMode.Week -> WeekGrid((0 until days).map { from.plusDays(it.toLong()) }, items, now, open, swipe)
                CalMode.Month -> MonthGrid(month, items, today, { anchor = it; mode = CalMode.Week }, swipe)
            }
        }
    }
    shown?.let { EventInfo(it) { shown = null } }
}

@Composable
private fun WeekGrid(dates: List<LocalDate>, items: List<CalItem>, now: LocalDateTime, onItem: (CalItem) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HOUR * 8).roundToPx() }) }
    val line = MaterialTheme.colorScheme.outlineVariant
    var allDayOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().then(modifier)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Spacer(Modifier.width(AXIS))
            dates.forEach { d -> DayHeader(d, d == now.toLocalDate(), Modifier.weight(1f)) }
        }
        val allDay = dates.map { allDayOn(items, it) }
        if (allDay.any { it.isNotEmpty() }) {
            val limit = if (allDayOpen) Int.MAX_VALUE else 3
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("all-day", Modifier.width(AXIS).padding(top = 2.dp, end = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                allDay.forEach { list ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        list.take(limit).forEach { Chip(it, Modifier.fillMaxWidth().clickable { onItem(it) }) }
                        if (list.size > limit) Text(
                            "+${list.size - limit}", Modifier.clickable { allDayOpen = true }.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.width(AXIS).height(HOUR * 24)) {
                for (h in 1..23) Text(
                    "%02d:00".format(h), Modifier.align(Alignment.TopEnd).offset(y = HOUR * h - 7.dp).padding(end = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            dates.forEach { d ->
                val placed = remember(items, d) { dayLayout(items, d) }
                BoxWithConstraints(
                    Modifier.weight(1f).height(HOUR * 24).drawBehind {
                        drawLine(line, Offset(0f, 0f), Offset(0f, size.height))
                        for (h in 1..23) HOUR.toPx().let { y -> drawLine(line, Offset(0f, y * h), Offset(size.width, y * h)) }
                    },
                ) {
                    placed.forEach { p -> Block(p, maxWidth, Modifier.clickable { onItem(p.item) }) }
                    if (d == now.toLocalDate()) {
                        val red = MaterialTheme.colorScheme.error
                        Box(Modifier.offset(y = HOUR * (now.hour * 60 + now.minute) / 60f - 1.dp).fillMaxWidth().height(2.dp).background(red))
                        Box(Modifier.offset(x = (-4).dp, y = HOUR * (now.hour * 60 + now.minute) / 60f - 4.dp).size(8.dp).background(red, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, isToday: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(day.format(WEEKDAY), style = MaterialTheme.typography.labelSmall, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.size(28.dp).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("${day.dayOfMonth}", fontWeight = FontWeight.SemiBold, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** A timed item in the week grid; deadlines are short markers with the task's status. */
@Composable
private fun Block(p: Placed, dayWidth: Dp, modifier: Modifier) {
    val colWidth = dayWidth / p.cols
    val height = HOUR * (p.endMin - p.startMin) / 60f
    val color = parseColor(p.item.color, MaterialTheme.colorScheme.primary)
    val task = p.item.task
    Box(
        Modifier.offset(x = colWidth * p.col + 1.dp, y = HOUR * p.startMin / 60f + 1.dp)
            .width(colWidth - 2.dp).height(height - 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (task != null) 0.14f else 0.24f))
            .drawBehind { drawRect(color, size = size.copy(width = 3.dp.toPx())) }
            .then(modifier)
            .padding(start = 6.dp, end = 2.dp, top = 1.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (task != null) StatusIcon(task.status, 10.dp)
                Text(
                    p.item.title, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = if (height < 36.dp) 1 else 3, overflow = TextOverflow.Ellipsis,
                    color = if (task?.closed == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (height >= 44.dp) Text(timeRange(p.item), fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** An all-day item, or any item in the month grid. */
@Composable
private fun Chip(item: CalItem, modifier: Modifier) {
    val color = parseColor(item.color, MaterialTheme.colorScheme.primary)
    Row(
        modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = if (item.allDay && item.task == null) 0.3f else 0.12f)).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (item.task != null) StatusIcon(item.task.status, 9.dp)
        else if (!item.allDay) Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(item.title, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MonthGrid(month: YearMonth, items: List<CalItem>, today: LocalDate, onDay: (LocalDate) -> Unit, modifier: Modifier) {
    val grid = monthGrid(month)
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize().then(modifier)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            grid.take(7).forEach { d ->
                Text(d.format(WEEKDAY), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                week.forEach { d ->
                    val dayItems = remember(items, d) { monthDayItems(items, d) }
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxHeight()
                            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f)); drawLine(line, Offset(0f, 0f), Offset(0f, size.height)) }
                            .clickable { onDay(d) }.padding(2.dp),
                    ) {
                        // Day number row is ~20dp; each chip ~16dp.
                        val fits = ((maxHeight - 20.dp) / 16.dp).toInt().coerceAtLeast(0)
                        val shown = if (dayItems.size > fits) (fits - 1).coerceAtLeast(0) else dayItems.size
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(
                                Modifier.align(Alignment.CenterHorizontally).size(20.dp)
                                    .background(if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${d.dayOfMonth}", fontSize = 11.sp,
                                    color = when {
                                        d == today -> MaterialTheme.colorScheme.onPrimary
                                        d.month != month.month -> MaterialTheme.colorScheme.outline
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                            dayItems.take(shown).forEach { Chip(it, Modifier.fillMaxWidth()) }
                            if (dayItems.size > shown) Text("+${dayItems.size - shown}", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventInfo(item: CalItem, onDismiss: () -> Unit) {
    val e = item.event ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text(item.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.start.format(DAY) + if (item.allDay) "" else " · " + timeRange(item))
                Text(listOfNotNull(e.calendarName, if (e.repeats) "repeats" else null).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

private fun timeRange(i: CalItem) = if (i.task != null) "Due " + i.task.due?.time?.take(5) else i.start.format(TIME) + "–" + i.end.format(TIME)

private fun rangeTitle(from: LocalDate, to: LocalDate) = when {
    from == to -> from.format(DAY)
    from.month == to.month -> "${from.format(MONTH_DAY)} – ${to.dayOfMonth}"
    else -> "${from.format(MONTH_DAY)} – ${to.format(MONTH_DAY)}"
}

private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
