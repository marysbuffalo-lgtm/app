package com.kiwixrag.app.rag

/**
 * Turns a natural language question into Xapian-friendly keyword queries. Xapian ANDs terms, so a full
 * sentence usually matches nothing; variants go from most specific to loosest.
 */
object KeywordExtractor {
    private val stopWords = setOf(
        "a", "about", "above", "after", "again", "all", "also", "am", "an", "and", "any", "are", "as", "at",
        "be", "because", "been", "before", "being", "between", "both", "but", "by", "can", "could", "did", "do",
        "does", "doing", "down", "during", "each", "few", "for", "from", "further", "had", "has", "have", "having",
        "he", "her", "here", "hers", "him", "his", "how", "i", "if", "in", "into", "is", "it", "its", "just", "me",
        "more", "most", "my", "no", "nor", "not", "of", "off", "on", "once", "only", "or", "other", "our", "out",
        "over", "own", "same", "she", "should", "so", "some", "such", "tell", "than", "that", "the", "their",
        "them", "then", "there", "these", "they", "this", "those", "through", "to", "too", "under", "until", "up",
        "us", "very", "was", "we", "were", "what", "when", "where", "which", "while", "who", "whom", "why", "will",
        "with", "would", "you", "your", "explain", "describe", "give", "show", "please", "mean", "means",
    )

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    fun keywords(question: String, max: Int = 8): List<String> =
        question.lowercase()
            .split(splitter)
            .filter { it.length > 1 && it !in stopWords }
            .distinct()
            .take(max)

    /**
     * Query strings from strict to loose. libzim ANDs every term (verified against libzim 9.8: a raw
     * question or `photosynthesis einstein` returns nothing), so the list ends with single keywords that
     * the library merges by rank fusion. The first entry is the full keyword phrase.
     */
    fun queryVariants(question: String): List<String> {
        val kws = keywords(question, 6)
        if (kws.isEmpty()) {
            val raw = question.trim()
            return if (raw.isEmpty()) emptyList() else listOf(raw)
        }
        val out = LinkedHashSet<String>()
        out += kws.joinToString(" ")
        if (kws.size > 3) out += kws.take(3).joinToString(" ")
        if (kws.size > 2) out += kws.take(2).joinToString(" ")
        kws.sortedByDescending { it.length }.take(4).forEach { out += it }
        return out.toList()
    }
}
