package com.kiwixrag.app.rag

import com.kiwixrag.app.zim.HitFusion
import com.kiwixrag.app.zim.RankedList
import com.kiwixrag.app.zim.ZimHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordAndFusionTest {
    @Test
    fun stopWordsAndPunctuationAreRemoved() {
        assertEquals(listOf("photosynthesis"), KeywordExtractor.keywords("What is photosynthesis?"))
        assertEquals(listOf("light", "chlorophyll", "absorb"), KeywordExtractor.keywords("Which light does chlorophyll absorb?").filter { it != "which" })
    }

    @Test
    fun variantsGoFromStrictToLoose() {
        val v = KeywordExtractor.queryVariants("How do mitochondria produce energy for the cell")
        assertEquals("mitochondria produce energy cell", v.first())
        assertTrue(v.contains("mitochondria produce energy"))
        assertTrue(v.contains("mitochondria produce"))
        assertTrue(v.last().split(' ').size == 1)
        assertEquals(v.size, v.toSet().size)
    }

    @Test
    fun questionWithNoKeywordsFallsBackToRawText() {
        assertEquals(listOf("what is it"), KeywordExtractor.queryVariants("what is it"))
        assertEquals(emptyList<String>(), KeywordExtractor.queryVariants("   "))
    }

    @Test
    fun unicodeKeywordsSurvive() {
        assertEquals(listOf("café", "über"), KeywordExtractor.keywords("Café, über!"))
    }

    private fun h(path: String) = ZimHit("z", "z.zim", path, path, "", 0.0, false)

    @Test
    fun articleInSeveralListsBeatsSingleListWinner() {
        val fused = HitFusion.fuse(
            listOf(
                RankedList(2.0, listOf(h("B"), h("A"))),
                RankedList(1.0, listOf(h("A"))),
                RankedList(1.0, listOf(h("C"), h("A"))),
            ),
        )
        assertEquals("A", fused.first().path)
        assertEquals(3, fused.size)
        assertTrue(fused.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun strictQueryOutweighsLooseOnes() {
        val fused = HitFusion.fuse(listOf(RankedList(4.0, listOf(h("strict"))), RankedList(1.0, listOf(h("loose")))))
        assertEquals(listOf("strict", "loose"), fused.map { it.path })
    }

    @Test
    fun duplicateAcrossArchivesIsKeptSeparately() {
        val a = ZimHit("z1", "a.zim", "P", "P", "", 0.0, false)
        val b = ZimHit("z2", "b.zim", "P", "P", "", 0.0, false)
        assertEquals(2, HitFusion.fuse(listOf(RankedList(1.0, listOf(a, b)))).size)
    }
}
