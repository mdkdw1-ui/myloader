package com.mdkdw1.myloader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
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

    private lateinit var urlInput: EditText
    private lateinit var chunkInput: EditText
    private lateinit var fileNameInput: EditText
    private lateinit var startBtn: Button
    private lateinit var pasteBtn: Button
    private lateinit var openBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private var lastFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        chunkInput = findViewById(R.id.chunkInput)
        fileNameInput = findViewById(R.id.fileNameInput)
        startBtn = findViewById(R.id.startBtn)
        pasteBtn = findViewById(R.id.pasteBtn)
        openBtn = findViewById(R.id.openBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        chunkInput.setText("8")

        startBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isBlank()) { log("⚠️ URL 없음"); return@setOnClickListener }
            val chunks = chunkInput.text.toString().toIntOrNull() ?: 8
            val name = fileNameInput.text.toString().ifBlank { guessName(url) }
            fileNameInput.setText(name)
            startDownload(url, name, chunks)
        }

        pasteBtn.setOnClickListener { pasteFromClipboard() }
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
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return

        val url = extractUrl(raw) ?: raw.trim()
        if (!url.startsWith("http")) return

        urlInput.setText(url)
        val name = guessName(url)
        fileNameInput.setText(name)
        log("🔗 외부 URL: $url")
        statusText.text = "자동 시작 대기..."

        startBtn.postDelayed({
            val chunks = chunkInput.text.toString().toIntOrNull() ?: 8
            startDownload(url, name, chunks)
        }, 500)
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("""(https?://[^\s"'<>]+)""")
        return regex.find(text)?.value
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = extractUrl(text) ?: text.trim().takeIf { it.startsWith("http") }
        if (url != null) {
            urlInput.setText(url)
            fileNameInput.setText(guessName(url))
            log("📋 클립보드: $url")
        } else {
            Toast.makeText(this, "클립보드에 URL 없음", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessName(url: String): String {
        return try {
            val path = url.substringBefore("?").substringAfterLast("/")
            if (path.isBlank() || !path.contains(".")) "download.bin" else path
        } catch (_: Exception) { "download.bin" }
    }

    private fun startDownload(url: String, name: String, chunks: Int) {
        startBtn.isEnabled = false
        openBtn.isEnabled = false
        progressBar.progress = 0
        statusText.text = "다운로드 준비 중..."
        logText.text = ""

        lifecycleScope.launch {
            try {
                // 1) WebView로 Cloudflare 등 통과 → 쿠키 & UA 획득
                log("🌐 WebView 프리패스 시작...")
                val result = withContext(Dispatchers.Main) {
                    WebViewCookieFetcher.fetch(this@MainActivity, url)
                }
                log("✅ 쿠키 획득: ${result.cookie.take(80)}${if (result.cookie.length > 80) "..." else ""}")
                log("✅ 최종 URL: ${result.finalUrl}")

                // 2) 병렬 다운로더 실행
                val dir = withContext(Dispatchers.IO) {
                    File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "").also { it.mkdirs() }
                }
                val outFile = File(dir, name)
                log("저장: ${outFile.absolutePath}")

                val downloader = ParallelDownloader(
                    url = result.originalUrl,
                    outputFile = outFile,
                    chunkCount = chunks,
                    cookieString = result.cookie.ifBlank { null },
                    userAgentOverride = result.userAgent.ifBlank { null },
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
                startBtn.isEnabled = true
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
        "txt" -> "text/plain"
        else -> "*/*"
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logText.append("[$time] $msg\n")
    }
}
