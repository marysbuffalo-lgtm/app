package com.kiwixrag.app.rag

import android.graphics.Bitmap
import com.kiwixrag.app.zim.ZimLibrary

class Source(
    val index: Int,
    val zimId: String,
    val zimFileName: String,
    val articleTitle: String,
    val path: String,
    val snippet: String,
    val text: String,
)

class RetrievedImage(
    val sourceIndex: Int,
    val label: String,
    val zimFileName: String,
    val articleTitle: String,
    val path: String,
    val fileName: String,
    val caption: String,
    val bitmap: Bitmap,
    val thumbnailJpeg: ByteArray,
) {
    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

class Retrieval(
    val keywords: List<String>,
    val sources: List<Source>,
    val images: List<RetrievedImage>,
) {
    /** Frees native bitmap memory. Call once the model is done with the prompt. */
    fun recycleImages() = images.forEach { it.recycle() }
}

/**
 * Keyword search over every loaded ZIM, article parsing, and photo extraction. There is deliberately no
 * embedding model or vector database: libzim's Xapian indexes do the retrieval.
 */
class RagPipeline(private val library: ZimLibrary) {

    fun retrieve(question: String, topArticles: Int, maxImages: Int, maxCharsPerArticle: Int = 6_000): Retrieval {
        val keywords = KeywordExtractor.keywords(question)
        val variants = KeywordExtractor.queryVariants(question)
        val hits = library.search(variants, topArticles)

        class Candidate(val source: Source, val images: List<ImageRef>)
        val candidates = mutableListOf<Candidate>()
        val seenTitles = HashSet<String>()

        for (hit in hits) {
            if (candidates.size >= topArticles) break
            val content = library.read(hit.zimId, hit.path) ?: continue
            if (!content.mimeType.startsWith("text/html")) continue
            val html = String(content.bytes, Charsets.UTF_8)
            val extracted = HtmlExtractor.extract(html, content.path, keywords, maxCharsPerArticle)
            if (extracted.text.length < 80) continue
            val title = extracted.title.ifBlank { hit.title.ifBlank { content.title } }
            if (!seenTitles.add("${hit.zimId}/$title")) continue
            candidates += Candidate(
                Source(candidates.size + 1, hit.zimId, hit.zimFileName, title, content.path, hit.snippet, extracted.text),
                extracted.images,
            )
        }

        val images = if (maxImages > 0) pickImages(candidates.map { it.source to it.images }, maxImages) else emptyList()
        return Retrieval(keywords, candidates.map { it.source }, images)
    }

    /** Round robin over the best-ranked articles so photos come from different pages, then fill up. */
    private fun pickImages(perSource: List<Pair<Source, List<ImageRef>>>, max: Int): List<RetrievedImage> {
        val out = mutableListOf<RetrievedImage>()
        val cursor = IntArray(perSource.size)
        var progress = true
        while (out.size < max && progress) {
            progress = false
            for ((i, pair) in perSource.withIndex()) {
                if (out.size >= max) break
                val (source, refs) = pair
                while (cursor[i] < refs.size) {
                    val ref = refs[cursor[i]++]
                    val decoded = loadImage(source, ref, out.size + 1) ?: continue
                    out += decoded
                    progress = true
                    break
                }
            }
        }
        return out
    }

    private fun loadImage(source: Source, ref: ImageRef, number: Int): RetrievedImage? {
        val content = library.read(source.zimId, ref.path, MAX_IMAGE_BYTES) ?: return null
        if (!content.mimeType.startsWith("image/") || content.mimeType.contains("svg")) return null
        val decoded = runCatching { ImageDecoder.decode(content.bytes) }.getOrNull() ?: return null
        return RetrievedImage(
            sourceIndex = source.index,
            label = "P$number",
            zimFileName = source.zimFileName,
            articleTitle = source.articleTitle,
            path = content.path,
            fileName = ref.fileName,
            caption = ref.caption,
            bitmap = decoded.bitmap,
            thumbnailJpeg = decoded.thumbnailJpeg,
        )
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    }
}
