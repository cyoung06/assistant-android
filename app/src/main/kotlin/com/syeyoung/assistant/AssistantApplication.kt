package com.syeyoung.assistant

import android.app.Application
import com.syeyoung.assistant.data.Crashes
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.notify.Reminders
import com.syeyoung.assistant.notify.SyncWorker
import com.syeyoung.assistant.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AssistantApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // A library service in an isolated process (no files, no network) also runs this class:
        // starting the app there would kill it.
        if (android.os.Process.isIsolated()) return
        Crashes.install(this)
        // Every successful sync (in the app or in the background) reschedules reminders and redraws the widget.
        Repo.get(this).onRefreshed = { data ->
            Reminders.schedule(this, data.tasks)
            scope.launch { runCatching { TodayWidget.update(this@AssistantApplication) } }
            scope.launch { runCatching { Crashes.upload(this@AssistantApplication, Repo.get(this@AssistantApplication).api()) } }
        }
        SyncWorker.start(this)
    }
}
