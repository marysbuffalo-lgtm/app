package com.kiwixrag.app.llm

import com.kiwixrag.app.rag.Retrieval
import com.kiwixrag.app.rag.Source

class BuiltPrompt(val prompt: RagPrompt, val estimatedTokens: Int, val keptPhotos: Int)

object PromptBuilder {
    const val SYSTEM_INSTRUCTION =
        "You are an offline research assistant. Answer strictly and only from the Kiwix context and photos " +
            "provided below. Do not use outside knowledge. If the context does not contain the answer, say that " +
            "you could not find it in the loaded Kiwix library.\n" +
            "Cite every claim with its source tag, for example [S1] or [S2]. Cite photos with their tag, for " +
            "example [P1]. End your answer with a line starting with \"Sources:\" that lists each cited item as " +
            "[S1] ZIM archive name | Article title and, for photos, [P1] ZIM archive name | Article title | image " +
            "file name | caption."

    private const val TEMPLATE_OVERHEAD_TOKENS = 60
    private const val CHARS_PER_TOKEN = 3.4

    fun contextBlock(sources: List<Source>, texts: List<String>): String = buildString {
        sources.forEachIndexed { i, s ->
            append("[S").append(s.index).append("] ZIM: ").append(s.zimFileName)
                .append(" | Article: ").append(s.articleTitle).append('\n')
            append(texts[i]).append("\n\n")
        }
    }.trimEnd()

    /**
     * Builds a prompt that fits [LlmEngine.CONTEXT_TOKENS]. Article text is shortened first, evenly across
     * sources. Photos are dropped only if the text cannot be made to fit.
     */
    fun build(retrieval: Retrieval, question: String, engine: LlmEngine, usePhotos: Boolean): BuiltPrompt {
        var photos = if (usePhotos && engine.supportsVision) retrieval.images.take(LlmEngine.MAX_IMAGES) else emptyList()
        val sources = retrieval.sources

        fun photoLines(): String = photos.joinToString("\n") {
            "[${it.label}] ZIM: ${it.zimFileName} | Article: ${it.articleTitle} | File: ${it.fileName} | Caption: ${it.caption}"
        }

        while (true) {
            val fixed = engine.countTokens(SYSTEM_INSTRUCTION) + engine.countTokens(question) +
                engine.countTokens(photoLines()) + photos.size * LlmEngine.TOKENS_PER_IMAGE + TEMPLATE_OVERHEAD_TOKENS
            val budget = LlmEngine.CONTEXT_TOKENS - LlmEngine.RESERVED_FOR_ANSWER - fixed
            val fitted = fitSources(sources, budget, engine)
            if (fitted != null || photos.isEmpty()) {
                val texts = fitted ?: sources.map { "" }
                val context = contextBlock(sources, texts)
                val total = fixed + engine.countTokens(context)
                val promptPhotos = photos.map { PromptPhoto(it.label, "${it.articleTitle}: ${it.caption}", it.bitmap) }
                return BuiltPrompt(RagPrompt(SYSTEM_INSTRUCTION, context + photoSection(photoLines()), promptPhotos, question), total, photos.size)
            }
            photos = photos.dropLast(1)
        }
    }

    private fun photoSection(lines: String): String = if (lines.isEmpty()) "" else "\n\nPhotos (shown in order):\n$lines"

    /** Returns per-source trimmed text, or null when even a minimal slice of each cannot fit. */
    internal fun fitSources(sources: List<Source>, budgetTokens: Int, engine: LlmEngine): List<String>? {
        if (sources.isEmpty()) return emptyList()
        if (budgetTokens < 120 * sources.size.coerceAtMost(2)) return null
        var perSourceChars = (budgetTokens * CHARS_PER_TOKEN / sources.size).toInt()
        repeat(10) {
            val texts = sources.map { trimToSentence(it.text, perSourceChars) }
            val tokens = engine.countTokens(contextBlock(sources, texts))
            if (tokens <= budgetTokens) return texts
            perSourceChars = (perSourceChars * 0.85).toInt()
            if (perSourceChars < 200) return null
        }
        return null
    }

    internal fun trimToSentence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf('\n'), cut.lastIndexOf("? "))
        return if (end > maxChars / 2) cut.take(end + 1).trim() else cut.trim()
    }
}
