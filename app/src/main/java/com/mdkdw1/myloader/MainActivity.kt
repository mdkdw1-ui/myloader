package com.mdkdw1.myloader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        chunkInput = findViewById(R.id.chunkInput)
        fileNameInput = findViewById(R.id.fileNameInput)
        startBtn = findViewById(R.id.startBtn)
        pasteBtn = findViewById(R.id.pasteBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        chunkInput.setText("4")
        urlInput.setText("https://speed.cloudflare.com/__down?bytes=104857600")
        fileNameInput.setText("cf100mb.bin")

        startBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isBlank()) { log("⚠️ URL 없음"); return@setOnClickListener }
            val chunks = chunkInput.text.toString().toIntOrNull() ?: 4
            val name = fileNameInput.text.toString().ifBlank { guessName(url) }
            fileNameInput.setText(name)
            startDownload(url, name, chunks)
        }

        pasteBtn.setOnClickListener { pasteFromClipboard() }

        log("앱 시작됨")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val url = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.let { extractUrl(it) }

        if (!url.isNullOrBlank()) {
            urlInput.setText(url)
            fileNameInput.setText(guessName(url))
            log("🔗 URL 수신: $url")
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("""(https?://[^\s]+|magnet:\?[^\s]+)""")
        return regex.find(text)?.value
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = extractUrl(text)
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

                val downloader = ParallelDownloader(
                    url = url,
                    outputFile = outFile,
                    chunkCount = chunks,
                    onProgress = { done, total, pct ->
                        runOnUiThread {
                            progressBar.progress = pct
                            statusText.text = "$pct%  (${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB)"
                        }
                    },
                    onLog = { msg -> runOnUiThread { log(msg) } }
                )

                downloader.start()
                statusText.text = "✅ 완료: ${outFile.name}"
                log("파일: ${outFile.absolutePath}")
            } catch (e: Exception) {
                statusText.text = "❌ 실패"
                log("에러: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                startBtn.isEnabled = true
            }
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logText.append("[$time] $msg\n")
    }
}
