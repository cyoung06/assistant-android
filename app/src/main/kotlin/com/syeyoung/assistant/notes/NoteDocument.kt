package com.syeyoung.assistant.notes

import android.content.Context
import com.syeyoung.assistant.core.SyncDoc
import com.syeyoung.assistant.core.TextChange
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import kotlinx.coroutines.CompletableDeferred

/**
 * A note as every device has it at once: a Yjs document ([SyncDoc], the shared core's) kept in step
 * with the API's (see [LiveConnection]), which writes the file. Edits typed here go out as they're
 * made ([edited]); others' come in through [onRemote], on the main thread, as the document takes
 * them (so the editor's text and the document's never drift apart). Course files only read.
 * [onStatus] hears "loading", "live", "connecting", "offline", "readonly", or "error" with why.
 */
class NoteDocument(
    context: Context,
    val path: String,
    private val onStatus: (state: String, message: String?) -> Unit,
    private val onRemote: (List<TextChange>) -> Unit,
) {
    private val app = context.applicationContext
    val readOnly = DriveProvider.readOnly(path)

    private val doc = SyncDoc()
    private var opened: CompletableDeferred<String>? = null

    private val connection = LiveConnection(app, path, doc::start, ::receive) { state, message ->
        if (state == "error") opened?.completeExceptionally(IllegalStateException(message))
        // Live once the note is here; until then, it's still opening.
        if (state != "live" || opened?.isCompleted == true) onStatus(state, message)
    }

    private fun receive(bytes: ByteArray): ByteArray? {
        val got = doc.receive(bytes)
        if (got.changes.isNotEmpty() && opened?.isCompleted == true) onRemote(got.changes)
        if (opened?.isCompleted == false && LiveConnection.isSyncStep2(bytes)) opened?.complete(doc.text())
        if (opened?.isCompleted == true) onStatus("live", null)
        return got.reply
    }

    /** The note's text: once it's come from the server (or, for course files, as the file is). */
    suspend fun open(): String {
        onStatus("loading", null)
        if (readOnly) {
            val text = Repo.get(app).api().driveRead(path).bytes.toString(Charsets.UTF_8)
            onStatus("readonly", null)
            return text
        }
        val first = CompletableDeferred<String>().also { opened = it }
        connection.connect()
        return first.await()
    }

    /** Typed here: [to] - [from] UTF-16 units from [from] replaced by [insert]. On the main thread, as the editor changes. */
    fun edited(from: Int, to: Int, insert: String) {
        if (readOnly) return
        connection.send(doc.edit(from.toUInt(), to.toUInt(), insert))
    }

    /** Lets the note go: what's still to be sent goes first (a few seconds at most). */
    suspend fun close() {
        connection.close()
        doc.close()
    }
}
