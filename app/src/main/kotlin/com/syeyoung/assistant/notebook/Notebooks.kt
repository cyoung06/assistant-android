package com.syeyoung.assistant.notebook

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray

/** PDFs written on recently (until the drive holds them), and blank notebooks. */
object Notebooks {
    data class Recent(val uri: Uri, val name: String, val openedAt: Long)

    private fun prefs(context: Context) = context.getSharedPreferences("notebooks", Context.MODE_PRIVATE)

    fun recent(context: Context): List<Recent> {
        val raw = JSONArray(prefs(context).getString("recent", "[]"))
        return (0 until raw.length()).map { raw.getJSONObject(it) }.map { Recent(Uri.parse(it.getString("uri")), it.getString("name"), it.getLong("at")) }
    }

    fun remember(context: Context, uri: Uri) {
        // Keeps access across restarts when the picker allowed it (it does for Open PDF and New notebook).
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val list = listOf(Recent(uri, name(context, uri), System.currentTimeMillis())) + recent(context).filter { it.uri != uri }
        val raw = JSONArray()
        list.take(30).forEach { raw.put(org.json.JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("at", it.openedAt)) }
        prefs(context).edit().putString("recent", raw.toString()).apply()
    }

    fun forget(context: Context, uri: Uri) {
        val raw = JSONArray()
        recent(context).filter { it.uri != uri }.forEach { raw.put(org.json.JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("at", it.openedAt)) }
        prefs(context).edit().putString("recent", raw.toString()).apply()
    }

    fun name(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Notebook.pdf"

    fun copyName(context: Context, uri: Uri) = name(context, uri).removeSuffix(".pdf") + " (notes).pdf"

    /** A blank notebook: [pages] plain A4 pages, written to [uri]. (Pages stay empty: drawn patterns made every page slow to render.) */
    fun writeBlank(context: Context, uri: Uri, pages: Int = 20) {
        val doc = PdfDocument()
        repeat(pages) { i -> doc.finishPage(doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())) }
        context.contentResolver.openOutputStream(uri, "wt")!!.use { doc.writeTo(it) }
        doc.close()
    }

    fun open(context: Context, uri: Uri) {
        context.startActivity(Intent(context, NotebookActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
}
