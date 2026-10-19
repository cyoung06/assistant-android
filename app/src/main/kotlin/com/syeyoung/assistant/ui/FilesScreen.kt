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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
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
    /** The file each browser was previewing. */
    val previews = mutableMapOf<String, DriveEntry>()
    /** Each browser's unfolded tree folders. */
    val expanded = mutableMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>>()
    /** What was last copied or cut, to paste. */
    var clip by mutableStateOf<DriveClip?>(null)
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
 * Browsing a storage from its top [root] (a project's folder). Wide: a folder tree on the left
 * (like an editor's explorer; its width dragged at the divider) and, on the right, the folder
 * chosen (icons or a list) or a preview of the file chosen. Narrow: folder by folder, files
 * previewed full screen. Every entry has a menu (long-press, or right-click with a mouse);
 * long-press and drag moves it, double-tap and drag copies it.
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
    var selected by remember(root) { mutableStateOf(FilesNav.previews[navKey]) }
    var entries by remember { mutableStateOf<List<DriveEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val expanded = remember(navKey) { FilesNav.expanded.getOrPut(navKey) { androidx.compose.runtime.mutableStateMapOf() } }

    LaunchedEffect(path, FilesNav.changes) {
        FilesNav.folders[navKey] = path
        error = null
        runCatching { repo.api().driveFolder(path).propertyEntries }.onSuccess { entries = it }.onFailure { error = it.message }
    }
    LaunchedEffect(selected) { if (selected == null) FilesNav.previews.remove(navKey) else FilesNav.previews[navKey] = selected!! }
    // The tree shows where we are.
    LaunchedEffect(path) {
        var at = path
        while (at.length > root.length) { expanded[at] = true; at = parentOf(at) }
    }
    val goTo = { folder: String -> path = folder; selected = null }
    when {
        selected != null -> BackHandler { selected = null }
        path != root -> BackHandler { goTo(parentOf(path)) }
        onExit != null -> BackHandler(onBack = onExit)
    }

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
    val actions = rememberEntryActions(onOpen = { e -> if (e.folder) goTo(e.path) else selected = e }, onBusy = { busy = it })
    val drag = { e: DriveEntry, copy: Boolean -> startDriveDrag(view, context, e, copy) }

    /** The folder shown: its path, then what's in it. */
    @Composable
    fun FolderContents(withCrumbs: Boolean) {
        var paneTarget by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().driveDropTarget(locked, onHover = { paneTarget = it }) { drop(path, it) }.then(if (paneTarget) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Where we are, from the project's top.
                    val parts = path.removePrefix(root).split('/').filter { it.isNotEmpty() }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (withCrumbs || parts.isEmpty()) Crumb(rootName) { goTo(root) }
                        parts.forEachIndexed { i, p ->
                            if (withCrumbs || i > 0) Text("›", color = MaterialTheme.colorScheme.outline)
                            Crumb(p) { goTo(root + parts.take(i + 1).joinToString("/") + "/") }
                        }
                    }
                    busy?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                    IconButton({ icons = !icons; prefs.edit().putString("files.view", if (icons) "icons" else "list").apply() }) {
                        Icon(if (icons) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView, if (icons) "Show as list" else "Show as icons")
                    }
                    if (withCrumbs) {
                        IconButton({ FilesNav.changes++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
                        SplitButton()
                    }
                }
                error?.let { Text("Couldn't reach the drive: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (locked) Text("From PLMS, kept as it is there: drag a copy out to write on it.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val list = entries.orEmpty()
                if (entries != null && list.isEmpty()) Text("Nothing here yet. Upload files, or save notebooks here.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (icons) {
                    LazyVerticalGrid(GridCells.Adaptive(116.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.path }) { e ->
                            var hover by remember { mutableStateOf(false) }
                            Box {
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .then(if (hover) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                                        .then(if (e.folder) Modifier.driveDropTarget(DriveProvider.readOnly(e.path), onHover = { hover = it }) { drop(e.path, it) } else Modifier)
                                        .driveGestures({ actions.open(e) }, { actions.menu(e) }) { copy -> drag(e, copy) }.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (e.name.lowercase().endsWith(".pdf")) PdfThumb(e) { Icon(iconOf(e), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    else Icon(iconOf(e), null, Modifier.size(56.dp), tint = if (e.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(6.dp))
                                    Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontSize = 13.sp)
                                }
                                EntryMenu(e, actions)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                        items(list, key = { it.path }) { e ->
                            Box {
                                ListItem(
                                    leadingContent = { Icon(iconOf(e), null, tint = if (e.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(listOfNotNull(if (e.folder) null else size(e.propertySize), e.modified?.let(::date)).joinToString(" · ")) },
                                    modifier = Modifier
                                        .then(if (e.folder) Modifier.driveDropTarget(DriveProvider.readOnly(e.path)) { drop(e.path, it) } else Modifier)
                                        .driveGestures({ actions.open(e) }, { actions.menu(e) }) { copy -> drag(e, copy) },
                                )
                                EntryMenu(e, actions)
                            }
                        }
                    }
                }
            }

            // New things here: a notebook (opened to write in), uploaded files, a folder, or what was copied.
            // Course files take nothing new.
            if (!locked) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                androidx.compose.material3.FloatingActionButton({ creating = true }) { Icon(Icons.Filled.Add, "New") }
                androidx.compose.material3.DropdownMenu(creating, { creating = false }) {
                    androidx.compose.material3.DropdownMenuItem({ Text("New notebook") }, { creating = false; notebook = true }, leadingIcon = { Icon(Icons.Filled.EditNote, null) })
                    androidx.compose.material3.DropdownMenuItem({ Text("Upload files") }, { creating = false; upload.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Icons.Filled.Upload, null) })
                    androidx.compose.material3.DropdownMenuItem({ Text("New folder") }, { creating = false; naming = true }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) })
                    FilesNav.clip?.let { c ->
                        androidx.compose.material3.DropdownMenuItem({ Text("Paste “${c.entry.name}”") }, { creating = false; actions.paste(path) }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) })
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            // The explorer: tree | folder or preview.
            val density = androidx.compose.ui.platform.LocalDensity.current
            var treeWidth by remember { mutableStateOf(prefs.getFloat("files.treeWidth", 280f).dp) }
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(treeWidth).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(rootName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton({ FilesNav.changes++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
                        SplitButton()
                    }
                    FolderTree(root, rootName, path, selected?.path, expanded, actions, drag, drop, onRoot = { goTo(root) })
                }
                // The divider: drag to resize the tree.
                Box(
                    Modifier.fillMaxHeight().width(9.dp).pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { prefs.edit().putFloat("files.treeWidth", treeWidth.value).apply() },
                        ) { change, amount ->
                            change.consume()
                            treeWidth = (treeWidth + with(density) { amount.toDp() }).coerceIn(180.dp, 520.dp)
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.VerticalDivider() }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val s = selected
                    if (s != null) FilePreview(s, fullScreen = false, onClose = { selected = null }, onOpenWith = { actions.openWith(s) })
                    else FolderContents(withCrumbs = false)
                }
            }
        } else {
            val s = selected
            if (s != null) FilePreview(s, fullScreen = true, onClose = { selected = null }, onOpenWith = { actions.openWith(s) })
            else FolderContents(withCrumbs = true)
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
    actions.Dialogs()
}

/** The folder a drive path is in. */
private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

/** Project folders (and Projects/ itself) follow the projects: not renamed, moved or deleted from here. */
private fun fixedPath(path: String) = path == PROJECTS || (path.startsWith(PROJECTS) && path.trimEnd('/').count { it == '/' } <= 1)

/** Something copied or cut, to paste into another folder. */
class DriveClip(val entry: DriveEntry, val cut: Boolean)

/** What can be done with an entry (from its menu), and the dialogs that asks for. */
class EntryActions(
    private val context: Context,
    private val repo: Repo,
    private val scope: kotlinx.coroutines.CoroutineScope,
    val open: (DriveEntry) -> Unit,
    private val onBusy: (String?) -> Unit,
    private val saveLauncher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    /** The entry whose menu is open. */
    var menuFor by mutableStateOf<String?>(null)
    var renaming by mutableStateOf<DriveEntry?>(null)
    var deleting by mutableStateOf<DriveEntry?>(null)
    var saving: DriveEntry? = null

    fun menu(e: DriveEntry) { menuFor = e.path }

    private fun run(what: String?, block: suspend () -> Unit) = scope.launch {
        onBusy(what)
        runCatching { block() }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
        onBusy(null)
        FilesNav.changes++
    }

    fun openInEditor(e: DriveEntry) = Notebooks.open(context, DriveProvider.uri(context, e.path))

    fun openWith(e: DriveEntry) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(DriveProvider.uri(context, e.path), e.contentType ?: DriveProvider.mimeOf(e.name, false)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(view, "Open ${e.name} with")) }.onFailure { Toast.makeText(context, "No app here opens ${e.name}", Toast.LENGTH_SHORT).show() }
    }

    fun share(e: DriveEntry) {
        val send = Intent(Intent.ACTION_SEND).setType(e.contentType ?: DriveProvider.mimeOf(e.name, false))
            .putExtra(Intent.EXTRA_STREAM, DriveProvider.uri(context, e.path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${e.name}"))
    }

    fun copy(e: DriveEntry) { FilesNav.clip = DriveClip(e, cut = false) }
    fun cut(e: DriveEntry) { FilesNav.clip = DriveClip(e, cut = true) }

    /** Puts what was copied or cut into [folder]. */
    fun paste(folder: String) {
        val c = FilesNav.clip ?: return
        val from = c.entry.path
        val to = folder + c.entry.name + if (c.entry.folder) "/" else ""
        if (folder.startsWith(from)) return Toast.makeText(context, "Can't put a folder inside itself", Toast.LENGTH_SHORT).show()
        if (c.cut && parentOf(from) == folder) { FilesNav.clip = null; return }
        run(if (c.cut) "Moving…" else "Copying…") {
            if (c.cut) { repo.api().driveMove(from, to); FilesNav.clip = null } else repo.api().driveCopy(from, to)
        }
    }

    fun duplicate(e: DriveEntry) = run("Copying…") { repo.api().driveCopy(e.path, e.path) }

    fun rename(e: DriveEntry, name: String) = run(null) { repo.api().driveMove(e.path, parentOf(e.path) + name + if (e.folder) "/" else "") }

    fun delete(e: DriveEntry) = run("Deleting…") { repo.api().driveDelete(e.path) }

    fun copyPath(e: DriveEntry) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(e.name, e.path))
    }

    fun saveToDevice(e: DriveEntry) { saving = e; saveLauncher.launch(e.name) }

    /** Writes the file being saved to where it was asked to go. */
    fun saveTo(dest: Uri) {
        val e = saving ?: return
        saving = null
        run("Saving…") {
            withContext(Dispatchers.IO) {
                val f = cached(context, e)
                context.contentResolver.openOutputStream(dest, "wt")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, "Saved ${e.name}", Toast.LENGTH_SHORT).show() }
        }
    }

    @Composable
    fun Dialogs() {
        renaming?.let { e ->
            NameDialog("Rename", e.name) { name ->
                renaming = null
                if (name != null && name != e.name) rename(e, name)
            }
        }
        deleting?.let { e ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete ${e.name}?") },
                text = { Text(if (e.folder) "The folder and everything in it are deleted." else "It's deleted from the drive.") },
                confirmButton = { TextButton({ deleting = null; delete(e) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun rememberEntryActions(onOpen: (DriveEntry) -> Unit, onBusy: (String?) -> Unit): EntryActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var actions: EntryActions? = null
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest -> if (dest != null) actions?.saveTo(dest) }
    val currentOpen by androidx.compose.runtime.rememberUpdatedState(onOpen)
    val currentBusy by androidx.compose.runtime.rememberUpdatedState(onBusy)
    actions = remember { EntryActions(context, Repo.get(context), scope, { currentOpen(it) }, { currentBusy(it) }, save) }
    return actions
}

/** An entry's menu, dropped down from it while open. */
@Composable
private fun EntryMenu(e: DriveEntry, actions: EntryActions) {
    val open = actions.menuFor == e.path
    if (!open) return
    val close = { actions.menuFor = null }
    val readOnly = DriveProvider.readOnly(e.path)
    val changeable = !readOnly && !fixedPath(e.path)
    val parentTakes = !DriveProvider.readOnly(parentOf(e.path)) && parentOf(e.path) != PROJECTS
    val pdf = e.name.lowercase().endsWith(".pdf")
    androidx.compose.material3.DropdownMenu(open, close) {
        @Composable
        fun Item(text: String, icon: ImageVector, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, action: () -> Unit) =
            androidx.compose.material3.DropdownMenuItem({ Text(text, color = color) }, { close(); action() }, leadingIcon = { Icon(icon, null, tint = if (color == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) })
        Item("Open", if (e.folder) Icons.Filled.FolderOpen else Icons.Filled.Visibility) { actions.open(e) }
        if (pdf) Item("Open in editor", Icons.Filled.EditNote) { actions.openInEditor(e) }
        if (!e.folder) {
            Item("Open with…", Icons.AutoMirrored.Filled.OpenInNew) { actions.openWith(e) }
            Item("Share", Icons.Filled.Share) { actions.share(e) }
        }
        androidx.compose.material3.HorizontalDivider()
        Item("Copy", Icons.Filled.ContentCopy) { actions.copy(e) }
        if (changeable) Item("Cut", Icons.Filled.ContentCut) { actions.cut(e) }
        FilesNav.clip?.let { c -> if (e.folder && !readOnly) Item("Paste “${c.entry.name}” here", Icons.Filled.ContentPaste) { actions.paste(e.path) } }
        if (parentTakes) Item("Duplicate", Icons.Filled.FileCopy) { actions.duplicate(e) }
        if (changeable) Item("Rename", Icons.Filled.DriveFileRenameOutline) { actions.renaming = e }
        androidx.compose.material3.HorizontalDivider()
        Item("Copy path", Icons.Filled.Link) { actions.copyPath(e) }
        if (!e.folder) Item("Save to device", Icons.Filled.Download) { actions.saveToDevice(e) }
        if (changeable) Item("Delete", Icons.Filled.Delete, MaterialTheme.colorScheme.error) { actions.deleting = e }
    }
}

/** One row of the tree: an entry at a depth. */
private class TreeRow(val entry: DriveEntry, val depth: Int)

/**
 * The storage's folders and files as a tree: tap a folder to open it (and show what's in it),
 * its arrow to fold it; tap a file to preview it. What's open or previewed is highlighted.
 */
@Composable
private fun FolderTree(
    root: String,
    rootName: String,
    current: String,
    selected: String?,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    actions: EntryActions,
    drag: (DriveEntry, Boolean) -> Unit,
    drop: (String, androidx.compose.ui.draganddrop.DragAndDropEvent) -> Unit,
    onRoot: () -> Unit,
) {
    val repo = Repo.get(LocalContext.current)
    val children = remember(root) { androidx.compose.runtime.mutableStateMapOf<String, List<DriveEntry>>() }
    // Each open folder's entries (again when anything changes).
    val open = listOf(root) + expanded.filterValues { it }.keys.filter { it.startsWith(root) }.sorted()
    for (folder in open) androidx.compose.runtime.key(folder) {
        LaunchedEffect(folder, FilesNav.changes) {
            runCatching { repo.api().driveFolder(folder).propertyEntries }.onSuccess { children[folder] = it }
        }
    }
    val rows = buildList {
        fun visit(folder: String, depth: Int) {
            for (e in children[folder].orEmpty()) {
                add(TreeRow(e, depth))
                if (e.folder && expanded[e.path] == true) visit(e.path, depth + 1)
            }
        }
        visit(root, 1)
    }
    val on = MaterialTheme.colorScheme.secondaryContainer
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item(key = root) {
            var hover by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (hover) MaterialTheme.colorScheme.primaryContainer else if (current == root && selected == null) on else androidx.compose.ui.graphics.Color.Transparent)
                    .driveDropTarget(DriveProvider.readOnly(root), onHover = { hover = it }) { drop(root, it) }
                    .clickable(onClick = onRoot).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(rootName, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        items(rows, key = { it.entry.path }) { row ->
            val e = row.entry
            var hover by remember { mutableStateOf(false) }
            val highlighted = if (e.folder) selected == null && current == e.path else selected == e.path
            Box {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (hover) MaterialTheme.colorScheme.primaryContainer else if (highlighted) on else androidx.compose.ui.graphics.Color.Transparent)
                        .then(if (e.folder) Modifier.driveDropTarget(DriveProvider.readOnly(e.path), onHover = { hover = it }) { drop(e.path, it) } else Modifier)
                        .driveGestures({ if (e.folder) { expanded[e.path] = true; actions.open(e) } else actions.open(e) }, { actions.menu(e) }) { copy -> drag(e, copy) }
                        .padding(start = (row.depth * 14).dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (e.folder) {
                        val isOpen = expanded[e.path] == true
                        Icon(
                            if (isOpen) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, if (isOpen) "Fold" else "Unfold",
                            Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).clickable { expanded[e.path] = !isOpen }.padding(6.dp),
                        )
                    } else Spacer(Modifier.width(32.dp))
                    Icon(iconOf(e), null, Modifier.size(18.dp), tint = if (e.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.name, Modifier.padding(start = 8.dp, top = 7.dp, bottom = 7.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                EntryMenu(e, actions)
            }
        }
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
            // A mouse's right button: the menu, at once.
            if (currentEvent.buttons.isSecondaryPressed) {
                down.consume()
                onMenu()
                return@awaitEachGesture
            }
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

private fun size(bytes: Long) = sizeText(bytes)

private fun date(iso: String) = runCatching { OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)) }.getOrDefault("")
