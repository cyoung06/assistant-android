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
import androidx.compose.foundation.indication
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Add
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

/** Where each storage was left (its open folder), and which storage Files had open: kept while the app runs, so switching away and back doesn't start over. */
private object FilesNav {
    val folders = mutableMapOf<String, String>()
    /** The storage each pane has open (0: the left or only one, 1: the right). */
    val open = androidx.compose.runtime.mutableStateMapOf<Int, Pair<String, String>>()
    /** Two panes side by side (on a wide screen), to drag things between. */
    var split by mutableStateOf(false)
    /** Bumped when something changes, so every pane shows it. */
    var changes by mutableIntStateOf(0)
}

/** Files, in one pane or two side by side. */
@Composable
fun Files() {
    if (FilesNav.split && wide()) Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { FilesScreen(0) }
        androidx.compose.material3.VerticalDivider()
        Box(Modifier.weight(1f)) { FilesScreen(1) }
    } else FilesScreen(0)
}

@Composable
private fun wide() = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600

/** Turns the second pane on or off (on wide screens). */
@Composable
private fun SplitButton() {
    if (wide()) IconButton({ FilesNav.split = !FilesNav.split }) {
        Icon(Icons.Filled.VerticalSplit, if (FilesNav.split) "One pane" else "Side by side", tint = if (FilesNav.split) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified)
    }
}

/** Each project's storage, as tiles in the project's colour; tap one to open it. */
@Composable
fun FilesScreen(pane: Int = 0) {
    val repo = Repo.get(LocalContext.current)
    val state by repo.state.collectAsStateCompat()
    val open = FilesNav.open[pane]
    val show = { v: Pair<String, String>? -> if (v == null) FilesNav.open.remove(pane) else FilesNav.open[pane] = v }
    var folders by remember { mutableStateOf<List<DriveEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { repo.api().driveFolder(PROJECTS).propertyEntries.filter { it.folder } }.onSuccess { folders = it }.onFailure { error = it.message }
    }
    open?.let { (path, name) ->
        BackHandler { show(null) }
        DriveBrowser(path, name, onExit = { show(null) }, pane = pane)
        return
    }
    val order = state.data.projects.map { it.name }
    val colors = state.data.projects.associate { it.name to it.color }
    val list = folders?.sortedWith(compareBy({ it.name == DriveProvider.NO_PROJECT }, { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
    Column(Modifier.fillMaxSize()) {
        if (wide()) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Storage", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            SplitButton()
        }
        error?.let { Text("Couldn't reach the drive: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (list == null && error == null) Text("Loading…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.orEmpty(), key = { it.path }) { f ->
                // Matched to the project by name (folders are named after their projects, made file-safe).
                val color = colors[f.name] ?: state.data.projects.firstOrNull { it.name.replace(":", " ").replace(Regex("\\s+"), " ").trim() == f.name }?.color
                Column(
                    Modifier.clip12().background(MaterialTheme.colorScheme.surfaceContainer).clickable { show(f.path to f.name) }.padding(14.dp),
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
        path != null -> if (FilesNav.split && wide()) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { DriveBrowser(path!!, projectName, onExit = null) }
            androidx.compose.material3.VerticalDivider()
            Box(Modifier.weight(1f)) { FilesScreen(1) }
        } else DriveBrowser(path!!, projectName, onExit = null)
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
fun DriveBrowser(root: String, rootName: String, onExit: (() -> Unit)?, pane: Int = 0) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var icons by remember { mutableStateOf(prefs.getString("files.view", "icons") == "icons") }
    val navKey = "$pane|$root"
    var path by remember(root) { mutableStateOf(FilesNav.folders[navKey] ?: root) }
    var entries by remember { mutableStateOf<List<DriveEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<DriveEntry?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path, reload, FilesNav.changes) {
        FilesNav.folders[navKey] = path
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
            FilesNav.changes++
        }
    }

    var creating by remember { mutableStateOf(false) }
    var notebook by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val locked = DriveProvider.readOnly(path)
    /** Puts what was dropped into [folder], then shows it everywhere. */
    val drop = { folder: String, event: androidx.compose.ui.draganddrop.DragAndDropEvent ->
        scope.launch {
            runCatching { dropInto(context, repo, folder, event) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            FilesNav.changes++
        }
        Unit
    }
    var paneTarget by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().driveDropTarget(locked, onHover = { paneTarget = it }) { drop(path, it) }.then(if (paneTarget) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)) {
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
            IconButton({ icons = !icons; prefs.edit().putString("files.view", if (icons) "icons" else "list").apply() }) {
                Icon(if (icons) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView, if (icons) "Show as list" else "Show as icons")
            }
            IconButton({ FilesNav.changes++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
            SplitButton()
        }
        error?.let { Text("Couldn't reach the drive: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (locked) Text("From PLMS, kept as it is there: drag a copy out to write on it.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = entries.orEmpty()
        if (entries != null && list.isEmpty()) Text("Nothing here yet. Upload files, or save notebooks here.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val open = { e: DriveEntry -> if (e.folder) path = e.path else openFile(context, e) }
        if (icons) {
            LazyVerticalGrid(GridCells.Adaptive(116.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.path }) { e ->
                    var hover by remember { mutableStateOf(false) }
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .then(if (hover) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                            .then(if (e.folder) Modifier.driveDropTarget(DriveProvider.readOnly(e.path), onHover = { hover = it }) { drop(e.path, it) } else Modifier)
                            .driveGestures({ open(e) }, { menuFor = e }) { copy -> startDriveDrag(view, context, e, copy) }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (e.name.lowercase().endsWith(".pdf")) PdfThumb(e) { Icon(iconOf(e), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else Icon(iconOf(e), null, Modifier.size(56.dp), tint = if (e.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                        modifier = Modifier
                            .then(if (e.folder) Modifier.driveDropTarget(DriveProvider.readOnly(e.path)) { drop(e.path, it) } else Modifier)
                            .driveGestures({ open(e) }, { menuFor = e }) { copy -> startDriveDrag(view, context, e, copy) },
                    )
                }
            }
        }
    }

    // New things here: a notebook (opened to write in), uploaded files, or a folder.
    // Course files take nothing new.
    if (!locked) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
        androidx.compose.material3.FloatingActionButton({ creating = true }) { Icon(Icons.Filled.Add, "New") }
        androidx.compose.material3.DropdownMenu(creating, { creating = false }) {
            androidx.compose.material3.DropdownMenuItem({ Text("New notebook") }, { creating = false; notebook = true }, leadingIcon = { Icon(Icons.Filled.EditNote, null) })
            androidx.compose.material3.DropdownMenuItem({ Text("Upload files") }, { creating = false; upload.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Icons.Filled.Upload, null) })
            androidx.compose.material3.DropdownMenuItem({ Text("New folder") }, { creating = false; naming = true }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) })
        }
    }
    }

    if (notebook) NewNotebookDialog { title, style ->
        notebook = false
        if (title != null) scope.launch {
            busy = "Making the notebook…"
            runCatching { createNotebook(context, repo, path, title, style) }
                .onSuccess { Notebooks.open(context, DriveProvider.uri(context, it)) }
                .onFailure { Toast.makeText(context, "Couldn't make the notebook: ${it.message}", Toast.LENGTH_LONG).show() }
            busy = null
            FilesNav.changes++
        }
    }
    if (naming) NameDialog("New folder", "") { name ->
        naming = false
        if (name != null) scope.launch { runCatching { repo.api().driveMakeFolder(path + name + "/") }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }; FilesNav.changes++ }
    }
    menuFor?.let { e ->
        var renaming by remember { mutableStateOf(false) }
        if (renaming) NameDialog("Rename", e.name) { name ->
            menuFor = null
            if (name != null && name != e.name) scope.launch {
                runCatching { repo.api().driveMove(e.path, path + name + if (e.folder) "/" else "") }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                FilesNav.changes++
            }
        } else AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(e.name) },
            text = { Text(if (e.folder) "Folder" else size(e.propertySize)) },
            confirmButton = { if (!DriveProvider.readOnly(e.path)) TextButton({ renaming = true }) { Text("Rename") } },
            dismissButton = {
                if (!DriveProvider.readOnly(e.path)) TextButton({
                    menuFor = null
                    scope.launch { runCatching { repo.api().driveDelete(e.path) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }; FilesNav.changes++ }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

/** A notebook's name and page style. */
@Composable
private fun NewNotebookDialog(onDone: (String?, com.syeyoung.assistant.notebook.PageStyle) -> Unit) {
    var name by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(com.syeyoung.assistant.notebook.PageStyle.DOTS) }
    AlertDialog(
        onDismissRequest = { onDone(null, style) },
        title = { Text("New notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, placeholder = { Text("Notebook") })
                com.syeyoung.assistant.notebook.StylePicker(style) { style = it }
            }
        },
        confirmButton = { TextButton({ onDone(name.trim().replace('/', ' ').ifBlank { "Notebook" }, style) }) { Text("Create") } },
        dismissButton = { TextButton({ onDone(null, style) }) { Text("Cancel") } },
    )
}

/** Previews already fetched, by path and version. */
private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(120)

/** A PDF's first page (from the server); [fallback] until it's loaded, or if there's none. */
@Composable
private fun PdfThumb(e: DriveEntry, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val key = "${e.path}|${e.modified}"
    var bitmap by remember(key) { mutableStateOf(thumbs.get(key)) }
    LaunchedEffect(key) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching { Repo.get(context).api().driveThumb(e.path, e.modified, 240) }.getOrNull()
                ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }?.also { thumbs.put(key, it) }
    }
    val b = bitmap
    if (b == null) fallback()
    else androidx.compose.foundation.Image(
        b.asImageBitmap(), null,
        Modifier.height(84.dp).clip(RoundedCornerShape(3.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
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

/** What a drag of a drive entry carries, besides its URI (for other apps): "assistant-drive:<copy|move>:<path>". */
private const val DRAG_PREFIX = "assistant-drive:"

/**
 * An entry's touches: tap to open; long-press for its menu, or long-press and drag to move it;
 * double-tap and drag to copy it (another pane, a folder, or another app).
 */
@Composable
private fun Modifier.driveGestures(onTap: () -> Unit, onMenu: () -> Unit, onDrag: (copy: Boolean) -> Unit): Modifier {
    val presses = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    return this.then(Modifier.indication(presses, androidx.compose.material3.ripple())).then(Modifier.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Shown pressed while touched (released however the touch ends).
            val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
            scope.launch { presses.emit(press) }
            try {
            // Until it's held long enough: let go (a tap), or moved away (a scroll, not ours).
            val first = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!c.pressed) return@withTimeoutOrNull true
                    if ((c.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            /** Held: dragged away (→ [drag]) or let go (→ [still]). */
            suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.holding(id: androidx.compose.ui.input.pointer.PointerId, from: androidx.compose.ui.geometry.Offset, drag: () -> Unit, still: () -> Unit) {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return
                    if (!c.pressed) return still()
                    if ((c.position - from).getDistance() > slop) { c.consume(); return drag() }
                    c.consume()
                }
            }
            when (first) {
                null -> holding(down.id, down.position, { onDrag(false) }, onMenu)
                true -> {
                    // A second touch soon after: a drag from it copies; otherwise it's a tap.
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                    if (second == null) onTap() else holding(second.id, second.position, { onDrag(true) }, onTap)
                }
                false -> {}
            }
            } finally {
                scope.launch { presses.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press)) }
            }
        }
    })
}

/** Starts a system drag of [e]: its URI (so other apps can take it) and what to do with it here. */
private fun startDriveDrag(view: android.view.View, context: Context, e: DriveEntry, copy: Boolean) {
    // Course files from PLMS stay where they are: dragging one copies it.
    val asCopy = copy || DriveProvider.readOnly(e.path)
    val item = android.content.ClipData.Item("$DRAG_PREFIX${if (asCopy) "copy" else "move"}:${e.path}", null, DriveProvider.uri(context, e.path))
    val clip = android.content.ClipData(e.name, arrayOf(if (e.folder) DriveProvider.mimeOf(e.name, true) else (e.contentType ?: DriveProvider.mimeOf(e.name, false)), android.content.ClipDescription.MIMETYPE_TEXT_PLAIN), item)
    val label = (if (asCopy) "Copy " else "Move ") + e.name
    view.startDragAndDrop(clip, LabelShadow(view, label), null, android.view.View.DRAG_FLAG_GLOBAL or android.view.View.DRAG_FLAG_GLOBAL_URI_READ)
}

/** The drag shadow: a chip saying what's being copied or moved. */
private class LabelShadow(view: android.view.View, private val label: String) : android.view.View.DragShadowBuilder(view) {
    private val density = view.resources.displayMetrics.density
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * density; color = android.graphics.Color.WHITE }
    private val back = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0303030.toInt() }
    private val shown = label.take(40)
    private val w = (text.measureText(shown) + 28 * density).toInt()
    private val h = (40 * density).toInt()
    override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) { size.set(w, h); touch.set(w / 2, h + (12 * density).toInt()) }
    override fun onDrawShadow(canvas: android.graphics.Canvas) {
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), h / 2f, h / 2f, back)
        canvas.drawText(shown, 14 * density, h / 2f + text.textSize / 3, text)
    }
}

/** Takes drops (drive entries, or files from other apps) into a folder; [locked] folders take none. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.driveDropTarget(locked: Boolean, onHover: (Boolean) -> Unit = {}, onDrop: (androidx.compose.ui.draganddrop.DragAndDropEvent) -> Unit): Modifier =
    if (locked) this else this.then(
        Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { true },
            target = object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = onHover(true)
                override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = onHover(false)
                override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = onHover(false)
                override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean { onHover(false); onDrop(event); return true }
            },
        ),
    )

/** Puts a drop into [folder]: a drive entry moved or copied (as dragged), or other apps' files uploaded. */
private suspend fun dropInto(context: Context, repo: Repo, folder: String, event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
    val e = event.toAndroidDragEvent()
    val clip = e.clipData ?: return
    val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
    val ours = items.mapNotNull { it.text?.toString()?.takeIf { t -> t.startsWith(DRAG_PREFIX) } }
    if (ours.isNotEmpty()) {
        for (t in ours) {
            val (mode, path) = t.removePrefix(DRAG_PREFIX).split(':', limit = 2)
            val isFolder = path.endsWith("/")
            val name = path.trimEnd('/').substringAfterLast('/')
            val to = folder + name + if (isFolder) "/" else ""
            if (folder.startsWith(path)) continue // Into itself.
            if (mode == "move") {
                if (path.trimEnd('/').substringBeforeLast('/') + "/" == folder) continue // Already here.
                repo.api().driveMove(path, to)
            } else repo.api().driveCopy(path, to)
        }
        return
    }
    val permissions = (context as? android.app.Activity)?.requestDragAndDropPermissions(e)
    try {
        items.mapNotNull { it.uri }.forEach { uploadInto(context, repo, folder, it) }
    } finally {
        permissions?.release()
    }
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
