package com.example.epubreader

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.FileInputStream

/**
 * 通过 WebViewAssetLoader 以同源 https://appassets.androidplatform.net 服务：
 *  - /assets/  → assets 目录（阅读器 HTML 与 lib）
 *  - /epubs/   → 内部书籍文件（filesDir/epubs，流式响应）
 * 页面与书籍同源，彻底规避自定义 scheme / file:// 的跨源与 fetch 不可靠问题。
 */
class EpubWebViewClient(
    context: Context,
    private val store: EpubStore,
    private val onPageLoaded: () -> Unit = {},
) : WebViewClient() {

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/epubs/", EpubPathHandler(store))
        .build()

    private var loadedOnce = false

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        return assetLoader.shouldInterceptRequest(request.url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // 所有链接（阅读器内部 + 自定义 http/https 网页）都留在本 WebView
        return false
    }

    override fun onPageFinished(view: WebView, url: String) {
        // 首载完成触发一次（WebDAV 自动同步等），避免重建书架等后续加载重复触发
        if (!loadedOnce) {
            loadedOnce = true
            onPageLoaded()
        }
    }

    private class EpubPathHandler(
        private val store: EpubStore,
    ) : WebViewAssetLoader.PathHandler {

        override fun handle(path: String): WebResourceResponse {
            val name = path.removePrefix("/")
            val file = store.resolveFile(name)
            if (file == null) {
                return notFound()
            }
            return try {
                WebResourceResponse(
                    "application/epub+zip",
                    null,
                    200,
                    "OK",
                    mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-cache"
                    ),
                    FileInputStream(file)
                )
            } catch (e: Exception) {
                notFound()
            }
        }

        private fun notFound() = WebResourceResponse(
            "application/octet-stream",
            null,
            404,
            "Not Found",
            null,
            null
        )
    }
}
