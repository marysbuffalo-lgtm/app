package com.kiwixrag.app.rag

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** An `<img>` found in an article, with its path already resolved to a ZIM entry path. */
data class ImageRef(
    val path: String,
    val fileName: String,
    val caption: String,
    val score: Int,
)

class ExtractedArticle(
    val title: String,
    val text: String,
    val images: List<ImageRef>,
)

object ImagePaths {
    /**
     * Resolves [src] against the article path the way a browser would against a URL whose path is the
     * ZIM path. Returns the decoded ZIM entry path, or null for external or inline images.
     */
    fun resolve(articlePath: String, src: String): String? {
        val trimmed = src.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("data:", true) || trimmed.contains("://") || trimmed.startsWith("//")) return null
        return try {
            val base = URI(null, null, "/" + articlePath.trimStart('/'), null)
            val clean = trimmed.substringBefore('#').substringBefore('?')
            val ref = try {
                URI(clean)
            } catch (e: Exception) {
                URI(null, null, clean, null)
            }
            normalize(base.resolve(ref).path.orEmpty())
        } catch (e: Exception) {
            null
        }
    }

    /** java.net.URI keeps leading "../" that point above the root. Browsers clamp them at the root. */
    private fun normalize(path: String): String? {
        val out = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/").takeIf { it.isNotEmpty() }
    }

    fun fileName(path: String): String = path.substringAfterLast('/')
}

object HtmlExtractor {
    private const val NOISE =
        "script, style, noscript, link, meta, sup.reference, .mw-editsection, .navbox, table.navbox, .reflist, " +
            ".references, .hatnote, .toc, #toc, .mw-empty-elt, .noprint, .metadata, .ambox, .sidebar-navigation, " +
            "[role=navigation], nav, footer"

    private val iconWords = listOf("icon", "logo", "edit-clear", "question_book", "commons-logo", "wikimedia", "ambox", "padlock", "symbol")
    private val footnote = Regex("\\[\\s*(\\d+|[a-z]|citation needed|note \\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")

    /**
     * @param keywords lowercase query keywords used to prefer relevant passages
     * @param maxChars upper bound on the returned text length
     */
    fun extract(html: String, articlePath: String, keywords: List<String>, maxChars: Int): ExtractedArticle {
        val doc: Document = Jsoup.parse(html)
        doc.select(NOISE).remove()

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { doc.title().trim() }
        val images = collectImages(doc, articlePath, keywords)
        val text = selectPassages(doc, keywords, maxChars)
        return ExtractedArticle(title, text, images)
    }

    private class Block(val text: String, val heading: Boolean, val index: Int)

    private fun selectPassages(doc: Document, keywords: List<String>, maxChars: Int): String {
        val blocks = mutableListOf<Block>()
        for (el in doc.select("h2, h3, h4, p, li, blockquote, dd, figcaption")) {
            if (el.parents().any { it.normalName() == "table" && it.hasClass("infobox").not() && it.hasClass("wikitable").not() }) continue
            val text = clean(el.text())
            val heading = el.normalName().startsWith("h")
            if (text.isEmpty()) continue
            if (!heading && text.length < 25) continue
            val prefix = if (el.normalName() == "figcaption") "Caption: " else ""
            blocks += Block(prefix + text, heading, blocks.size)
        }
        if (blocks.isEmpty()) return clean(doc.body()?.text().orEmpty()).take(maxChars)

        fun hits(b: Block): Int {
            val lower = b.text.lowercase()
            return keywords.count { lower.contains(it) }
        }

        val chosen = sortedSetOf<Int>()
        var used = 0
        fun take(i: Int): Boolean {
            if (i in chosen) return true
            val len = blocks[i].text.length + 1
            if (used + len > maxChars) return false
            chosen += i
            used += len
            return true
        }

        // The lead is almost always the best summary: always keep the first two real paragraphs.
        blocks.filter { !it.heading }.take(2).forEach { take(it.index) }
        // Then the passages that mention the most keywords, strongest first.
        val ranked = blocks.filter { !it.heading && hits(it) > 0 }.sortedWith(compareByDescending<Block> { hits(it) }.thenBy { it.index })
        for (b in ranked) {
            if (!take(b.index)) continue
            var h = b.index - 1
            while (h >= 0 && !blocks[h].heading) h--
            if (h >= 0) take(h)
        }
        // Nothing matched: fall back to reading order.
        if (ranked.isEmpty()) {
            for (b in blocks) if (!b.heading && !take(b.index)) break
        }
        return chosen.joinToString("\n") { blocks[it].text }
    }

    private fun collectImages(doc: Document, articlePath: String, keywords: List<String>): List<ImageRef> {
        val out = mutableListOf<ImageRef>()
        val seen = HashSet<String>()
        doc.select("img[src]").forEachIndexed { order, img ->
            val ref = toRef(img, order, articlePath, keywords) ?: return@forEachIndexed
            if (seen.add(ref.path)) out += ref
        }
        return out.sortedByDescending { it.score }
    }

    private fun toRef(img: Element, order: Int, articlePath: String, keywords: List<String>): ImageRef? {
        val path = ImagePaths.resolve(articlePath, img.attr("src")) ?: return null
        val lowerPath = path.lowercase()
        if (lowerPath.endsWith(".svg") || lowerPath.endsWith(".ico")) return null
        if (img.hasClass("mwe-math-fallback-image-inline") || img.hasClass("mwe-math-fallback-image-display")) return null
        val width = img.attr("width").toIntOrNull()
        val height = img.attr("height").toIntOrNull()
        if ((width != null && width < 60) || (height != null && height < 60)) return null
        val alt = img.attr("alt").trim()
        val name = ImagePaths.fileName(path)
        val lowerName = name.lowercase()
        if (iconWords.any { lowerName.contains(it) || alt.lowercase().contains(it) }) return null

        val figure = img.closest("figure")
        val figcaption = figure?.selectFirst("figcaption")?.text()?.trim().orEmpty()
        val caption = clean(figcaption.ifEmpty { alt.ifEmpty { img.attr("title").trim() } })
        val inInfobox = img.closest(".infobox, table.infobox, .thumb, .thumbinner") != null

        var score = 0
        if (figure != null) score += 3
        if (inInfobox) score += 2
        if (caption.isNotEmpty()) score += 1
        val lowerCaption = (caption + " " + lowerName).lowercase()
        score += keywords.count { lowerCaption.contains(it) } * 2
        score += (if (order < 3) 2 else if (order < 8) 1 else 0)
        if (width != null && width >= 200) score += 1
        return ImageRef(path, name, caption.ifEmpty { prettyName(name) }, score)
    }

    private fun prettyName(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()

    private fun clean(s: String): String = s.replace(footnote, "").replace(spaces, " ").trim()
}
