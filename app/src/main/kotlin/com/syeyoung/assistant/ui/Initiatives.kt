package com.syeyoung.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.InitiativeResponse
import com.syeyoung.assistant.api.models.InitiativeResponse.Status
import com.syeyoung.assistant.data.InitiativeChange
import com.syeyoung.assistant.data.Optional
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.sortInitiatives
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Initiatives, as in Linear and on the web: a larger goal (a semester, a research effort) grouping projects.

private val INITIATIVE_STATUSES = listOf(Status.active to "Active", Status.planned to "Planned", Status.completed to "Completed")
private val TARGET = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

private fun progress(i: InitiativeResponse) = if (i.taskCount > 0) i.doneCount.toFloat() / i.taskCount else 0f

@Composable
fun StatusMark(status: Status) = when (status) {
    Status.active -> Icon(Icons.Filled.Timelapse, "Active", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
    Status.completed -> Icon(Icons.Filled.CheckCircle, "Completed", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    else -> Icon(Icons.Filled.RadioButtonUnchecked, "Planned", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
}

@Composable
fun InitiativesScreen(onOpen: (InitiativeResponse) -> Unit) {
    val repo = Repo.get(LocalContext.current)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val list = sortInitiatives(state.data.initiatives)
    val add = {
        val n = name.trim()
        if (n.isNotEmpty()) scope.launch {
            runCatching { repo.createInitiative(n) }.onSuccess { name = ""; onOpen(it) }.onFailure { error = it.message ?: "Couldn't add" }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("New initiative") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                trailingIcon = { if (name.isNotBlank()) TextButton({ add() }) { Text("Add") } },
            )
            error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (list.isEmpty()) {
                Text(
                    "No initiatives yet. Group projects toward a bigger goal, like a semester. PLMS adds one per semester.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(list, key = { it.id }) { i ->
            ListItem(
                leadingContent = { StatusMark(i.status) },
                headlineContent = { Text(i.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            listOfNotNull(
                                "${i.projectIds.size} project${if (i.projectIds.size == 1) "" else "s"}",
                                i.targetDate?.let { LocalDate.parse(it).format(TARGET) },
                                "${i.doneCount}/${i.taskCount} done",
                            ).joinToString(" · "),
                        )
                        LinearProgressIndicator({ progress(i) }, Modifier.fillMaxWidth())
                    }
                },
                trailingContent = { Text("${(progress(i) * 100).toInt()}%") },
                modifier = Modifier.clickable { onOpen(i) },
            )
        }
    }
}

/** An initiative: name, status, target date, description, and its projects (add or remove). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InitiativeDetail(id: UUID, onOpenProject: (UUID, String) -> Unit, onDeleted: () -> Unit) {
    val repo = Repo.get(LocalContext.current)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    val i = state.data.initiatives.firstOrNull { it.id == id }
    if (i == null) {
        Text(if (state.loading) "Loading…" else "No such initiative.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val change = { c: InitiativeChange -> scope.launch { runCatching { repo.changeInitiative(id, c) } }; Unit }
    var name by remember(id) { mutableStateOf(i.name) }
    var description by remember(id) { mutableStateOf(i.description) }
    var statusOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val mine = state.data.projects.filter { it.id in i.projectIds }
    val others = state.data.projects.filter { it.id !in i.projectIds && it.state.value != "completed" && it.state.value != "canceled" }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Text saves when it loses focus, as on the web.
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && name.isNotBlank() && name.trim() != i.name) change(InitiativeChange(name = name.trim())) },
                    label = { Text("Name") }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Column {
                        AssistChip({ statusOpen = true }, { Text(INITIATIVE_STATUSES.first { it.first == i.status }.second) }, leadingIcon = { StatusMark(i.status) })
                        DropdownMenu(statusOpen, { statusOpen = false }) {
                            INITIATIVE_STATUSES.forEach { (s, label) -> DropdownMenuItem({ Text(label) }, { change(InitiativeChange(status = s)); statusOpen = false }, leadingIcon = { StatusMark(s) }) }
                        }
                    }
                    AssistChip({ picking = true }, { Text(i.targetDate?.let { "Target " + LocalDate.parse(it).format(TARGET) } ?: "Set target") })
                    if (i.targetDate != null) TextButton({ change(InitiativeChange(targetDate = Optional(null))) }) { Text("Clear") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator({ progress(i) }, Modifier.weight(1f))
                    Text("${(progress(i) * 100).toInt()}% · ${i.doneCount}/${i.taskCount} done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    description, { description = it }, Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && description != i.description) change(InitiativeChange(description = description)) },
                    label = { Text("Description") }, minLines = 2,
                )
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Projects", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (others.isNotEmpty()) Column {
                    TextButton({ addOpen = true }) { Text("Add project") }
                    DropdownMenu(addOpen, { addOpen = false }) {
                        others.forEach { p -> DropdownMenuItem({ Text(p.name) }, { change(InitiativeChange(projectIds = i.projectIds + p.id)); addOpen = false }, leadingIcon = { Dot(p.color) }) }
                    }
                }
            }
            if (mine.isEmpty()) Text("No projects in this initiative yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(mine, key = { it.id }) { p ->
            val open = state.data.tasks.count { it.projectId == p.id && !it.closed }
            ListItem(
                leadingContent = { Dot(p.color, 10) },
                headlineContent = { Text(p.name) },
                supportingContent = { Text(listOfNotNull(if (open > 0) "$open open" else null, p.state.value.takeIf { it == "completed" || it == "canceled" }?.replaceFirstChar { it.uppercase() }).joinToString(" · ").ifEmpty { "No open tasks" }) },
                trailingContent = { IconButton({ change(InitiativeChange(projectIds = i.projectIds - p.id)) }) { Icon(Icons.Filled.Close, "Remove from initiative") } },
                modifier = Modifier.clickable { onOpenProject(p.id, p.name) },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(top = 16.dp))
            TextButton({ confirmDelete = true }, Modifier.padding(8.dp)) { Text("Delete initiative", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (picking) {
        val millis = (i.targetDate?.let(LocalDate::parse) ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val picker = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    picker.selectedDateMillis?.let { change(InitiativeChange(targetDate = Optional(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${i.name}?") },
            text = { Text("Its projects stay.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch { runCatching { repo.deleteInitiative(id) }; onDeleted() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
        )
    }
}

/** A project's initiatives, at the top of its task list, with a menu to add it to others. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InitiativeChips(projectId: UUID, onOpen: (InitiativeResponse) -> Unit) {
    val repo = Repo.get(LocalContext.current)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    val all = sortInitiatives(state.data.initiatives)
    val mine = all.filter { projectId in it.projectIds }
    val others = all.filter { projectId !in it.projectIds && it.status != Status.completed }
    var open by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        mine.forEach { i ->
            InputChip(
                selected = false, onClick = { onOpen(i) }, label = { Text(i.name) }, leadingIcon = { StatusMark(i.status) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, "Remove from ${i.name}", Modifier.size(16.dp).clickable {
                        scope.launch { runCatching { repo.changeInitiative(i.id, InitiativeChange(projectIds = i.projectIds - projectId)) } }
                    })
                },
            )
        }
        if (others.isNotEmpty()) Column {
            AssistChip({ open = true }, { Text(if (mine.isEmpty()) "Add to initiative" else "Add") }, leadingIcon = { Icon(Icons.Filled.Flag, null, Modifier.size(16.dp)) })
            DropdownMenu(open, { open = false }) {
                others.forEach { i ->
                    DropdownMenuItem({ Text(i.name) }, {
                        open = false
                        scope.launch { runCatching { repo.changeInitiative(i.id, InitiativeChange(projectIds = i.projectIds + projectId)) } }
                    }, leadingIcon = { StatusMark(i.status) })
                }
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}
