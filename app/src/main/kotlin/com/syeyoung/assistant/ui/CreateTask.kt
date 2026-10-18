package com.syeyoung.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.CreateTaskRequest
import com.syeyoung.assistant.api.models.DueDto
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.dueText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** New task: title (with @), due date, project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskSheet(initialProject: UUID?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<DueDto?>(null) }
    var projectId by remember { mutableStateOf(initialProject) }
    var projectMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New task", style = MaterialTheme.typography.titleMedium)
            MentionField(title, { title = it }, label = "Title (@ to reference)")
            MentionField(description, { description = it }, label = "Description", minLines = 2)
            Text("Due: " + (due?.let { dueText(it, LocalDate.now()) } ?: "none"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            DueChooser(due, { due = it })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val project = state.data.projects.find { it.id == projectId }
                Box {
                    AssistChip({ projectMenu = true }, { Text(project?.name ?: "No project") }, leadingIcon = project?.let { { Dot(it.color) } })
                    ProjectMenu(projectMenu, state.data.projects, { projectMenu = false }) { projectId = it }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching { repo.create(CreateTaskRequest(title = title.trim(), description = description, due = due, projectId = projectId)) }
                            .onSuccess { onDismiss() }.onFailure { error = it.message; saving = false }
                    }
                },
                enabled = title.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) { Text("Create task") }
        }
    }
}
