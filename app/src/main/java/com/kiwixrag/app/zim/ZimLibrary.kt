package com.kiwixrag.app.zim

import kotlinx.coroutines.flow.StateFlow

data class ZimInfo(
    val id: String,
    val fileName: String,
    val title: String,
    val sizeBytes: Long,
    val articleCount: Int,
    val hasFulltext: Boolean,
    val hasTitleIndex: Boolean,
    val language: String?,
)

data class ZimHit(
    val zimId: String,
    val zimFileName: String,
    val path: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val titleMatch: Boolean,
)

class ZimContent(
    val zimId: String,
    val path: String,
    val title: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** Read and search access to every loaded .zim archive. Implementations must be thread safe. */
interface ZimLibrary {
    val archives: StateFlow<List<ZimInfo>>

    /**
     * Searches all archives. [queries] are tried in order (most specific first) until [limit] distinct
     * hits are collected. Title suggestions rank above full-text matches.
     */
    fun search(queries: List<String>, limit: Int): List<ZimHit>

    /** Reads an entry, following redirects. Returns null if missing or larger than [maxBytes]. */
    fun read(zimId: String, path: String, maxBytes: Int = DEFAULT_MAX_ITEM_BYTES): ZimContent?

    fun info(zimId: String): ZimInfo?

    /** Shrinks libzim caches. Called when Android reports memory pressure. */
    fun trimCaches()

    fun close()

    companion object {
        const val DEFAULT_MAX_ITEM_BYTES = 6 * 1024 * 1024
    }
}
