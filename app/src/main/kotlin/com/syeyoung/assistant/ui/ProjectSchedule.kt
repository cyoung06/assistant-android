package com.syeyoung.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.ProjectSchedule
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.plainText
import com.syeyoung.assistant.data.scheduleLine
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * A project's calendar without the clutter: its repeating events (lectures) as one line each, and
 * only what's coming up once: exams, meetings, and lectures that won't happen.
 */
@Composable
fun ProjectScheduleHeader(projectId: UUID, projectName: String) {
    val repo = Repo.get(LocalContext.current)
    var schedule by remember(projectId) { mutableStateOf<ProjectSchedule?>(null) }
    LaunchedEffect(projectId) { schedule = runCatching { repo.projectSchedule(projectId) }.getOrNull() }
    val s = schedule ?: return
    val zone = ZoneId.systemDefault()
    val items = s.upcoming.map { o ->
        val start = o.start?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone) }
        Item(start?.toLocalDate() ?: LocalDate.parse(o.startDate!!), start?.toLocalTime()?.format(TIME).orEmpty(), plainText(o.title), false)
    } + s.skipped.map { k -> Item(LocalDate.parse(k.date), k.start?.take(5).orEmpty(), plainText(k.title) + " · skipped", true) }
    if (s.recurring.isEmpty() && items.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        s.recurring.forEach { r ->
            val title = plainText(r.title)
            Text((if (title == projectName) "" else "$title · ") + scheduleLine(r), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.sortedWith(compareBy({ it.date }, { it.time })).forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(i.date.format(DAY), Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(i.time, Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    i.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (i.skipped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private data class Item(val date: LocalDate, val time: String, val title: String, val skipped: Boolean)

private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
