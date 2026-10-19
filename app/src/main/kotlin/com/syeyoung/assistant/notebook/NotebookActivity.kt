package com.syeyoung.assistant.notebook

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
    private var title = ""
    private lateinit var view: NotebookView
    private val renderer = PageRenderer()
    private var file: NotebookFile? = null
    private val io = Mutex()
    private var dirty = false
    private var saveJob: Job? = null
    private val ui = ToolbarState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostics (adb only): how PdfRenderer places a page under a transform with an offset.
        if (intent.getBooleanExtra("renderTest", false)) { RenderTest.run(this); return finish() }
        val uri = intent.data ?: return finish()
        Notebooks.remember(this, uri)
        title = Notebooks.name(this, uri)
        ui.load(prefs())

        val ink = InProgressStrokesView(this)
        view = NotebookView(this, ink, renderer).apply {
            onInkChanged = { scheduleSave() }
            onPageChanged = { p, n -> ui.page = p; ui.pages = n }
            onUndoChanged = { u, r -> ui.canUndo = u; ui.canRedo = r }
            onSelectionChanged = { img -> ui.imageSelected = hasSelection; ui.groupSelected = selectionIsGroup; if (img == null) ui.cropping = false }
            onSelectionFrame = { r -> ui.selectionFrame = r }
            onLongPressEmpty = { x, y -> ui.pasteAt = android.graphics.PointF(x, y) }
            onTouchStart = { ui.pasteAt = null }
        }
        ui.applyTo(view)
        val stack = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val changed = { ui.applyTo(view); ui.save(prefs()) }
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
                    Column(Modifier.fillMaxSize()) {
                        NotebookHeader(ui, title, onBack = ::finish, onSearch = ::search, onHit = ::showHit, onChange = { changed(); scheduleSave() }, onPaste = ::paste)
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            Box(Modifier.fillMaxHeight().weight(1f)) {
                                AndroidView({ stack }, Modifier.fillMaxSize())
                                NotebookToolbar(ui, onChange = changed, onUndo = view::undo, onRedo = view::redo, onInsert = ::insertFrom)
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
                    if (ui.browsing) PageBrowser(ui, ::thumbnail, onGo = { view.goTo(it, animate = false) }, onChange = { scheduleSave() })
                    Dialogs()
                }
            }
        }

        lifecycleScope.launch {
            ui.status = "Opening…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val opened = NotebookFile.open(this@NotebookActivity, uri) { InkIds.next() }
                    opened to renderer.open(opened.file.base)
                }
            }.onSuccess { (opened, sizes) ->
                file = opened.file
                ui.bookmarks = opened.bookmarks
                view.setDocument(sizes, opened.strokes, opened.images)
                // Diagnostics (adb only): open at a given zoom.
                intent.getFloatExtra("zoom", 0f).takeIf { it > 0f }?.let { z -> view.post { view.zoomForTest(z) } }
                // Diagnostics (adb only): insert an image file.
                intent.getStringExtra("insertImage")?.let { path -> lifecycleScope.launch { insertImage(android.net.Uri.fromFile(java.io.File(path))) } }
                // Diagnostics (adb only): zoom slowly, as a long pinch would.
                intent.getFloatExtra("zoomTo", 0f).takeIf { it > 0f }?.let { z ->
                    view.postDelayed({
                        android.animation.ValueAnimator.ofFloat(1f, z).apply {
                            duration = 8000
                            addUpdateListener { view.zoomForTest(it.animatedValue as Float) }
                            start()
                        }
                    }, 1500)
                }
                ui.status = ""
            }.onFailure { e ->
                Toast.makeText(this@NotebookActivity, "Couldn't open this PDF: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Composable
    private fun Dialogs() {
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
        // A read-only PDF (a mail attachment, a download): its writing goes into a copy.
        val copy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
            if (dest != null) {
                file?.uri = dest
                Notebooks.remember(this, dest)
                scheduleSave(now = true)
            }
        }
        LaunchedEffect(ui.needsCopy) {
            val f = file
            if (ui.needsCopy && f != null) { ui.needsCopy = false; copy.launch(Notebooks.copyName(this@NotebookActivity, f.uri)) }
        }
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("notebooks", Context.MODE_PRIVATE)

    private fun scheduleSave(now: Boolean = false) {
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
        val f = file ?: return
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
        val sorted = pages.sorted()
        pageEdit({ it.deletePages(pages) }, land = (view.currentPage - sorted.count { it < view.currentPage }).coerceAtLeast(0)) { old ->
            if (old in pages) null else old - sorted.count { it < old }
        }
    }

    private fun addPage(style: PageStyle, atEnd: Boolean) {
        val at = ui.addAfter ?: view.currentPage
        ui.addAfter = null
        val after = if (atEnd) view.pageCount - 1 else at
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

    override fun onPause() {
        super.onPause()
        if (dirty) lifecycleScope.launch { save() }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.close()
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
    var jumping by mutableStateOf(false)
    var browsing by mutableStateOf(false)
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
