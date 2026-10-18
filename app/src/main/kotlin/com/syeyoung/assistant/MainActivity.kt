package com.syeyoung.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syeyoung.assistant.ui.AssistantApp
import com.syeyoung.assistant.ui.AssistantTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** A task to open, from a notification or the widget. */
    private var openTask by mutableStateOf<UUID?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTask = taskOf(intent)
        setContent { AssistantTheme { AssistantApp(openTask) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTask = taskOf(intent)
    }

    private fun taskOf(intent: Intent?) = intent?.getStringExtra(EXTRA_TASK)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        const val EXTRA_TASK = "task"
    }
}
