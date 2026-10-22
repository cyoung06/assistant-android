package com.syeyoung.assistant.drive

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.syeyoung.assistant.R
import com.syeyoung.assistant.api.models.DriveEntry
import com.syeyoung.assistant.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.time.OffsetDateTime

/**
 * Each project's storage (its folder in the drive) as an Android storage location of its own, in
 * every app's file picker and the Files app, reaching the server through the app (its built-in
 * Tailscale too). Document IDs are drive paths (folders end with "/"). A file being opened is its
 * copy in [LocalCopies] (downloaded only if the drive's version changed); one opened for writing is
 * sent back (if it wasn't changed on the drive meanwhile) when the app writing it closes it.
 */
class DriveProvider : DocumentsProvider() {

    companion object {
        fun authority(context: android.content.Context) = "${context.packageName}.drive"
        const val TOP = "/"
        const val PROJECTS = "Projects/"
        const val NO_PROJECT = "No project"

        /** The document URI of a drive path (for opening a drive file in the app or elsewhere). */
        fun uri(context: android.content.Context, path: String) = DocumentsContract.buildDocumentUri(authority(context), path)

        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON)
        private val DOC_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        fun mimeOf(name: String, folder: Boolean): String =
            if (folder) Document.MIME_TYPE_DIR
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

        /** A project's Course files (and that folder): kept as PLMS has them, so only read here; copy one out to write on it. */
        fun readOnly(path: String) = path.split('/').let { it.size >= 3 && it[0] == "Projects" && it[2] == "Course files" }

        /** Project folders (and Projects/ itself) follow the projects: not renamed or deleted from here. */
        private fun fixed(path: String) = path == "Projects/" || (path.startsWith("Projects/") && path.trimEnd('/').count { it == '/' } <= 1)
    }

    private val repo get() = Repo.get(context!!)
    /** What each listing said about its entries, for queryDocument. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, DriveEntry>()
    private val uploads by lazy { Handler(HandlerThread("drive-uploads").apply { start() }.looper) }

    override fun onCreate() = true

    private fun <T> net(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    /** Each project's folder is a storage location of its own (and No project, for things that belong to none). */
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val projects = runCatching { net { repo.driveFolder(PROJECTS) } }.getOrNull()?.propertyEntries?.filter { it.folder }.orEmpty()
        // Projects in the app's order (active ones first), No project last.
        val order = repo.state.value.data.projects.map { it.name }
        projects.sortedWith(compareBy({ it.name == NO_PROJECT }, { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name })).forEach { p ->
            known[p.path] = p
            cursor.newRow().add(Root.COLUMN_ROOT_ID, p.path).add(Root.COLUMN_DOCUMENT_ID, p.path).add(Root.COLUMN_TITLE, p.name)
                .add(Root.COLUMN_SUMMARY, "Assistant").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        cursor.setNotificationUri(context!!.contentResolver, DocumentsContract.buildRootsUri(authority(context!!)))
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DOC_COLUMNS).apply {
        if (documentId == TOP) {
            newRow().add(Document.COLUMN_DOCUMENT_ID, TOP).add(Document.COLUMN_DISPLAY_NAME, "Assistant").add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            return@apply
        }
        val entry = known[documentId] ?: lookUp(documentId) ?: throw FileNotFoundException(documentId)
        row(this, entry)
    }

    /** An entry not seen in a listing yet: from its parent folder's. */
    private fun lookUp(path: String): DriveEntry? {
        val parent = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return runCatching { net { repo.driveFolder(parent) } }.getOrNull()?.propertyEntries?.onEach { known[it.path] = it }?.find { it.path == path }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_COLUMNS)
        val folder = if (parentDocumentId == TOP) "" else parentDocumentId
        try {
            net { repo.driveFolder(folder) }.propertyEntries.forEach { known[it.path] = it; row(cursor, it) }
        } catch (e: Exception) {
            Log.w("DriveProvider", "listing $folder: $e")
            cursor.extras = android.os.Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Couldn't reach the drive: ${e.message}") }
        }
        cursor.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(context!!), parentDocumentId))
        return cursor
    }

    private fun row(c: MatrixCursor, e: DriveEntry) {
        var flags = 0
        if (!readOnly(e.path)) {
            if (e.folder) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            else flags = flags or Document.FLAG_SUPPORTS_WRITE
            if (!fixed(e.path)) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, e.path).add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, if (e.folder) Document.MIME_TYPE_DIR else (e.contentType ?: mimeOf(e.name, false)))
            .add(Document.COLUMN_SIZE, if (e.folder) null else e.propertySize)
            .add(Document.COLUMN_LAST_MODIFIED, e.modified?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() })
            .add(Document.COLUMN_FLAGS, flags)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == TOP || documentId.startsWith(parentDocumentId)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        if ('w' !in mode) {
            val copy = try { net { LocalCopies.fetch(context!!, documentId) } } catch (e: Exception) { throw FileNotFoundException("$documentId: ${e.message}") }
            return ParcelFileDescriptor.open(copy.file, pfdMode)
        }
        if (readOnly(documentId)) throw SecurityException("$documentId is kept as PLMS has it; copy it out to write on it")
        // A file of its own, which becomes the copy (and goes to the drive) only once it's closed without an error.
        val w = net { LocalCopies.writing(context!!, documentId, truncate = 't' in mode, listed = known[documentId]?.etag) }
        return ParcelFileDescriptor.open(w, pfdMode, uploads) { error ->
            if (error != null) Log.w("DriveProvider", "writing $documentId: $error")
            try {
                net { LocalCopies.closed(context!!, documentId, w, ok = error == null) }
            } catch (e: Exception) {
                Log.e("DriveProvider", "keeping $documentId: $e")
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = if (parentDocumentId == TOP) "" else parentDocumentId
        val name = displayName.replace('/', ' ')
        val folder = mimeType == Document.MIME_TYPE_DIR
        if (folder) {
            val path = "$parent$name/"
            net { repo.api().driveMakeFolder(path) }
            notifyParent(path)
            return path
        }
        // Never over a file that's there: "name (2).ext" and so on instead, as the contract allows.
        val empty = File.createTempFile("new", null, context!!.cacheDir)
        try {
            val (path, etag) = net { repo.api().driveCreate(parent + name) { at -> repo.api().driveUpload(at, empty, mimeType, absent = true) } }
            LocalCopies.created(context!!, path, etag)
            notifyParent(path)
            return path
        } finally {
            empty.delete()
        }
    }

    override fun deleteDocument(documentId: String) {
        net { repo.api().driveDelete(documentId) }
        known.remove(documentId)
        LocalCopies.forget(context!!, documentId)
        notifyParent(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val folder = documentId.endsWith("/")
        val parent = documentId.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val to = parent + displayName.replace('/', ' ') + if (folder) "/" else ""
        net { repo.api().driveMove(documentId, to) }
        known.remove(documentId)
        LocalCopies.forget(context!!, documentId)
        notifyParent(documentId)
        return to
    }

    private fun notifyParent(path: String) {
        val parent = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) TOP else "$it/" }
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context!!), parent), null)
    }
}
