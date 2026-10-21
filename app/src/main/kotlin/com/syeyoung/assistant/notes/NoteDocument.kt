package com.syeyoung.assistant.notes

import android.content.Context
import com.syeyoung.assistant.core.SyncDoc
import com.syeyoung.assistant.core.TextChange
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A note as every device has it at once: a Yjs document ([SyncDoc], the shared core's) kept in step
 * with the API's (y-websocket, see [com.syeyoung.assistant.data.Api.sync]), which writes the file.
 * Edits typed here go out as they're made ([edited]); others' come in through [onRemote], on the main
 * thread, as the document takes them (so the editor's text and the document's never drift apart).
 * Course files only read. [onStatus] hears "loading", "live", "connecting", "offline", "readonly",
 * or "error" with why.
 */
class NoteDocument(
    context: Context,
    val path: String,
    private val onStatus: (state: String, message: String?) -> Unit,
    private val onRemote: (List<TextChange>) -> Unit,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val readOnly = DriveProvider.readOnly(path)

    private val doc = SyncDoc()
    /** What's to be sent; edits made while offline wait here (and the sync on reconnecting covers them anyway). */
    private val pending = Channel<ByteArray>(Channel.UNLIMITED)
    private var opened: CompletableDeferred<String>? = null
    /** Refused by the server (not a note to edit live, or moved or deleted): no more tries. */
    private var refused = false

    /** The note's text: once it's come from the server (or, for course files, as the file is). */
    suspend fun open(): String {
        onStatus("loading", null)
        if (readOnly) {
            val text = Repo.get(app).api().driveRead(path).bytes.toString(Charsets.UTF_8)
            onStatus("readonly", null)
            return text
        }
        val first = CompletableDeferred<String>().also { opened = it }
        scope.launch { connect() }
        return first.await()
    }

    /** Typed here: [to] - [from] UTF-16 units from [from] replaced by [insert]. On the main thread, as the editor changes. */
    fun edited(from: Int, to: Int, insert: String) {
        if (readOnly) return
        pending.trySend(doc.edit(from.toUInt(), to.toUInt(), insert))
    }

    /** Connected, with tries again (a little later each time, up to half a minute) until let go. */
    private suspend fun connect() {
        var wait = 1000L
        while (scope.isActive && !refused) {
            onStatus("connecting", null)
            try {
                Repo.get(app).api().sync(path) {
                    wait = 1000L
                    // Old messages: what they carried is in the sync this connection starts with.
                    while (pending.tryReceive().isSuccess) Unit
                    send(Frame.Binary(true, doc.start()))
                    val sender = launch { for (m in pending) send(Frame.Binary(true, m)) }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val bytes = frame.readBytes()
                            // On the main thread: the document and the editor's text change together.
                            val reply = withContext(Dispatchers.Main.immediate) {
                                val got = doc.receive(bytes)
                                if (got.changes.isNotEmpty() && opened?.isCompleted == true) onRemote(got.changes)
                                // The server's answer to our sync step 1 (y-protocols: sync, step 2): the note is all here.
                                if (opened?.isCompleted == false && bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1] == 1.toByte()) {
                                    opened?.complete(doc.text())
                                }
                                if (opened?.isCompleted == true) onStatus("live", null)
                                got.reply
                            }
                            reply?.let { send(Frame.Binary(true, it)) }
                        }
                        val reason = closeReason.await()
                        if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                            refused = true
                            onStatus("error", reason.message.ifEmpty { "This note can't be opened" })
                            opened?.completeExceptionally(IllegalStateException(reason.message))
                        }
                    } finally {
                        sender.cancel()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("NoteDocument", "$path: ${e.message}")
            }
            if (refused || !scope.isActive) break
            onStatus("offline", null)
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000)
        }
    }

    /** Lets the note go: what's still to be sent goes first (a few seconds at most). */
    suspend fun close() {
        withTimeoutOrNull(3000) { while (!pending.isEmpty) delay(50) }
        scope.cancel()
        doc.close()
    }
}
