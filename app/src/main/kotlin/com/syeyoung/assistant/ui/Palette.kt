package com.syeyoung.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.value
import kotlinx.coroutines.delay

/** Something the palette can do: go somewhere, make something. */
class PaletteCommand(val label: String, val keywords: String = "", val run: () -> Unit)

private class PaletteRow(val key: String, val label: String, val detail: String?, val run: () -> Unit)

/**
 * The palette: one box for everything. What's typed narrows the app's commands (go to a screen, new
 * task, quick note, new note in a project) and finds things (tasks, projects, events, notes, files,
 * mail, PLMS) to open.
 */
@Composable
fun Palette(commands: List<PaletteCommand>, onTask: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<com.syeyoung.assistant.api.models.Ref>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { found = emptyList(); return@LaunchedEffect }
        delay(200)
        runCatching { repo.api().refs.search(q, 12).value() }.onSuccess { found = it }
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun matches(text: String) = words.all { text.lowercase().contains(it) }
    val rows = buildList {
        commands.filter { matches("${it.label} ${it.keywords}") }.forEach { c -> add(PaletteRow("c:${c.label}", c.label, null, c.run)) }
        if (words.isNotEmpty()) {
            repo.state.value.data.projects.filter { matches("new note in ${it.name}") }.forEach { p ->
                add(PaletteRow("n:${p.id}", "New note in ${p.name}", null) { context.startActivity(com.syeyoung.assistant.notes.QuickNoteActivity.intent(context, notebook = false, projectId = p.id.toString())) })
            }
            found.forEach { r ->
                add(PaletteRow("r:${r.url}", r.title, listOfNotNull(r.kind.value.replace('_', ' '), r.context).joinToString(" · ")) { openRef(context, r.url, onTask) })
            }
        }
    }
    val choose = { row: PaletteRow -> onClose(); row.run() }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                    TextField(
                        query, { query = it }, Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text("Go to, make, or find anything…") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { rows.firstOrNull()?.let(choose) }),
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { choose(r) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            r.detail?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}
