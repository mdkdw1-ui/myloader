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

/**
 * WebView로 URL을 로드해서 Cloudflare JS 챌린지를 통과한 뒤
 * 최종 쿠키와 User-Agent를 반환한다.
 */
object WebViewCookieFetcher {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(context: Context, url: String): Result {
        return suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            val settings: WebSettings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString // keep default (Chrome-like)
            settings.loadsImagesAutomatically = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            var finished = false

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    // 리다이렉트 그대로 진행
                    return false
                }

                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    if (finished) return
                    finished = true

                    // 쿠키 수집
                    val cookies = CookieManager.getInstance().getCookie(loadedUrl) ?: ""
                    val ua = view.settings.userAgentString

                    // WebView 정리
                    view.stopLoading()
                    view.destroy()

                    if (cont.isActive) {
                        cont.resume(Result(loadedUrl, cookies, ua))
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
        val finalUrl: String,
        val cookie: String,
        val userAgent: String,
    )
}
