package com.syeyoung.assistant.notebook

import android.content.Context
import android.content.Intent
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

    /** A new notebook: [pages] A4 pages in [style] (see [BlankPdf]), written to [uri]. */
    fun writeBlank(context: Context, uri: Uri, style: PageStyle, pages: Int = 20) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(BlankPdf.notebook(pages, style)) }
    }

    /** Opens the PDF at [uri] in a window of its own (one per PDF); [beside] puts it next to this one, in split screen. */
    fun open(context: Context, uri: Uri, beside: Boolean = false) {
        val intent = Intent(context, NotebookActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (context !is android.app.Activity || beside) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (beside) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        context.startActivity(intent)
    }
}
