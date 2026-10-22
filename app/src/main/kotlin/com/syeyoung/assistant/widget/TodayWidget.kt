package com.syeyoung.assistant.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.syeyoung.assistant.MainActivity
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.dueText
import com.syeyoung.assistant.data.plainText
import com.syeyoung.assistant.data.sortTasks
import com.syeyoung.assistant.data.value
import androidx.glance.appwidget.cornerRadius
import java.time.LocalDate

/**
 * Home-screen widget, the day at a glance: the next class (its time and room), what's overdue and
 * due today (from the last sync), and a quick note or capture a tap away.
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val tasks = Repo.get(context).state.value.data.tasks
        val due = sortTasks(tasks.filter { !it.closed && it.due != null && (it.overdue || !LocalDate.parse(it.due!!.date).isAfter(today)) })
        val next = nextClass(context)
        provideContent { GlanceTheme { Content(context, next, due, today) } }
    }

    /** The next class as one line ("확률및통계 · Mon 12:30 · Science 101"): from the server, else as last known. */
    private suspend fun nextClass(context: Context): String? {
        val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
        val fresh = runCatching {
            kotlinx.coroutines.withTimeout(5_000) { Repo.get(context).api().today.today().value() }.next?.let { n ->
                val start = java.time.OffsetDateTime.parse(n.event.start!!).atZoneSameInstant(java.time.ZoneId.systemDefault())
                val day = if (start.toLocalDate() == LocalDate.now()) "" else start.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " "
                listOfNotNull(n.event.title, day + start.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), n.event.location).joinToString(" · ")
            }.orEmpty()
        }.onSuccess { prefs.edit().putString("next", it).apply() }
        return (fresh.getOrNull() ?: prefs.getString("next", null))?.ifEmpty { null }
    }

    @Composable
    private fun Content(context: Context, next: String?, due: List<TaskResponse>, today: LocalDate) {
        val open = Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(12.dp)) {
            if (next != null) {
                Text(
                    next, maxLines = 1,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.primary),
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp).clickable(actionStartActivity(open)),
                )
            }
            Text(
                if (due.isEmpty()) "Nothing due today" else "Due · ${due.size}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            )
            Spacer(GlanceModifier.width(4.dp))
            LazyColumn(GlanceModifier.defaultWeight()) {
                items(due, itemId = { it.number }) { t ->
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable(actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TASK, t.id.toString()).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))),
                    ) {
                        Text(
                            plainText(t.title), GlanceModifier.defaultWeight(), maxLines = 1,
                            style = TextStyle(fontSize = 13.sp, color = if (t.overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurface),
                        )
                        Text(" " + dueText(t.due!!, today), style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                }
            }
            Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
                Action("Quick note", com.syeyoung.assistant.notes.QuickNoteActivity.intent(context, notebook = false))
                Spacer(GlanceModifier.width(8.dp))
                Action("Capture", Intent(context, com.syeyoung.assistant.capture.CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    @Composable
    private fun Action(label: String, intent: Intent) {
        Text(
            label,
            style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, color = GlanceTheme.colors.onPrimaryContainer),
            modifier = GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(14.dp).padding(horizontal = 12.dp, vertical = 6.dp).clickable(actionStartActivity(intent)),
        )
    }

    companion object {
        suspend fun update(context: Context) = TodayWidget().updateAll(context)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
