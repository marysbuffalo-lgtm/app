package com.kiwixrag.app.rag

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiwixrag.app.FixtureZimLibrary
import com.kiwixrag.app.zim.ZimContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineTest {
    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.rgb(40, 120, 200))
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test
    fun whatIsQuestionFindsArticleThroughTitleAndReturnsPhoto() {
        val lib = FixtureZimLibrary()
        val r = RagPipeline(lib).retrieve("What is photosynthesis?", topArticles = 4, maxImages = 2)
        assertEquals("Photosynthesis", r.sources.first().articleTitle)
        assertEquals("test_wiki_2026-01.zim", r.sources.first().zimFileName)
        assertEquals("A/Photosynthesis", r.sources.first().path)
        assertTrue(r.sources.first().text.contains("convert light energy into chemical energy"))
        val img = r.images.single()
        assertEquals(1, img.sourceIndex)
        assertEquals("chloroplast_diagram.png", img.fileName)
        assertEquals("Structure of a chloroplast", img.caption)
        assertEquals("P1", img.label)
        assertTrue(img.thumbnailJpeg.isNotEmpty())
        r.recycleImages()
        assertTrue(img.bitmap.isRecycled)
    }

    @Test
    fun redirectIsFollowedAndDeduplicated() {
        val lib = FixtureZimLibrary()
        val content = lib.read(lib.uuid, "A/Photosynthesis_(plants)")
        assertNotNull(content)
        assertEquals("A/Photosynthesis", content!!.path)
        assertEquals("text/html", content.mimeType)
    }

    @Test
    fun multiTermQuestionThatBreaksAndSearchStillFindsBothTopics() {
        val lib = FixtureZimLibrary()
        // Strict AND of "einstein" and "photosynthesis" matches nothing, as with real libzim. Fusion of the
        // looser single-keyword queries must still surface both articles.
        val r = RagPipeline(lib).retrieve("Compare photosynthesis and Einstein", topArticles = 4, maxImages = 0)
        val titles = r.sources.map { it.articleTitle }
        assertTrue(titles.toString(), "Photosynthesis" in titles && "Albert Einstein" in titles)
    }

    @Test
    fun fullTextQuestionFindsArticleByBodyText() {
        val lib = FixtureZimLibrary()
        val r = RagPipeline(lib).retrieve("Which light does chlorophyll absorb?", topArticles = 3, maxImages = 0)
        assertEquals("Photosynthesis", r.sources.first().articleTitle)
        assertTrue(r.images.isEmpty())
    }

    @Test
    fun unrelatedQuestionReturnsNothing() {
        val r = RagPipeline(FixtureZimLibrary()).retrieve("quantum zebra bicycle", topArticles = 4, maxImages = 2)
        assertTrue(r.sources.isEmpty())
        assertTrue(r.images.isEmpty())
    }

    @Test
    fun photosComeFromDifferentArticlesAndAreCappedAtTwo() {
        val html = { name: String -> "<html><body><h1>$name</h1><p>${name} article is about energy and the cell and mitochondria, with several sentences of real content in it.</p><img src='../I/$name.png' width='300' height='300' alt='$name picture'/></body></html>".toByteArray() }
        val extra = mutableMapOf<String, ZimContent>()
        for (n in listOf("Alpha", "Beta", "Gamma")) {
            extra["A/$n"] = ZimContent("x", "A/$n", n, "text/html", html(n))
            extra["I/$n.png"] = ZimContent("x", "I/$n.png", "", "image/png", png(300, 300))
        }
        val r = RagPipeline(FixtureZimLibrary(extra = extra)).retrieve("energy cell mitochondria", topArticles = 5, maxImages = 2)
        assertEquals(2, r.images.size)
        assertEquals(2, r.images.map { it.sourceIndex }.toSet().size)
        r.recycleImages()
    }

    @Test
    fun largeImageIsDownscaledToFiveHundredTwelve() {
        val big = png(2000, 1500)
        val d = ImageDecoder.decode(big)!!
        assertEquals(512, maxOf(d.bitmap.width, d.bitmap.height))
        assertEquals(2000f / 1500f, d.bitmap.width.toFloat() / d.bitmap.height, 0.02f)
        val thumb = android.graphics.BitmapFactory.decodeByteArray(d.thumbnailJpeg, 0, d.thumbnailJpeg.size)
        assertTrue(maxOf(thumb.width, thumb.height) <= ImageDecoder.THUMB_SIDE)
        d.bitmap.recycle()
    }

    @Test
    fun smallImageIsNotUpscaled() {
        val d = ImageDecoder.decode(png(300, 200))!!
        assertEquals(300, d.bitmap.width)
        d.bitmap.recycle()
    }

    @Test
    fun tinyOrExtremeOrCorruptImagesAreRejected() {
        assertEquals("tiny", null, ImageDecoder.decode(png(50, 50)))
        assertEquals("extreme aspect", null, ImageDecoder.decode(png(1400, 100)))
        assertEquals("corrupt", null, ImageDecoder.decode(byteArrayOf(1, 2, 3, 4)))
        assertEquals("empty", null, ImageDecoder.decode(ByteArray(0)))
    }

    @Test
    fun sampleSizeHalvesUntilNearTarget() {
        assertEquals(1, ImageDecoder.sampleSize(600, 512))
        assertEquals(2, ImageDecoder.sampleSize(1024, 512))
        assertEquals(4, ImageDecoder.sampleSize(2100, 512))
        assertEquals(8, ImageDecoder.sampleSize(4096, 512))
    }

    @Test
    fun nonImageBytesNamedAsImageAreSkippedWithoutCrashing() {
        val extra = mapOf(
            "A/Broken" to ZimContent("x", "A/Broken", "Broken", "text/html", "<html><body><h1>Broken</h1><p>energy article with an image that is not decodable at all in this file, and some more words to pad it out.</p><img src='../I/bad.png' width='300' height='300'/></body></html>".toByteArray()),
            "I/bad.png" to ZimContent("x", "I/bad.png", "", "image/png", "not a png".toByteArray()),
        )
        val r = RagPipeline(FixtureZimLibrary(extra = extra)).retrieve("broken energy", topArticles = 3, maxImages = 2)
        assertTrue(r.sources.any { it.articleTitle == "Broken" })
        assertFalse(r.images.any { it.fileName == "bad.png" })
    }
}
