package com.musictrainer.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.musictrainer.library.FileEntry
import com.musictrainer.library.MusicFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Library folders via the Storage Access Framework (folder picker with persisted permission). */
class AndroidMusicFiles(private val activity: ComponentActivity) : MusicFiles {
    private val context: Context get() = activity.applicationContext
    private var pending: CompletableDeferred<Uri?>? = null

    // Must be registered before the activity is started (i.e. constructed in onCreate).
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pending?.complete(uri)
        pending = null
    }

    override suspend fun pickFolder(): String? {
        val result = CompletableDeferred<Uri?>()
        pending = result
        launcher.launch(null)
        val uri = result.await() ?: return null
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri.toString()
    }

    override suspend fun list(folder: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder)
        val out = ArrayList<FileEntry>()
        fun walk(docId: String, depth: Int) {
            if (depth > 8) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            runCatching {
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(id, depth + 1)
                        } else if (name.substringAfterLast('.', "").lowercase() in MusicFiles.SUPPORTED_EXTENSIONS) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            out += FileEntry(uri.toString(), name, c.getLong(3), c.getLong(4))
                        }
                    }
                }
            }
        }
        runCatching { walk(DocumentsContract.getTreeDocumentId(tree), 0) }
        out
    }

    override suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(Uri.parse(id))?.use { it.readBytes() } }.getOrNull()
    }

    override fun label(folder: String): String =
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder)).substringAfter(':').ifEmpty { "Storage" } }
            .getOrDefault(folder)
}
