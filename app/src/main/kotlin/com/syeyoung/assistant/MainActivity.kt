package com.syeyoung.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syeyoung.assistant.data.messageIdOf
import com.syeyoung.assistant.data.plmsWebUrl
import com.syeyoung.assistant.ui.AssistantApp
import com.syeyoung.assistant.ui.AssistantTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** A task to open, from a notification or the widget. */
    private var openTask by mutableStateOf<UUID?>(null)
    /** A mail to show, from a mid: link. */
    private var openMail by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (handlePlms(intent) && savedInstanceState == null && isTaskRoot) return finish()
        openTask = taskOf(intent)
        openMail = intent?.dataString?.let(::messageIdOf)
        setContent { AssistantTheme { AssistantApp(openTask, openMail) { openMail = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handlePlms(intent)) return
        openTask = taskOf(intent)
        intent.dataString?.let(::messageIdOf)?.let { openMail = it }
    }

    /** plms: links open PLMS in the browser; true when [intent] was one. */
    private fun handlePlms(intent: Intent?): Boolean {
        val web = intent?.dataString?.let(::plmsWebUrl) ?: return false
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)))
        return true
    }

    private fun taskOf(intent: Intent?) = intent?.getStringExtra(EXTRA_TASK)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        const val EXTRA_TASK = "task"
    }
}
