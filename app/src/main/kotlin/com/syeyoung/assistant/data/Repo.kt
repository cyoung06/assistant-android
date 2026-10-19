package com.syeyoung.assistant.data

import android.content.Context
import com.syeyoung.assistant.api.models.CreateTaskRequest
import com.syeyoung.assistant.api.models.DueDto
import com.syeyoung.assistant.api.models.CreateInitiativeRequest
import com.syeyoung.assistant.api.models.EventOccurrence
import com.syeyoung.assistant.api.models.InitiativeResponse
import com.syeyoung.assistant.api.models.ProjectSchedule
import com.syeyoung.assistant.api.models.LabelResponse
import com.syeyoung.assistant.api.models.MailMessage
import com.syeyoung.assistant.api.models.TriageInputResponse
import com.syeyoung.assistant.api.models.ProjectResponse
import com.syeyoung.assistant.api.models.TaskResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Everything the task views show; the last good copy is kept on disk for offline reading. */
@Serializable
data class Snapshot(
    val tasks: List<TaskResponse> = emptyList(),
    val projects: List<ProjectResponse> = emptyList(),
    val labels: List<LabelResponse> = emptyList(),
    val initiatives: List<InitiativeResponse> = emptyList(),
    /** When it was fetched (epoch ms); 0 = never. */
    val fetchedAt: Long = 0,
)

data class State(val data: Snapshot = Snapshot(), val loading: Boolean = false, val error: String? = null)

/** A change to a task, applied on screen at once and then sent. */
data class TaskChange(
    val title: String? = null,
    val description: String? = null,
    val status: TaskResponse.Status? = null,
    val priority: TaskResponse.Priority? = null,
    /** Some(null) clears the due date. */
    val due: Optional<DueDto>? = null,
    val projectId: Optional<UUID>? = null,
    val labelIds: List<UUID>? = null,
)

data class Optional<T>(val value: T?)

/** A change to an initiative: null fields are left alone; [targetDate] holds null to clear it; [projectIds] replaces the list. */
data class InitiativeChange(
    val name: String? = null,
    val description: String? = null,
    val status: InitiativeResponse.Status? = null,
    val targetDate: Optional<java.time.LocalDate>? = null,
    val projectIds: List<UUID>? = null,
)

/** The app's data: one per process. */
class Repo private constructor(private val context: Context) {
    val settings = Settings(context)
    private val cacheFile = File(context.filesDir, "snapshot.json")
    private val _state = MutableStateFlow(State(loadCache()))
    val state: StateFlow<State> = _state
    private val refreshLock = Mutex()

    @Volatile private var cachedApi: Pair<Endpoint, Api>? = null

    /** Called after each successful refresh (reminders, widget). */
    @Volatile var onRefreshed: ((Snapshot) -> Unit)? = null

    /** The API at the current endpoint (direct, or through the embedded Tailscale bridge). */
    suspend fun api(): Api {
        val endpoint = Connection.endpoint(context, settings)
        cachedApi?.let { (e, a) -> if (e == endpoint) return a }
        return Api(endpoint).also { cachedApi = endpoint to it }
    }

    suspend fun refresh(): Boolean = refreshLock.withLock {
        _state.update { it.copy(loading = true) }
        return try {
            val api = api()
            val data = coroutineScope {
                val tasks = async { api.tasks.tasks().value() }
                val projects = async { api.tasks.projects().value() }
                val labels = async { api.tasks.labels().value() }
                val initiatives = async { api.tasks.initiatives().value() }
                Snapshot(tasks.await(), projects.await(), labels.await(), initiatives.await(), System.currentTimeMillis())
            }
            _state.value = State(data)
            refreshTriage()
            runCatching { cacheFile.writeText(AppJson.encodeToString(Snapshot.serializer(), data)) }
            runCatching { onRefreshed?.invoke(data) }
            true
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            false
        }
    }

    suspend fun change(task: TaskResponse, c: TaskChange) {
        _state.update { s -> s.copy(data = s.data.copy(tasks = s.data.tasks.map { if (it.id == task.id) applyLocally(it, c) else it })) }
        val patch = buildJsonObject {
            c.title?.let { put("title", JsonPrimitive(it)) }
            c.description?.let { put("description", JsonPrimitive(it)) }
            c.status?.let { put("status", JsonPrimitive(it.value)) }
            c.priority?.let { put("priority", JsonPrimitive(it.value)) }
            c.due?.let { d -> put("due", d.value?.let { buildJsonObject { put("date", JsonPrimitive(it.date)); it.time?.let { t -> put("time", JsonPrimitive(t)) } } } ?: JsonNull) }
            c.projectId?.let { p -> put("projectId", p.value?.let { JsonPrimitive(it.toString()) } ?: JsonNull) }
            c.labelIds?.let { ids -> put("labelIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it.toString())) } }) }
        }
        try {
            api().patchTask(task.id, patch)
        } finally {
            refresh()
        }
    }

    suspend fun create(req: CreateTaskRequest): TaskResponse = api().createTask(req).also { refresh() }

    suspend fun delete(task: TaskResponse) {
        _state.update { s -> s.copy(data = s.data.copy(tasks = s.data.tasks.filter { it.id != task.id && it.parentId != task.id })) }
        try {
            api().tasks.delete(task.id).let { if (!it.success) throw ApiException(it.status, "Couldn't delete") }
        } finally {
            refresh()
        }
    }

    suspend fun events(from: String, to: String): List<EventOccurrence> = api().calendar.occurrences(from, to).value()

    // ---- Initiatives ------------------------------------------------------------------

    suspend fun createInitiative(name: String): InitiativeResponse = api().tasks.createInitiative(CreateInitiativeRequest(name = name)).value().also { refresh() }

    /** Shows a change to an initiative at once (name, status, target, projects), then sends it as a merge patch. */
    suspend fun changeInitiative(id: UUID, c: InitiativeChange) {
        _state.update { s ->
            s.copy(data = s.data.copy(initiatives = s.data.initiatives.map { i ->
                if (i.id != id) i else i.copy(
                    name = c.name ?: i.name,
                    description = c.description ?: i.description,
                    status = c.status ?: i.status,
                    targetDate = c.targetDate?.let { it.value?.toString() } ?: i.targetDate,
                    projectIds = c.projectIds ?: i.projectIds,
                )
            }))
        }
        val patch = buildJsonObject {
            c.name?.let { put("name", JsonPrimitive(it)) }
            c.description?.let { put("description", JsonPrimitive(it)) }
            c.status?.let { put("status", JsonPrimitive(it.value)) }
            c.targetDate?.let { put("targetDate", it.value?.let { d -> JsonPrimitive(d.toString()) } ?: JsonNull) }
            c.projectIds?.let { ids -> put("projectIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it.toString())) } }) }
        }
        try {
            api().patchInitiative(id, patch)
        } finally {
            refresh()
        }
    }

    suspend fun deleteInitiative(id: UUID) {
        try {
            api().tasks.deleteInitiative(id).let { if (!it.success) throw ApiException(it.status, "Couldn't delete") }
        } finally {
            refresh()
        }
    }

    suspend fun event(id: String) = api().event(id)

    suspend fun saveEvent(id: String?, body: kotlinx.serialization.json.JsonObject) = if (id == null) api().postEvent(body) else api().patchEvent(id, body)

    suspend fun deleteEvent(id: String) = api().deleteEvent(id)

    suspend fun projectSchedule(id: UUID): ProjectSchedule = api().calendar.projectSchedule(id, 28).value()

    suspend fun mailMessage(messageId: String): MailMessage = api().mail.mailMessage(messageId).value()

    // ---- Triage ---------------------------------------------------------------------

    private val _triage = MutableStateFlow<List<TriageInputResponse>>(emptyList())
    /** Inputs with their suggestions; loaded with every refresh and while the Triage screen is open. */
    val triage: StateFlow<List<TriageInputResponse>> = _triage

    suspend fun refreshTriage() {
        runCatching { api().triage.triage().value() }.onSuccess { _triage.value = it }
    }

    /** Changes Triage on screen at once, sends it, and puts it back if the server refuses. */
    suspend fun triageAction(change: (List<TriageInputResponse>) -> List<TriageInputResponse>, send: suspend (Api) -> Unit) {
        val before = _triage.value
        _triage.value = change(before)
        try {
            send(api())
            refreshTriage()
            refresh()
        } catch (e: Exception) {
            _triage.value = before
            throw e
        }
    }

    private fun applyLocally(t: TaskResponse, c: TaskChange): TaskResponse {
        var n = t
        c.title?.let { n = n.copy(title = it) }
        c.description?.let { n = n.copy(description = it) }
        c.priority?.let { n = n.copy(priority = it) }
        c.due?.let { n = n.copy(due = it.value) }
        c.projectId?.let { n = n.copy(projectId = it.value) }
        c.labelIds?.let { n = n.copy(labelIds = it) }
        c.status?.let { s ->
            val closed = s == TaskResponse.Status.done || s == TaskResponse.Status.canceled
            n = n.copy(status = s, closedAt = if (closed) Instant.now().toString() else null, overdue = if (closed) false else n.overdue)
        }
        return n
    }

    private fun loadCache(): Snapshot =
        runCatching { AppJson.decodeFromString(Snapshot.serializer(), cacheFile.readText()) }.getOrElse { Snapshot() }

    companion object {
        @Volatile private var instance: Repo? = null
        fun get(context: Context): Repo = instance ?: synchronized(this) { instance ?: Repo(context.applicationContext).also { instance = it } }
    }
}
