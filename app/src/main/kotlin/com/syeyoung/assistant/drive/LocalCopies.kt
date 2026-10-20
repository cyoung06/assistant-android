package com.syeyoung.assistant.drive

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.syeyoung.assistant.data.ApiException
import com.syeyoung.assistant.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The drive files this device has, each kept with the version (ETag) it's based on. Opening one asks
 * the drive whether that's still its version (If-None-Match) and only downloads it if it changed;
 * without a connection, the copy here opens as it is. Kept in the app's own storage, not its cache,
 * so the system doesn't drop them.
 *
 * Writing goes to a file of its own, which replaces the copy only when the writer closes it without
 * an error (so a crash or a failed write leaves the copy as it was). The copy is then marked edited
 * and sent to the drive by [Upload], with If-Match on the version it was based on; until that's
 * done, it's never replaced by the drive's version. If the drive's file changed meanwhile, the edits
 * go next to it as a conflict copy instead.
 */
object LocalCopies {
    class Copy(val file: File, val etag: String?)

    /** Edits to [path] that couldn't go there (it changed on the drive meanwhile) and went to [copy], beside it. */
    class Conflict(val path: String, val copy: String)

    /** Each conflict as it happens, for whoever is editing that file (to carry on in the copy). */
    val conflicts = MutableSharedFlow<Conflict>(extraBufferCapacity = 16)

    private val locks = ConcurrentHashMap<String, Mutex>()
    /** How many writers each path has open: the copy isn't refreshed under them. */
    private val writers = ConcurrentHashMap<String, Int>()

    private fun lock(path: String) = locks.getOrPut(path) { Mutex() }
    private fun dir(context: Context) = File(context.filesDir, "drive").apply { mkdirs() }
    /** Each path's version ("<path>") and, while it has edits not on the drive yet, their number ("edited:<path>"). */
    private fun state(context: Context) = context.getSharedPreferences("drive-copies", Context.MODE_PRIVATE)
    private fun edited(context: Context, path: String) = state(context).getLong("edited:$path", 0)

    /** Where [path]'s copy is kept (it may not be there yet). */
    fun file(context: Context, path: String) =
        File(dir(context), Integer.toHexString(path.hashCode()) + "-" + path.trimEnd('/').substringAfterLast('/').replace('/', ' '))

    /** [path] as the drive has it now: the copy here, refreshed first if the drive's version differs (unless it's being edited here). */
    suspend fun fetch(context: Context, path: String): Copy = lock(path).withLock { withContext(Dispatchers.IO) { refresh(context, path) } }

    private suspend fun refresh(context: Context, path: String): Copy {
        val f = file(context, path)
        val known = state(context).getString(path, null)?.takeIf { f.exists() }
        if (f.exists() && (edited(context, path) != 0L || (writers[path] ?: 0) > 0)) return Copy(f, known)
        val fetched = try {
            val part = File(f.parentFile, f.name + ".part")
            Repo.get(context).api().driveFetch(path, part, known).also { if (it.changed) part.renameTo(f) else part.delete() }
        } catch (e: Exception) {
            // Offline (or the drive unreachable): the copy here, if there is one.
            if (known != null) return Copy(f, known)
            throw e
        }
        state(context).edit().putString(path, fetched.etag).apply()
        return Copy(f, fetched.etag)
    }

    /**
     * A file to write [path]'s new contents to, starting empty if [truncate], else as the copy is
     * (which needs the copy: a [FileNotFoundException] if it can't be had). [listed] is the version
     * the drive's listing showed, which the new contents replace if there's no copy here to go by.
     * Hand it back to [closed].
     */
    suspend fun writing(context: Context, path: String, truncate: Boolean, listed: String?): File = lock(path).withLock {
        withContext(Dispatchers.IO) {
            val f = file(context, path)
            if (!truncate) {
                try { refresh(context, path) } catch (e: Exception) { throw FileNotFoundException("$path: ${e.message}") }
            } else if (!f.exists() || state(context).getString(path, null) == null) {
                state(context).edit().apply { if (listed != null) putString(path, listed) else remove(path) }.apply()
            }
            val w = File(f.parentFile, f.name + ".w-" + UUID.randomUUID())
            if (truncate) w.createNewFile() else f.copyTo(w)
            writers.merge(path, 1, Int::plus)
            w
        }
    }

    /** The writer of [w] (from [writing]) closed it; [ok] if without an error, which makes it [path]'s copy, to be sent to the drive. */
    suspend fun closed(context: Context, path: String, w: File, ok: Boolean) = lock(path).withLock {
        withContext(Dispatchers.IO) {
            writers.computeIfPresent(path) { _, n -> (n - 1).takeIf { it > 0 } }
            if (!ok) { w.delete(); return@withContext }
            if (!w.renameTo(file(context, path))) { w.delete(); throw IOException("couldn't keep the edits to $path") }
            state(context).edit().putLong("edited:$path", edited(context, path) + 1).apply()
        }
        Upload.start(context, path)
    }

    /** [path]'s copy here is now version [etag] (it was just written to the drive from it); null if unknown. */
    fun written(context: Context, path: String, etag: String?) {
        state(context).edit().apply { if (etag != null) putString(path, etag) else remove(path) }.apply()
    }

    /** [path] was just made on the drive, empty, as version [etag]. */
    fun created(context: Context, path: String, etag: String?) {
        forget(context, path)
        file(context, path).createNewFile()
        written(context, path, etag)
    }

    /** Forgets [path]'s copy (it was moved or deleted). */
    fun forget(context: Context, path: String) {
        state(context).edit().remove(path).remove("edited:$path").apply()
        file(context, path).delete()
    }

    /** Sends a copy's edits to the drive; retried (when there's a connection) until it's there. */
    class Upload(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        companion object {
            fun start(context: Context, path: String) {
                val work = OneTimeWorkRequestBuilder<Upload>()
                    .setInputData(workDataOf("path" to path))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(10))
                    .build()
                // After one already going (it may have sent older edits); each sends whatever's newest.
                WorkManager.getInstance(context).enqueueUniqueWork("upload:$path", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            }
        }

        override suspend fun doWork(): Result {
            val context = applicationContext
            val path = inputData.getString("path") ?: return Result.failure()
            val api = Repo.get(context).api()
            val type = DriveProvider.mimeOf(path, false)
            // What's sent is a snapshot, so the copy can be edited again meanwhile.
            val snapshot = File(context.cacheDir, "upload-" + UUID.randomUUID())
            try {
                val (edits, base) = lock(path).withLock {
                    withContext(Dispatchers.IO) {
                        val edits = edited(context, path)
                        if (edits == 0L) return@withContext null
                        file(context, path).copyTo(snapshot, overwrite = true)
                        edits to state(context).getString(path, null)
                    }
                } ?: return Result.success()
                val etag = try {
                    // No version known: it's a new file, which mustn't replace one made meanwhile.
                    api.driveUpload(path, snapshot, type, ifMatch = base, absent = base == null)
                } catch (e: ApiException) {
                    if (e.status != 412) {
                        Log.e("LocalCopies", "uploading $path: $e")
                        return if (e.status >= 500) Result.retry() else Result.failure()
                    }
                    // The drive's file changed since: these edits go next to it, and the copy here becomes the drive's again.
                    val (beside, besideTag) = keepConflict(path, snapshot, type)
                    Log.w("LocalCopies", "$path changed on the drive; the edits made here are in $beside")
                    // The copy is kept here as what it now is, so whoever follows it carries on from there.
                    lock(beside).withLock {
                        withContext(Dispatchers.IO) { snapshot.copyTo(file(context, beside), overwrite = true) }
                        written(context, beside, besideTag)
                    }
                    conflicts.tryEmit(Conflict(path, beside))
                    lock(path).withLock {
                        if (edited(context, path) == edits) {
                            state(context).edit().remove(path).remove("edited:$path").apply()
                            file(context, path).delete()
                        }
                    }
                    return Result.success()
                }
                lock(path).withLock {
                    // Newer edits since the snapshot are based on what was just sent.
                    state(context).edit().apply {
                        if (etag != null) putString(path, etag) else remove(path)
                        if (edited(context, path) == edits) remove("edited:$path")
                    }.apply()
                }
                return Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Mostly the connection; the edits stay here, marked, until it goes through.
                Log.w("LocalCopies", "uploading $path: $e")
                return Result.retry()
            } finally {
                snapshot.delete()
            }
        }

        /** Writes [from] next to [path] as "name (conflict <time>).ext" (numbered if that's taken); where it went, and its version. */
        private suspend fun keepConflict(path: String, from: File, type: String): Pair<String, String?> {
            val name = path.substringAfterLast('/')
            val stem = name.substringBeforeLast('.').ifEmpty { name }
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
            val at = path.dropLast(name.length) + "$stem (conflict $time)" + name.substring(stem.length)
            val api = Repo.get(applicationContext).api()
            return api.driveCreate(at) { api.driveUpload(it, from, type, absent = true) }
        }
    }
}
