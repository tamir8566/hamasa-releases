package com.tamir.hamasa.flows

/*
 * The update download (data/update/UpdateDownloader), against a local web server inside the test:
 *  1. downloadParallelParts   a 3 MB file served with Range support: comes down in 4 parts, byte for byte the same.
 *  2. downloadNoRangesFallback the same server without Range support: one stream, still byte for byte the same.
 *  3. downloadRedirectThenParts a redirect first (like GitHub), then the parts.
 * No images.
 */

import com.tamir.hamasa.data.update.UpdateDownloader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadFlowsTest {

    /** A tiny HTTP server on a plain socket (the JDK's HttpServer is not on the Android test classpath). */
    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()
    private lateinit var body: ByteArray
    private val rangeRequests = AtomicInteger(0)
    private val fullRequests = AtomicInteger(0)
    private var allowRanges = true
    @Volatile private var running = true

    @Before
    fun setUp() {
        body = ByteArray(3 * 1024 * 1024) { i -> ((i * 31 + (i shr 7)) and 0xFF).toByte() }
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        running = true
        pool.execute {
            while (running) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                pool.execute { runCatching { serve(sock) } }
            }
        }
        UpdateDownloader.plainUrls = true
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            val path = requestLine.split(' ').getOrElse(1) { "/" }
            val out = s.getOutputStream()
            fun head(status: String, headers: List<String>) {
                out.write(("HTTP/1.1 $status\r\n" + headers.joinToString("") { it + "\r\n" } + "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            }
            when {
                path.startsWith("/redirect") -> head("302 Found", listOf("Location: http://127.0.0.1:${server.localPort}/file.apk", "Content-Length: 0"))
                allowRanges && range != null && range.startsWith("bytes=") -> {
                    rangeRequests.incrementAndGet()
                    val (a, b) = range.removePrefix("bytes=").split("-")
                    val from = a.toLong()
                    val to = if (b.isBlank()) body.size - 1L else b.toLong().coerceAtMost(body.size - 1L)
                    val len = (to - from + 1).toInt()
                    head("206 Partial Content", listOf("Content-Range: bytes $from-$to/${body.size}", "Accept-Ranges: bytes", "Content-Length: $len", "Content-Type: application/octet-stream"))
                    out.write(body, from.toInt(), len)
                }
                else -> {
                    fullRequests.incrementAndGet()
                    head("200 OK", listOf("Content-Length: ${body.size}", "Content-Type: application/octet-stream"))
                    out.write(body)
                }
            }
            out.flush()
        }
    }

    @After
    fun tearDown() {
        UpdateDownloader.plainUrls = false
        running = false
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    @Test
    fun downloadParallelParts() {
        val flow = "downloadParallelParts"
        val temp = File.createTempFile("hamasa", ".part")
        val ok = runBlocking { UpdateDownloader.downloadTo(url("/file.apk"), temp) }
        assertTrue("[$flow] download ok", ok)
        assertArrayEquals("[$flow] bytes identical", body, temp.readBytes())
        // the probe (0-0) plus the four parts
        assertEquals("[$flow] four parts after the probe", UpdateDownloader.PARTS + 1, rangeRequests.get())
        assertEquals("[$flow] no full stream needed", 0, fullRequests.get())
        assertEquals("[$flow] progress complete", 1f, UpdateDownloader.progress.value, 0.001f)
        temp.delete()
    }

    @Test
    fun downloadNoRangesFallback() {
        val flow = "downloadNoRangesFallback"
        allowRanges = false
        val temp = File.createTempFile("hamasa", ".part")
        val ok = runBlocking { UpdateDownloader.downloadTo(url("/file.apk"), temp) }
        assertTrue("[$flow] download ok", ok)
        assertArrayEquals("[$flow] bytes identical", body, temp.readBytes())
        assertTrue("[$flow] one stream used", fullRequests.get() >= 1)
        temp.delete()
    }

    @Test
    fun downloadRedirectThenParts() {
        val flow = "downloadRedirectThenParts"
        val temp = File.createTempFile("hamasa", ".part")
        val ok = runBlocking { UpdateDownloader.downloadTo(url("/redirect"), temp) }
        assertTrue("[$flow] download ok", ok)
        assertArrayEquals("[$flow] bytes identical", body, temp.readBytes())
        assertEquals("[$flow] parts went to the final url", UpdateDownloader.PARTS + 1, rangeRequests.get())
        // the ranges cover the file exactly once
        val r = UpdateDownloader.ranges(body.size.toLong())
        assertEquals("[$flow] first byte", 0L, r.first().first)
        assertEquals("[$flow] last byte", body.size - 1L, r.last().second)
        for (i in 1 until r.size) assertEquals("[$flow] contiguous", r[i - 1].second + 1, r[i].first)
        temp.delete()
    }
}
