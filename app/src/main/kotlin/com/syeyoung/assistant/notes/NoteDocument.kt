package com.syeyoung.assistant.notes

import android.content.Context
import com.syeyoung.assistant.data.ApiException
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A note's file and what's typed into it, as the web editor keeps them: saved a second after typing
 * stops, only over the version it's based on (If-Match); what isn't written yet is kept as a draft
 * on the device until it is, and offered when the note opens again (as a conflict if the note
 * changed meanwhile). [text] is what the editor has now; [onStatus] hears how saving is going.
 */
class NoteDocument(
    context: Context,
    val path: String,
    private val text: () -> String,
    private val onStatus: (state: String, message: String?) -> Unit,
) {
    private val app = context.applicationContext
    private suspend fun api() = Repo.get(app).api()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val readOnly = DriveProvider.readOnly(path)

    /** The version the text is based on (an ETag). */
    private var version: String? = null
    private var dirty = false
    private var conflicted = false
    private var saving: Job? = null
    private var timer: Job? = null
    private var keepTimer: Job? = null
    /** Deleted: nothing's written back. */
    private var gone = false

    private val drafts = File(app.filesDir, "note-drafts").apply { mkdirs() }
    /** Draft writes, one at a time and in order, off the main thread. */
    private val disk = Dispatchers.IO.limitedParallelism(1)
    private val draftFile = File(drafts, MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".json")

    /** The note to show: its text, and how it opens ("saved", or "unsaved"/"conflict" with a draft). */
    class Opened(val text: String, val state: String)

    /** Reads the note; a draft left from before is shown instead (see the class). */
    suspend fun open(): Opened {
        onStatus("loading", null)
        val file = api().driveRead(path)
        version = file.etag
        val written = file.bytes.toString(Charsets.UTF_8)
        val draft = if (readOnly) null else readDraft()
        val state = when {
            draft == null -> if (readOnly) "readonly" else "saved"
            draft.text == written -> { clearDraft(); "saved" }
            draft.base == version -> { dirty = true; schedule(); "unsaved" }
            else -> { version = draft.base; dirty = true; conflicted = true; "conflict" }
        }
        onStatus(state, null)
        return Opened(draft?.text?.takeIf { state != "saved" } ?: written, state)
    }

    /** The text was typed into (not loaded): kept as a draft at once, saved a second after typing stops. */
    fun edited() {
        if (readOnly || gone) return
        dirty = true
        keepTimer?.cancel()
        keepTimer = scope.launch { delay(300); keep() }
        if (!conflicted) { onStatus("unsaved", null); schedule() }
    }

    private fun schedule() {
        timer?.cancel()
        timer = scope.launch { delay(1000); save() }
    }

    /** Writes the note; with [force], even over a version written elsewhere. */
    suspend fun save(force: Boolean = false) {
        if (readOnly || gone || (!dirty && !force) || (conflicted && !force)) return
        saving?.let { it.join(); return save(force) }
        val writing = text()
        dirty = false
        onStatus("saving", null)
        saving = scope.launch {
            try {
                version = api().driveWrite(path, writing.toByteArray(), "text/markdown; charset=utf-8", if (force) null else version)
                conflicted = false
                onStatus(if (dirty) "unsaved" else "saved", null)
            } catch (e: ApiException) {
                dirty = true
                if (e.status == 412) { conflicted = true; onStatus("conflict", null) } else onStatus("error", e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dirty = true
                throw e
            } catch (e: Exception) {
                dirty = true
                onStatus("error", e.message)
            } finally {
                keep()
            }
        }
        saving?.join()
        saving = null
    }

    /** Writes what's unsaved now; true once everything typed is written (false: it's kept as a draft). */
    suspend fun flush(): Boolean {
        timer?.cancel()
        keep()
        save()
        return readOnly || gone || (!dirty && !conflicted)
    }

    /** Coming back to the note: a newer version is shown if nothing's being edited (only asked if it changed). */
    suspend fun newer(): String? {
        if (dirty || saving != null || conflicted) return null
        val fresh = runCatching { api().driveReadIfChanged(path, version) }.getOrNull() ?: return null
        version = fresh.etag
        return fresh.bytes.toString(Charsets.UTF_8)
    }

    // ---- a conflict: what to keep ----

    /** Writes what's here over the other version. */
    fun keepMine() = scope.launch { save(force = true) }

    /** The other version, instead of what's here (which is dropped). */
    suspend fun loadTheirs(): String {
        val file = api().driveRead(path)
        version = file.etag
        dirty = false
        conflicted = false
        clearDraft()
        onStatus("saved", null)
        return file.bytes.toString(Charsets.UTF_8)
    }

    /** What's here, written beside the note as "name (my copy).md"; then the note's own version is shown. Its name, and that version. */
    suspend fun saveCopy(): Pair<String, String> {
        val name = path.substringAfterLast('/').let { n -> n.substringBeforeLast('.', n) + " (my copy)" + (if ('.' in n) "." + n.substringAfterLast('.') else "") }
        val folder = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val mine = text().toByteArray()
        val (at, _) = api().driveCreate(folder + name) { api().driveWrite(it, mine, "text/markdown; charset=utf-8", absent = true) }
        return at.substringAfterLast('/') to loadTheirs()
    }

    /** The note is being deleted: nothing's written back (that would make it again). */
    fun stop() {
        gone = true
        timer?.cancel()
        keepTimer?.cancel()
        clearDraft()
    }

    fun close() = scope.cancel()

    // ---- the draft ----

    private class Draft(val text: String, val base: String?)

    /** Keeps what's unsaved as a draft (none once it's all written). */
    private fun keep() {
        keepTimer?.cancel()
        if (readOnly || gone) return
        if (!dirty && !conflicted) return clearDraft()
        val json = JSONObject().put("text", text()).put("base", version ?: JSONObject.NULL).put("at", System.currentTimeMillis()).toString()
        scope.launch(disk) {
            // Written whole, then put in place: a crash mid-write leaves the last draft.
            val tmp = File(drafts, draftFile.name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(draftFile)
        }
    }

    private suspend fun readDraft(): Draft? = kotlinx.coroutines.withContext(disk) { readDraftNow() }

    private fun readDraftNow(): Draft? = runCatching {
        val j = JSONObject(draftFile.readText())
        Draft(j.getString("text"), j.optString("base").takeIf { !j.isNull("base") })
    }.getOrNull()

    private fun clearDraft() {
        scope.launch(disk) { draftFile.delete() }
    }
}
