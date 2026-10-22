package com.syeyoung.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syeyoung.assistant.api.models.DriveEntry
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import com.syeyoung.assistant.drive.LocalCopies
import com.syeyoung.assistant.notebook.Notebooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** How a file is shown in the preview. */
enum class PreviewKind { PDF, IMAGE, TEXT, MARKDOWN, MEDIA, OTHER }

fun previewKind(name: String): PreviewKind {
    val n = name.lowercase()
    return when {
        n.endsWith(".pdf") -> PreviewKind.PDF
        Regex("\\.(png|jpe?g|gif|webp|bmp|heic|heif|avif)$").containsMatchIn(n) -> PreviewKind.IMAGE
        Regex("\\.(md|markdown)$").containsMatchIn(n) -> PreviewKind.MARKDOWN
        Regex("\\.(txt|csv|tsv|json|ya?ml|toml|xml|html?|css|log|tex|bib|sh|py|ipynb|c|h|cc|cpp|hpp|java|kt|kts|js|jsx|ts|tsx|rs|go|rb|swift|sql|m|r|ini|conf|gradle)$").containsMatchIn(n) -> PreviewKind.TEXT
        Regex("\\.(mp4|mov|webm|mkv|m4v|mp3|m4a|wav|ogg|flac|aac)$").containsMatchIn(n) -> PreviewKind.MEDIA
        else -> PreviewKind.OTHER
    }
}

/** Text files larger than this aren't previewed. */
private const val TEXT_LIMIT = 1024 * 1024

/**
 * A drive file shown in the app: PDFs page by page (Open in editor to write on them), images
 * (pinch to zoom), text and code, Markdown (simply); media and anything else in the app that
 * handles it. [onClose] goes back (an arrow when it fills the screen, a cross beside the tree).
 */
@Composable
fun FilePreview(e: DriveEntry, fullScreen: Boolean, onClose: () -> Unit, onOpenWith: () -> Unit) {
    val context = LocalContext.current
    val kind = previewKind(e.name)
    // Notes open to write in.
    if (kind == PreviewKind.MARKDOWN) return com.syeyoung.assistant.notes.NoteEditorPane(e.path, fullScreen, onClose)
    // The file, fetched into the cache (kept per version, so reopening it is instant).
    var file by remember(e.path, e.etag) { mutableStateOf<File?>(null) }
    var failed by remember(e.path, e.etag) { mutableStateOf<String?>(null) }
    val needsFile = kind == PreviewKind.PDF || kind == PreviewKind.IMAGE || ((kind == PreviewKind.TEXT || kind == PreviewKind.MARKDOWN) && e.propertySize <= TEXT_LIMIT)
    LaunchedEffect(e.path, e.etag) {
        if (!needsFile) return@LaunchedEffect
        runCatching { cached(context, e) }.onSuccess { file = it }.onFailure { failed = it.message ?: "Couldn't fetch it" }
        // Then with the writing on it drawn in (PdfRenderer leaves annotations out), once that's ready.
        if (kind == PreviewKind.PDF && file != null) {
            runCatching { flattened(context, e) }.onSuccess { file = it }
                .onFailure { x -> android.util.Log.w("FilePreview", "couldn't draw the writing on ${e.path}", x); failed = "Couldn't show the writing on it: ${x.message}" }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(if (fullScreen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, "Close") }
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(sizeText(e.propertySize), if (DriveProvider.readOnly(e.path)) "From PLMS · read-only" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == PreviewKind.PDF) Button({ Notebooks.open(context, DriveProvider.uri(context, e.path)) }) {
                Icon(Icons.Filled.EditNote, null, Modifier.size(18.dp)); Text("  Open in editor")
            }
            IconButton(onOpenWith) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with…") }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            val f = file
            when {
                failed != null -> Text("Couldn't show it: $failed", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                !needsFile -> Elsewhere(e, kind, onOpenWith)
                f == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                kind == PreviewKind.PDF -> PdfPages(f)
                kind == PreviewKind.IMAGE -> ZoomableImage(f)
                kind == PreviewKind.MARKDOWN -> MarkdownText(remember(f) { f.readText() })
                else -> CodeText(remember(f) { f.readText() })
            }
        }
        LinkedFrom(filePage(e.path))
    }
}

/** Media, big text files and unknown kinds: open them in another app. */
@Composable
private fun Elsewhere(e: DriveEntry, kind: PreviewKind, onOpenWith: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (kind == PreviewKind.MEDIA) Icons.Filled.PlayArrow else Icons.Filled.InsertDriveFile, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when (kind) {
                PreviewKind.MEDIA -> "Plays in another app"
                PreviewKind.TEXT, PreviewKind.MARKDOWN -> "Too large to preview"
                else -> "No preview for this kind of file"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onOpenWith) { Text(if (kind == PreviewKind.MEDIA) "Play" else "Open with…") }
    }
}

/** A drive file as the drive has it now (its copy on this device; see [LocalCopies]). */
suspend fun cached(context: Context, e: DriveEntry): File = LocalCopies.fetch(context, e.path).file

/**
 * A drive PDF with its annotations (the writing done on it here) drawn into its pages, for
 * PdfRenderer, which leaves annotations out; the file itself if it has none. Made once per version
 * (checking a PDF that has none takes a moment, so the preview shows the file itself meanwhile).
 */
suspend fun flattened(context: Context, e: DriveEntry): File = withContext(Dispatchers.IO) {
    val copy = LocalCopies.fetch(context, e.path)
    // Kept per version of the file and of the drawing code (copies an older version made may be wrong).
    val drawnDirs = File(context.cacheDir, "drawn").apply { mkdirs() }
    val current = "v${com.syeyoung.assistant.notebook.Flatten.VERSION}"
    drawnDirs.listFiles()?.filter { it.name != current }?.forEach { it.deleteRecursively() }
    val dir = File(drawnDirs, current).apply { mkdirs() }
    val drawn = File(dir, Integer.toHexString("${e.path}|${copy.etag}".hashCode()) + ".pdf")
    if (copy.etag != null && drawn.exists()) return@withContext drawn
    val tmp = File(dir, drawn.name + ".part")
    try {
        if (com.syeyoung.assistant.notebook.Flatten.flatten(context, copy.file, tmp) && tmp.renameTo(drawn)) drawn else copy.file
    } finally {
        tmp.delete()
    }
}

/** A PDF's pages, one under another, drawn as they scroll into view. */
@Composable
private fun PdfPages(file: File) {
    val opened = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
    }
    val renderer = opened.getOrNull() ?: return Text("Couldn't read this PDF", color = MaterialTheme.colorScheme.error)
    // PdfRenderer draws one page at a time, and can't be closed while a page is open.
    val lock = remember(renderer) { Mutex() }
    val app = LocalContext.current.applicationContext as com.syeyoung.assistant.AssistantApplication
    // Closed once the page being drawn (if any) is done: that goes on after the preview has moved on.
    DisposableEffect(renderer) { onDispose { app.scope.launch(Dispatchers.IO) { lock.withLock { renderer.close() } } } }
    // Each page's size as it's drawn (opening every page first is slow for a long book); until then, the first page's.
    val first = remember(renderer) { if (renderer.pageCount == 0) 1 to 1 else renderer.openPage(0).use { it.width to it.height } }
    val sizes = remember(renderer) { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Int, Int>>() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 32.dp).toPx() }.toInt().coerceIn(200, 1800)
        LazyColumn(
            Modifier.fillMaxSize().transformable(zoom, lockRotationOnZoomPan = true, canPan = { scale > 1f })
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(renderer.pageCount) { i ->
                val (w, h) = sizes[i] ?: first
                // Drawn again from another file (the one with the writing drawn in, once it's ready).
                var page by remember(renderer, i, widthPx) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(renderer, i, widthPx) {
                    page = withContext(Dispatchers.IO) {
                        lock.withLock {
                            renderer.openPage(i).use { p ->
                                sizes[i] = p.width to p.height
                                Bitmap.createBitmap(widthPx, (widthPx.toLong() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(w.toFloat() / h).background(Color.White)) {
                    page?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/** An image, fitted, pinch to zoom and pan. */
@Composable
private fun ZoomableImage(file: File) {
    val bitmap = remember(file) {
        // Big photos are decoded smaller (about 2048px across is plenty for a screen).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return Text("Couldn't read this image", color = MaterialTheme.colorScheme.error)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Image(
        bitmap.asImageBitmap(), null,
        Modifier.fillMaxSize().padding(8.dp).transformable(zoom).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

@Composable
private fun CodeText(text: String) {
    SelectionContainer(Modifier.fillMaxSize()) {
        Text(
            text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}

/** Markdown, simply: headings, lists, quotes and code blocks by line (the full editor comes later). */
@Composable
private fun MarkdownText(text: String) {
    val lines = remember(text) { text.lines() }
    SelectionContainer(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var code = false
            for (line in lines) {
                if (line.trimStart().startsWith("```")) { code = !code; continue }
                val heading = Regex("^(#{1,6})\\s+(.*)").find(line)
                when {
                    code -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp))
                    heading != null -> Text(
                        heading.groupValues[2], fontWeight = FontWeight.SemiBold,
                        style = when (heading.groupValues[1].length) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Regex("^\\s*([-*+]|\\d+\\.)\\s+").containsMatchIn(line) -> Text("•  " + line.replace(Regex("^\\s*([-*+]|\\d+\\.)\\s+"), ""), Modifier.padding(start = (line.takeWhile { it == ' ' }.length * 6).dp))
                    line.startsWith(">") -> Text(line.removePrefix(">").trim(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    else -> Text(line.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("`([^`]+)`"), "$1"))
                }
            }
        }
    }
}

fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
}
