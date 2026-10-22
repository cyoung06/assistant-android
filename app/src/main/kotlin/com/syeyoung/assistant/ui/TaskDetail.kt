package com.syeyoung.assistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.AttachmentInfo
import com.syeyoung.assistant.api.models.CreateTaskRequest
import com.syeyoung.assistant.api.models.TaskResponse
import com.syeyoung.assistant.data.Optional
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.TaskChange
import com.syeyoung.assistant.data.closed
import com.syeyoung.assistant.data.dueText
import com.syeyoung.assistant.data.labelsOf
import com.syeyoung.assistant.data.plainText
import com.syeyoung.assistant.data.priorityLabel
import com.syeyoung.assistant.data.statusLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** One task: title and description (with references), properties, sub-tasks, attachments. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskDetail(id: UUID, onBack: () -> Unit, onOpenTask: (UUID) -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    val t = state.data.tasks.find { it.id == id }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.let { "#${it.number}" } ?: "") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    t?.url?.let { url -> IconButton({ openRef(context, url) {} }) { Icon(Icons.Filled.OpenInNew, "Open in ${t.source ?: "source"}") } }
                    var confirm by remember { mutableStateOf(false) }
                    IconButton({ confirm = true }) { Icon(Icons.Filled.Delete, "Delete") }
                    if (confirm && t != null) AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text(if (t.subtasks > 0) "Delete with ${t.subtasks} sub-tasks?" else "Delete this task?") },
                        confirmButton = { TextButton({ confirm = false; scope.launch { runCatching { repo.delete(t) }; onBack() } }) { Text("Delete") } },
                        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } },
                    )
                },
            )
        },
    ) { padding ->
        if (t == null) {
            Text("This task isn't here (anymore).", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val change: (TaskChange) -> Unit = { c -> scope.launch { runCatching { repo.change(t, c) }.onFailure { error = it.message } } }
        val fixed = t.source != null
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // Title: tap to edit (references show as links when not editing).
            var editingTitle by remember(t.id) { mutableStateOf(false) }
            if (editingTitle && !fixed) {
                var text by remember(t.id) { mutableStateOf(t.title) }
                Column {
                    MentionField(text, { text = it }, singleLine = false, label = "Title")
                    Row { TextButton({ editingTitle = false }) { Text("Cancel") }; TextButton({ editingTitle = false; if (text.isNotBlank() && text != t.title) change(TaskChange(title = text.trim())) }) { Text("Save") } }
                }
            } else {
                Box(Modifier.clickable(enabled = !fixed) { editingTitle = true }) {
                    RefText(t.title, MaterialTheme.typography.headlineSmall) { tid -> onOpenTask(UUID.fromString(tid)) }
                }
            }

            Properties(t, change)

            // Description
            var editingDescription by remember(t.id) { mutableStateOf(false) }
            if (editingDescription) {
                var text by remember(t.id) { mutableStateOf(t.description) }
                Column {
                    MentionField(text, { text = it }, label = "Description (@ to reference)", minLines = 4)
                    Row { TextButton({ editingDescription = false }) { Text("Cancel") }; TextButton({ editingDescription = false; if (text != t.description) change(TaskChange(description = text)) }) { Text("Save") } }
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { editingDescription = true }.padding(vertical = 6.dp)) {
                    if (t.description.isBlank()) Text("Add description…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else RefText(t.description) { tid -> onOpenTask(UUID.fromString(tid)) }
                }
            }

            if (t.attachments.isNotEmpty()) {
                HorizontalDivider()
                Text("Attachments", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.attachments.forEach { a -> AttachmentThumb(a) }
                }
            }

            HorizontalDivider()
            val subs = state.data.tasks.filter { it.parentId == t.id }.sortedBy { it.number }
            Text("Sub-tasks ${if (subs.isNotEmpty()) "${t.subtasksDone}/${t.subtasks}" else ""}", style = MaterialTheme.typography.titleSmall)
            subs.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { onOpenTask(s.id) }, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ scope.launch { runCatching { repo.change(s, TaskChange(status = if (s.closed) TaskResponse.Status.todo else TaskResponse.Status.done)) } } }) { StatusIcon(s.status) }
                    Text("#${s.number} ${plainText(s.title)}", color = if (s.closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
            var newSub by remember(t.id) { mutableStateOf("") }
            OutlinedTextField(
                newSub, { newSub = it }, Modifier.fillMaxWidth(), placeholder = { Text("Add sub-task") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val title = newSub.trim()
                    if (title.isNotEmpty()) scope.launch { runCatching { repo.create(CreateTaskRequest(title = title, parentId = t.id, projectId = t.projectId)) }; newSub = "" }
                }),
            )

            LinkedFrom("/task/${t.id}") { tid -> onOpenTask(UUID.fromString(tid)) }

            if (t.relations.isNotEmpty()) {
                HorizontalDivider()
                Text("Relations", style = MaterialTheme.typography.titleSmall)
                t.relations.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenTask(r.task.id) }.padding(vertical = 6.dp)) {
                        Text("${r.kind.value.replace('_', ' ')}: #${r.task.number} ${plainText(r.task.title)}")
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Properties(t: TaskResponse, change: (TaskChange) -> Unit) {
    val context = LocalContext.current
    val state by Repo.get(context).state.collectAsStateCompat()
    val today = LocalDate.now()
    var statusMenu by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var labelsDialog by remember { mutableStateOf(false) }
    val project = state.data.projects.find { it.id == t.projectId }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            AssistChip({ statusMenu = true }, { Text(statusLabel(t.status)) }, leadingIcon = { StatusIcon(t.status, 16.dp) })
            StatusMenu(statusMenu, { statusMenu = false }) { change(TaskChange(status = it)) }
        }
        Box {
            AssistChip({ priorityMenu = true }, { Text(priorityLabel(t.priority)) }, leadingIcon = { PriorityIcon(t.priority) })
            PriorityMenu(priorityMenu, { priorityMenu = false }) { change(TaskChange(priority = it)) }
        }
        Box {
            AssistChip({ projectMenu = true }, { Text(project?.name ?: "No project") }, leadingIcon = project?.let { { Dot(it.color) } })
            ProjectMenu(projectMenu, state.data.projects, { projectMenu = false }) { change(TaskChange(projectId = Optional(it))) }
        }
        val labels = labelsOf(t, state.data.labels)
        AssistChip({ labelsDialog = true }, { Text(if (labels.isEmpty()) "Labels" else labels.joinToString { it.name }) })
    }
    if (labelsDialog) LabelsDialog(state.data.labels, t.labelIds, { labelsDialog = false }) { change(TaskChange(labelIds = it)) }

    Column {
        Text(
            "Due: " + (t.due?.let { dueText(it, today) } ?: "none") + (if (t.overdue) " · overdue" else "") + (if (t.source != null) " · set in ${t.source!!.uppercase()}" else ""),
            color = if (t.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.source == null) DueChooser(t.due, { change(TaskChange(due = Optional(it))) })
    }
}

@Composable
private fun AttachmentThumb(a: AttachmentInfo) {
    val context = LocalContext.current
    var bitmap by remember(a.id) { mutableStateOf<ImageBitmap?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(a.id) {
        if (a.contentType.startsWith("image/")) runCatching {
            val bytes = Repo.get(context).api().download(a.id)
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Box(Modifier.size(110.dp, 84.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, a.filename) } ?: Text(a.filename, style = MaterialTheme.typography.labelSmall)
    }
    if (open) bitmap?.let { b ->
        AlertDialog(onDismissRequest = { open = false }, confirmButton = { TextButton({ open = false }) { Text("Close") } }, text = { Image(b, a.filename, Modifier.fillMaxWidth()) })
    }
}

