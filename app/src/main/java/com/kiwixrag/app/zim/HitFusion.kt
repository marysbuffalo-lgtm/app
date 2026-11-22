package com.kiwixrag.app.zim

/** One ranked result list (best first) produced by a single query, with a weight for how specific it is. */
class RankedList(val weight: Double, val hits: List<ZimHit>)

/**
 * Reciprocal rank fusion. libzim's Xapian search ANDs every term and does not support OR, so a loose
 * "any keyword" search is emulated by running several queries and merging them. An article that ranks
 * well for the strict query, or for several keywords, ends up on top.
 */
object HitFusion {
    private const val K = 60.0

    fun fuse(lists: List<RankedList>): List<ZimHit> {
        val score = LinkedHashMap<String, Double>()
        val first = HashMap<String, ZimHit>()
        for (list in lists) {
            list.hits.forEachIndexed { rank, hit ->
                val key = key(hit)
                score[key] = (score[key] ?: 0.0) + list.weight / (K + rank)
                val existing = first[key]
                if (existing == null || (existing.snippet.isEmpty() && hit.snippet.isNotEmpty())) first[key] = hit
            }
        }
        return score.entries
            .sortedByDescending { it.value }
            .map { (k, s) -> first.getValue(k).copy(score = s) }
    }

    fun key(hit: ZimHit) = "${hit.zimId}\u0000${hit.path}"

    fun distinct(lists: List<RankedList>): Int = lists.flatMap { l -> l.hits.map(::key) }.toSet().size
}
