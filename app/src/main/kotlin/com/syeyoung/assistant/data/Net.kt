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
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
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
    private val http = HttpClient(engine, config)

    suspend fun patchTask(id: UUID, patch: JsonObject) = send {
        http.patch("$base/api/v1/tasks/$id") { contentType(ContentType.Application.Json); setBody(patch) }
    }.let { }

    suspend fun patchEvent(id: String, patch: JsonObject) = send {
        http.patch("$base/api/v1/events/${java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")}") {
            contentType(ContentType.Application.Json); setBody(patch)
        }
    }.let { }

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
