package com.syeyoung.assistant.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.DriveEntry
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.notes.NoteEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate

/** "1-3, 5" as page indices (from 0); empty: all. Throws on what isn't pages. */
internal fun exportPages(text: String): List<Int>? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return t.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.flatMap { part ->
        val m = Regex("""^(\d+)(?:-(\d+))?$""").matchEntire(part) ?: throw IllegalArgumentException("“$part” isn't a page or a range")
        val from = m.groupValues[1].toInt()
        val to = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
        if (from < 1 || to < from) throw IllegalArgumentException("“$part” isn't a page or a range")
        (from..to).map { it - 1 }
    }
}

private class ExportRow(val entry: DriveEntry, on: Boolean) {
    var on by mutableStateOf(on)
    var pages by mutableStateOf("")
}

/**
 * Notebooks, PDFs and notes in [folder] made into one PDF to hand in: which, in what order, which
 * pages, and a cover page; shared (to send or save anywhere), or saved in the folder ([onSaved]).
 */
@Composable
internal fun ExportDialog(folder: String, first: String?, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("export", Context.MODE_PRIVATE) }
    val rows = remember { mutableStateListOf<ExportRow>() }
    var loaded by remember { mutableStateOf(false) }
    val parts = folder.split('/').filter { it.isNotEmpty() }
    var cover by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(parts.lastOrNull() ?: "Export") }
    var subtitle by remember { mutableStateOf(if (parts.firstOrNull() == "Projects" && parts.size > 2) parts[1] else "") }
    var author by remember { mutableStateOf(prefs.getString("author", "") ?: "") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(folder) {
        runCatching { Repo.get(context).driveFolder(folder).propertyEntries }.onSuccess { entries ->
            val files = entries.filter { !it.folder && (it.name.lowercase().endsWith(".pdf") || NoteEditor.isNote(it.name)) }
            val ordered = files.filter { it.path == first } + files.filter { it.path != first }
            rows.clear()
            rows.addAll(ordered.map { ExportRow(it, first == null || it.path == first) })
        }.onFailure { error = it.message }
        loaded = true
    }

    val picked = rows.filter { it.on }
    val fileName = ((if (cover && title.isNotBlank()) title else picked.firstOrNull()?.entry?.name?.substringBeforeLast('.')) ?: "Export").replace('/', ' ') + ".pdf"

    fun request() = buildJsonObject {
        put("items", buildJsonArray {
            for (r in picked) add(buildJsonObject {
                put("path", r.entry.path)
                if (r.entry.name.lowercase().endsWith(".pdf")) exportPages(r.pages)?.let { pages -> put("pages", buildJsonArray { pages.forEach { add(JsonPrimitive(it)) } }) }
            })
        })
        if (cover) put("cover", buildJsonObject {
            put("title", title.trim().ifEmpty { "Export" })
            subtitle.trim().takeIf { it.isNotEmpty() }?.let { put("subtitle", it) }
            author.trim().takeIf { it.isNotEmpty() }?.let { put("author", it) }
            date.trim().takeIf { it.isNotEmpty() }?.let { put("date", it) }
        })
    }

    fun run(what: String, block: suspend () -> Unit) {
        error = null
        busy = what
        prefs.edit().putString("author", author).apply()
        scope.launch {
            runCatching { block() }.onSuccess { onClose() }.onFailure { error = it.message ?: "Failed" }
            busy = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        title = { Text("Export as PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One PDF to hand in: notebooks with their writing, PDFs, and notes typeset as they show here, in this order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loaded && rows.isEmpty()) Text("No PDFs or notes in this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    itemsIndexed(rows, key = { _, r -> r.entry.path }) { i, r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(r.on, { r.on = it })
                            Text(r.entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (r.on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.entry.name.lowercase().endsWith(".pdf")) {
                                OutlinedTextField(r.pages, { r.pages = it }, Modifier.width(96.dp), enabled = r.on, singleLine = true, placeholder = { Text("All", style = MaterialTheme.typography.bodySmall) }, textStyle = MaterialTheme.typography.bodySmall)
                            }
                            IconButton({ if (i > 0) rows.add(i - 1, rows.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "Earlier") }
                            IconButton({ if (i < rows.size - 1) rows.add(i + 1, rows.removeAt(i)) }, enabled = i < rows.size - 1) { Icon(Icons.Filled.ArrowDownward, "Later") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(cover, { cover = it })
                    Text("Cover page")
                }
                if (cover) {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(subtitle, { subtitle = it }, Modifier.fillMaxWidth(), label = { Text("Course") }, singleLine = true)
                    OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("Name, student ID") }, singleLine = true)
                    OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Date") }, singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton({
                run("share") {
                    val request = request()
                    val dir = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val out = File(dir, fileName)
                    withContext(Dispatchers.IO) { Repo.get(context).api().driveExport(request, out) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", out)
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, out.name))
                }
            }, enabled = busy == null && picked.isNotEmpty()) { Text(if (busy == "share") "Making it…" else "Share") }
        },
        dismissButton = {
            Row {
                TextButton({ if (busy == null) onClose() }) { Text("Cancel") }
                TextButton({
                    run("save") {
                        val saved = Repo.get(context).api().driveExportSave(request(), folder + fileName)
                        Toast.makeText(context, "Saved as ${saved.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                }, enabled = busy == null && picked.isNotEmpty()) { Text(if (busy == "save") "Saving…" else "Save here") }
            }
        },
    )
}
