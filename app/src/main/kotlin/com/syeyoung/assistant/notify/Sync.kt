package com.syeyoung.assistant.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.syeyoung.assistant.data.Repo
import java.time.Duration

/** Refreshes tasks in the background (hourly), which reschedules reminders and updates the widget. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (Repo.get(applicationContext).refresh()) Result.success() else Result.retry()

    companion object {
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(1)).build(),
            )
        }

        fun now(context: Context) = WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
    }
}

/** Alarms don't survive a restart; sync again to put the reminders back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SyncWorker.now(context)
    }
}
