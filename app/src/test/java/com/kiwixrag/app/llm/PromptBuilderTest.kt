package com.kiwixrag.app.llm

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiwixrag.app.rag.RetrievedImage
import com.kiwixrag.app.rag.Retrieval
import com.kiwixrag.app.rag.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class FakeEngine(override val supportsVision: Boolean = true, private val charsPerToken: Double = 4.0) : LlmEngine {
    override val modelName = "fake"
    override val backendLabel = "fake"
    override fun countTokens(text: String) = if (text.isEmpty()) 0 else (text.length / charsPerToken).toInt() + 1
    var lastPrompt: RagPrompt? = null
    var answer = "Answer [S1]."
    var failWith: Throwable? = null
    var closed = false
    override suspend fun generate(prompt: RagPrompt, onPartial: (String) -> Unit): String {
        lastPrompt = prompt
        failWith?.let { throw it }
        onPartial("Ans")
        onPartial(answer)
        return answer
    }
    override fun close() { closed = true }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptBuilderTest {
    private fun source(i: Int, chars: Int) = Source(
        i, "z", "wiki_en.zim", "Article $i", "A/Article_$i", "",
        (1..chars / 40).joinToString(" ") { "Sentence $it of article $i is here." },
    )

    private fun image(src: Int, n: Int) = RetrievedImage(
        src, "P$n", "wiki_en.zim", "Article $src", "I/pic$n.jpg", "pic$n.jpg", "caption $n",
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), ByteArray(4),
    )

    @Test
    fun promptAlwaysFitsContextWindowWithAnswerReserve() {
        val engine = FakeEngine()
        for (chars in listOf(2_000, 6_000, 40_000)) {
            val r = Retrieval(listOf("x"), (1..5).map { source(it, chars) }, listOf(image(1, 1), image(2, 2)))
            val built = PromptBuilder.build(r, "What is the thing?", engine, usePhotos = true)
            assertTrue("chars=$chars tokens=${built.estimatedTokens}", built.estimatedTokens <= LlmEngine.CONTEXT_TOKENS - LlmEngine.RESERVED_FOR_ANSWER)
            assertEquals(2, built.keptPhotos)
        }
    }

    @Test
    fun conservativeTokenizerStillFits() {
        val engine = FakeEngine(charsPerToken = 2.0)
        val r = Retrieval(listOf("x"), (1..5).map { source(it, 20_000) }, listOf(image(1, 1), image(2, 2)))
        val built = PromptBuilder.build(r, "Q", engine, usePhotos = true)
        assertTrue(built.estimatedTokens <= LlmEngine.CONTEXT_TOKENS - LlmEngine.RESERVED_FOR_ANSWER)
        assertTrue(built.prompt.contextBlock.contains("[S1] ZIM: wiki_en.zim | Article: Article 1"))
    }

    @Test
    fun textOnlyModelGetsNoPhotos() {
        val r = Retrieval(listOf("x"), listOf(source(1, 800)), listOf(image(1, 1)))
        val built = PromptBuilder.build(r, "Q", FakeEngine(supportsVision = false), usePhotos = true)
        assertEquals(0, built.prompt.photos.size)
        assertFalse(built.prompt.contextBlock.contains("[P1]"))
    }

    @Test
    fun photosListedWithArchiveArticleFileAndCaption() {
        val r = Retrieval(listOf("x"), listOf(source(1, 800)), listOf(image(1, 1)))
        val p = PromptBuilder.build(r, "Q", FakeEngine(), true).prompt
        assertTrue(p.contextBlock.contains("[P1] ZIM: wiki_en.zim | Article: Article 1 | File: pic1.jpg | Caption: caption 1"))
        assertEquals("P1", p.photos.single().label)
    }

    @Test
    fun systemPromptDemandsGroundingAndExactCitations() {
        val s = PromptBuilder.SYSTEM_INSTRUCTION
        assertTrue(s.contains("only from the Kiwix context"))
        assertTrue(s.contains("ZIM archive name"))
        assertTrue(s.contains("Article title"))
        assertTrue(s.contains("image file name"))
        assertTrue(s.contains("[S1]") && s.contains("[P1]"))
        assertFalse(s.contains('\u2014'))
    }

    @Test
    fun trimToSentenceCutsAtBoundary() {
        val t = "First sentence here. Second sentence here. Third sentence that is long."
        assertEquals("First sentence here. Second sentence here.", PromptBuilder.trimToSentence(t, 50))
        assertEquals(t, PromptBuilder.trimToSentence(t, 500))
    }

    @Test
    fun hugeQuestionDropsPhotosBeforeBreakingTheBudget() {
        val engine = FakeEngine()
        val r = Retrieval(listOf("x"), listOf(source(1, 3_000), source(2, 3_000)), listOf(image(1, 1), image(2, 2)))
        val built = PromptBuilder.build(r, "word ".repeat(2_000), engine, usePhotos = true)
        assertTrue(built.keptPhotos < 2)
    }
}
