package com.mdkdw1.myloader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object WebViewCookieFetcher {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(context: Context, url: String): Result {
        return suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            val settings: WebSettings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            var finished = false

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = false

                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    if (finished) return
                    finished = true

                    // ★ 원본 URL과 최종 URL 양쪽의 쿠키를 수집
                    val cm = CookieManager.getInstance()
                    val originalCookies = cm.getCookie(url) ?: ""
                    val finalCookies = cm.getCookie(loadedUrl) ?: ""
                    val merged = (originalCookies + "; " + finalCookies)
                        .split(";")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("; ")

                    val ua = view.settings.userAgentString

                    view.stopLoading()
                    view.destroy()

                    if (cont.isActive) {
                        cont.resume(Result(url, loadedUrl, merged, ua))
                    }
                }
            }

            webView.loadUrl(url)

            cont.invokeOnCancellation {
                finished = true
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    data class Result(
        val originalUrl: String,
        val finalUrl: String,
        val cookie: String,
        val userAgent: String,
    )
}
