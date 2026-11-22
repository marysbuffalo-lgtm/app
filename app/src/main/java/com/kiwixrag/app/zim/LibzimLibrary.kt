package com.kiwixrag.app.zim

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.SearchIterator
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class LoadReport(val opened: Int, val failed: List<String>)

/**
 * libzim backed implementation. Archives are opened in place with `Archive(path)`, so a 70 GB library is
 * never copied or indexed by the app. Search uses libzim's built-in Xapian indexes.
 */
class LibzimLibrary(private val context: Context) : ZimLibrary {
    private class Open(val info: ZimInfo, val archive: Archive, val descriptor: ParcelFileDescriptor?)

    private val searchLock = Any()
    @Volatile private var open: Map<String, Open> = emptyMap()
    private val _archives = MutableStateFlow<List<ZimInfo>>(emptyList())
    override val archives: StateFlow<List<ZimInfo>> = _archives.asStateFlow()

    // ---------------------------------------------------------------- loading

    /** Opens every .zim in [dir] (two levels deep). Requires all files access on Android 11+. */
    fun loadFolder(dir: File): LoadReport {
        val files = dir.walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(".zim", true) }.sortedBy { it.name }.toList()
        return replaceAll(files.map { f -> { openPath(f) } }, files.map { it.name })
    }

    /** Fallback when all files access is missing: opens each document through a file descriptor. */
    fun loadTree(tree: Uri): LoadReport {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val found = mutableListOf<Pair<String, Uri>>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.endsWith(".zim", true)) found += name to DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        found.sortBy { it.first }
        return replaceAll(found.map { (name, uri) -> { openDescriptor(name, uri) } }, found.map { it.first })
    }

    private fun replaceAll(openers: List<() -> Open>, names: List<String>): LoadReport {
        val previous = open
        val next = LinkedHashMap<String, Open>()
        val failed = mutableListOf<String>()
        openers.forEachIndexed { i, opener ->
            try {
                var o = opener()
                var id = o.info.id
                var n = 2
                while (next.containsKey(id)) id = "${o.info.id}#${n++}"
                if (id != o.info.id) o = Open(o.info.copy(id = id), o.archive, o.descriptor)
                next[id] = o
            } catch (e: Throwable) {
                failed += "${names[i]}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        synchronized(searchLock) {
            open = next
            _archives.value = next.values.map { it.info }
        }
        previous.values.forEach { dispose(it) }
        return LoadReport(next.size, failed)
    }

    private fun openPath(file: File): Open {
        val archive = Archive(file.absolutePath)
        return Open(describe(archive, file.name, file.length()), archive, null)
    }

    private fun openDescriptor(name: String, uri: Uri): Open {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open $name")
        return try {
            val archive = Archive(pfd.fileDescriptor)
            Open(describe(archive, name, pfd.statSize), archive, pfd)
        } catch (e: Throwable) {
            pfd.close()
            throw e
        }
    }

    private fun describe(archive: Archive, fileName: String, size: Long): ZimInfo {
        archive.setClusterCacheMaxSize(8)
        val title = runCatching { archive.getMetadata("Title") }.getOrNull()?.takeIf { it.isNotBlank() } ?: fileName.removeSuffix(".zim")
        return ZimInfo(
            id = archive.uuid,
            fileName = fileName,
            title = title,
            sizeBytes = size,
            articleCount = runCatching { archive.articleCount }.getOrDefault(0),
            hasFulltext = archive.hasFulltextIndex(),
            hasTitleIndex = archive.hasTitleIndex(),
            language = runCatching { archive.mainLanguage }.getOrNull()?.takeIf { it.isNotBlank() },
        )
    }

    private fun dispose(o: Open) {
        runCatching { o.archive.dispose() }
        runCatching { o.descriptor?.close() }
    }

    // ----------------------------------------------------------------- search

    override fun search(queries: List<String>, limit: Int): List<ZimHit> = synchronized(searchLock) {
        val snapshot = open.values.toList()
        if (snapshot.isEmpty() || queries.isEmpty() || limit <= 0) return emptyList()
        val byId = snapshot.associateBy { it.info.id }
        val lists = mutableListOf<RankedList>()
        val enough = limit * 3

        // 1. Title suggestions for the whole keyword phrase: high precision for "what is X" questions.
        val phrase = queries.first()
        lists += RankedList(TITLE_PHRASE_WEIGHT, snapshot.filter { it.info.hasTitleIndex }.flatMap { o ->
            runCatching { suggest(o, phrase, 3) }.getOrNull().orEmpty()
        })

        // 2. Full-text search over every archive with a Xapian index, strict query first. Xapian ANDs terms
        //    and has no OR, so looser variants are merged afterwards by rank fusion.
        val indexed = snapshot.filter { it.info.hasFulltext }.map { it.archive }.toTypedArray()
        if (indexed.isNotEmpty()) {
            val searcher = runCatching { Searcher(indexed) }.getOrNull()
            if (searcher != null) {
                try {
                    for (q in queries) {
                        val terms = q.trim().split(' ').size
                        if (terms == 1 && HitFusion.distinct(lists) >= enough) break
                        val hits = runCatching { fullText(searcher, q, limit * 2, byId) }.getOrNull().orEmpty()
                        if (hits.isNotEmpty()) lists += RankedList(terms.toDouble(), hits)
                    }
                } finally {
                    searcher.dispose()
                }
            }
        }

        // 3. Still thin: title suggestions for single keywords, in every archive.
        if (HitFusion.distinct(lists) < limit) {
            for (q in queries.filter { !it.contains(' ') }) {
                val hits = snapshot.filter { it.info.hasTitleIndex }.flatMap { o ->
                    runCatching { suggest(o, q, 3) }.getOrNull().orEmpty()
                }
                if (hits.isNotEmpty()) lists += RankedList(TITLE_WORD_WEIGHT, hits)
            }
        }
        HitFusion.fuse(lists).take(limit * 2)
    }

    private fun suggest(o: Open, query: String, max: Int): List<ZimHit> {
        val searcher = SuggestionSearcher(o.archive)
        try {
            val search = searcher.suggest(query)
            try {
                val it = search.getResults(0, max)
                val out = mutableListOf<ZimHit>()
                try {
                    var rank = 0
                    while (it.hasNext()) {
                        val s = it.next()
                        out += ZimHit(
                            zimId = o.info.id,
                            zimFileName = o.info.fileName,
                            path = s.path,
                            title = s.title,
                            snippet = if (s.hasSnippet()) s.snippet else "",
                            score = 0.0,
                            titleMatch = true,
                        )
                    }
                } finally {
                    it.dispose()
                }
                return out
            } finally {
                search.dispose()
            }
        } finally {
            searcher.dispose()
        }
    }

    private fun fullText(searcher: Searcher, text: String, max: Int, byId: Map<String, Open>): List<ZimHit> {
        val query = Query(text)
        try {
            val search = searcher.search(query)
            try {
                val results = search.getResults(0, max)
                try {
                    return drain(results, byId)
                } finally {
                    results.dispose()
                }
            } finally {
                search.dispose()
            }
        } finally {
            query.dispose()
        }
    }

    /**
     * In the libzim JNI binding the iterator's getters (path, title, snippet, zimId) describe the element
     * that `next()` will return, and `next()` then advances (see search_iterator.cpp in java-libkiwix).
     * The getters are therefore read first, and cross-checked against the returned Entry as a safety net.
     */
    private fun drain(results: SearchIterator, byId: Map<String, Open>): List<ZimHit> {
        val out = mutableListOf<ZimHit>()
        var guard = 0
        while (results.hasNext() && guard++ < MAX_DRAIN) {
            val prePath = runCatching { results.path }.getOrNull()
            val preTitle = runCatching { results.title }.getOrNull()
            val preSnippet = runCatching { results.snippet }.getOrNull()
            val preZim = runCatching { results.zimId }.getOrNull()
            val entry = results.next()
            val path = entry.path
            val consistent = prePath == path
            val zim = resolveArchive(if (consistent) preZim else null, path, byId) ?: continue
            out += ZimHit(
                zimId = zim.info.id,
                zimFileName = zim.info.fileName,
                path = path,
                title = (if (consistent) preTitle else null) ?: entry.title,
                snippet = (if (consistent) preSnippet else null).orEmpty(),
                score = 0.0,
                titleMatch = false,
            )
        }
        return out
    }

    private fun resolveArchive(rawId: String?, path: String, byId: Map<String, Open>): Open? {
        if (rawId != null) {
            byId[rawId]?.let { return it }
            val norm = rawId.replace("-", "")
            byId.values.firstOrNull { it.info.id.substringBefore('#').replace("-", "").equals(norm, true) }?.let { return it }
        }
        return byId.values.firstOrNull { runCatching { it.archive.hasEntryByPath(path) }.getOrDefault(false) }
    }

    // ------------------------------------------------------------------ read

    override fun read(zimId: String, path: String, maxBytes: Int): ZimContent? {
        val o = open[zimId] ?: return null
        return try {
            val entry = o.archive.getEntryByPath(path)
            val item = entry.getItem(true)
            if (item.size > maxBytes) return null
            val blob = item.data
            try {
                ZimContent(zimId, item.path, entry.title, item.mimetype, blob.data)
            } finally {
                blob.dispose()
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun info(zimId: String): ZimInfo? = open[zimId]?.info

    override fun trimCaches() {
        open.values.forEach {
            runCatching { it.archive.setClusterCacheMaxSize(1) }
            runCatching { it.archive.setDirentCacheMaxSize(256) }
        }
    }

    override fun close() {
        synchronized(searchLock) {
            val all = open.values.toList()
            open = emptyMap()
            _archives.value = emptyList()
            all.forEach { dispose(it) }
        }
    }

    private companion object {
        const val TITLE_PHRASE_WEIGHT = 6.0
        const val TITLE_WORD_WEIGHT = 1.5
        const val MAX_DRAIN = 50
    }
}
