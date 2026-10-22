package com.syeyoung.assistant.notes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.syeyoung.assistant.data.Repo
import com.syeyoung.assistant.drive.DriveProvider
import com.syeyoung.assistant.notebook.Notebooks
import kotlinx.coroutines.launch

/**
 * Makes a quick note (or, with [EXTRA_NOTEBOOK], a notebook page) for the class on now, in its
 * project's Notes, and opens it to write in. Shows nothing of its own: it's what the Quick Settings
 * tiles (and anything else) start.
 */
class QuickNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notebook = intent.getBooleanExtra(EXTRA_NOTEBOOK, false)
        val project = intent.getStringExtra(EXTRA_PROJECT)
        lifecycleScope.launch {
            runCatching { Repo.get(this@QuickNoteActivity).api().quickNote(notebook, project) }
                .onSuccess { path ->
                    if (notebook) Notebooks.open(this@QuickNoteActivity, DriveProvider.uri(this@QuickNoteActivity, path))
                    else NoteActivity.open(this@QuickNoteActivity, path, writing = true)
                }
                .onFailure { Toast.makeText(this@QuickNoteActivity, "Couldn't make a note: ${it.message}", Toast.LENGTH_LONG).show() }
            finish()
        }
    }

    companion object {
        const val EXTRA_NOTEBOOK = "notebook"
        /** In this project's Notes (its id), not the class on now's. */
        const val EXTRA_PROJECT = "project"

        fun intent(context: Context, notebook: Boolean, projectId: String? = null) =
            Intent(context, QuickNoteActivity::class.java).putExtra(EXTRA_NOTEBOOK, notebook).putExtra(EXTRA_PROJECT, projectId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** A Quick Settings tile: a note (or notebook page) for the class on now, from anywhere. */
abstract class QuickNoteTile(private val notebook: Boolean) : TileService() {
    override fun onClick() {
        val intent = QuickNoteActivity.intent(this, notebook)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, if (notebook) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            // Before Android 14 the PendingIntent form doesn't exist; this one is what there is.
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

class QuickNoteTileService : QuickNoteTile(notebook = false)
class QuickNotebookTileService : QuickNoteTile(notebook = true)
