package com.syeyoung.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.syeyoung.assistant.api.models.DueDto
import com.syeyoung.assistant.api.models.LabelResponse
import com.syeyoung.assistant.api.models.ProjectResponse
import com.syeyoung.assistant.api.models.TaskResponse.Priority
import com.syeyoung.assistant.api.models.TaskResponse.Status
import com.syeyoung.assistant.data.dueChoices
import com.syeyoung.assistant.data.parseTime
import com.syeyoung.assistant.data.priorityLabel
import com.syeyoung.assistant.data.statusLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

val STATUSES = listOf(Status.backlog, Status.todo, Status.in_progress, Status.done, Status.canceled)
val PRIORITIES = listOf(Priority.none, Priority.urgent, Priority.high, Priority.medium, Priority.low)

@Composable
fun StatusMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (Status) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        STATUSES.forEach { s -> DropdownMenuItem(text = { Text(statusLabel(s)) }, leadingIcon = { StatusIcon(s) }, onClick = { onPick(s); onDismiss() }) }
    }
}

@Composable
fun PriorityMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (Priority) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        PRIORITIES.forEach { p -> DropdownMenuItem(text = { Text(priorityLabel(p)) }, leadingIcon = { PriorityIcon(p) }, onClick = { onPick(p); onDismiss() }) }
    }
}

@Composable
fun ProjectMenu(expanded: Boolean, projects: List<ProjectResponse>, onDismiss: () -> Unit, onPick: (UUID?) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text("No project") }, onClick = { onPick(null); onDismiss() })
        projects.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, leadingIcon = { Dot(p.color) }, onClick = { onPick(p.id); onDismiss() }) }
    }
}

@Composable
fun Dot(color: String?, size: Int = 9) {
    androidx.compose.foundation.layout.Box(Modifier.size(size.dp).background(parseColor(color), CircleShape))
}

@Composable
fun LabelsDialog(labels: List<LabelResponse>, selected: List<UUID>, onDismiss: () -> Unit, onDone: (List<UUID>) -> Unit) {
    var picked by remember { mutableStateOf(selected.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Labels") },
        text = {
            LazyColumn {
                items(labels) { l ->
                    ListItem(
                        headlineContent = { Text(l.name) },
                        leadingContent = { Checkbox(l.id in picked, onCheckedChange = { picked = if (it) picked + l.id else picked - l.id }) },
                        trailingContent = { Dot(l.color) },
                        modifier = Modifier.clickable { picked = if (l.id in picked) picked - l.id else picked + l.id },
                    )
                }
            }
        },
        confirmButton = { TextButton({ onDone(picked.toList()); onDismiss() }) { Text("Done") } },
    )
}

/** Quick due dates plus a date picker (and an optional time); keeps the time when a quick date is picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueChooser(current: DueDto?, onPick: (DueDto?) -> Unit, today: LocalDate = LocalDate.now()) {
    var picking by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<LocalDate?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        dueChoices(today).take(3).forEach { (label, date) ->
            AssistChip(onClick = { onPick(DueDto(date.toString(), current?.time)) }, label = { Text(label) })
        }
        AssistChip(onClick = { picking = true }, label = { Text("Pick…") })
        if (current != null) TextButton({ onPick(null) }) { Text("Clear") }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (current?.date?.let(LocalDate::parse) ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { timeFor = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("Next") }
            },
        ) { DatePicker(state) }
    }
    timeFor?.let { date ->
        val t = parseTime(current?.time)
        val state = rememberTimePickerState(t?.hour ?: 23, t?.minute ?: 59, true)
        AlertDialog(
            onDismissRequest = { timeFor = null },
            title = { Text("Time (optional)") },
            text = { TimePicker(state) },
            confirmButton = { TextButton({ onPick(DueDto(date.toString(), "%02d:%02d".format(state.hour, state.minute))); timeFor = null }) { Text("Set time") } },
            dismissButton = { TextButton({ onPick(DueDto(date.toString(), null)); timeFor = null }) { Text("No time") } },
        )
    }
}
