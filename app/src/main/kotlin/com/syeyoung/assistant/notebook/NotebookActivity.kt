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
        }
        ui.applyTo(view)
        val stack = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val changed = { ui.applyTo(view); ui.save(prefs()) }
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        NotebookHeader(ui, title, onBack = ::finish, onSearch = ::search, onHit = ::showHit, onChange = { changed(); scheduleSave() })
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            Box(Modifier.fillMaxHeight().weight(1f)) {
                                AndroidView({ stack }, Modifier.fillMaxSize())
                                NotebookToolbar(ui, onChange = changed, onUndo = view::undo, onRedo = view::redo)
                                if (ui.searching) Box(Modifier.align(Alignment.TopEnd).padding(end = 8.dp)) { SearchResults(ui, ::showHit) }
                            }
                            // Beside the page, not over it: the page narrows to make room.
                            if (ui.quickPages) PageSidebar(ui, ::thumbnail, pageActions, onAll = { ui.quickPages = false; ui.browsing = true }, onChange = { scheduleSave() })
                        }
                    }
                    if (ui.addingPage) AddPageDialog(ui) { style, atEnd -> addPage(style, atEnd); ui.save(prefs()) }
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
                view.setDocument(sizes, opened.strokes)
                // Diagnostics (adb only): open at a given zoom.
                intent.getFloatExtra("zoom", 0f).takeIf { it > 0f }?.let { z -> view.post { view.zoomForTest(z) } }
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
        val bookmarks = ui.bookmarks
        dirty = false
        ui.status = "Saving…"
        val result = withContext(Dispatchers.IO) { io.withLock { runCatching { f.save(strokes, bookmarks) } } }
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
        lifecycleScope.launch {
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val name = title.removeSuffix(".pdf") + if (pages.size == 1) " p${pages.first() + 1}.pdf" else " (${pages.size} pages).pdf"
            val out = java.io.File(dir, name.replace('/', '-'))
            runCatching { withContext(Dispatchers.IO) { io.withLock { f.export(strokes, pages, out) } } }
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
