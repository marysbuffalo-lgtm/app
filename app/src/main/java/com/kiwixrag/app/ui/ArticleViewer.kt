package com.kiwixrag.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kiwixrag.app.zim.ZimLibrary
import java.io.ByteArrayInputStream

private const val HOST = "zim.local"

/**
 * Serves ZIM content to a WebView through shouldInterceptRequest. URLs look like
 * https://zim.local/{zimId}/{entry path}, so relative links inside articles resolve exactly as they do
 * in the archive. JavaScript and network access stay off.
 */
class ZimWebViewClient(private val library: ZimLibrary) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != HOST) return notFound()
        val segments = url.pathSegments
        if (segments.size < 2) return notFound()
        val zimId = segments.first()
        val path = segments.drop(1).joinToString("/")
        val content = library.read(zimId, path, ZimLibrary.DEFAULT_MAX_ITEM_BYTES * 4) ?: return notFound()
        val mime = content.mimeType.substringBefore(';')
        val encoding = if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) "utf-8" else null
        return WebResourceResponse(mime, encoding, ByteArrayInputStream(content.bytes))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != HOST

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

fun articleUrl(zimId: String, path: String): String =
    "https://$HOST/${Uri.encode(zimId)}/${Uri.encode(path, "/")}"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArticleViewer(library: ZimLibrary, target: ViewerTarget, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(target.title) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = ZimWebViewClient(library)
                        loadUrl(articleUrl(target.zimId, target.path))
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
