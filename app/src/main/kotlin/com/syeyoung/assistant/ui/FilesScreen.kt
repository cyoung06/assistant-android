package com.syeyoung.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syeyoung.assistant.api.models.DriveEntry
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import com.syeyoung.assistant.notebook.Notebooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private const val PROJECTS = "Projects/"

/** Each project's storage, as tiles in the project's colour; tap one to open it. */
@Composable
fun FilesScreen() {
    val repo = Repo.get(LocalContext.current)
    val state by repo.state.collectAsStateCompat()
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    var folders by remember { mutableStateOf<List<DriveEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { repo.api().driveFolder(PROJECTS).propertyEntries.filter { it.folder } }.onSuccess { folders = it }.onFailure { error = it.message }
    }
    open?.let { (path, name) ->
        BackHandler { open = null }
        DriveBrowser(path, name, onExit = { open = null })
        return
    }
    val order = state.data.projects.map { it.name }
    val colors = state.data.projects.associate { it.name to it.color }
    val list = folders?.sortedWith(compareBy({ it.name == DriveProvider.NO_PROJECT }, { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
    Column(Modifier.fillMaxSize()) {
        error?.let { Text("Couldn't reach the drive: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (list == null && error == null) Text("Loading…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.orEmpty(), key = { it.path }) { f ->
                // Matched to the project by name (folders are named after their projects, made file-safe).
                val color = colors[f.name] ?: state.data.projects.firstOrNull { it.name.replace(":", " ").replace(Regex("\\s+"), " ").trim() == f.name }?.color
                Column(
                    Modifier.clip12().background(MaterialTheme.colorScheme.surfaceContainer).clickable { open = f.path to f.name }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(40.dp), tint = color?.let { parseColor(it) } ?: MaterialTheme.colorScheme.outline)
                    Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** A project's own storage (its folder in the drive), as on its page's Files tab. */
@Composable
fun ProjectFiles(projectId: UUID, projectName: String) {
    val repo = Repo.get(LocalContext.current)
    var path by remember(projectId) { mutableStateOf<String?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) { runCatching { repo.api().drive.projectFolder(projectId, "").let { r -> if (r.success) r.body().path else error("HTTP ${r.status}") } }.onSuccess { path = it }.onFailure { error = it.message } }
    when {
        path != null -> DriveBrowser(path!!, projectName, onExit = null)
        error != null -> Text("Couldn't reach the drive: $error", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        else -> Text("Loading…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Modifier.clip12() = this.then(Modifier.clip(RoundedCornerShape(12.dp)))

/**
 * Browsing a storage from its top [root] (a project's folder): icons by default (or a list),
 * folders first; PDFs open in the notebook editor and save back here; other files open in the app
 * that handles them. Upload, new folder, rename and delete.
 */
@Composable
fun DriveBrowser(root: String, rootName: String, onExit: (() -> Unit)?) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var icons by remember { mutableStateOf(prefs.getString("files.view", "icons") == "icons") }
    var path by remember(root) { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<DriveEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<DriveEntry?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path, reload) {
        error = null
        runCatching { repo.api().driveFolder(path).propertyEntries }.onSuccess { entries = it }.onFailure { error = it.message }
    }
    if (path != root) BackHandler { path = path.trimEnd('/').substringBeforeLast('/') + "/" }
    else if (onExit != null) BackHandler(onBack = onExit)

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Uploading…"
            for (u in uris) runCatching { uploadInto(context, repo, path, u) }.onFailure { Toast.makeText(context, "Couldn't upload: ${it.message}", Toast.LENGTH_LONG).show() }
            busy = null
            reload++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Where we are, from the project's top.
            val parts = path.removePrefix(root).split('/').filter { it.isNotEmpty() }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Crumb(rootName) { path = root }
                parts.forEachIndexed { i, p ->
                    Text("›", color = MaterialTheme.colorScheme.outline)
                    Crumb(p) { path = root + parts.take(i + 1).joinToString("/") + "/" }
                }
            }
            busy?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            IconButton({ upload.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Upload, "Upload") }
            IconButton({ naming = true }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
            IconButton({ icons = !icons; prefs.edit().putString("files.view", if (icons) "icons" else "list").apply() }) {
                Icon(if (icons) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView, if (icons) "Show as list" else "Show as icons")
            }
            IconButton({ reload++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        error?.let { Text("Couldn't reach the drive: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        val list = entries.orEmpty()
        if (entries != null && list.isEmpty()) Text("Nothing here yet. Upload files, or save notebooks here.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val open = { e: DriveEntry -> if (e.folder) path = e.path else openFile(context, e) }
        if (icons) {
            LazyVerticalGrid(GridCells.Adaptive(116.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.path }) { e ->
                    Column(
                        Modifier.androidLongClick({ open(e) }, { menuFor = e }).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(iconOf(e), null, Modifier.size(56.dp), tint = if (e.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontSize = 13.sp)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.path }) { e ->
                    ListItem(
                        leadingContent = { Icon(iconOf(e), null, tint = if (e.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(listOfNotNull(if (e.folder) null else size(e.propertySize), e.modified?.let(::date)).joinToString(" · ")) },
                        modifier = Modifier.androidLongClick({ open(e) }, { menuFor = e }),
                    )
                }
            }
        }
    }

    if (naming) NameDialog("New folder", "") { name ->
        naming = false
        if (name != null) scope.launch { runCatching { repo.api().driveMakeFolder(path + name + "/") }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }; reload++ }
    }
    menuFor?.let { e ->
        var renaming by remember { mutableStateOf(false) }
        if (renaming) NameDialog("Rename", e.name) { name ->
            menuFor = null
            if (name != null && name != e.name) scope.launch {
                runCatching { repo.api().driveMove(e.path, path + name + if (e.folder) "/" else "") }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                reload++
            }
        } else AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(e.name) },
            text = { Text(if (e.folder) "Folder" else size(e.propertySize)) },
            confirmButton = { TextButton({ renaming = true }) { Text("Rename") } },
            dismissButton = {
                TextButton({
                    menuFor = null
                    scope.launch { runCatching { repo.api().driveDelete(e.path) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }; reload++ }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun Crumb(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton({ onDone(name.trim().replace('/', ' ').ifBlank { null }) }) { Text("OK") } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } },
    )
}

/** Tap to open, long-press for more. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.androidLongClick(onClick: () -> Unit, onLongClick: () -> Unit) = this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

private fun iconOf(e: DriveEntry): ImageVector {
    if (e.folder) return Icons.Filled.Folder
    val n = e.name.lowercase()
    return when {
        n.endsWith(".pdf") -> Icons.Filled.PictureAsPdf
        Regex("\\.(png|jpe?g|gif|webp|heic)$").containsMatchIn(n) -> Icons.Filled.Image
        Regex("\\.(mp4|mov|webm|mkv)$").containsMatchIn(n) -> Icons.Filled.Movie
        else -> Icons.Filled.InsertDriveFile
    }
}

/** PDFs in the notebook editor (saved back to the drive); anything else in the app that handles it. */
private fun openFile(context: Context, e: DriveEntry) {
    val uri = DriveProvider.uri(context, e.path)
    if (e.name.lowercase().endsWith(".pdf")) return Notebooks.open(context, uri)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.contentType ?: DriveProvider.mimeOf(e.name, false)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(view) }.onFailure { Toast.makeText(context, "No app here opens ${e.name}", Toast.LENGTH_SHORT).show() }
}

/** Copies a picked file into the drive folder [folder]. */
private suspend fun uploadInto(context: Context, repo: Repo, folder: String, uri: Uri) = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
    val tmp = File.createTempFile("upload", null, context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        repo.api().driveUpload(folder + name.replace('/', ' '), tmp, context.contentResolver.getType(uri))
    } finally {
        tmp.delete()
    }
}

private fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
}

private fun date(iso: String) = runCatching { OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)) }.getOrDefault("")
