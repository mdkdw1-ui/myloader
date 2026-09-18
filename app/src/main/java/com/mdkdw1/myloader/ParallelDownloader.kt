package com.mdkdw1.myloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ParallelDownloader(
    private val url: String,
    private val outputFile: File,
    private val chunkCount: Int = 4,
    private val onProgress: (downloaded: Long, total: Long, percent: Int) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloaded = AtomicLong(0)

    suspend fun start() = coroutineScope {
        val total = contentLength()
        if (total <= 0) {
            onLog("⚠️ Content-Length 없음 → 단일 스트림 다운로드")
            downloadSingle()
            return@coroutineScope
        }

        onLog("📦 크기: ${total / 1024 / 1024} MB, 청크: $chunkCount")

        RandomAccessFile(outputFile, "rw").use { it.setLength(total) }

        val partSize = total / chunkCount
        val jobs = (0 until chunkCount).map { i ->
            val start = i * partSize
            val end = if (i == chunkCount - 1) total - 1 else (start + partSize - 1)
            async(Dispatchers.IO) {
                onLog("청크 ${i + 1} 시작: $start-$end")
                downloadChunk(start, end, total)
            }
        }
        jobs.awaitAll()
        onLog("✅ 완료: ${outputFile.absolutePath}")
    }

    private suspend fun contentLength(): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val ranges = resp.header("Accept-Ranges")
            if (ranges != "bytes") onLog("⚠️ 서버가 Range를 지원하지 않을 수 있음")
            resp.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    private suspend fun downloadSingle() = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            resp.body!!.byteStream().use { input ->
                outputFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        val done = downloaded.addAndGet(n.toLong())
                        reportProgress(done, total)
                    }
                }
            }
        }
    }

    private suspend fun downloadChunk(start: Long, end: Long, total: Long) =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-$end")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206)
                    error("청크 실패 HTTP ${resp.code}")

                RandomAccessFile(outputFile, "rw").use { raf ->
                    raf.seek(start)
                    resp.body!!.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            raf.write(buf, 0, n)
                            val done = downloaded.addAndGet(n.toLong())
                            reportProgress(done, total)
                        }
                    }
                }
            }
        }

    private fun reportProgress(done: Long, total: Long) {
        if (total > 0) {
            val pct = (done * 100 / total).toInt()
            onProgress(done, total, pct)
        }
    }
}
