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
import io.ktor.client.request.header
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
import io.ktor.http.HttpStatusCode
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
        driveFetch(path, to, null)
    }

    /** What [driveFetch] found: whether the file was written, and the version it is. */
    class Fetched(val changed: Boolean, val etag: String?)

    /**
     * Downloads a drive file to [to], unless the drive says it's still version [ifNoneMatch]
     * (then [to] is left as it is).
     */
    suspend fun driveFetch(path: String, to: java.io.File, ifNoneMatch: String?): Fetched =
        http.prepareGet("$base/api/v1/drive/file?path=${drivePath(path)}") {
            if (ifNoneMatch != null) header(HttpHeaders.IfNoneMatch, ifNoneMatch)
        }.execute { r ->
            if (r.status == HttpStatusCode.NotModified) return@execute Fetched(false, ifNoneMatch)
            if (!r.status.isSuccess()) throw ApiException(r.status.value, "HTTP ${r.status.value}")
            to.outputStream().use { out -> r.bodyAsChannel().toInputStream().use { it.copyTo(out) } }
            Fetched(true, etagOf(r))
        }

    /** A small drive file (a note, an image it shows): its bytes, version and type. */
    class DriveFile(val bytes: ByteArray, val etag: String?, val type: String?)

    suspend fun driveRead(path: String): DriveFile = driveReadIfChanged(path, null)!!

    /** A small drive file, unless it's still version [ifNoneMatch] (then null: nothing is sent). */
    suspend fun driveReadIfChanged(path: String, ifNoneMatch: String?): DriveFile? =
        http.prepareGet("$base/api/v1/drive/file?path=${drivePath(path)}") {
            if (ifNoneMatch != null) header(HttpHeaders.IfNoneMatch, ifNoneMatch)
        }.execute { r ->
            if (r.status == HttpStatusCode.NotModified) return@execute null
            if (!r.status.isSuccess()) throw ApiException(r.status.value, "HTTP ${r.status.value}")
            DriveFile(r.bodyAsChannel().toInputStream().use { it.readBytes() }, etagOf(r), r.headers[HttpHeaders.ContentType])
        }

    /**
     * Writes a small drive file; with [ifMatch] (the version it was read at) only if it's still that
     * version; with [absent], only if there's no file there yet: a 412 [ApiException] otherwise. The
     * new version, if the drive says.
     */
    suspend fun driveWrite(path: String, bytes: ByteArray, type: String?, ifMatch: String? = null, absent: Boolean = false): String? {
        val r = http.put("$base/api/v1/drive/file?path=${drivePath(path)}") {
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            if (absent) header(HttpHeaders.IfNoneMatch, "*")
            setBody(io.ktor.http.content.ByteArrayContent(bytes, type?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream))
        }
        if (!r.status.isSuccess()) throw ApiException(r.status.value, problem(r.bodyAsText()) ?: "HTTP ${r.status.value}")
        return etagOf(r)
    }

    /** A response's ETag, as the drive gave it (a proxy compressing it on the way marks it weak). */
    private fun etagOf(r: io.ktor.client.statement.HttpResponse) = r.headers[HttpHeaders.ETag]?.removePrefix("W/")

    /** A preview of a drive PDF's first page (JPEG); null if there's none. */
    suspend fun driveThumb(path: String, version: String?, width: Int): ByteArray? =
        http.prepareGet("$base/api/v1/drive/thumb?path=${drivePath(path)}&version=${drivePath(version ?: "")}&width=$width") {
            // (Without it only JSON is accepted, and the drive answers 406.)
            header(HttpHeaders.Accept, ContentType.Image.JPEG.toString())
        }.execute { r ->
            if (r.status.isSuccess()) r.bodyAsChannel().toInputStream().use { it.readBytes() } else null
        }

    /**
     * Writes [from] to a drive file (its folder is made if needed); its new version, if the drive
     * says. With [ifMatch] (the version it was based on), only if the drive still has that version;
     * with [absent], only if there's no file there yet. A 412 [ApiException] otherwise.
     */
    suspend fun driveUpload(path: String, from: java.io.File, type: String?, ifMatch: String? = null, absent: Boolean = false): String? = send {
        http.put("$base/api/v1/drive/file?path=${drivePath(path)}") {
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            if (absent) header(HttpHeaders.IfNoneMatch, "*")
            // Streamed from the file, not read into memory (scans and videos can be large).
            setBody(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                override val contentLength = from.length()
                override val contentType = type?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream
                override fun readFrom() = from.inputStream().toByteReadChannel()
            })
        }
    }.let { etagOf(it) }

    /**
     * Makes a new file at [path], or at "name (2).ext" and so on if that's taken: [put] writes it to
     * the path it's given, with `absent` set so it never replaces a file (a 412 tries the next name).
     * Where it went, and what [put] returned.
     */
    suspend fun <T> driveCreate(path: String, put: suspend (String) -> T): Pair<String, T> {
        val folder = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val name = path.substringAfterLast('/')
        val stem = name.substringBeforeLast('.').ifEmpty { name }
        val ext = name.substring(stem.length)
        for (n in 1..100) {
            val at = folder + if (n == 1) name else "$stem ($n)$ext"
            try {
                return at to put(at)
            } catch (e: ApiException) {
                if (e.status != 412) throw e
            }
        }
        throw ApiException(409, "no free name for $name")
    }

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
