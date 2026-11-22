package com.kiwixrag.app

import com.kiwixrag.app.zim.HitFusion
import com.kiwixrag.app.zim.RankedList
import com.kiwixrag.app.zim.ZimContent
import com.kiwixrag.app.zim.ZimHit
import com.kiwixrag.app.zim.ZimInfo
import com.kiwixrag.app.zim.ZimLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * In-memory ZimLibrary backed by entries exported from a real ZIM built with libzim 9.8. Search mimics
 * what was measured against the real library: every term must match (AND, no OR), matching is case
 * insensitive, and title suggestions match prefixes of single terms.
 */
class FixtureZimLibrary(val fileName: String = "test_wiki_2026-01.zim", extra: Map<String, ZimContent> = emptyMap()) : ZimLibrary {
    private class Entry(val path: String, val title: String, val mime: String, val bytes: ByteArray, val redirectTo: String?)

    val uuid: String
    private val entries = LinkedHashMap<String, Entry>()
    var searchCalls = 0
        private set
    var lastQueries: List<String> = emptyList()
        private set

    init {
        fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("zimfixture/$name")!!.readBytes()
        val index = JSONObject(String(res("index.json")))
        uuid = index.getString("uuid")
        val arr = index.getJSONArray("entries")
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val requested = e.getString("requested")
            val real = e.getString("path")
            entries[real] = Entry(real, e.getString("title"), e.getString("mime"), res(e.getString("file")), null)
            if (e.getBoolean("redirect")) entries[requested] = Entry(requested, e.getString("title"), e.getString("mime"), ByteArray(0), real)
        }
        extra.forEach { (p, c) -> entries[p] = Entry(p, c.title, c.mimeType, c.bytes, null) }
    }

    override val archives: StateFlow<List<ZimInfo>> =
        MutableStateFlow(listOf(ZimInfo(uuid, fileName, "Test Wiki", 1234L, entries.size, true, true, "eng")))

    private fun resolve(path: String): Entry? {
        var e = entries[path] ?: return null
        while (e.redirectTo != null) e = entries[e.redirectTo!!] ?: return null
        return e
    }

    private fun plain(e: Entry) = String(e.bytes).replace(Regex("<[^>]+>"), " ").lowercase()

    private fun articles() = entries.values.filter { it.mime == "text/html" && it.redirectTo == null }

    override fun search(queries: List<String>, limit: Int): List<ZimHit> {
        searchCalls++
        lastQueries = queries
        val lists = mutableListOf<RankedList>()
        fun hit(e: Entry, title: Boolean) = ZimHit(uuid, fileName, e.path, e.title, "", 0.0, title)
        // title suggestions: prefix match on the phrase or on a single term (multi-word phrases match nothing)
        fun titleList(q: String): List<ZimHit> {
            if (q.contains(' ')) return emptyList()
            return articles().filter { a -> a.title.lowercase().split(' ', '_').any { it.startsWith(q.lowercase()) } }.map { hit(it, true) }
        }
        lists += RankedList(6.0, titleList(queries.first()))
        for (q in queries) {
            val terms = q.lowercase().split(' ')
            val hits = articles().filter { a -> val t = plain(a); terms.all { t.contains(it) } }.map { hit(it, false) }
            if (hits.isNotEmpty()) lists += RankedList(terms.size.toDouble(), hits)
        }
        if (HitFusion.distinct(lists) < limit) queries.filter { !it.contains(' ') }.forEach { lists += RankedList(1.5, titleList(it)) }
        return HitFusion.fuse(lists).take(limit * 2)
    }

    override fun read(zimId: String, path: String, maxBytes: Int): ZimContent? {
        if (zimId != uuid) return null
        val e = resolve(path) ?: return null
        if (e.bytes.size > maxBytes) return null
        return ZimContent(zimId, e.path, e.title, e.mime, e.bytes)
    }

    override fun info(zimId: String): ZimInfo? = archives.value.firstOrNull { it.id == zimId }
    override fun trimCaches() = Unit
    override fun close() = Unit
}
