package com.mdkdw1.myloader

import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
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
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        urlInput.setText("https://speed.hetzner.de/100MB.bin")
        chunkInput.setText("8")
        fileNameInput.setText("test.bin")

        startBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val chunks = chunkInput.text.toString().toIntOrNull() ?: 4
            val name = fileNameInput.text.toString().ifBlank { "download.bin" }

            if (url.isBlank()) {
                log("⚠️ URL을 입력하세요")
                return@setOnClickListener
            }
            startDownload(url, name, chunks)
        }

        log("앱 시작됨")
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
                log("저장 경로: ${outFile.absolutePath}")

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
                log("파일 위치: ${outFile.absolutePath}")
            } catch (e: Exception) {
                statusText.text = "❌ 실패"
                log("에러: ${e.javaClass.simpleName}: ${e.message}")
                e.stackTrace.take(8).forEach { log("  at $it") }
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
