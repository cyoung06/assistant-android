package com.syeyoung.assistant.capture

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syeyoung.assistant.api.models.CreateTaskRequest
import com.syeyoung.assistant.api.models.DueDto
import com.syeyoung.assistant.api.models.SubmitRequest
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.data.dueText
import com.syeyoung.assistant.data.refMarkdown
import com.syeyoung.assistant.data.value
import com.syeyoung.assistant.ui.AssistantTheme
import com.syeyoung.assistant.ui.Dot
import com.syeyoung.assistant.ui.DueChooser
import com.syeyoung.assistant.ui.MentionField
import com.syeyoung.assistant.ui.ProjectMenu
import com.syeyoung.assistant.ui.collectAsStateCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Something shared into the app: text, a link, or images (screenshots). */
data class Shared(val text: String, val subject: String?, val images: List<SharedImage>)

class SharedImage(val uri: Uri, val name: String, val mimeType: String, val bytes: ByteArray, val preview: ImageBitmap?)

/**
 * The share target: by default straight to Triage (the model reads it in the background), or a
 * quick task made right away.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val intent = intent
        setContent {
            AssistantTheme {
                Surface(Modifier.fillMaxSize()) {
                    var shared by remember { mutableStateOf<Shared?>(null) }
                    LaunchedEffect(Unit) { shared = withContext(Dispatchers.IO) { read(intent) } }
                    shared?.let { CaptureScreen(it, onDone = { finish() }) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }

    private fun read(intent: Intent): Shared {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        }
        val images = uris.mapNotNull { uri ->
            runCatching {
                // Any file: a picture shown, the rest by name.
                val type = contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "screenshot.${type.substringAfter('/')}"
                val preview = if (type.startsWith("image/")) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() else null
                SharedImage(uri, name, type, bytes, preview)
            }.getOrNull()
        }
        return Shared(text, subject, images)
    }
}

private val URL = Regex("""https?://\S+""")

@Composable
private fun CaptureScreen(shared: Shared, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    // Files that aren't pictures (a PDF): most likely for a project's folder.
    var mode by remember { mutableStateOf(if (shared.images.any { !it.mimeType.startsWith("image/") }) 2 else 0) }

    LaunchedEffect(Unit) { if (repo.state.value.data.fetchedAt == 0L) repo.refresh() }

    Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Capture", style = MaterialTheme.typography.titleLarge)
        if (shared.images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shared.images) { img ->
                    img.preview?.let { Image(it, img.name, Modifier.height(140.dp).width(100.dp), contentScale = ContentScale.Crop) }
                        ?: Text(img.name, Modifier.width(140.dp).padding(8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
        }
        if (shared.text.isNotBlank()) Text(shared.text.take(500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(mode == 0, { mode = 0 }, SegmentedButtonDefaults.itemShape(0, 3)) { Text("To Triage") }
            SegmentedButton(mode == 1, { mode = 1 }, SegmentedButtonDefaults.itemShape(1, 3)) { Text("Quick task") }
            SegmentedButton(mode == 2, { mode = 2 }, SegmentedButtonDefaults.itemShape(2, 3)) { Text("Project") }
        }
        when (mode) {
            0 -> SendToTriage(shared, onDone)
            1 -> QuickTask(shared, onDone)
            else -> IntoProject(shared, onDone)
        }
    }
}

/** A task from what was shared: a link becomes a reference; screenshots are attached. */
@Composable
private fun QuickTask(shared: Shared, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    val link = URL.find(shared.text)?.value
    var title by remember { mutableStateOf(shared.subject ?: shared.text.replace(URL, "").trim().lineSequence().firstOrNull().orEmpty().take(120)) }
    var description by remember { mutableStateOf(shared.text.takeIf { it.length > 120 || it.contains('\n') }.orEmpty()) }
    var due by remember { mutableStateOf<DueDto?>(null) }
    var projectId by remember { mutableStateOf<UUID?>(null) }
    var projectMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // A shared link is named by the server (PLMS pages, our own links, web page titles).
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        runCatching { repo.api().refs.resolve(link).value() }.onSuccess { ref ->
            val md = refMarkdown(ref.title, ref.url)
            if (title.isBlank() || title == link) title = md else description = (md + "\n" + description).trim()
        }
    }

    MentionField(title, { title = it }, label = "Title (@ to reference)")
    MentionField(description, { description = it }, label = "Description", minLines = 2)
    Text("Due: " + (due?.let { dueText(it, LocalDate.now()) } ?: "none"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    DueChooser(due, { due = it })
    Box {
        val project = state.data.projects.find { it.id == projectId }
        AssistChip({ projectMenu = true }, { Text(project?.name ?: "No project") }, leadingIcon = project?.let { { Dot(it.color) } })
        ProjectMenu(projectMenu, state.data.projects, { projectMenu = false }) { projectId = it }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            saving = true
            scope.launch {
                runCatching {
                    val api = repo.api()
                    val ids = shared.images.map { api.upload(it.bytes, it.name, it.mimeType).id }
                    repo.create(CreateTaskRequest(title = title.trim(), description = description, due = due, projectId = projectId, attachmentIds = ids))
                }.onSuccess { onDone() }.onFailure { error = it.message; saving = false }
            }
        },
        enabled = title.isNotBlank() && !saving,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (saving) "Saving…" else "Create task") }
}

/**
 * What was shared, into a project: the files into its folder, or (with text, or asked) a note in its
 * Notes with them beside it.
 */
@Composable
private fun IntoProject(shared: Shared, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val state by repo.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    var projectId by remember { mutableStateOf<UUID?>(null) }
    var projectMenu by remember { mutableStateOf(false) }
    var asNote by remember { mutableStateOf(shared.images.isEmpty()) }
    var title by remember { mutableStateOf(shared.subject ?: shared.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)) }
    var text by remember { mutableStateOf(shared.text) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box {
        val project = state.data.projects.find { it.id == projectId }
        AssistChip({ projectMenu = true }, { Text(project?.name ?: "No project") }, leadingIcon = project?.let { { Dot(it.color) } })
        ProjectMenu(projectMenu, state.data.projects, { projectMenu = false }) { projectId = it }
    }
    if (shared.images.isNotEmpty()) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(!asNote, { asNote = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Files") }
        SegmentedButton(asNote, { asNote = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Note") }
    }
    if (asNote) {
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Text (optional)") }, minLines = 2)
    }
    Text(
        if (asNote) "A note in the project's Notes" + if (shared.images.isEmpty()) "" else ", with the ${shared.images.size} file(s) beside it"
        else "${shared.images.size} file(s) into the project's folder",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            busy = true
            error = null
            scope.launch {
                runCatching {
                    val api = repo.api()
                    val ids = shared.images.map { api.upload(it.bytes, it.name, it.mimeType).id.toString() }
                    api.shareToProject(if (asNote) "note" else "file", projectId?.toString(), title, if (asNote) text else "", ids)
                }.onSuccess {
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    onDone()
                }.onFailure { error = it.message; busy = false }
            }
        },
        enabled = !busy && (shared.images.isNotEmpty() || title.isNotBlank() || text.isNotBlank()),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Saving…" else "Save") }
}

/**
 * Sends what was shared to Triage and closes: the model reads it in the background and its
 * suggestions wait in Triage to be accepted.
 */
@Composable
private fun SendToTriage(shared: Shared, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = Repo.get(context)
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Anything to add (optional)") })
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            busy = true
            error = null
            scope.launch {
                runCatching {
                    val api = repo.api()
                    // The model reads pictures (other files go into a project instead).
                    val ids = shared.images.filter { it.mimeType.startsWith("image/") }.map { api.upload(it.bytes, it.name, it.mimeType).id }
                    val text = listOf(shared.subject.orEmpty(), shared.text, note).filter { it.isNotBlank() }.joinToString("\n\n")
                    api.triage.submitTriage(SubmitRequest(source = "share", text = text, attachmentIds = ids, title = shared.subject.orEmpty())).value()
                }.onSuccess {
                    Toast.makeText(context, "Sent to Triage", Toast.LENGTH_SHORT).show()
                    onDone()
                }.onFailure { error = it.message; busy = false }
            }
        },
        enabled = !busy && (shared.images.isNotEmpty() || shared.text.isNotBlank() || note.isNotBlank()),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Sending…" else "Send to Triage") }
}
