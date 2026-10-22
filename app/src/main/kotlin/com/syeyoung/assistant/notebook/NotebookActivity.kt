package com.syeyoung.assistant.notebook

import com.syeyoung.assistant.BuildConfig

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Writing on a PDF (course material or a notebook): the pen writes, fingers move through pages,
 * and a slim toolbar can be dragged to any edge. The writing is saved into the PDF as you go.
 */
class NotebookActivity : ComponentActivity() {
    private var title by androidx.compose.runtime.mutableStateOf("")
    private lateinit var view: NotebookView
    private val renderer = PageRenderer()
    private var file: NotebookFile? = null
    /** A notebook in the drive, edited live with every device on it (the server writes the PDF); null for other PDFs. */
    private var live: NotebookSync? = null
    private val io = Mutex()
    private var dirty = false
    private var saveJob: Job? = null
    private val ui = ToolbarState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostics (adb, debug builds only): how PdfRenderer places a page under a transform with an offset.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("renderTest", false)) { RenderTest.run(this); return finish() }
        // Documents only (content://): a file:// path would be read and saved with this app's own access.
        val uri = intent.data?.takeIf { it.scheme == android.content.ContentResolver.SCHEME_CONTENT } ?: return finish()
        Notebooks.remember(this, uri)
        title = Notebooks.name(this, uri)
        ui.load(prefs())
        // A notebook in the drive (one that can be written on) is edited live; other PDFs as files of their own.
        val livePath = uri.takeIf { it.authority == com.syeyoung.assistant.drive.DriveProvider.authority(this) }
            ?.let { android.provider.DocumentsContract.getDocumentId(it) }
            ?.takeIf { !com.syeyoung.assistant.drive.DriveProvider.readOnly(it) }
        if (uri.authority == com.syeyoung.assistant.drive.DriveProvider.authority(this) && livePath == null) followConflicts(android.provider.DocumentsContract.getDocumentId(uri))
        // In the drive: where it was left is kept there (any device opens it at that page).
        placePath = uri.takeIf { it.authority == com.syeyoung.assistant.drive.DriveProvider.authority(this) }?.let { android.provider.DocumentsContract.getDocumentId(it) }

        val ink = InProgressStrokesView(this)
        view = NotebookView(this, ink, renderer).apply {
            onInkChanged = { scheduleSave() }
            onPageChanged = { p, n -> ui.page = p; ui.pages = n; keepPlace() }
            onUndoChanged = { u, r -> ui.canUndo = u; ui.canRedo = r }
            onSelectionChanged = { img -> ui.imageSelected = hasSelection; ui.groupSelected = selectionIsGroup; if (img == null) ui.cropping = false }
            onSelectionFrame = { r -> ui.selectionFrame = r }
            onLongPressEmpty = { x, y -> ui.pasteAt = android.graphics.PointF(x, y) }
            // A link in the PDF: its page here, a web address in the browser.
            onLink = { link ->
                link.target?.let { goTo(it) } ?: link.uri?.let { uri ->
                    runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))) }
                        .onFailure { Toast.makeText(this@NotebookActivity, "Couldn't open $uri", Toast.LENGTH_SHORT).show() }
                }
            }
            onTouchStart = { ui.pasteAt = null }
            onClip = { page, rect -> ui.clipping = false; clip(page, rect) }
        }
        ui.applyTo(view)
        val stack = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val changed = { ui.applyTo(view); ui.save(prefs()); keepPlace() }
                // Images dragged in from another app (split screen, pop-up view) land where they're dropped.
                val dropTarget = remember {
                    object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                        override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                            val e = event.toAndroidDragEvent()
                            val clip = e.clipData ?: return false
                            val permissions = requestDragAndDropPermissions(e)
                            val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                            // From the window to the page view's own coordinates.
                            val at = IntArray(2).also { view.getLocationInWindow(it) }
                            val x = e.x - at[0]; val y = e.y - at[1]
                            lifecycleScope.launch {
                                uris.forEach { insertImage(it, x, y) }
                                permissions?.release()
                            }
                            return uris.isNotEmpty()
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().dragAndDropTarget(
                        shouldStartDragAndDrop = { ev -> ev.mimeTypes().any { it.startsWith("image/") } },
                        target = dropTarget,
                    ),
                ) {
                    // Clear of the status and navigation bars (the window is edge to edge on phones), as notes are.
                    Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
                        NotebookHeader(ui, title, onBack = ::finish, onSearch = ::search, onHit = ::showHit, onChange = { changed(); scheduleSave() }, onPaste = ::paste)
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            Box(Modifier.fillMaxHeight().weight(1f)) {
                                AndroidView({ stack }, Modifier.fillMaxSize())
                                if (ui.readOnly) {
                                    androidx.compose.material3.Surface(
                                        Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50), shadowElevation = 6.dp,
                                    ) {
                                        Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("Read-only", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                                            TextButton({ ui.needsCopy = true }) { Text("Copy to edit") }
                                        }
                                    }
                                } else NotebookToolbar(ui, onChange = changed, onUndo = view::undo, onRedo = view::redo, onInsert = ::insertFrom)
                                if (ui.searching) Box(Modifier.align(Alignment.TopEnd).padding(end = 8.dp)) { SearchResults(ui, ::showHit) }
                            }
                            // Beside the page, not over it: the page narrows to make room.
                            if (ui.quickPages) PageSidebar(ui, ::thumbnail, pageActions, onAll = { ui.quickPages = false; ui.browsing = true }, onChange = { scheduleSave() })
                        }
                    }
                    if (ui.addingPage) AddPageDialog(ui) { style, atEnd -> addPage(style, atEnd); ui.save(prefs()) }
                    // Taking a photo to insert.
                    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
                        val f = cameraFile
                        if (ok && f != null) lifecycleScope.launch { insertImage(androidx.core.content.FileProvider.getUriForFile(this@NotebookActivity, "$packageName.files", f)) }
                    }
                    LaunchedEffect(ui.takingPhoto) {
                        if (ui.takingPhoto) {
                            ui.takingPhoto = false
                            val f = java.io.File(java.io.File(cacheDir, "exports").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
                            cameraFile = f
                            camera.launch(androidx.core.content.FileProvider.getUriForFile(this@NotebookActivity, "$packageName.files", f))
                        }
                    }
                    // Picking an image to insert.
                    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { lifecycleScope.launch { insertImage(it) } } }
                    LaunchedEffect(ui.pickingImage) {
                        if (ui.pickingImage) { ui.pickingImage = false; pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    }
                    // The bar for the selected image, floating just above it (below it near the top).
                    val frame = ui.selectionFrame
                    if (ui.imageSelected && frame != null) FloatingBar(frame, top = 57f) {
                        if (ui.cropping) {
                            TextButton({ view.resetCrop() }) { Text("Reset") }
                            TextButton({ ui.cropping = false; view.cropping = false }) { Text("Done") }
                        } else {
                            TextButton({ copySelected(); view.deleteSelection() }) { Text("Cut") }
                            TextButton(::copySelected) { Text("Copy") }
                            TextButton({ view.duplicateSelection() }) { Text("Duplicate") }
                            if (!ui.groupSelected) TextButton({ ui.cropping = true; view.cropping = true }) { Text("Crop") }
                            TextButton({ view.deleteSelection() }) { Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        }
                    }
                    // Long-press on an empty spot: paste there.
                    ui.pasteAt?.let { at ->
                        FloatingBar(android.graphics.RectF(at.x, at.y, at.x, at.y), top = 57f) {
                            TextButton({ ui.pasteAt = null; paste(at.x, at.y) }) { Text("Paste") }
                            TextButton({ ui.pasteAt = null; ui.pickingImage = true }) { Text("Insert image…") }
                        }
                    }
                    // Clipping: the view takes drags as the region; a bar says so, with a way out.
                    androidx.compose.runtime.LaunchedEffect(ui.clipping) { view.clipping = ui.clipping }
                    if (ui.clipping) {
                        androidx.compose.material3.Surface(
                            Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.inverseSurface,
                            shadowElevation = 4.dp,
                        ) {
                            androidx.compose.foundation.layout.Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Drag over what to copy as a picture", color = androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface)
                                TextButton({ ui.clipping = false }) { Text("Cancel", color = androidx.compose.material3.MaterialTheme.colorScheme.inversePrimary) }
                            }
                        }
                    }
                    if (ui.browsing) PageBrowser(ui, ::thumbnail, onGo = { view.goTo(it, animate = false) }, onChange = { scheduleSave() })
                    Dialogs()
                }
            }
        }

        if (livePath != null) {
            // Kept here only as a file (in a starred folder, never opened live): with no connection,
            // written on as that file, which goes to the drive once there is one.
            if (!com.syeyoung.assistant.notes.LiveCache(this, livePath).has() && com.syeyoung.assistant.drive.LocalCopies.file(this, livePath).exists()) {
                lifecycleScope.launch {
                    ui.status = "Opening…"
                    val reachable = runCatching { com.syeyoung.assistant.data.Repo.get(this@NotebookActivity).api().syncEpoch(livePath) }.isSuccess
                    if (reachable) openLive(livePath) else openFile(uri)
                }
                return
            }
            return openLive(livePath)
        }
        openFile(uri)
    }

    /** Opens the PDF at [uri] as a file of its own (written on in place). */
    private fun openFile(uri: android.net.Uri) {
        lifecycleScope.launch {
            ui.status = "Opening…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val opened = NotebookFile.open(this@NotebookActivity, uri) { InkIds.next() }
                    opened to renderer.open(opened.file.base, opened.sizes)
                }
            }.onSuccess { (opened, sizes) ->
                file = opened.file
                ui.readOnly = !writable(uri)
                view.readOnly = ui.readOnly
                ui.bookmarks = opened.bookmarks
                view.setDocument(sizes, opened.strokes, opened.images, opened.links)
                restorePlace()
                if (BuildConfig.DEBUG) diagnostics()
                ui.status = ""
            }.onFailure { e ->
                Toast.makeText(this@NotebookActivity, "Couldn't open this PDF: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** Opens the drive notebook at [path] live (see [NotebookSync]). */
    // ---- where it was left: kept in the drive's state of the file, so any device opens it there ----

    private var placePath: String? = null
    /** Where it was left is being put back: page changes meanwhile aren't the person's. */
    private var placing = false
    private var keepingPlace: kotlinx.coroutines.Job? = null

    /** Opens at the page it was left at (on any device), with the tool that was in hand. */
    private fun restorePlace() {
        val path = placePath ?: return
        placing = true
        lifecycleScope.launch {
            runCatching { com.syeyoung.assistant.data.Repo.get(this@NotebookActivity).api().drivePlace(path) }.onSuccess { (page, tool) ->
                page?.let { view.goTo(it, animate = false) }
                tool?.let { t -> runCatching { Tool.valueOf(t.uppercase()) }.getOrNull()?.takeIf { !ui.readOnly }?.let { ui.tool = it; ui.applyTo(view) } }
            }
            placing = false
            // Opened: for Recent.
            runCatching { com.syeyoung.assistant.data.Repo.get(this@NotebookActivity).api().driveChangeState(path, kotlinx.serialization.json.buildJsonObject { put("opened", kotlinx.serialization.json.JsonPrimitive(true)) }) }
        }
    }

    /** A copy of a course file whose course file was updated since: the person's asked what to do. */
    private fun checkSource() {
        val path = placePath ?: return
        lifecycleScope.launch {
            val a = runCatching { com.syeyoung.assistant.data.Repo.get(this@NotebookActivity).api().driveAnnotation(path) }.getOrNull() ?: return@launch
            if (a.copy == path && a.sourceChanged) ui.updatedSource = a.source
        }
    }

    /** The writing carried over to the course file's new version (on the server; the pages change here as it does). */
    private fun carryOver() {
        val path = placePath ?: return
        ui.carrying = true
        lifecycleScope.launch {
            runCatching { com.syeyoung.assistant.data.Repo.get(this@NotebookActivity).api().driveCarryOver(path) }
                .onSuccess { ui.updatedSource = null }
                .onFailure { e -> Toast.makeText(this@NotebookActivity, "Couldn't carry it over: ${e.message}", Toast.LENGTH_LONG).show() }
            ui.carrying = false
        }
    }

    private fun keepAsItIs() {
        val path = placePath ?: return
        ui.updatedSource = null
        lifecycleScope.launch { runCatching { com.syeyoung.assistant.data.Repo.get(this@NotebookActivity).api().driveKeepAnnotation(path) } }
    }

    /** The page (and tool) kept a moment after it changes. */
    private fun keepPlace() {
        val path = placePath ?: return
        if (placing) return
        keepingPlace?.cancel()
        keepingPlace = lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            val change = kotlinx.serialization.json.buildJsonObject {
                put("page", kotlinx.serialization.json.JsonPrimitive(view.currentPage))
                put("tool", kotlinx.serialization.json.JsonPrimitive(ui.tool.name.lowercase()))
            }
            runCatching { com.syeyoung.assistant.data.Repo.get(this@NotebookActivity).api().driveChangeState(path, change) }
        }
    }

    private fun openLive(path: String) {
        lifecycleScope.launch {
            ui.status = "Opening…"
            val sync = NotebookSync(this@NotebookActivity, path, onStatus = { s, m ->
                ui.status = when (s) { "connecting" -> "Connecting…"; "offline" -> "Offline (kept)"; "error" -> m ?: "Couldn't open it"; else -> "" }
            }, listener = object : NotebookSync.Listener {
                override fun pages(sources: List<PageRenderer.Source>, sizes: List<android.util.SizeF>, links: List<PdfLink>, moved: (Int) -> Int?) {
                    renderer.open(sources)
                    view.pagesChanged(sizes, moved)
                    view.setLinks(links)
                    // Page numbers changed: the text is read again on the next search.
                    text = null
                }
                override fun remote(removedStrokes: Set<Long>, putStrokes: List<InkStroke>, removedImages: Set<Long>, putImages: List<NoteImage>) =
                    view.applyRemote(removedStrokes, putStrokes, removedImages, putImages)
                override fun bookmarks(pages: Set<Int>) { ui.bookmarks = pages }
                override fun links(links: List<PdfLink>) = view.setLinks(links)
            })
            runCatching { sync.open() }.onSuccess { o ->
                live = sync
                renderer.open(o.sources)
                ui.readOnly = false
                view.readOnly = false
                ui.bookmarks = o.bookmarks
                view.setDocument(o.sizes, o.strokes, o.images, o.links)
                restorePlace()
                view.onEdit = { rs, ps, ri, pi -> sync.edited(rs, ps, ri, pi) }
                checkSource()
                ui.status = ""
            }.onFailure { e ->
                Toast.makeText(this@NotebookActivity, "Couldn't open this notebook: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Composable
    private fun Dialogs() {
        ui.updatedSource?.let { source ->
            AlertDialog(
                onDismissRequest = { if (!ui.carrying) ui.updatedSource = null },
                title = { Text("Updated on PLMS") },
                text = { Text("${source.substringAfterLast('/')} has a new version. Carry your writing over to it? Each page goes to the new page with its text; pages taken out are kept at the end.") },
                confirmButton = { TextButton({ carryOver() }, enabled = !ui.carrying) { Text(if (ui.carrying) "Carrying over…" else "Carry over") } },
                dismissButton = {
                    androidx.compose.foundation.layout.Row {
                        TextButton({ ui.updatedSource = null }, enabled = !ui.carrying) { Text("Later") }
                        TextButton({ keepAsItIs() }, enabled = !ui.carrying) { Text("Keep as it is") }
                    }
                },
            )
        }
        if (ui.confirmDelete) {
            AlertDialog(
                onDismissRequest = { ui.confirmDelete = false },
                title = { Text("Delete page ${ui.page + 1}?") },
                text = { Text("The page and its writing go. This can't be undone.") },
                confirmButton = { TextButton({ ui.confirmDelete = false; deletePage() }) { Text("Delete") } },
                dismissButton = { TextButton({ ui.confirmDelete = false }) { Text("Cancel") } },
            )
        }
        if (ui.jumping) {
            var target by remember { mutableFloatStateOf(ui.page.toFloat()) }
            AlertDialog(
                onDismissRequest = { ui.jumping = false },
                title = { Text("Page ${target.roundToInt() + 1} of ${ui.pages}") },
                text = { if (ui.pages > 1) Slider(target, { target = it }, valueRange = 0f..(ui.pages - 1).toFloat(), steps = (ui.pages - 2).coerceAtLeast(0)) },
                confirmButton = { TextButton({ ui.jumping = false; view.goTo(target.roundToInt()) }) { Text("Go") } },
                dismissButton = { TextButton({ ui.jumping = false }) { Text("Cancel") } },
            )
        }
        // A read-only PDF (a course file, a mail attachment, a download): its writing goes into a
        // copy, saved where it's chosen (starting beside the original's folder, for drive files).
        val copy = rememberLauncherForActivityResult(object : ActivityResultContracts.CreateDocument("application/pdf") {
            override fun createIntent(context: Context, input: String) = super.createIntent(context, input).apply {
                startFolder()?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }) { dest ->
            if (dest != null) {
                file?.uri = dest
                Notebooks.remember(this, dest)
                ui.readOnly = false
                view.readOnly = false
                scheduleSave(now = true)
            }
        }
        LaunchedEffect(ui.needsCopy) {
            val f = file
            if (ui.needsCopy && f != null) { ui.needsCopy = false; copy.launch(Notebooks.copyName(this@NotebookActivity, f.uri)) }
        }
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("notebooks", Context.MODE_PRIVATE)

    /** Diagnostics driven from adb (debug builds only): zooming and inserting an image file. */
    private fun diagnostics() {
        // Open at a given zoom.
        intent.getFloatExtra("zoom", 0f).takeIf { it > 0f }?.let { z -> view.post { view.zoomForTest(z) } }
        // Insert an image file.
        intent.getStringExtra("insertImage")?.let { path -> lifecycleScope.launch { insertImage(android.net.Uri.fromFile(java.io.File(path))) } }
        // Zoom slowly, as a long pinch would.
        intent.getFloatExtra("zoomTo", 0f).takeIf { it > 0f }?.let { z ->
            view.postDelayed({
                android.animation.ValueAnimator.ofFloat(1f, z).apply {
                    duration = 8000
                    addUpdateListener { view.zoomForTest(it.animatedValue as Float) }
                    start()
                }
            }, 1500)
        }
    }

    /**
     * When the drive file open here (at [path]) was changed elsewhere, the writing went into a copy
     * beside it: from then on this notebook is that copy (so it isn't copied again on every save).
     */
    private fun followConflicts(path: String) {
        var current = path
        lifecycleScope.launch {
            com.syeyoung.assistant.drive.LocalCopies.conflicts.collect { c ->
                if (c.path != current) return@collect
                current = c.copy
                val dest = com.syeyoung.assistant.drive.DriveProvider.uri(this@NotebookActivity, c.copy)
                file?.uri = dest
                Notebooks.remember(this@NotebookActivity, dest)
                title = c.copy.substringAfterLast('/')
                Toast.makeText(this@NotebookActivity, "This file was changed on another device. Your writing is in \"$title\", beside it.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Whether a document's storage says it can be written (others are found out on saving). */
    private fun writable(uri: android.net.Uri): Boolean {
        if (!android.provider.DocumentsContract.isDocumentUri(this, uri)) return true
        val flags = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        }.getOrNull() ?: return true
        return flags and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
    }

    /** Where a copy is first offered to go: a drive course file's project folder. */
    private fun startFolder(): android.net.Uri? {
        val uri = file?.uri ?: return null
        if (uri.authority != com.syeyoung.assistant.drive.DriveProvider.authority(this)) return null
        val parts = android.provider.DocumentsContract.getDocumentId(uri).split('/')
        return if (parts.size >= 3 && parts[0] == "Projects") com.syeyoung.assistant.drive.DriveProvider.uri(this, "Projects/${parts[1]}/") else null
    }

    private fun scheduleSave(now: Boolean = false) {
        // Live: the ink went as it was written; bookmarks go now (the server writes the PDF).
        live?.let { it.setBookmarks(ui.bookmarks); return }
        dirty = true
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            if (!now) delay(1500)
            save()
        }
    }

    private suspend fun save() {
        val f = file ?: return
        if (!dirty) return
        val strokes = view.strokesSnapshot()
        val images = view.imagesSnapshot()
        val bookmarks = ui.bookmarks
        dirty = false
        ui.status = "Saving…"
        val result = withContext(Dispatchers.IO) { io.withLock { runCatching { f.save(strokes, images, bookmarks) } } }
        result.onSuccess { ui.status = "" }.onFailure { e ->
            dirty = true
            ui.status = "Not saved"
            if (e is SecurityException) ui.needsCopy = true
            else Toast.makeText(this, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val pageActions = object : PageActions {
        override fun go(page: Int) = view.goTo(page)
        override fun addAfter(page: Int) { ui.addAfter = page; ui.addingPage = true }
        override fun delete(pages: Set<Int>) = deletePages(pages)
        override fun export(pages: Set<Int>) = exportPages(pages) { f -> share(f) }
        override fun print(pages: Set<Int>) = exportPages(pages) { f -> print(f) }
    }

    /** Writes [pages] (with their writing) to a PDF of their own, then hands it to [then]. */
    private fun exportPages(pages: Set<Int>, then: (java.io.File) -> Unit) {
        val f = file ?: return exportLive(pages, then)
        val strokes = view.strokesSnapshot()
        val images = view.imagesSnapshot()
        lifecycleScope.launch {
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val name = title.removeSuffix(".pdf") + if (pages.size == 1) " p${pages.first() + 1}.pdf" else " (${pages.size} pages).pdf"
            val out = java.io.File(dir, name.replace('/', '-'))
            runCatching { withContext(Dispatchers.IO) { io.withLock { f.export(strokes, images, pages, out) } } }
                .onSuccess { then(out) }
                .onFailure { e -> Toast.makeText(this@NotebookActivity, "Couldn't export: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    /** A live notebook's pages, from the PDF as the server last wrote it (a few seconds behind the writing at most). */
    private fun exportLive(pages: Set<Int>, then: (java.io.File) -> Unit) {
        val uri = intent.data ?: return
        lifecycleScope.launch {
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val name = title.removeSuffix(".pdf") + if (pages.size == 1) " p${pages.first() + 1}.pdf" else " (${pages.size} pages).pdf"
            val out = java.io.File(dir, name.replace('/', '-'))
            runCatching {
                withContext(Dispatchers.IO) {
                    val o = NotebookFile.open(this@NotebookActivity, uri) { InkIds.next() }
                    try { o.file.export(o.strokes, o.images, pages, out) } finally { o.file.close() }
                }
            }.onSuccess { then(out) }.onFailure { e -> Toast.makeText(this@NotebookActivity, "Couldn't export: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun share(f: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(android.content.Intent.createChooser(send, f.name))
    }

    /** Through Android's print service (printers, or Save as PDF). */
    private fun print(f: java.io.File) {
        val pm = getSystemService(android.print.PrintManager::class.java)
        pm.print(f.name, object : android.print.PrintDocumentAdapter() {
            override fun onLayout(old: android.print.PrintAttributes?, new: android.print.PrintAttributes, cancel: android.os.CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                cb.onLayoutFinished(android.print.PrintDocumentInfo.Builder(f.name).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>, dest: android.os.ParcelFileDescriptor, cancel: android.os.CancellationSignal?, cb: WriteResultCallback) {
                runCatching { java.io.FileOutputStream(dest.fileDescriptor).use { o -> f.inputStream().use { it.copyTo(o) } } }
                    .onSuccess { cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES)) }
                    .onFailure { cb.onWriteFailed(it.message) }
            }
        }, null)
    }

    private fun deletePages(pages: Set<Int>) {
        if (pages.isEmpty() || pages.size >= view.pageCount) return
        live?.let { l -> lifecycleScope.launch { l.deletePages(pages) }; return }
        val sorted = pages.sorted()
        pageEdit({ it.deletePages(pages) }, land = (view.currentPage - sorted.count { it < view.currentPage }).coerceAtLeast(0)) { old ->
            if (old in pages) null else old - sorted.count { it < old }
        }
    }

    private fun addPage(style: PageStyle, atEnd: Boolean) {
        val at = ui.addAfter ?: view.currentPage
        ui.addAfter = null
        val after = if (atEnd) view.pageCount - 1 else at
        live?.let { l -> lifecycleScope.launch { l.insertPage(after, ref = at, style = style); view.goTo(after + 1) }; return }
        pageEdit({ it.insertPage(after, ref = at, style = style) }, land = after + 1) { old -> if (old > after) old + 1 else old }
    }

    // ---- images: insert, paste, drop, copy ----

    /** Reads an image (as PNG or JPEG; other kinds are converted to PNG) and places it. */
    private suspend fun insertImage(uri: android.net.Uri, x: Float? = null, y: Float? = null) {
        val read = withContext(Dispatchers.IO) {
            runCatching {
                val type = contentResolver.getType(uri) ?: "image/png"
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (type == "image/png" || type == "image/jpeg") bytes to type
                else {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                    java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() to "image/png"
                }
            }.getOrNull()
        }
        val (bytes, mime) = read ?: return Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
        val placed = if (x != null && y != null) view.addImage(bytes, mime, x, y) else view.addImage(bytes, mime)
        if (!placed) Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
    }

    private var cameraFile: java.io.File? = null

    /** The toolbar's + menu. */
    private fun insertFrom(what: String) {
        when (what) {
            "image" -> ui.pickingImage = true
            "camera" -> ui.takingPhoto = true
            "paste" -> paste()
        }
    }

    /** Pastes an image from the clipboard (a screenshot, or one copied here or in another app). */
    private fun paste() = paste(null, null)

    private fun paste(x: Float?, y: Float?) {
        val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip
        val uri = clip?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri }.firstOrNull() }
        // Still what we copied: paste the real strokes (and images), not the picture of them.
        if (uri != null && uri == NoteClipboard.systemUri && (NoteClipboard.strokes.isNotEmpty() || NoteClipboard.images.isNotEmpty())) {
            if (x != null && y != null) view.pasteContents(NoteClipboard.strokes, NoteClipboard.images, x, y) else view.pasteContents(NoteClipboard.strokes, NoteClipboard.images)
            return
        }
        if (uri == null || contentResolver.getType(uri)?.startsWith("image/") != true) {
            Toast.makeText(this, "No image on the clipboard", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch { insertImage(uri, x, y) }
    }

    /**
     * Copies what's selected: kept here as strokes and images (to paste back as they were), and on
     * the clipboard as a picture (the image itself, or a drawing of the handwriting) for other apps.
     */
    private fun copySelected() {
        val (st, im) = view.selectionContents()
        if (st.isEmpty() && im.isEmpty()) return
        val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
        val f = if (st.isEmpty() && im.size == 1) {
            java.io.File(dir, "image-${im[0].id}." + if (im[0].mime == "image/jpeg") "jpg" else "png").apply { writeBytes(im[0].bytes) }
        } else {
            val bmp = view.renderSelection() ?: return
            java.io.File(dir, "selection-${System.currentTimeMillis()}.png").apply { outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        NoteClipboard.strokes = st
        NoteClipboard.images = im
        NoteClipboard.systemUri = uri
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newUri(contentResolver, "Notebook selection", uri))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * Region [rect] of [page] (the page as printed, the writing and images on it) on the clipboard
     * as a picture, sharp enough to read (about 3 pixels to the point, at most 4096 across): to paste
     * into a note, or anywhere.
     */
    private fun clip(page: Int, rect: android.graphics.RectF) {
        val k = minOf(3f, 4096f / maxOf(rect.width(), rect.height()))
        val w = (rect.width() * k).toInt().coerceAtLeast(1)
        val h = (rect.height() * k).toInt().coerceAtLeast(1)
        val m = android.graphics.Matrix().apply { setTranslate(-rect.left, -rect.top); postScale(k, k) }
        renderer.render(page, w, h, m, PageRenderer.Kind.TILE, priority = -1f) { pdf ->
            if (pdf == null) return@render
            val bmp = view.renderClip(page, rect, k, pdf)
            pdf.recycle()
            lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) {
                    val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
                    java.io.File(dir, "clip-${System.currentTimeMillis()}.png").apply { outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                }
                bmp.recycle()
                val uri = androidx.core.content.FileProvider.getUriForFile(this@NotebookActivity, "$packageName.files", f)
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newUri(contentResolver, "Clip of page ${page + 1}", uri))
                Toast.makeText(this@NotebookActivity, "Clipped: paste it in a note", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Ctrl+V on a keyboard pastes. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_V && event.isCtrlPressed) { paste(); return true }
        if (keyCode == android.view.KeyEvent.KEYCODE_C && event.isCtrlPressed) { copySelected(); return true }
        if (keyCode == android.view.KeyEvent.KEYCODE_X && event.isCtrlPressed) { copySelected(); view.deleteSelection(); return true }
        if ((keyCode == android.view.KeyEvent.KEYCODE_DEL || keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL) && view.hasSelection) { view.deleteSelection(); return true }
        if (keyCode == android.view.KeyEvent.KEYCODE_Z && event.isCtrlPressed) { if (event.isShiftPressed) view.redo() else view.undo(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---- search over the PDF's text ----

    private var text: List<PageText>? = null
    private var searchJob: Job? = null

    private fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { ui.hits = emptyList(); view.hits = emptyList(); view.focusedHit = null; return }
        val f = file ?: return
        searchJob = lifecycleScope.launch {
            delay(200)
            val pages = text ?: run {
                ui.searchIndexing = true
                withContext(Dispatchers.IO) { io.withLock { runCatching { TextSearch.extract(f.base) }.getOrDefault(emptyList()) } }.also { text = it; ui.searchIndexing = false }
            }
            val hits = withContext(Dispatchers.Default) { TextSearch.find(pages, query) }
            ui.hits = hits
            view.hits = hits
            // Start from the first match at or after the page on screen.
            val first = hits.indexOfFirst { it.page >= view.currentPage }.let { if (it < 0) 0 else it }
            if (hits.isNotEmpty()) showHit(first) else view.focusedHit = null
        }
    }

    private fun showHit(k: Int) {
        val h = ui.hits.getOrNull(k) ?: return
        ui.hitIndex = k
        view.show(h)
    }

    private fun deletePage() {
        if (view.pageCount <= 1) return
        val at = view.currentPage
        live?.let { l -> lifecycleScope.launch { l.deletePages(setOf(at)) }; return }
        pageEdit({ it.deletePage(at) }, land = at) { old -> when { old == at -> null; old > at -> old - 1; else -> old } }
    }

    /** Changes the pages, then shows the result at page [land]; [moved] says where each old page went. */
    private fun pageEdit(change: (NotebookFile) -> Unit, land: Int, moved: (Int) -> Int?) {
        val f = file ?: return
        lifecycleScope.launch {
            val sizes = runCatching { withContext(Dispatchers.IO) { io.withLock { change(f); renderer.open(f.base) } } }
                .getOrElse { e -> Toast.makeText(this@NotebookActivity, "Couldn't change pages: ${e.message}", Toast.LENGTH_LONG).show(); return@launch }
            view.pagesChanged(sizes, moved)
            ui.bookmarks = ui.bookmarks.mapNotNull(moved).toSet()
            // Page numbers changed: the text is read again on the next search.
            text = null
            if (ui.query.isNotBlank()) search(ui.query)
            view.goTo(land.coerceIn(0, sizes.size - 1))
            scheduleSave(now = true)
        }
    }

    /** A small picture of page [i] for the page browser. */
    private suspend fun thumbnail(i: Int): android.graphics.Bitmap? {
        val size = view.pageSize(i) ?: return null
        val w = (180 * resources.displayMetrics.density).toInt()
        val scale = w / size.width
        return kotlinx.coroutines.suspendCancellableCoroutine { c ->
            renderer.render(i, w, (size.height * scale).toInt(), android.graphics.Matrix().apply { setScale(scale, scale) }, PageRenderer.Kind.THUMBNAIL) { if (c.isActive) c.resumeWith(Result.success(it)) }
        }
    }

    /** The save started on leaving, which outlives this screen (its own scope would cancel it). */
    private var leaving: Job? = null

    override fun onPause() {
        super.onPause()
        if (dirty) leaving = (application as com.syeyoung.assistant.AssistantApplication).scope.launch(Dispatchers.Main) { save() }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.close()
        // Live: what's written went as it was; what's still on its way goes, then the connection.
        live?.let { l -> (application as com.syeyoung.assistant.AssistantApplication).scope.launch(Dispatchers.Main) { l.close() } }
        val f = file ?: return
        val before = leaving
        // Once anything unsaved is saved, this session's working files go.
        (application as com.syeyoung.assistant.AssistantApplication).scope.launch(Dispatchers.Main) {
            before?.join()
            save()
            if (!dirty) withContext(Dispatchers.IO) { io.withLock { f.close() } }
        }
    }
}

/**
 * A small bar of actions floating over the notebook, centred above [anchor] (screen pixels of the
 * notebook view), or below it when there's no room above. [top] is the header's height in dp.
 */
@Composable
private fun FloatingBar(anchor: android.graphics.RectF, top: Float, content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var size by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = with(density) { maxWidth.toPx() }
        val headerPx = with(density) { top.dp.toPx() }
        val gap = with(density) { 12.dp.toPx() }
        val above = anchor.top + headerPx - gap - size.height
        val y = if (above > headerPx) above else anchor.bottom + headerPx + gap
        val x = (anchor.centerX() - size.width / 2f).coerceIn(gap, (screenW - size.width - gap).coerceAtLeast(gap))
        androidx.compose.material3.Surface(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }.onSizeChanged { size = it },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50), shadowElevation = 6.dp,
        ) { Row(Modifier.padding(horizontal = 6.dp)) { content() } }
    }
}

/** The toolbar's state: tool, colours and sizes (remembered), where it sits, and page info. */
class ToolbarState {
    var tool by mutableStateOf(Tool.PEN)
    var penColor by mutableIntStateOf(0)
    var penWidth by mutableFloatStateOf(0.45f)
    var highlighterColor by mutableIntStateOf(0)
    var highlighterWidth by mutableFloatStateOf(9f)
    var eraserRadius by mutableFloatStateOf(10f)
    var mode by mutableStateOf(ScrollMode.PAGED)
    var edge by mutableStateOf(Edge.TOP)
    var along by mutableFloatStateOf(0.5f)
    var collapsed by mutableStateOf(false)

    var page by mutableIntStateOf(0)
    var pages by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var status by mutableStateOf("")
    var confirmDelete by mutableStateOf(false)
    /** The course file this notebook is a copy of, updated on PLMS since: offered to carry the writing over. */
    var updatedSource by mutableStateOf<String?>(null)
    var carrying by mutableStateOf(false)
    var jumping by mutableStateOf(false)
    var browsing by mutableStateOf(false)
    /** Marking a region to copy as a picture (see NotebookView.clipping). */
    var clipping by mutableStateOf(false)
    var quickPages by mutableStateOf(false)
    var addingPage by mutableStateOf(false)
    /** The page a new page goes after, when chosen in the page sidebar (else the current page). */
    var addAfter by mutableStateOf<Int?>(null)
    var pageStyle by mutableStateOf(PageStyle.DOTS)
    var searching by mutableStateOf(false)
    var pickingImage by mutableStateOf(false)
    var takingPhoto by mutableStateOf(false)
    /** Something is selected (an image, or a lasso group). */
    var imageSelected by mutableStateOf(false)
    var groupSelected by mutableStateOf(false)
    var cropping by mutableStateOf(false)
    /** The selected image's frame on screen, to float its bar above. */
    var selectionFrame by mutableStateOf<android.graphics.RectF?>(null)
    /** Where a long-press asked to paste. */
    var pasteAt by mutableStateOf<android.graphics.PointF?>(null)
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
    var hitIndex by mutableIntStateOf(0)
    var searchIndexing by mutableStateOf(false)
    var bookmarks by mutableStateOf<Set<Int>>(emptySet())
    var needsCopy by mutableStateOf(false)
    /** Opened only for reading (its storage doesn't let it be written): write on a copy. */
    var readOnly by mutableStateOf(false)

    fun applyTo(v: NotebookView) {
        v.tool = tool
        v.penColor = PEN_COLORS[penColor]
        v.penSize = penWidth
        v.highlighterColor = HIGHLIGHTER_COLORS[highlighterColor]
        v.highlighterSize = highlighterWidth
        v.eraserRadius = eraserRadius
        v.mode = mode
    }

    fun load(p: SharedPreferences) {
        tool = runCatching { Tool.valueOf(p.getString("tool", "PEN")!!) }.getOrDefault(Tool.PEN)
        penColor = p.getInt("penColor", 0).coerceIn(PEN_COLORS.indices)
        penWidth = p.getFloat("penWidth", 0.45f).coerceIn(SIZE_RANGES.getValue(Tool.PEN))
        highlighterColor = p.getInt("hlColor", 0).coerceIn(HIGHLIGHTER_COLORS.indices)
        highlighterWidth = p.getFloat("hlWidth", 9f).coerceIn(SIZE_RANGES.getValue(Tool.HIGHLIGHTER))
        eraserRadius = p.getFloat("eraserRadius", 10f).coerceIn(SIZE_RANGES.getValue(Tool.ERASER))
        mode = runCatching { ScrollMode.valueOf(p.getString("scroll", "PAGED")!!) }.getOrDefault(ScrollMode.PAGED)
        edge = runCatching { Edge.valueOf(p.getString("edge", "TOP")!!) }.getOrDefault(Edge.TOP)
        along = p.getFloat("along", 0.5f)
        collapsed = p.getBoolean("collapsed", false)
        pageStyle = runCatching { PageStyle.valueOf(p.getString("pageStyle", "DOTS")!!) }.getOrDefault(PageStyle.DOTS)
    }

    fun save(p: SharedPreferences) {
        p.edit().putString("tool", tool.name).putInt("penColor", penColor).putFloat("penWidth", penWidth)
            .putInt("hlColor", highlighterColor).putFloat("hlWidth", highlighterWidth).putFloat("eraserRadius", eraserRadius).putString("scroll", mode.name)
            .putString("edge", edge.name).putFloat("along", along).putBoolean("collapsed", collapsed).putString("pageStyle", pageStyle.name).apply()
    }
}
