package com.syeyoung.assistant.notes

import android.content.Context
import com.syeyoung.assistant.data.Repo
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * time, up to half a minute), until let go or refused. A connection that hasn't opened within
 * [OPENING] is given up and tried again (after sleep, one can hang on the way to the server).
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
            val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            val attempt = scope.async {
                Repo.get(app).api().sync(path) {
                    opened.set(true)
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
            }
            // How it ended: as the attempt ends, or when it hasn't opened in time (not waiting for a
            // stuck attempt to notice it's been let go).
            val ended = CompletableDeferred<Throwable?>()
            attempt.invokeOnCompletion { ended.complete(it) }
            val watchdog = scope.launch {
                delay(OPENING)
                if (!opened.get()) {
                    attempt.cancel()
                    ended.complete(java.util.concurrent.TimeoutException("not opened within ${OPENING / 1000} s"))
                }
            }
            val cause = try {
                ended.await()
            } finally {
                watchdog.cancel()
            }
            if (!scope.isActive) break
            if (cause != null && cause !is kotlinx.coroutines.CancellationException) android.util.Log.w("LiveConnection", "$path: ${cause.message}")
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
        /** How long a connection may take to open. */
        private const val OPENING = 15_000L

        /** Whether [message] is the server's sync step 2 (y-protocols: sync, step 2): the document is all here. */
        fun isSyncStep2(message: ByteArray) = message.size > 1 && message[0] == 0.toByte() && message[1] == 1.toByte()
    }
}
