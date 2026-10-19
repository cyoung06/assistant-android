package com.syeyoung.assistant.notebook

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.annotation.SuppressLint
import androidx.annotation.RequiresExtension
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfWriteHandle
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.ink.EditablePdfViewerFragment
import androidx.pdf.viewer.fragment.PdfViewerFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A PDF to write on with the pen (course material or a blank notebook), using Jetpack PDF's
 * editable viewer: pen, highlighter, eraser and undo, saved into the PDF as standard annotations,
 * so any PDF reader shows them. Opens a PDF given to it ("Open with") or picked in Notebooks.
 */
class NotebookActivity : FragmentActivity() {
    private lateinit var uri: Uri
    private lateinit var save: Button
    private val editor get() = supportFragmentManager.findFragmentById(CONTAINER) as? NotebookFragment

    /** Where to write when the PDF we were given can't be written back (opened read-only). */
    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        val f = pendingCopy ?: return@registerForActivityResult
        pendingCopy = null
        if (dest != null) lifecycleScope.launch { copyOut(f, dest); open(dest) }
    }
    private var pendingCopy: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: return finish()
        Notebooks.remember(this, uri)

        val root = FrameLayout(this)
        root.addView(FrameLayout(this).apply { id = CONTAINER }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        save = Button(this).apply {
            text = "Save"
            visibility = View.GONE
            setOnClickListener { editor?.applyDraftEdits() }
        }
        root.addView(save, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, 48, 32, 0) })
        setContentView(root)

        if (savedInstanceState == null) {
            @SuppressLint("NewApi") // checked by canAnnotate()
            val fragment = if (canAnnotate()) NotebookFragment() else PdfViewerFragment().also {
                Toast.makeText(this, "Writing on PDFs needs a newer Android system update; showing it read-only.", Toast.LENGTH_LONG).show()
            }
            supportFragmentManager.beginTransaction().replace(CONTAINER, fragment).commitNow()
            fragment.documentUri = uri
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val e = editor
                if (e == null || !e.hasUnsavedChanges) return finish()
                AlertDialog.Builder(this@NotebookActivity)
                    .setTitle("Save your writing?")
                    .setPositiveButton("Save") { _, _ -> closeAfterSave = true; e.applyDraftEdits() }
                    .setNegativeButton("Discard") { _, _ -> finish() }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
        })
    }

    private var closeAfterSave = false

    fun editing(on: Boolean) { save.visibility = if (on) View.VISIBLE else View.GONE }

    /** The edits as a new PDF: written to a temporary file first, then over the original. */
    @OptIn(ExperimentalPdfApi::class)
    fun write(handle: PdfWriteHandle) {
        lifecycleScope.launch {
            val tmp = File(cacheDir, "notebook-${System.nanoTime()}.pdf")
            try {
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { handle.writeTo(it) }
            } catch (e: Exception) {
                failed(e)
                return@launch
            } finally {
                handle.close()
            }
            editor?.isEditModeEnabled = false
            try {
                copyOut(tmp, uri)
                if (closeAfterSave) finish() else editor?.documentUri = uri
            } catch (e: SecurityException) {
                // Opened read-only (e.g. from a mail or a download): keep it as a copy.
                pendingCopy = tmp
                saveCopy.launch(Notebooks.copyName(this@NotebookActivity, uri))
            }
        }
    }

    fun failed(e: Throwable) {
        closeAfterSave = false
        Toast.makeText(this, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
    }

    private suspend fun copyOut(from: File, to: Uri) = withContext(Dispatchers.IO) {
        contentResolver.openOutputStream(to, "wt")!!.use { out -> from.inputStream().use { it.copyTo(out) } }
        from.delete()
    }

    private fun open(dest: Uri) {
        startActivity(Intent(this, NotebookActivity::class.java).setData(dest).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        finish()
    }

    companion object {
        private val CONTAINER = View.generateViewId()

        /** Jetpack PDF's editing needs SDK extension 18 (a recent Android system update). */
        fun canAnnotate() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18
    }
}

@OptIn(ExperimentalPdfApi::class)
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
class NotebookFragment : EditablePdfViewerFragment() {
    private val host get() = activity as? NotebookActivity
    override fun onEnterEditMode() { host?.editing(true) }
    override fun onExitEditMode() { host?.editing(false) }
    override fun onApplyEditsSuccess(handle: PdfWriteHandle) { host?.write(handle) ?: handle.close() }
    override fun onApplyEditsFailed(error: Throwable) { host?.failed(error) }
}
