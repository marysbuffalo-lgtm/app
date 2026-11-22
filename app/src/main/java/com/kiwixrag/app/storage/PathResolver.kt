package com.kiwixrag.app.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Turns Storage Access Framework results into real file paths so native code can open large files
 * in place. Nothing is ever copied into app storage.
 */
object PathResolver {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** Maps a document id such as `primary:Download/model.task` to `/storage/emulated/0/Download/model.task`. */
    fun documentIdToPath(docId: String): String? {
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:").trimEnd('/').ifEmpty { null }
        val colon = docId.indexOf(':')
        if (colon < 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1).trim('/')
        val root = when {
            volume.equals("primary", ignoreCase = true) -> PRIMARY_ROOT
            volume.equals("home", ignoreCase = true) -> "$PRIMARY_ROOT/Documents"
            volume.isBlank() -> return null
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    fun resolveDocument(context: Context, uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getDocumentId(uri)
        when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> documentIdToPath(docId)
            DOWNLOADS_AUTHORITY -> documentIdToPath(docId) ?: dataColumn(context, uri)
            else -> dataColumn(context, uri)
        }
    }.getOrNull()

    fun resolveTree(uri: Uri): String? = runCatching {
        documentIdToPath(DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun dataColumn(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model"
    }
}

/**
 * A model file that can be opened by path. If the picker result cannot be mapped to a path (or the app
 * lacks all-files access), a read-only file descriptor is held open and exposed as `/proc/self/fd/N`,
 * which still avoids copying the file.
 */
class ModelHandle(
    val path: String,
    val displayName: String,
    val viaDescriptor: Boolean,
    private val descriptor: ParcelFileDescriptor?,
) : Closeable {
    override fun close() {
        runCatching { descriptor?.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri): ModelHandle {
            val name = PathResolver.displayName(context, uri)
            val direct = PathResolver.resolveDocument(context, uri)
            if (direct != null) {
                val f = File(direct)
                if (f.isFile && f.canRead() && f.length() > 0) return ModelHandle(direct, name, false, null)
            }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open $name")
            val procPath = "/proc/self/fd/${pfd.fd}"
            val f = File(procPath)
            if (!f.canRead()) {
                pfd.close()
                throw IOException("Cannot read $name. Grant all files access and pick it again.")
            }
            return ModelHandle(procPath, name, true, pfd)
        }
    }
}
