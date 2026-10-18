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
import java.time.LocalDate

/** Home-screen widget: what's overdue and due today, from the last sync. */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val tasks = Repo.get(context).state.value.data.tasks
        val due = sortTasks(tasks.filter { !it.closed && it.due != null && (it.overdue || !LocalDate.parse(it.due!!.date).isAfter(today)) })
        provideContent { GlanceTheme { Content(context, due, today) } }
    }

    @Composable
    private fun Content(context: Context, due: List<TaskResponse>, today: LocalDate) {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(12.dp)) {
            Text(
                if (due.isEmpty()) "Nothing due today" else "Due · ${due.size}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            )
            Spacer(GlanceModifier.width(4.dp))
            LazyColumn {
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
        }
    }

    companion object {
        suspend fun update(context: Context) = TodayWidget().updateAll(context)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
