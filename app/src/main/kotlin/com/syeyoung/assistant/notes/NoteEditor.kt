package com.syeyoung.assistant.notes

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.syeyoung.assistant.data.ApiException
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A note (a Markdown file in the drive) in the web app's editor, bundled in the app (assets/editor,
 * see sync-editor.sh) and shown in a WebView. The page reaches nothing itself: it asks, and this
 * reads and writes the drive (through the app's own connection) and answers. The contract is the
 * web repo's src/embed/README.md.
 */
@SuppressLint("SetJavaScriptEnabled")
class NoteEditor(
    context: Context,
    val path: String,
    /** The editor's state for a title bar: saved, saving, unsaved, error (with a message), conflict, readonly, loading. */
    private val onStatus: (state: String, message: String?) -> Unit,
    /** A link followed in the note: a drive file ([resolvedPath]) or a web address. */
    private val onLink: (href: String, resolvedPath: String?) -> Unit,
) {
    private val app = context.applicationContext
    private val repo = Repo.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val readOnly = DriveProvider.readOnly(path)
    /** The version of the note the editor has (what saves are based on). */
    @Volatile private var etag: String? = null
    private var ready = false
    private var destroyed = false
    /** Waiting for the page's saves to finish (see [flush]). */
    private val flushed = mutableMapOf<Int, () -> Unit>()
    private var nextFlush = 0

    private val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
            // Nothing navigates away from the editor: links go to the app.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        // The page's messages, from the editor's own page only (not from anything framed in a note).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "AssistantAndroid", setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) message.data?.let(::postMessage)
            }
        }
        loadUrl("$ORIGIN/assets/editor/embed.html")
    }

    private fun postMessage(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        // Requests are numbered by the page; notices have no id.
        val id = if (m.has("id") && !m.isNull("id")) m.optLong("id", -1).takeIf { it >= 0 } else null
        val payload = m.optJSONObject("payload") ?: JSONObject()
        if (!destroyed) receive(m.optString("type"), id, payload)
    }

    private fun receive(type: String, id: Long?, p: JSONObject) {
        when (type) {
            "ready" -> { ready = true; open() }
            "status" -> onStatus(p.optString("state"), p.optString("message").takeIf { p.has("message") && !p.isNull("message") })
            "openLink" -> {
                val resolved = p.optString("resolvedPath").takeIf { p.has("resolvedPath") && !p.isNull("resolvedPath") && it.isNotBlank() }
                val href = p.optString("href")
                // Drive files, or the web and mail: nothing else (no intent:, file:, content:).
                if (resolved != null || android.net.Uri.parse(href).scheme?.lowercase() in setOf("http", "https", "mailto")) onLink(href, resolved)
            }
            "flushed" -> flushed.remove(p.optInt("n"))?.invoke()
            else -> if (id != null) answer(id) { request(type, p) }
        }
    }

    /** Runs a request off the main thread and replies with its answer (or its failure). */
    private fun answer(id: Long, block: suspend () -> JSONObject) {
        scope.launch {
            val result = runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { block() } }
            result.exceptionOrNull()?.let { Log.w("NoteEditor", "request $id: $it") }
            js("assistantEditor._reply($id, ${result.getOrNull()?.let { JSONObject.quote(it.toString()) } ?: "null"}, ${result.exceptionOrNull()?.let { JSONObject.quote(it.message ?: "failed") } ?: "undefined"})")
        }
    }

    private suspend fun request(type: String, p: JSONObject): JSONObject {
        val api = repo.api()
        return when (type) {
            "save" -> {
                if (readOnly) error("This file is kept as PLMS has it")
                val target = p.getString("path")
                // Only the note that's open here.
                require(target == path) { "Not the note that's open" }
                val base = if (p.optBoolean("force")) null else p.optString("etag").takeIf { p.has("etag") && !p.isNull("etag") && it.isNotBlank() }
                try {
                    val tag = api.driveWrite(target, p.getString("text").toByteArray(), "text/markdown; charset=utf-8", base)
                    if (target == path) etag = tag
                    JSONObject().put("etag", tag ?: JSONObject.NULL)
                } catch (e: ApiException) {
                    if (e.status == 412) JSONObject().put("conflict", true) else throw e
                }
            }
            "load" -> {
                val f = api.driveRead(p.getString("path"))
                if (p.getString("path") == path) etag = f.etag
                JSONObject().put("text", f.bytes.toString(Charsets.UTF_8)).put("etag", f.etag ?: JSONObject.NULL)
            }
            "saveCopy" -> {
                val target = p.getString("path")
                val name = target.substringAfterLast('/').let { n -> n.substringBeforeLast('.', n) + " (my copy)" + (if ('.' in n) "." + n.substringAfterLast('.') else "") }
                val text = p.getString("text").toByteArray()
                val (at, _) = api.driveCreate(parentOf(target) + name) { api.driveWrite(it, text, "text/markdown; charset=utf-8", absent = true) }
                JSONObject().put("name", at.substringAfterLast('/'))
            }
            "uploadImage" -> {
                val folder = parentOf(p.getString("notePath"))
                val dir = folder + "attachments/"
                val bytes = Base64.decode(p.getString("base64"), Base64.DEFAULT)
                val type = p.optString("mimeType").ifBlank { null }
                val (at, _) = api.driveCreate(dir + p.getString("name").replace('/', ' ')) { api.driveWrite(it, bytes, type, absent = true) }
                JSONObject().put("relativePath", "attachments/" + android.net.Uri.encode(at.substringAfterLast('/')))
            }
            "readImage" -> {
                val f = api.driveRead(p.getString("path"))
                JSONObject().put("mimeType", f.type ?: DriveProvider.mimeOf(p.getString("path"), false)).put("base64", Base64.encodeToString(f.bytes, Base64.NO_WRAP))
            }
            else -> error("Unknown request $type")
        }
    }

    /** The note's text, given to the page once it's up. */
    private fun open() {
        onStatus("loading", null)
        scope.launch {
            runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { repo.api().driveRead(path) } }
                .onSuccess { f ->
                    etag = f.etag
                    val note = JSONObject().put("path", path).put("text", f.bytes.toString(Charsets.UTF_8)).put("etag", f.etag ?: JSONObject.NULL)
                        .put("readOnly", readOnly).put("theme", theme())
                    js("assistantEditor.open($note)")
                }
                .onFailure { onStatus("error", "Couldn't open it: ${it.message}") }
        }
    }

    /** Saves what's unsaved, then [then] (also if the page doesn't answer in a few seconds). */
    fun flush(then: () -> Unit = {}) {
        if (!ready || destroyed) return then()
        val n = nextFlush++
        var done = false
        val finish = { if (!done) { done = true; then() } }
        flushed[n] = finish
        main.postDelayed({ flushed.remove(n); finish() }, 5000)
        js("assistantEditor.flush().finally(() => AssistantAndroid.postMessage(JSON.stringify({ type: 'flushed', payload: { n: $n } })))")
    }

    /** Coming back to the note: a newer version saved elsewhere is shown, if nothing's being edited here. */
    fun refresh() {
        if (!ready || destroyed) return
        scope.launch {
            val f = runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { repo.api().driveRead(path) } }.getOrNull() ?: return@launch
            if (f.etag != null && f.etag != etag) {
                js("assistantEditor.reloadIfUnchanged(${JSONObject().put("text", f.bytes.toString(Charsets.UTF_8)).put("etag", f.etag)})")
                etag = f.etag
            }
        }
    }

    fun setSource(on: Boolean) = js("assistantEditor.setSource($on)")

    fun setTheme(dark: Boolean) = js("assistantEditor.setTheme(${JSONObject.quote(if (dark) "dark" else "light")})")

    private fun theme() = if ((app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"

    /** Saves, then lets the page go. */
    fun destroy() {
        flush {
            destroyed = true
            scope.cancel()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, "AssistantAndroid")
            webView.destroy()
        }
    }

    private fun js(code: String) {
        if (destroyed) return
        main.post { if (!destroyed) webView.evaluateJavascript(code, null) }
    }

    companion object {
        /** Where the editor's page is served from (by [WebViewAssetLoader], inside the app). */
        private const val ORIGIN = "https://appassets.androidplatform.net"

        fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        fun isNote(name: String) = Regex("\\.(md|markdown)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
}
