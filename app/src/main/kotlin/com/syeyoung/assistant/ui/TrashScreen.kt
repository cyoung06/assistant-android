package com.syeyoung.assistant.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.Trashed
import kotlinx.coroutines.launch

/**
 * The drive's trash: what was deleted (here, on the web, on the Mac, or in Finder), kept 30 days.
 * Each can be put back where it was, or deleted for good; or the whole trash emptied.
 */
@Composable
fun TrashScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repo.get(context) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Trashed>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var purging by remember { mutableStateOf<Trashed?>(null) }
    var emptying by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { repo.api().driveTrash() }.onSuccess { items = it; error = null }.onFailure { error = it.message }
    }
    fun act(work: suspend () -> Unit) = scope.launch {
        runCatching { work() }.onFailure { error = it.message }
        load()
        FilesNav.changes++
    }
    LaunchedEffect(Unit) { load() }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text("Trash", style = MaterialTheme.typography.titleLarge)
                        Text("Deleted things are kept 30 days.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ emptying = true }, enabled = !items.isNullOrEmpty()) { Text("Empty Trash") }
                }
                HorizontalDivider()
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                val list = items
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("The trash is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { t ->
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if (t.folder) Icons.Filled.Folder else Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val from = parentOf(t.path).removePrefix("Projects/").ifEmpty { "the drive" }
                                    val whenText = DateUtils.getRelativeTimeSpanString(t.deletedAt.toEpochMilli())
                                    Text("from $from · $whenText" + if (t.folder) "" else " · " + Formatter.formatShortFileSize(context, t.size),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton({ act { note = "“${t.name}” is back in " + parentOf(repo.api().driveRestore(t.id)).removePrefix("Projects/").ifEmpty { "the drive" } } }) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, "Put back")
                                }
                                IconButton({ purging = t }) { Icon(Icons.Filled.DeleteForever, "Delete for good", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    purging?.let { t ->
        AlertDialog(
            onDismissRequest = { purging = null },
            title = { Text("Delete ${t.name} for good?") },
            text = { Text("It can't be put back after this.") },
            confirmButton = { TextButton({ purging = null; act { repo.api().drivePurge(t.id) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ purging = null }) { Text("Keep") } },
        )
    }
    if (emptying) {
        AlertDialog(
            onDismissRequest = { emptying = false },
            title = { Text("Empty the trash?") },
            text = { Text("Everything in it is deleted for good.") },
            confirmButton = { TextButton({ emptying = false; act { repo.api().driveEmptyTrash() } }) { Text("Empty Trash", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ emptying = false }) { Text("Keep") } },
        )
    }
}
