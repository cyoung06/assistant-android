package com.syeyoung.assistant.drive

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.syeyoung.assistant.api.models.DriveEntry
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.notebook.NotebookSync
import com.syeyoung.assistant.notes.LiveCache
import com.syeyoung.assistant.notes.NoteDocument
import com.syeyoung.assistant.notes.NoteEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration

/**
 * What's starred in the drive, kept on this device to be read and written offline: starred folders
 * with everything in them (listed, see [Repo.driveListing]), and starred files. Notes and notebooks
 * are kept as their live documents (see [LiveCache]: opened at once, written offline, sent on
 * reconnecting), other files (and PDFs never opened live) as copies ([LocalCopies]: written offline
 * too, sent on reconnecting, with a conflict copy if the drive's changed meanwhile). Brought up to date on starting the app, after
 * starring, and every few hours; only what changed is downloaded.
 */
class OfflineFolders(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every few hours, and now. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "offline-folders", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<OfflineFolders>(Duration.ofHours(4)).setConstraints(online).build(),
            )
            now(context)
        }

        /** Once, soon (after what's starred changed); after one already going. */
        fun now(context: Context) {
            val work = OneTimeWorkRequestBuilder<OfflineFolders>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("offline-folders-now", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repo = Repo.get(context)
        val files = LinkedHashMap<String, DriveEntry>()
        try {
            suspend fun walk(folder: String) {
                val (listing, kept) = repo.driveListing(folder)
                if (kept) throw IOException("no connection")
                for (e in listing.propertyEntries) if (e.folder) walk(e.path) else files[e.path] = e
            }
            for (s in repo.api().driveStarred().also { repo.driveStarred() }) {
                if (s.endsWith("/")) walk(s)
                else repo.driveListing(s.substringBeforeLast('/') + "/").first.propertyEntries.find { it.path == s }?.let { files[s] = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("OfflineFolders", "listing what's starred: $e")
            return Result.retry()
        }
        var failed = 0
        for (e in files.values) {
            try {
                keep(context, e.path)
            } catch (x: kotlinx.coroutines.CancellationException) {
                throw x
            } catch (x: Exception) {
                failed++
                Log.w("OfflineFolders", "keeping ${e.path}: $x")
            }
        }
        Log.i("OfflineFolders", "kept ${files.size - failed} of ${files.size} files")
        return if (failed == 0) Result.success() else Result.retry()
    }

    /** Brings [path] as kept here up to the drive's (one open on a screen is kept by that screen). */
    private suspend fun keep(context: Context, path: String) {
        if (path in LiveCache.inUse) return
        val name = path.substringAfterLast('/')
        val live = !DriveProvider.readOnly(path)
        when {
            live && NoteEditor.isNote(name) -> withContext(Dispatchers.Main) { NoteDocument(context, path, { _, _ -> }, {}).keepUpToDate() }
            // A notebook only once it's been opened live somewhere: the first time, the server writes ids
            // into the PDF, which isn't for this to do to every PDF starred. Until then, a copy.
            live && name.lowercase().endsWith(".pdf") && (LiveCache(context, path).has() || Repo.get(context).api().syncEpoch(path) != null) ->
                withContext(Dispatchers.Main) { NotebookSync(context, path, { _, _ -> }, NotebookSync.Unseen).keepUpToDate() }
            else -> LocalCopies.fetch(context, path)
        }
    }
}
