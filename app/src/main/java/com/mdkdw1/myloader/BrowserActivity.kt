package com.mdkdw1.myloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var goBtn: Button
    private lateinit var progress: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        goBtn = findViewById(R.id.goBtn)
        progress = findViewById(R.id.webProgress)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = settings.userAgentString // default Chrome-like

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                // http/https/magnet 만 내부 처리, 나머지는 외부로
                return if (u.startsWith("http") || u.startsWith("magnet:")) {
                    view.loadUrl(u); true
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                urlBar.setText(url)
                progress.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        // ★★★ 핵심: 다운로드 링크 클릭 가로채기 ★★★
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            onDownloadDetected(url, userAgent, contentDisposition, mimeType, contentLength)
        })

        goBtn.setOnClickListener {
            val u = normalize(urlBar.text.toString())
            if (u.isNotBlank()) webView.loadUrl(u)
        }

        // 인텐트로 URL 받으면 바로 로드
        intent?.dataString?.let { webView.loadUrl(it); urlBar.setText(it) }
            ?: run {
                webView.loadUrl("https://www.google.com")
                urlBar.setText("https://www.google.com")
            }
    }

    private fun normalize(input: String): String {
        val s = input.trim()
        return when {
            s.isBlank() -> ""
            s.startsWith("http") -> s
            s.contains(".") -> "https://$s"
            else -> "https://www.google.com/search?q=${Uri.encode(s)}"
        }
    }

    private fun onDownloadDetected(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
    ) {
        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
        val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)

        Toast.makeText(this, "다운로드 감지: $guessedName", Toast.LENGTH_SHORT).show()

        // MainActivity로 전달
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.mdkdw1.myloader.DOWNLOAD"
            putExtra("url", url)
            putExtra("cookie", cookies)
            putExtra("userAgent", userAgent)
            putExtra("fileName", guessedName)
            putExtra("contentLength", contentLength)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
