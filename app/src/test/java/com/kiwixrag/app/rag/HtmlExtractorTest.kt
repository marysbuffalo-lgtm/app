package com.kiwixrag.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlExtractorTest {
    private fun fixture(name: String) = String(javaClass.classLoader!!.getResourceAsStream("zimfixture/$name")!!.readBytes())

    @Test
    fun realArticleYieldsTextCaptionAndResolvedImage() {
        val html = fixture("A__Photosynthesis")
        val a = HtmlExtractor.extract(html, "A/Photosynthesis", listOf("chlorophyll"), 4_000)
        assertEquals("Photosynthesis", a.title)
        assertTrue(a.text.contains("Chlorophyll absorbs mostly blue and red light"))
        assertTrue(a.text.contains("green plants and algae"))
        assertEquals(1, a.images.size)
        val img = a.images.single()
        assertEquals("I/chloroplast_diagram.png", img.path)
        assertEquals("chloroplast_diagram.png", img.fileName)
        assertEquals("Structure of a chloroplast", img.caption)
    }

    @Test
    fun maxCharsIsRespectedAndLeadIsKept() {
        val long = (1..40).joinToString("") { "<p>Paragraph number $it talks about various plants and their leaves in some detail here.</p>" }
        val a = HtmlExtractor.extract("<html><body><h1>T</h1>$long</body></html>", "A/T", listOf("zzz"), 300)
        assertTrue(a.text.length <= 300)
        assertTrue(a.text.startsWith("Paragraph number 1 "))
    }

    @Test
    fun keywordPassageFromDeepInTheArticleIsSelected() {
        val filler = (1..30).joinToString("") { "<p>Unrelated filler sentence number $it about nothing in particular at all.</p>" }
        val html = "<html><body><h1>T</h1><p>Lead paragraph about the topic of this article overall.</p>$filler" +
            "<h2>Details</h2><p>The rare keyword mitochondria appears only in this specific passage of text.</p></body></html>"
        val a = HtmlExtractor.extract(html, "A/T", listOf("mitochondria"), 400)
        assertTrue(a.text.contains("mitochondria appears only"))
        assertTrue(a.text.contains("Details"))
        assertTrue(a.text.contains("Lead paragraph"))
    }

    @Test
    fun noiseIsStripped() {
        val html = "<html><body><h1>T</h1><p>Real content paragraph with enough characters to count.</p>" +
            "<sup class='reference'>[1]</sup><div class='navbox'><p>Navigation box link text that must not appear.</p></div>" +
            "<script>var x = 'script text that must not appear';</script><style>.a{}</style></body></html>"
        val a = HtmlExtractor.extract(html, "A/T", emptyList(), 2_000)
        assertTrue(a.text.contains("Real content"))
        assertFalse(a.text.contains("Navigation box"))
        assertFalse(a.text.contains("script text"))
        assertFalse(a.text.contains("[1]"))
    }

    @Test
    fun unusableImagesAreFilteredAndBestIsFirst() {
        val html = """<html><body><h1>T</h1><p>Some paragraph text long enough to be kept in output.</p>
            <img src="../I/Commons-logo.png" width="200" height="200"/>
            <img src="../I/diagram.svg"/>
            <img src="../I/tiny.png" width="20" height="20"/>
            <img src="data:image/png;base64,AAAA"/>
            <img src="https://example.com/x.png"/>
            <img class="mwe-math-fallback-image-inline" src="../I/math.png"/>
            <img src="../I/plain.jpg" width="300" height="200" alt="A plain photo"/>
            <figure><img src="../I/best.jpg" width="400" height="300"/><figcaption>The best figure here</figcaption></figure>
            </body></html>"""
        val imgs = HtmlExtractor.extract(html, "A/T", emptyList(), 1_000).images
        assertEquals(listOf("I/best.jpg", "I/plain.jpg"), imgs.map { it.path })
    }

    @Test
    fun imagePathResolutionMatchesBrowserSemantics() {
        assertEquals("I/x.png", ImagePaths.resolve("A/Foo", "../I/x.png"))
        assertEquals("I/x.png", ImagePaths.resolve("Foo", "./I/x.png"))
        assertEquals("I/x.png", ImagePaths.resolve("Foo", "I/x.png"))
        assertEquals("I/x.png", ImagePaths.resolve("A/Foo", "/I/x.png"))
        assertEquals("A/I/x.png", ImagePaths.resolve("A/Foo", "I/x.png"))
        assertEquals("I/a b.png", ImagePaths.resolve("A/Foo", "../I/a%20b.png"))
        assertEquals("I/a b.png", ImagePaths.resolve("A/Foo", "../I/a b.png"))
        assertEquals("I/x.png", ImagePaths.resolve("A/Foo", "../I/x.png?width=200#frag"))
        assertEquals("A/Sub/I/x.png", ImagePaths.resolve("A/Sub/Foo bar", "I/x.png"))
        assertEquals("I/x.png", ImagePaths.resolve("A/B/Foo", "../../I/x.png"))
        assertEquals("I/x.png", ImagePaths.resolve("Foo", "../../I/x.png"))
        assertNull(ImagePaths.resolve("A/Foo", "https://a/b.png"))
        assertNull(ImagePaths.resolve("A/Foo", "//cdn/b.png"))
        assertNull(ImagePaths.resolve("A/Foo", "data:image/png;base64,AA"))
        assertNull(ImagePaths.resolve("A/Foo", "  "))
    }
}
