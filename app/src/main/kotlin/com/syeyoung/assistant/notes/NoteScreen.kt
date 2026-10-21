package com.syeyoung.assistant.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.syeyoung.assistant.drive.DriveProvider
import com.syeyoung.assistant.notebook.Notebooks
import com.syeyoung.assistant.ui.AssistantTheme

/** A note on its own screen (from a link in another note, or wherever a note is opened outside Files). */
class NoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        setContent {
            AssistantTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
                    NoteEditorPane(path, fullScreen = true, onClose = ::finish)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        /**
         * Opens the note at [path] in a window of its own (one per note: opened again, it's the same
         * one); [beside] puts it next to this one, in split screen.
         */
        fun open(context: Context, path: String, beside: Boolean = false) {
            val intent = Intent(context, NoteActivity::class.java).setData(DriveProvider.uri(context, path)).putExtra(EXTRA_PATH, path)
            if (context !is android.app.Activity || beside) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (beside) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            context.startActivity(intent)
        }
    }
}

/**
 * A note being written: its name and save state above, the editor below (staying above the
 * keyboard). Saved as it's written, and before the app leaves it; a version saved elsewhere meanwhile
 * shows when coming back, unless it's being edited here (then saving asks what to keep).
 */
@Composable
fun NoteEditorPane(path: String, fullScreen: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var state by remember(path) { mutableStateOf("loading") }
    var message by remember(path) { mutableStateOf<String?>(null) }
    var source by remember(path) { mutableStateOf(false) }
    var editing by remember(path) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val editor = remember(path) {
        NoteEditor(context, path, onStatus = { s, m -> state = s; message = m }, onLink = { href, resolved -> follow(context, href, resolved) })
    }
    DisposableEffect(editor) {
        editor.onEditing = { editing = it }
        onDispose { editor.destroy() }
    }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { editor.setTheme(dark) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(if (fullScreen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, "Close") }
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText(state, message), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (state == "error" || state == "offline") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A note opens as a preview; editing is asked for.
            if (state != "readonly" && state != "loading") {
                if (editing) androidx.compose.material3.TextButton({ editor.setEditing(false) }) { Text("Done") }
                else androidx.compose.material3.TextButton({ editor.setEditing(true) }) { Icon(Icons.Filled.Edit, null, Modifier.padding(end = 6.dp)); Text("Edit") }
            }
            IconButton({ openWith(context, path) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with…") }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (source) "Show as written (rendered)" else "Show Markdown source") }, { menu = false; source = !source; editor.setSource(source) })
                    if (!fullScreen) DropdownMenuItem({ Text("Open beside") }, { menu = false; NoteActivity.open(context, path, beside = true) })
                    DropdownMenuItem({ Text("Share") }, { menu = false; share(context, path) })
                    DropdownMenuItem({ Text("Open with…") }, { menu = false; openWith(context, path) })
                }
            }
        }
        HorizontalDivider()
        androidx.compose.runtime.key(editor) {
            // The text column at most as wide as the web's, centred; nothing drawn outside the pane
            // (Compose doesn't clip what it holds unless asked).
            Box(Modifier.fillMaxSize().imePadding().clipToBounds(), contentAlignment = Alignment.TopCenter) {
                AndroidView({ editor.view.apply { (parent as? android.view.ViewGroup)?.removeView(this) } }, Modifier.fillMaxHeight().widthIn(max = 808.dp).fillMaxWidth())
            }
        }
    }
}

/** The note changed elsewhere since it was opened: what to keep (as the web editor asks). */
private fun statusText(state: String, message: String?) = when (state) {
    "loading" -> "Opening…"
    "live" -> "Live"
    "connecting" -> "Connecting…"
    "offline" -> "Offline (edits kept)"
    "error" -> message ?: "Couldn't open it"
    "readonly" -> "From PLMS · read-only"
    else -> ""
}

/** A link followed in a note: notes open here, PDFs in the notebook editor, other drive files in their app, web addresses in the browser. */
private fun follow(context: Context, href: String, resolved: String?) {
    val view = { uri: Uri, type: String? ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).apply { if (type != null) setDataAndType(uri, type) else data = uri }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { Toast.makeText(context, "Nothing here opens it", Toast.LENGTH_SHORT).show() }
    }
    when {
        resolved == null -> view(Uri.parse(href), null)
        resolved.endsWith("/") -> Toast.makeText(context, "Open Files to see ${resolved.trimEnd('/').substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
        NoteEditor.isNote(resolved) -> NoteActivity.open(context, resolved)
        resolved.lowercase().endsWith(".pdf") -> Notebooks.open(context, DriveProvider.uri(context, resolved))
        else -> view(DriveProvider.uri(context, resolved), DriveProvider.mimeOf(resolved, false))
    }
}

private fun openWith(context: Context, path: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(DriveProvider.uri(context, path), "text/markdown").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
}

private fun share(context: Context, path: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, DriveProvider.uri(context, path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}
