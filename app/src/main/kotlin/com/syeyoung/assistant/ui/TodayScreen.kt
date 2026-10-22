package com.syeyoung.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.EventOccurrence
import com.syeyoung.assistant.api.models.Today
import com.syeyoung.assistant.data.EventTarget
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.dueText
import com.syeyoung.assistant.data.value
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private fun local(s: String) = OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault())

/** "12:30–13:45", or "All day". */
private fun whenOf(e: EventOccurrence): String {
    val s = e.start ?: return "All day"
    val end = e.end ?: return "All day"
    return "${local(s).format(TIME)}–${local(end).format(TIME)}"
}

/** "Now, until 13:45", "In 25 min", or the day and time. */
private fun nextWhen(e: EventOccurrence): String {
    val start = local(e.start ?: return "All day")
    val end = local(e.end ?: return "All day")
    val now = OffsetDateTime.now().atZoneSameInstant(ZoneId.systemDefault())
    if (!start.isAfter(now)) return "Now, until ${end.format(TIME)}"
    val minutes = Duration.between(now, start).toMinutes()
    if (minutes < 60) return "In $minutes min"
    val day = if (start.toLocalDate() == now.toLocalDate()) "Today" else start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return "$day ${whenOf(e)}"
}

/** One screen of the day: the next class (room, newest files), what's due soon, today's events, Triage. */
@Composable
fun TodayScreen(onOpenTask: (UUID) -> Unit, onOpenEvent: (EventTarget) -> Unit, onTriage: () -> Unit, onOpenFile: (String) -> Unit) {
    val context = LocalContext.current
    var today by remember { mutableStateOf<Today?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Kept fresh while it's shown: the next class moves on, tasks get done.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Repo.get(context).api().today.today().value() }.onSuccess { today = it; error = null }.onFailure { error = it.message }
            delay(60_000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val t = today
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text("Couldn't load today: $it", color = MaterialTheme.colorScheme.error) }
        if (t == null) return@Column
        Section("Next class") {
            val n = t.next
            if (n == null) Hint("No classes this week. A lecture shows here once its event is linked to the course's project.")
            else {
                Text(n.event.title, Modifier.clickable { onOpenEvent(EventTarget.Existing(n.event.id, local(n.event.start!!).toLocalDate())) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(nextWhen(n.event), n.event.location, n.projectName).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                n.files.forEach { f ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenFile(f.path) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        f.modified?.let { Text(OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton({ context.startActivity(com.syeyoung.assistant.notes.QuickNoteActivity.intent(context, notebook = false)) }) { Text("Quick note") }
            androidx.compose.material3.FilledTonalButton({ context.startActivity(android.content.Intent(context, com.syeyoung.assistant.capture.ClassPhotoActivity::class.java)) }) { Text("Class photo") }
        }
        Section("Due soon") {
            if (t.due.isEmpty()) Hint("Nothing due in the next two days.")
            t.due.forEach { task ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenTask(task.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    task.due?.let { Text(dueText(it, LocalDate.parse(t.date)), style = MaterialTheme.typography.labelMedium, color = if (task.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Section("Today's events") {
            if (t.events.isEmpty()) Hint("Nothing on the calendar today.")
            t.events.forEach { e ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenEvent(EventTarget.Existing(e.id, LocalDate.parse(t.date))) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(parseColor(e.color, MaterialTheme.colorScheme.primary)))
                    Spacer(Modifier.width(10.dp))
                    Text(e.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(whenOf(e), e.location).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (t.triage > 0) Card(Modifier.fillMaxWidth().clickable(onClick = onTriage)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${t.triage}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("waiting in Triage")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
