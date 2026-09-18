package com.mdkdw1.myloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
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
    // HTTP/1.1 강제 + 커넥션 풀 비활성 (unexpected end of stream 회피)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MINUTES))
        .build()

    private val downloaded = AtomicLong(0)
    private val maxRetries = 4

    suspend fun start() = coroutineScope {
        val total = contentLength()
        if (total <= 0) {
            onLog("⚠️ Content-Length 없음 → 단일 스트림")
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
                downloadChunkWithRetry(i + 1, start, end, total)
            }
        }

        try {
            jobs.awaitAll()
            onLog("✅ 완료: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            onLog("⚠️ 병렬 실패(${e.message}) → 단일 스트림 폴백")
            outputFile.delete()
            downloaded.set(0)
            downloadSingle()
            onLog("✅ 완료(단일): ${outputFile.absolutePath}")
        }
    }

    private suspend fun contentLength(): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", UA)
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val ranges = resp.header("Accept-Ranges")
            if (ranges != "bytes") onLog("⚠️ Range 미지원 가능")
            resp.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    private suspend fun downloadSingle() = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Encoding", "identity")
            .build()
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

    private suspend fun downloadChunkWithRetry(
        chunkNo: Int,
        start: Long,
        end: Long,
        total: Long,
    ) = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                onLog("청크 $chunkNo 시작: $start-$end" + if (attempt > 1) " (재시도 $attempt)" else "")
                downloadChunk(start, end, total)
                return@withContext
            } catch (e: HttpRetryableException) {
                if (attempt >= maxRetries) {
                    onLog("청크 $chunkNo 최종 실패(HTTP): ${e.message}")
                    throw e
                }
                val wait = e.retryAfterMs ?: backoff(attempt)
                onLog("청크 $chunkNo → HTTP ${e.code}, ${wait}ms 후 재시도")
                delay(wait)
            } catch (e: IOException) {
                if (attempt >= maxRetries) {
                    onLog("청크 $chunkNo 최종 실패(IO): ${e.message}")
                    throw e
                }
                val wait = backoff(attempt)
                onLog("청크 $chunkNo → IO 오류(${e.message}), ${wait}ms 후 재시도")
                delay(wait)
            }
        }
    }

    private fun backoff(attempt: Int): Long = when (attempt) {
        1 -> 500L
        2 -> 1500L
        3 -> 3000L
        else -> 5000L
    }

    private fun downloadChunk(start: Long, end: Long, total: Long) {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", UA)
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .build()

        client.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code == 429 || code in 500..599) {
                val retryAfter = resp.header("Retry-After")?.toLongOrNull()?.times(1000)
                throw HttpRetryableException(code, retryAfter)
            }
            if (!resp.isSuccessful && code != 206) error("청크 실패 HTTP $code")

            val body = resp.body ?: throw IOException("empty body")
            val expected = end - start + 1

            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.seek(start)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    var written = 0L
                    while (input.read(buf).also { n = it } != -1) {
                        raf.write(buf, 0, n)
                        written += n
                        val done = downloaded.addAndGet(n.toLong())
                        reportProgress(done, total)
                    }
                    if (written != expected) {
                        throw IOException("청크 크기 부족 ($written/$expected)")
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

    private class HttpRetryableException(
        val code: Int,
        val retryAfterMs: Long?,
    ) : Exception("HTTP $code")

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android) MyLoader/1.0"
    }
}
