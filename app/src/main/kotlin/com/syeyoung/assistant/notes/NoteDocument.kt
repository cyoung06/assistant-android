package com.syeyoung.assistant.notes

import android.content.Context
import com.syeyoung.assistant.core.SyncDoc
import com.syeyoung.assistant.core.TextChange
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    /** As this device last had it (opened from at once, offline too; see [LiveCache]). */
    private val cache = LiveCache(app, path)
    /** Replaced (before connecting) if what's kept turns out to be another document's. */
    private var doc = cache.load()?.let { runCatching { SyncDoc.fromState(it) }.getOrNull() } ?: SyncDoc()
    private var opened: CompletableDeferred<String>? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var saving: kotlinx.coroutines.Job? = null

    /** Keeps the note on the device a moment after it changes (here or elsewhere). */
    private fun keepSoon() {
        saving?.cancel()
        saving = scope.launch {
            kotlinx.coroutines.delay(500)
            val state = doc.state()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.save(state) }
        }
    }

    private val connection = LiveConnection(app, path, { doc.start() }, ::receive) { state, message ->
        if (state == "error") {
            opened?.completeExceptionally(IllegalStateException(message))
            // Moved or deleted: what's kept here is no one's now.
            cache.forget()
        }
        // Live once the note is here; until then, it's still opening.
        if (state != "live" || opened?.isCompleted == true) onStatus(state, message)
    }

    private fun receive(bytes: ByteArray): ByteArray? {
        val got = doc.receive(bytes)
        if (got.changes.isNotEmpty() && opened?.isCompleted == true) onRemote(got.changes)
        if (got.changes.isNotEmpty()) keepSoon()
        if (LiveConnection.isSyncStep2(bytes)) scope.launch { runCatching { Repo.get(app).api().syncEpoch(path) }.getOrNull()?.let { cache.epoch = it } }
        if (opened?.isCompleted == false && LiveConnection.isSyncStep2(bytes)) opened?.complete(doc.text())
        if (opened?.isCompleted == true) onStatus("live", null)
        return got.reply
    }

    /** The note's text: once it's come from the server (or, for course files, as the file is). */
    suspend fun open(): String {
        onStatus("loading", null)
        if (readOnly) {
            // The copy kept here (refreshed if the drive's changed): offline too.
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.syeyoung.assistant.drive.LocalCopies.fetch(app, path).file.readText() }
            onStatus("readonly", null)
            return text
        }
        // What's kept is used only while it's still this path's document (not one deleted since and made again).
        if (!cache.stillCurrent()) {
            cache.forget()
            doc.close()
            doc = SyncDoc()
        }
        LiveCache.inUse += path
        onScreen = true
        val first = CompletableDeferred<String>().also { opened = it }
        // Kept here: shown at once, what's changed since coming in as it does.
        if (cache.load() != null) first.complete(doc.text())
        connection.connect()
        return first.await()
    }

    /** Whether it's open on a screen (not just being brought up to date). */
    private var onScreen = false

    /**
     * Brings what's kept of the note up to the server's (to be read and written offline later), then
     * lets it go. Not for a note open on a screen. Fails if the server can't be reached in [timeout] ms.
     */
    suspend fun keepUpToDate(timeout: Long = 60_000) {
        try {
            if (!cache.stillCurrent()) {
                cache.forget()
                doc.close()
                doc = SyncDoc()
            }
            val synced = CompletableDeferred<String>().also { opened = it }
            connection.connect()
            kotlinx.coroutines.withTimeout(timeout) { synced.await() }
        } finally {
            close()
        }
    }

    /** Typed here: [to] - [from] UTF-16 units from [from] replaced by [insert]. On the main thread, as the editor changes. */
    fun edited(from: Int, to: Int, insert: String) {
        if (readOnly) return
        connection.send(doc.edit(from.toUInt(), to.toUInt(), insert))
        keepSoon()
    }

    /** Lets the note go: what's still to be sent goes first (a few seconds at most). */
    suspend fun close() {
        if (!readOnly) {
            saving?.cancel()
            val state = doc.state()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.save(state) }
        }
        connection.close()
        scope.cancel()
        doc.close()
        if (onScreen) LiveCache.inUse -= path
    }
}
