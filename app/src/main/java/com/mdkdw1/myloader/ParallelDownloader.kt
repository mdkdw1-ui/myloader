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
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ParallelDownloader(
    private val url: String,
    private val outputFile: File,
    private val chunkCount: Int = 4,
    private val cookieString: String? = null,
    private val userAgentOverride: String? = null,
    private val onProgress: (downloaded: Long, total: Long, percent: Int) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloaded = AtomicLong(0)
    private val maxRetries = 4

    /** URL에서 자동으로 origin 추출 → Referer 로 사용 */
    private val referer: String = runCatching {
        val u = URI(url)
        "${u.scheme}://${u.host}/"
    }.getOrDefault(url)

    private fun Request.Builder.browserHeaders(): Request.Builder = apply {
        header("User-Agent", userAgentOverride ?: UA)
        header("Accept", "*/*")
        header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
        header("Accept-Encoding", "identity")
        header("Referer", referer)
        header("Origin", referer.trimEnd('/'))
        header("Sec-Fetch-Dest", "empty")
        header("Sec-Fetch-Mode", "cors")
        header("Sec-Fetch-Site", "same-origin")
        header("Connection", "keep-alive")
        if (!cookieString.isNullOrBlank()) {
            header("Cookie", cookieString)
        }
    }

    suspend fun start() = coroutineScope {
        val meta = fetchMeta()
        val total = meta.first
        val acceptRanges = meta.second

        if (total <= 0) {
            onLog("⚠️ Content-Length 없음 → 단일 스트림")
            downloadSingle()
            return@coroutineScope
        }
        if (!acceptRanges) {
            onLog("⚠️ Range 미지원 → 단일 스트림")
            downloadSingle()
            return@coroutineScope
        }

        onLog("📦 크기: ${total / 1024 / 1024} MB, 청크: $chunkCount")
        RandomAccessFile(outputFile, "rw").use { it.setLength(total) }

        val partSize = total / chunkCount
        val jobs = (0 until chunkCount).map { i ->
            val start = i * partSize
            val end = if (i == chunkCount - 1) total - 1 else (start + partSize - 1)
            async(Dispatchers.IO) { downloadChunkWithRetry(i + 1, start, end, total) }
        }

        try {
            jobs.awaitAll()
            onLog("✅ 완료: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            onLog("⚠️ 병렬 실패(${e.message}) → 단일 폴백")
            outputFile.delete()
            downloaded.set(0)
            downloadSingle()
            onLog("✅ 완료(단일): ${outputFile.absolutePath}")
        }
    }

    private suspend fun fetchMeta(): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        // 1차: HEAD
        runCatching {
            val req = Request.Builder().url(url).head()
                .browserHeaders()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HEAD HTTP ${resp.code}")
                val total = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                val ar = resp.header("Accept-Ranges") == "bytes"
                onLog("HEAD OK: $total bytes, ranges=$ar")
                return@withContext total to ar
            }
        }.onFailure { onLog("HEAD 실패(${it.message}) → GET probe") }

        // 2차: GET Range 0-0
        val req = Request.Builder().url(url).get()
            .browserHeaders()
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(req).execute().use { resp ->
            val code = resp.code
            onLog("GET probe: HTTP $code")
            when (code) {
                206 -> {
                    val cr = resp.header("Content-Range")
                    val total = cr?.substringAfterLast("/")?.toLongOrNull() ?: 0L
                    return@withContext total to true
                }
                200 -> {
                    val total = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    return@withContext total to false
                }
                403 -> {
                    val body = runCatching { resp.body?.string()?.take(300) }.getOrNull()
                    onLog("❌ 403 차단됨. body=$body")
                    onLog("💡 브라우저에서 이 링크를 먼저 열어 쿠키를 받아오거나, 쿠키를 앱에 입력하세요")
                    error("HTTP 403 (서버가 Referer/Cookie 검증 중)")
                }
                else -> {
                    val body = runCatching { resp.body?.string()?.take(200) }.getOrNull()
                    error("HTTP $code (body: ${body?.replace("\n", " ")})")
                }
            }
        }
    }

    private suspend fun downloadSingle() = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get()
            .browserHeaders()
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
        chunkNo: Int, start: Long, end: Long, total: Long,
    ) = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                onLog("청크 $chunkNo: $start-$end" + if (attempt > 1) " (재시도 $attempt)" else "")
                downloadChunk(start, end, total)
                return@withContext
            } catch (e: HttpRetryableException) {
                if (attempt >= maxRetries) throw e
                val wait = e.retryAfterMs ?: backoff(attempt)
                onLog("청크 $chunkNo → HTTP ${e.code}, ${wait}ms 후 재시도")
                delay(wait)
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e
                val wait = backoff(attempt)
                onLog("청크 $chunkNo → IO(${e.message}), ${wait}ms 후 재시도")
                delay(wait)
            }
        }
    }

    private fun backoff(attempt: Int) = when (attempt) {
        1 -> 500L; 2 -> 1500L; 3 -> 3000L; else -> 5000L
    }

    private fun downloadChunk(start: Long, end: Long, total: Long) {
        val req = Request.Builder().url(url).get()
            .browserHeaders()
            .header("Range", "bytes=$start-$end")
            .build()

        client.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code == 429 || code in 500..599) {
                throw HttpRetryableException(code, resp.header("Retry-After")?.toLongOrNull()?.times(1000))
            }
            if (!resp.isSuccessful && code != 206) error("청크 HTTP $code")

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
                    if (written != expected) throw IOException("크기 부족 ($written/$expected)")
                }
            }
        }
    }

    private fun reportProgress(done: Long, total: Long) {
        if (total > 0) onProgress(done, total, (done * 100 / total).toInt())
    }

    private class HttpRetryableException(val code: Int, val retryAfterMs: Long?) : Exception("HTTP $code")

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991N) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
