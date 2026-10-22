package com.syeyoung.assistant.capture

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.syeyoung.assistant.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A photo for the class on now (a whiteboard, a handout): the camera, then into the class's project
 * folder, named by the time and the class, as taken. Shows nothing of its own.
 */
class ClassPhotoActivity : ComponentActivity() {
    private lateinit var file: File

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (!taken || !file.isFile || file.length() == 0L) return@registerForActivityResult finish()
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                Repo.get(this@ClassPhotoActivity).api().classPhoto(bytes, "image/jpeg")
            }.onSuccess { path -> Toast.makeText(this@ClassPhotoActivity, "Saved to ${path.split('/').getOrNull(1) ?: "the drive"}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@ClassPhotoActivity, "Couldn't save the photo: ${it.message}", Toast.LENGTH_LONG).show() }
            file.delete()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        file = File(dir, "photo.jpg")
        if (savedInstanceState == null) {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            camera.launch(uri)
        }
    }
}
