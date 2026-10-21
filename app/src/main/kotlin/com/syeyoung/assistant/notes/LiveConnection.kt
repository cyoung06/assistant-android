package com.syeyoung.assistant.notes

import android.content.Context
import com.syeyoung.assistant.data.Repo
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
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
 * A live document's line to the API (y-websocket, see [com.syeyoung.assistant.data.Api.sync]): for
 * a note ([NoteDocument]) or a notebook. Connected, and again after it drops (a little later each
 * time, up to half a minute), until let go or refused.
 *
 * What comes in is handed to [receive] on the main thread, as the document takes it (so a view
 * showing the document changes with it); its reply goes back. [greeting] is the document's sync step 1,
 * sent on connecting. [onStatus] hears "connecting", "live", "offline", or "error" with why.
 */
class LiveConnection(
    context: Context,
    private val path: String,
    private val greeting: () -> ByteArray,
    private val receive: (ByteArray) -> ByteArray?,
    private val onStatus: (state: String, message: String?) -> Unit,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** What's to be sent; edits made while offline wait here (and the sync on reconnecting covers them anyway). */
    private val pending = Channel<ByteArray>(Channel.UNLIMITED)
    /** Refused by the server (not a file to edit live, or moved or deleted): no more tries. */
    private var refused = false
    var refusal: String? = null
        private set

    fun connect() {
        scope.launch { run() }
    }

    fun send(message: ByteArray) {
        pending.trySend(message)
    }

    private suspend fun run() {
        var wait = 1000L
        while (scope.isActive && !refused) {
            onStatus("connecting", null)
            try {
                Repo.get(app).api().sync(path) {
                    wait = 1000L
                    // Old messages: what they carried is in the sync this connection starts with.
                    while (pending.tryReceive().isSuccess) Unit
                    send(Frame.Binary(true, greeting()))
                    val sender = launch { for (m in pending) send(Frame.Binary(true, m)) }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val bytes = frame.readBytes()
                            // On the main thread: the document and what shows it change together.
                            withContext(Dispatchers.Main.immediate) { receive(bytes) }?.let { send(Frame.Binary(true, it)) }
                        }
                        val reason = closeReason.await()
                        if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                            refused = true
                            refusal = reason.message.ifEmpty { "This can't be opened" }
                            onStatus("error", refusal)
                        }
                    } finally {
                        sender.cancel()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LiveConnection", "$path: ${e.message}")
            }
            if (refused || !scope.isActive) break
            onStatus("offline", null)
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000)
        }
    }

    /** Lets go: what's still to be sent goes first (a few seconds at most). */
    suspend fun close() {
        withTimeoutOrNull(3000) { while (!pending.isEmpty) delay(50) }
        scope.cancel()
    }

    companion object {
        /** Whether [message] is the server's sync step 2 (y-protocols: sync, step 2): the document is all here. */
        fun isSyncStep2(message: ByteArray) = message.size > 1 && message[0] == 0.toByte() && message[1] == 1.toByte()
    }
}
