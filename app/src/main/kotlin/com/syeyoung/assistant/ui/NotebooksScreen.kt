package com.syeyoung.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.syeyoung.assistant.notebook.Notebooks
import com.syeyoung.assistant.notebook.PageStyle
import com.syeyoung.assistant.notebook.StylePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Write on PDFs with the pen: open course material or start a blank notebook; recent ones listed. */
@Composable
fun NotebooksScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recent by remember { mutableStateOf(Notebooks.recent(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { recent = Notebooks.recent(context) } }

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Notebooks.open(context, it) } }
    // A new notebook: its name, page style, and the project it belongs to (or none); it's made in
    // that project's folder in the drive and opens there.
    var choosing by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(PageStyle.DOTS) }
    var project by remember { mutableStateOf<java.util.UUID?>(null) }
    var name by remember { mutableStateOf("") }
    val repo = com.syeyoung.assistant.data.Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    if (choosing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("New notebook") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, placeholder = { Text("Notebook") })
                    StylePicker(style) { style = it }
                    Text("In", style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.FilterChip(project == null, { project = null }, { Text("No project") })
                        com.syeyoung.assistant.data.drawerProjects(state.data).forEach { p ->
                            androidx.compose.material3.FilterChip(project == p.id, { project = p.id }, { Text(p.name) }, leadingIcon = { Dot(p.color) })
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    choosing = false
                    val title = name.trim().replace('/', ' ').ifBlank { "Notebook" }
                    scope.launch {
                        runCatching {
                            val folder = project?.let { id -> repo.api().drive.projectFolder(id, "").let { r -> if (r.success) r.body().path else error("HTTP ${r.status}") } }
                                ?: "Projects/${com.syeyoung.assistant.drive.DriveProvider.NO_PROJECT}/"
                            val existing = repo.api().driveFolder(folder).propertyEntries.map { it.name }.toSet()
                            var file = "$title.pdf"
                            var n = 2
                            while (file in existing) file = "$title ($n).pdf".also { n++ }
                            val tmp = java.io.File.createTempFile("notebook", ".pdf", context.cacheDir)
                            try {
                                tmp.writeBytes(com.syeyoung.assistant.notebook.BlankPdf.notebook(20, style))
                                repo.api().driveUpload(folder + file, tmp, "application/pdf")
                            } finally {
                                tmp.delete()
                            }
                            folder + file
                        }.onSuccess { path -> name = ""; Notebooks.open(context, com.syeyoung.assistant.drive.DriveProvider.uri(context, path)) }
                            .onFailure { android.widget.Toast.makeText(context, "Couldn't make the notebook: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Create") }
            },
            dismissButton = { androidx.compose.material3.TextButton({ choosing = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ openPdf.launch(arrayOf("application/pdf")) }) { Text("Open PDF…") }
                OutlinedButton({ choosing = true }) { Text("New notebook") }
            }
            if (recent.isEmpty()) {
                Text(
                    "Open a course PDF to write on it, or start a blank notebook. Write with the pen, move with your fingers. Writing is saved into the PDF itself as you go, so any PDF reader shows it.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(recent, key = { it.uri.toString() }) { r ->
            ListItem(
                leadingContent = { Icon(Icons.Filled.Description, null) },
                headlineContent = { Text(r.name) },
                supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.openedAt))) },
                trailingContent = { IconButton({ Notebooks.forget(context, r.uri); recent = Notebooks.recent(context) }) { Icon(Icons.Filled.Close, "Remove from list") } },
                modifier = Modifier.clickable { Notebooks.open(context, r.uri) },
            )
        }
    }
}
