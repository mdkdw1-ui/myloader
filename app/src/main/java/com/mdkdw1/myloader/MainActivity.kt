package com.mdkdw1.myloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var startBrowserBtn: Button
    private lateinit var openBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private var lastFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startBrowserBtn = findViewById(R.id.startBrowserBtn)
        openBtn = findViewById(R.id.openBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        startBrowserBtn.setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        openBtn.setOnClickListener { openLastFile() }
        openBtn.isEnabled = false

        log("앱 시작됨")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return

        // 내부 브라우저가 다운로드를 감지해서 넘겨준 경우
        if (intent.action == "com.mdkdw1.myloader.DOWNLOAD") {
            val url = intent.getStringExtra("url") ?: return
            val cookie = intent.getStringExtra("cookie")
            val ua = intent.getStringExtra("userAgent")
            val name = intent.getStringExtra("fileName") ?: guessName(url)
            log("📥 브라우저 감지: $name")
            log("URL: $url")
            startDownload(url, name, 8, cookie, ua)
            return
        }

        // 외부 공유/링크 열기
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val url = raw.trim().takeIf { it.startsWith("http") } ?: return
        log("🔗 외부 URL: $url")
        startDownload(url, guessName(url), 8, null, null)
    }

    private fun guessName(url: String): String {
        return try {
            val path = url.substringBefore("?").substringAfterLast("/")
            if (path.isBlank() || !path.contains(".")) "download.bin" else path
        } catch (_: Exception) { "download.bin" }
    }

    private fun startDownload(
        url: String,
        name: String,
        chunks: Int,
        cookie: String?,
        userAgent: String?,
    ) {
        startBrowserBtn.isEnabled = false
        openBtn.isEnabled = false
        progressBar.progress = 0
        statusText.text = "다운로드 중..."
        logText.text = ""

        lifecycleScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "").also { it.mkdirs() }
                }
                val outFile = File(dir, name)
                log("저장: ${outFile.absolutePath}")
                if (!cookie.isNullOrBlank()) log("🍪 쿠키: ${cookie.take(60)}...")

                val downloader = ParallelDownloader(
                    url = url,
                    outputFile = outFile,
                    chunkCount = chunks,
                    cookieString = cookie,
                    userAgentOverride = userAgent,
                    onProgress = { done, total, pct ->
                        runOnUiThread {
                            progressBar.progress = pct
                            statusText.text = "$pct%  (${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB)"
                        }
                    },
                    onLog = { msg -> runOnUiThread { log(msg) } }
                )

                downloader.start()
                lastFile = outFile
                statusText.text = "✅ 완료: ${outFile.name}"
                log("파일: ${outFile.absolutePath}")
                openBtn.isEnabled = true
                Toast.makeText(this@MainActivity, "완료: ${outFile.name}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                statusText.text = "❌ 실패"
                log("에러: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                startBrowserBtn.isEnabled = true
            }
        }
    }

    private fun openLastFile() {
        val f = lastFile ?: return
        if (!f.exists()) { log("⚠️ 파일 없음"); return }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(f.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "파일 열기"))
        } catch (e: Exception) {
            log("열기 실패: ${e.message}")
        }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        else -> "*/*"
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logText.append("[$time] $msg\n")
    }
}
