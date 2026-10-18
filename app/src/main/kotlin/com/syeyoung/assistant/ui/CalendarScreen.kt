package com.syeyoung.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.EventOccurrence
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.plainText
import com.syeyoung.assistant.data.weekEnd
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Locale
import java.util.UUID

private sealed interface Entry {
    val sortKey: String
    data class Ev(val e: EventOccurrence, override val sortKey: String) : Entry
    data class Due(val t: TaskResponse, override val sortKey: String) : Entry
}

/** A week as an agenda: each day's events and deadlines (events are edited on the web or the Mac). */
@Composable
fun CalendarScreen(onOpenTask: (UUID) -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    var weekStart by remember { mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))) }
    var events by remember { mutableStateOf<List<EventOccurrence>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val end = weekEnd(weekStart)

    LaunchedEffect(weekStart) {
        events = null
        runCatching { repo.events(weekStart.toString(), end.toString()) }.onSuccess { events = it; error = null }.onFailure { error = it.message; events = emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ weekStart = weekStart.minusWeeks(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week") }
            Text("${weekStart.format(MONTH_DAY)} – ${end.format(MONTH_DAY)}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)) }) { Text("Today") }
            IconButton({ weekStart = weekStart.plusWeeks(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week") }
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.fillMaxSize()) {
            for (i in 0..6) {
                val day = weekStart.plusDays(i.toLong())
                val dayEvents = events.orEmpty().filter { e ->
                    e.startDate?.let { s -> !LocalDate.parse(s).isAfter(day) && !LocalDate.parse(e.endDate ?: s).isBefore(day) }
                        ?: (e.start?.let { OffsetDateTime.parse(it).toLocalDate() == day } ?: false)
                }.map { Entry.Ev(it, it.start ?: "0") }
                val deadlines = state.data.tasks.filter { it.due?.date == day.toString() && it.status != TaskResponse.Status.canceled }
                    .map { Entry.Due(it, it.due!!.time?.let { t -> "${day}T$t" } ?: "1") }
                val entries = (dayEvents + deadlines).sortedBy { it.sortKey }
                item(key = day.toString()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            day.format(DAY), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            fontWeight = FontWeight.SemiBold, color = if (day == LocalDate.now()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (entries.isEmpty()) item(key = "$day-empty") { Text("—", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(entries, key = { e -> "$day:" + when (e) { is Entry.Ev -> "e:${e.e.id}:${e.e.start}"; is Entry.Due -> "d:${e.t.id}" } }) { e ->
                    when (e) {
                        is Entry.Ev -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(4.dp).height(32.dp).background(parseColor(e.e.color), RoundedCornerShape(2.dp)))
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(plainText(e.e.title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(
                                        e.e.start?.let { s -> OffsetDateTime.parse(s).format(TIME) + "–" + OffsetDateTime.parse(e.e.end!!).format(TIME) } ?: "All day",
                                        if (e.e.repeats) "repeats" else null, e.e.calendarName,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is Entry.Due -> Row(
                            Modifier.fillMaxWidth().clickable { onOpenTask(e.t.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatusIcon(e.t.status, 16.dp)
                            Column {
                                Text(plainText(e.t.title), color = if (e.t.closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                Text("Due" + (e.t.due?.time?.let { " ${it.take(5)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = if (e.t.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
