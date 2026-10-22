package com.syeyoung.assistant.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.Proposal
import com.syeyoung.assistant.api.models.TriageInputResponse
import com.syeyoung.assistant.api.models.TriageItemResponse
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.dueText
import com.syeyoung.assistant.data.messageIdOf
import com.syeyoung.assistant.data.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val SOURCES = mapOf("share" to "Shared", "capture" to "Captured", "addin" to "From Outlook", "mail" to "Mail", "chatgpt" to "From ChatGPT", "plms" to "PLMS")

/** Waiting suggestions plus inputs the model hasn't read yet: the drawer's count. */
fun triageCount(inputs: List<TriageInputResponse>) = inputs.sumOf { i -> if (i.status == "pending") 1 else i.items.count { it.status == "pending" } }

/**
 * Triage, as on the web: what was shared or came in by mail, read by the model, with suggestions.
 * Swipe a suggestion right to accept it (a task or event is made) or left to decline; edit its title
 * and project first if needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriageScreen(onOpenTask: (UUID) -> Unit, onOpenMail: (String) -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val inputs by repo.triage.collectAsStateCompat()
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    val edits = remember { mutableStateMapOf<UUID, Pair<String, UUID?>>() }
    var refreshing by remember { mutableStateOf(false) }
    var showMaybe by remember { mutableStateOf(false) }
    var showNothing by remember { mutableStateOf(false) }

    // While the model reads, look again every few seconds.
    LaunchedEffect(Unit) {
        while (true) {
            repo.refreshTriage()
            delay(if (repo.triage.value.any { it.status == "pending" }) 5_000 else 30_000)
        }
    }

    fun act(change: (List<TriageInputResponse>) -> List<TriageInputResponse>, send: suspend (com.syeyoung.assistant.data.Api) -> Unit) {
        scope.launch {
            runCatching { repo.triageAction(change, send) }.onFailure { Toast.makeText(context, it.message ?: "Couldn't save", Toast.LENGTH_SHORT).show() }
        }
    }

    fun decide(item: TriageItemResponse, accept: Boolean) {
        val (title, projectId) = edits[item.id] ?: (item.proposal.title to item.proposal.projectId)
        val proposal = item.proposal.copy(title = title, projectId = projectId)
        val status = if (accept) "accepted" else "declined"
        act({ list -> list.map { i -> i.copy(items = i.items.map { if (it.id == item.id) it.copy(status = status) else it }) } }) { api ->
            if (accept) api.triage.acceptTriage(item.id, proposal).value() else api.triage.declineTriage(item.id).value()
        }
    }

    val undecided = inputs.filter { i -> i.items.any { it.status == "pending" } }
    val open = undecided.filter { it.verdict != "maybe" }
    val maybe = undecided.filter { it.verdict == "maybe" }
    val reading = inputs.filter { it.status == "pending" }
    val failed = inputs.filter { it.status == "failed" }
    val nothing = inputs.filter { it.status == "done" && it.items.isEmpty() }
    val projects = state.data.projects.filter { it.state.value != "completed" && it.state.value != "canceled" }

    @Composable
    fun card(i: TriageInputResponse) = InputCard(
        i,
        onOpenMail = onOpenMail,
        onRetry = { act({ it }) { api -> api.triage.retryTriage(i.id).value() } },
        onDismiss = { act({ list -> list.filter { it.id != i.id } }) { api -> api.triage.dismissTriage(i.id).value() } },
    ) {
        i.items.forEach { item ->
            if (item.status == "pending") {
                val (title, projectId) = edits[item.id] ?: (item.proposal.title to item.proposal.projectId)
                SwipeToDecide(onAccept = { decide(item, true) }, onDecline = { decide(item, false) }) {
                    Suggestion(
                        item.proposal, title, projectId, projects, item.sameAs?.let { n -> state.data.tasks.find { it.number == n } }?.let { it.number to it.id },
                        onTitle = { edits[item.id] = it to projectId }, onProject = { edits[item.id] = title to it },
                        onAccept = { decide(item, true) }, onDecline = { decide(item, false) }, onOpenTask = onOpenTask,
                    )
                }
            } else {
                Text(
                    "${if (item.proposal.kind == "event") "Event" else "Task"} · ${item.proposal.title} · ${if (item.status == "accepted") "accepted" else "declined"}",
                    Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; repo.refreshTriage(); refreshing = false } }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (open.isEmpty() && reading.isEmpty() && failed.isEmpty()) item {
                Text("All clear. Share a screenshot to Assistant and the model will suggest tasks here.", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(reading, key = { "r" + it.id }) { i -> InputCard(i, onOpenMail, {}, { act({ l -> l.filter { it.id != i.id } }) { api -> api.triage.dismissTriage(i.id).value() } }) {
                Text(i.error?.let { "Waiting: $it" } ?: "Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } }
            items(open, key = { "o" + it.id }) { card(it) }
            if (maybe.isNotEmpty()) {
                item(key = "maybe") { Fold("Maybe", "${maybe.size} · might not apply to you", showMaybe) { showMaybe = !showMaybe } }
                if (showMaybe) items(maybe, key = { "m" + it.id }) { card(it) }
            }
            items(failed, key = { "f" + it.id }) { i -> card(i) }
            if (nothing.isNotEmpty()) {
                item(key = "nothing") { Fold("Nothing to do", "${nothing.size}", showNothing) { showNothing = !showNothing } }
                if (showNothing) items(nothing, key = { "n" + it.id }) { card(it) }
            }
        }
    }
}

@Composable
private fun Fold(title: String, detail: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (open) "▾ " else "▸ ") + title, fontWeight = FontWeight.SemiBold)
        Text("  $detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InputCard(i: TriageInputResponse, onOpenMail: (String) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listOfNotNull((SOURCES[i.source] ?: i.source).uppercase(), i.sender?.replace(Regex("""\s*<.*>$"""), ""), ago(i.createdAt)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val mail = i.link?.let(::messageIdOf)
            Text(
                (if (mail != null) "✉ " else "") + i.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (mail != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = if (mail != null) Modifier.clickable { onOpenMail(mail) } else Modifier,
            )
            if (i.attachments.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(i.attachments) { a -> Shot(a.id) } }
            i.reason?.let { Text((if (i.verdict == "nothing") "Nothing to do: " else "") + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (i.status == "failed") Text("Couldn't read this: ${i.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (i.status != "pending") TextButton(onRetry) { Text("Read again") }
                TextButton(onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** A shared screenshot, loaded through the API. */
@Composable
private fun Shot(id: UUID) {
    val repo = Repo.get(LocalContext.current)
    var image by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        image = runCatching { repo.api().download(id) }.getOrNull()?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
    }
    Box(Modifier.height(120.dp).width(90.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Swipe right to accept, left to decline; the row springs back if the swipe doesn't go far. */
@Composable
private fun SwipeToDecide(onAccept: () -> Unit, onDecline: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> onAccept()
            SwipeToDismissBoxValue.EndToStart -> onDecline()
            else -> {}
        }
        false
    })
    SwipeToDismissBox(
        swipe,
        backgroundContent = {
            val accept = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize().background(if (accept) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (accept) Arrangement.Start else Arrangement.End,
            ) {
                Icon(if (accept) Icons.Filled.Check else Icons.Filled.Close, null)
                Text(if (accept) " Accept" else " Decline")
            }
        },
    ) { content() }
}

@Composable
private fun Suggestion(
    p: Proposal, title: String, projectId: UUID?, projects: List<com.syeyoung.assistant.api.models.ProjectResponse>, same: Pair<Long, UUID>?,
    onTitle: (String) -> Unit, onProject: (UUID?) -> Unit, onAccept: () -> Unit, onDecline: () -> Unit, onOpenTask: (UUID) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (p.kind == "event") "EVENT" else "TASK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        BasicTextField(
            title, onTitle, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(whenText(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                val project = projects.find { it.id == projectId }
                AssistChip({ menu = true }, { Text(project?.name ?: "No project", maxLines = 1) }, leadingIcon = project?.let { { Dot(it.color) } })
                ProjectMenu(menu, projects, { menu = false }) { onProject(it) }
            }
            same?.let { (n, id) -> AssistChip({ onOpenTask(id) }, { Text("Looks like #$n") }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onDecline) { Text("Decline") }
            Button(onAccept) { Text("Accept") }
        }
    }
}

private fun whenText(p: Proposal): String {
    val zone = ZoneId.systemDefault()
    return when {
        p.kind == "event" && p.start != null -> {
            val s = OffsetDateTime.parse(p.start).atZoneSameInstant(zone)
            s.format(DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.ENGLISH)) + (p.end?.let { "–" + OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalTime().toString().take(5) } ?: "")
        }
        p.kind == "event" && p.startDate != null -> LocalDate.parse(p.startDate).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)) + " (all day)"
        p.due != null -> "Due " + dueText(p.due!!, LocalDate.now())
        else -> "No due date"
    }
}

private fun ago(iso: String): String {
    val minutes = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    }
}
