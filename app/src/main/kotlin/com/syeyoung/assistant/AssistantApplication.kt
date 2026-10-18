package com.syeyoung.assistant

import android.app.Application
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
        // Every successful sync (in the app or in the background) reschedules reminders and redraws the widget.
        Repo.get(this).onRefreshed = { data ->
            Reminders.schedule(this, data.tasks)
            scope.launch { runCatching { TodayWidget.update(this@AssistantApplication) } }
        }
        SyncWorker.start(this)
    }
}
