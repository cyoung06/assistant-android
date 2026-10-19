package com.syeyoung.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.ProjectResponse
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.data.Group
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.dueText
import com.syeyoung.assistant.data.plainText
import java.time.LocalDate

/** Grouped rows: tap the circle to change status, the row to open the task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskList(
    groups: List<Group>,
    closed: List<TaskResponse>,
    projects: List<ProjectResponse>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (TaskResponse) -> Unit,
    onStatus: (TaskResponse, TaskResponse.Status) -> Unit,
    showProject: Boolean = true,
    empty: String = "Nothing here.",
    header: (@Composable () -> Unit)? = null,
) {
    var showClosed by remember { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            header?.let { h -> item(key = "header") { h() } }
            if (groups.isEmpty() && closed.isEmpty()) item { Text(empty, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            groups.forEach { g ->
                item(key = "h:${g.key}") { GroupHeader(g.label, g.tasks.size, g.danger, g.color) }
                items(g.tasks, key = { "${g.key}:${it.id}" }) { t -> TaskRow(t, projects, onOpen, onStatus, showProject) }
            }
            if (closed.isNotEmpty()) {
                item(key = "h:closed") {
                    Box(Modifier.clickable { showClosed = !showClosed }) { GroupHeader((if (showClosed) "▾ " else "▸ ") + "Closed", closed.size) }
                }
                if (showClosed) items(closed, key = { "closed:${it.id}" }) { t -> TaskRow(t, projects, onOpen, onStatus, showProject) }
            }
            item { Spacer(Modifier.size(96.dp)) }
        }
    }
}

@Composable
private fun GroupHeader(label: String, count: Int, danger: Boolean = false, color: String? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (color != null) Dot(color)
            Text(label, fontWeight = FontWeight.SemiBold, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun TaskRow(t: TaskResponse, projects: List<ProjectResponse>, onOpen: (TaskResponse) -> Unit, onStatus: (TaskResponse, TaskResponse.Status) -> Unit, showProject: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Column0 {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton({ menu = true }) { StatusIcon(t.status) }
                StatusMenu(menu, { menu = false }) { onStatus(t, it) }
            }
            PriorityIcon(t.priority, 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                plainText(t.title),
                Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (t.closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (t.subtasks > 0) Text(" ${t.subtasksDone}/${t.subtasks}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showProject) projects.find { it.id == t.projectId }?.let { p -> Spacer(Modifier.width(8.dp)); Dot(p.color, 8) }
            t.due?.let { d ->
                Spacer(Modifier.width(8.dp))
                val color = when {
                    t.closed -> MaterialTheme.colorScheme.onSurfaceVariant
                    t.overdue -> MaterialTheme.colorScheme.error
                    !LocalDate.parse(d.date).isAfter(today.plusDays(1)) -> Warn
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(dueText(d, today), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = color, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (t.source != null) Text(" " + t.source!!.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Column0(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Column { content() }
