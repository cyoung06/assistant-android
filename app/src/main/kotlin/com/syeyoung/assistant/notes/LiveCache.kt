package com.syeyoung.assistant.notes

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * A live document as this device last had it (its Yjs state): kept so it opens at once, offline
 * too, and what was written while away isn't lost if the app closes before it's sent. On connecting,
 * only what's changed since goes either way (see [LiveConnection]). In the app's files (not its
 * cache, which Android may clear), by a hash of the drive path.
 */
class LiveCache(context: Context, path: String) {
    private val file = File(File(context.applicationContext.filesDir, "live").apply { mkdirs() }, sha256(path) + ".ydoc")

    /** The kept state; null if there's none (or it can't be read). */
    fun load(): ByteArray? = runCatching { file.takeIf { it.isFile && it.length() > 0 }?.readBytes() }.getOrNull()

    /** Keeps [state], whole or not at all. */
    fun save(state: ByteArray) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeBytes(state)
            tmp.renameTo(file)
        }.onFailure { android.util.Log.w("LiveCache", "not kept: $it") }
    }

    /** Lets go of it (the file was moved or deleted: what's kept is no one's any more). */
    fun forget() {
        file.delete()
    }

    private companion object {
        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
