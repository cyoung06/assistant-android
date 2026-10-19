package com.syeyoung.assistant.data

import android.content.Context
import com.syeyoung.assistant.api.apis.CalendarControllerApi
import com.syeyoung.assistant.api.apis.CaptureControllerApi
import com.syeyoung.assistant.api.apis.MailControllerApi
import com.syeyoung.assistant.api.apis.RefControllerApi
import com.syeyoung.assistant.api.apis.TriageControllerApi
import com.syeyoung.assistant.api.apis.TaskControllerApi
import com.syeyoung.assistant.api.models.AnalyzeRequest
import com.syeyoung.assistant.api.models.AttachmentInfo
import com.syeyoung.assistant.api.models.CreateEventRequest
import com.syeyoung.assistant.api.models.EventResponse
import com.syeyoung.assistant.api.models.CreateTaskRequest
import com.syeyoung.assistant.api.infrastructure.HttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.util.UUID

object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Absent fields are left out rather than sent as null, so the API applies its defaults. */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    serializersModule = SerializersModule { contextual(UuidSerializer) }
}

class ApiException(val status: Int, message: String) : Exception(message)

/** Where the app reaches the server: directly (tailnet VPN on), or through the embedded Tailscale bridge. */
data class Endpoint(val baseUrl: String, val headers: Map<String, String> = emptyMap())

/** The API: the generated client for most calls, plain Ktor for merge patches and uploads. */
class Api(endpoint: Endpoint) {
    private val config: (HttpClientConfig<*>) -> Unit = {
        it.install(ContentNegotiation) { json(AppJson) }
        it.install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 15_000 }
        it.defaultRequest { endpoint.headers.forEach { (k, v) -> headers.append(k, v) } }
    }
    private val base = endpoint.baseUrl.trimEnd('/')
    private val engine = OkHttp.create()

    val tasks = TaskControllerApi(base, engine, config)
    val calendar = CalendarControllerApi(base, engine, config)
    val capture = CaptureControllerApi(base, engine, config)
    val refs = RefControllerApi(base, engine, config)
    val mail = MailControllerApi(base, engine, config)
    val triage = TriageControllerApi(base, engine, config)
    val drive = com.syeyoung.assistant.api.apis.DriveControllerApi(base, engine, config)
    private val http = HttpClient(engine, config)

    suspend fun patchTask(id: UUID, patch: JsonObject) = send {
        http.patch("$base/api/v1/tasks/$id") { contentType(ContentType.Application.Json); setBody(patch) }
    }.let { }

    // Event IDs are iCalendar UIDs (often with @ or spaces): the generated client doesn't escape path values.
    private fun eventUrl(id: String) = "$base/api/v1/events/${java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")}"

    suspend fun patchEvent(id: String, patch: JsonObject) = send {
        http.patch(eventUrl(id)) { contentType(ContentType.Application.Json); setBody(patch) }
    }.let { }

    suspend fun patchInitiative(id: UUID, patch: JsonObject) = send {
        http.patch("$base/api/v1/initiatives/$id") { contentType(ContentType.Application.Json); setBody(patch) }
    }.let { }

    suspend fun event(id: String): EventResponse = send { http.get(eventUrl(id)) }.body()

    suspend fun postEvent(body: JsonObject) = send {
        http.post("$base/api/v1/events") { contentType(ContentType.Application.Json); setBody(body) }
    }.let { }

    suspend fun deleteEvent(id: String) = send { http.delete(eventUrl(id)) }.let { }

    suspend fun upload(bytes: ByteArray, filename: String, mimeType: String): AttachmentInfo = send {
        http.submitFormWithBinaryData(
            "$base/api/v1/attachments",
            formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${filename.replace("\"", "")}\"")
                })
            },
        )
    }.body()

    // ---- the drive (paths from its root; folders end with "/") ----

    private fun drivePath(p: String) = java.net.URLEncoder.encode(p, Charsets.UTF_8).replace("+", "%20")

    suspend fun driveFolder(path: String): com.syeyoung.assistant.api.models.DriveFolder = drive.folder(path).value()

    /** Copies a drive file into [to]. */
    suspend fun driveDownload(path: String, to: java.io.File) {
        http.prepareGet("$base/api/v1/drive/file?path=${drivePath(path)}").execute { r ->
            if (!r.status.isSuccess()) throw ApiException(r.status.value, "HTTP ${r.status.value}")
            to.outputStream().use { out -> r.bodyAsChannel().toInputStream().use { it.copyTo(out) } }
        }
    }

    /** A preview of a drive PDF's first page (JPEG); null if there's none. */
    suspend fun driveThumb(path: String, version: String?, width: Int): ByteArray? =
        http.prepareGet("$base/api/v1/drive/thumb?path=${drivePath(path)}&version=${drivePath(version ?: "")}&width=$width").execute { r ->
            if (r.status.isSuccess()) r.bodyAsChannel().toInputStream().use { it.readBytes() } else null
        }

    /** Writes [from] to a drive file (its folder is made if needed). */
    suspend fun driveUpload(path: String, from: java.io.File, type: String?) = send {
        http.put("$base/api/v1/drive/file?path=${drivePath(path)}") {
            // Streamed from the file, not read into memory (scans and videos can be large).
            setBody(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                override val contentLength = from.length()
                override val contentType = type?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream
                override fun readFrom() = from.inputStream().toByteReadChannel()
            })
        }
    }.let { }

    suspend fun driveMakeFolder(path: String) = send { http.post("$base/api/v1/drive/folder?path=${drivePath(path)}") }.let { }
    suspend fun driveMove(from: String, to: String) = send { http.post("$base/api/v1/drive/move?from=${drivePath(from)}&to=${drivePath(to)}") }.let { }
    /** Copies a drive file or folder to [to] (numbered if that's taken); where the copy went. */
    suspend fun driveCopy(from: String, to: String): String =
        send { http.post("$base/api/v1/drive/copy?from=${drivePath(from)}&to=${drivePath(to)}") }.bodyAsText()
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["path"]?.jsonPrimitive?.content ?: to }

    suspend fun driveDelete(path: String) = send { http.delete("$base/api/v1/drive/file?path=${drivePath(path)}") }.let { }

    suspend fun download(id: UUID): ByteArray = send { http.get("$base/api/v1/attachments/$id") }.body()

    suspend fun createTask(req: CreateTaskRequest) = tasks.create(req).value()
    suspend fun createEvent(req: CreateEventRequest) = calendar.createEvent(req).value()
    suspend fun analyze(req: AnalyzeRequest) = capture.analyzeCapture(req).value()

    private suspend fun send(block: suspend () -> io.ktor.client.statement.HttpResponse): io.ktor.client.statement.HttpResponse {
        val r = block()
        if (!r.status.isSuccess()) throw ApiException(r.status.value, problem(r.body<String>()) ?: "HTTP ${r.status.value}")
        return r
    }
}

/** The body of a generated call, or an [ApiException] with the API's problem detail. */
suspend fun <T : Any> HttpResponse<T>.value(): T {
    if (!success) throw ApiException(status, problem(response.body<String>()) ?: "HTTP $status")
    return body()
}

private fun problem(body: String): String? = runCatching {
    AppJson.parseToJsonElement(body).let { (it as JsonObject)["detail"]?.toString()?.trim('"') }
}.getOrNull()

/** Settings kept on the device. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var serverUrl: String
        get() = prefs.getString("serverUrl", DEFAULT_SERVER)!!
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()
    var useEmbeddedTailscale: Boolean
        get() = prefs.getBoolean("embeddedTailscale", false)
        set(v) = prefs.edit().putBoolean("embeddedTailscale", v).apply()
    /** Optional: an auth key from the Tailscale admin console, instead of signing in with a link. */
    var tailscaleAuthKey: String
        get() = prefs.getString("tailscaleAuthKey", "")!!
        set(v) = prefs.edit().putString("tailscaleAuthKey", v.trim()).apply()

    companion object {
        const val DEFAULT_SERVER = "https://assistant.lonk-kelvin.ts.net"
    }
}
