package com.kiwixrag.app.zim

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiwixrag.app.ui.articleUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ViewerUrlTest {
    @Test
    fun urlKeepsPathStructureSoRelativeLinksResolve() {
        val url = articleUrl("bedd1322-b348", "A/Albert Einstein (physicist)")
        val u = Uri.parse(url)
        assertEquals("zim.local", u.host)
        assertEquals(listOf("bedd1322-b348", "A", "Albert Einstein (physicist)"), u.pathSegments)
        // A relative image link from the article resolves to the same archive root
        val resolved = java.net.URI(url).resolve("../I/pic.png")
        assertEquals("/bedd1322-b348/I/pic.png", resolved.path)
    }
}
