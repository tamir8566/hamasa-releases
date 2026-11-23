package com.tamir.hamasa.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the APK asset of a release into `cacheDir/updates/hamasa-<n>.apk` and hands it to the
 * system package installer through the app's FileProvider.
 *
 * GitHub answers the asset url with a redirect to a pre-signed storage url. That second request
 * must NOT carry the Authorization header (the storage rejects two auth mechanisms), so redirects
 * are followed by hand instead of letting HttpURLConnection do it.
 */
object UpdateDownloader {
    private const val TAG = "UpdateDownloader"
    private const val MAX_REDIRECTS = 5

    private val _progress = MutableStateFlow(0f)
    /** 0..1 while a download is running; stays at 1 after a successful download. */
    val progress: StateFlow<Float> = _progress

    fun targetFile(context: Context, build: Int): File =
        File(File(context.cacheDir, "updates"), "hamasa-$build.apk")

    /** Downloads [info]'s APK. Returns the file, or null on any failure (logged). */
    suspend fun download(context: Context, info: ReleaseInfo): File? = withContext(Dispatchers.IO) {
        val assetUrl = info.assetUrl
        if (assetUrl == null) {
            Log.w(TAG, "release ${info.build} has no asset named ${UpdateConfig.ASSET_NAME}")
            return@withContext null
        }
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create ${dir.absolutePath}")
            return@withContext null
        }
        // Remove older downloads so the cache does not grow.
        dir.listFiles()?.forEach { f -> if (f.name != "hamasa-${info.build}.apk") f.delete() }
        val target = targetFile(context, info.build)
        val temp = File(dir, "hamasa-${info.build}.part")
        _progress.value = 0f
        val ok = downloadTo(assetUrl, temp)
        if (!ok || temp.length() <= 0L) {
            Log.w(TAG, "download failed")
            temp.delete()
            return@withContext null
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            Log.w(TAG, "rename failed")
            temp.delete()
            return@withContext null
        }
        _progress.value = 1f
        target
    }

    // ------------------------------------------------------------------ the transfer itself

    /** Parallel parts for a big file (owner's wish 2026-11-08: the download took long on his phone). */
    const val PARTS = 4
    /** Below this size the file is fetched in one stream: the parts would not help. */
    const val PARALLEL_MIN_BYTES = 2L * 1024 * 1024

    /** Tests only: the url is used as given (no GitHub headers on the first hop). */
    @Volatile internal var plainUrls = false

    /**
     * Fetches [url] into [temp]: first the redirects are followed to the real file url and its size and
     * Range support are read; a big file that allows ranges comes down in [PARTS] parallel pieces written
     * straight into their places in the file; anything else (small file, no ranges, a part that failed)
     * falls back to the plain single stream. Returns true when [temp] holds the whole file.
     */
    internal suspend fun downloadTo(url: String, temp: File): Boolean {
        val head = probe(url)
        if (head != null && head.total >= PARALLEL_MIN_BYTES && head.ranges) {
            val ok = runCatching { downloadParallel(head.finalUrl, head.total, temp) }.getOrElse {
                Log.w(TAG, "parallel download error", it)
                false
            }
            if (ok && temp.length() == head.total) return true
            Log.w(TAG, "parallel download incomplete (${temp.length()} of ${head.total}), falling back to one stream")
            temp.delete()
            _progress.value = 0f
        }
        return downloadSingle(url, temp)
    }

    internal data class Probe(val finalUrl: String, val total: Long, val ranges: Boolean)

    /**
     * Follows the redirects and asks for the first byte only (Range 0-0): a 206 answer with Content-Range
     * tells the total size and that ranges work; a 200 answer gives the size from Content-Length and no ranges.
     */
    internal fun probe(url: String): Probe? {
        var conn: HttpURLConnection? = null
        try {
            val open = openFollowingRedirects(url, rangeHeader = "bytes=0-0") ?: return null
            conn = open
            val code = open.responseCode
            val finalUrl = open.url.toString()
            if (code == 206) {
                // Content-Range: bytes 0-0/31013178
                val cr = open.getHeaderField("Content-Range") ?: return null
                val total = cr.substringAfterLast('/').trim().toLongOrNull() ?: return null
                return Probe(finalUrl, total, ranges = true)
            }
            val total = open.contentLengthLong
            return if (total > 0L) Probe(finalUrl, total, ranges = false) else null
        } catch (e: Exception) {
            Log.w(TAG, "probe error", e)
            return null
        } finally {
            runCatching { conn?.inputStream?.close() }
            conn?.disconnect()
        }
    }

    /** The byte ranges of [parts] pieces of a [total]-byte file: (first, last) inclusive. */
    internal fun ranges(total: Long, parts: Int = PARTS): List<Pair<Long, Long>> {
        val n = parts.coerceAtLeast(1)
        val size = (total + n - 1) / n
        val out = ArrayList<Pair<Long, Long>>()
        var from = 0L
        while (from < total) {
            val to = minOf(from + size - 1, total - 1)
            out.add(from to to)
            from = to + 1
        }
        return out
    }

    private suspend fun downloadParallel(finalUrl: String, total: Long, temp: File): Boolean = coroutineScope {
        // the file is created at its full size first, so every part can write at its own offset
        java.io.RandomAccessFile(temp, "rw").use { it.setLength(total) }
        val copied = java.util.concurrent.atomic.AtomicLong(0L)
        val jobs: List<kotlinx.coroutines.Deferred<Boolean>> = ranges(total).map { (from, to) ->
            async(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    val c = URL(finalUrl).openConnection() as HttpURLConnection
                    conn = c
                    c.requestMethod = "GET"
                    c.instanceFollowRedirects = false
                    c.connectTimeout = UpdateConfig.TIMEOUT_MS
                    c.readTimeout = UpdateConfig.TIMEOUT_MS
                    c.setRequestProperty("Range", "bytes=$from-$to")
                    if (c.responseCode != 206) return@async false
                    val want = to - from + 1
                    var got = 0L
                    java.io.RandomAccessFile(temp, "rw").use { raf ->
                        raf.seek(from)
                        c.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (got < want) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), want - got).toInt())
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                got += n
                                val all = copied.addAndGet(n.toLong())
                                _progress.value = (all.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    }
                    got == want
                } catch (e: Exception) {
                    Log.w(TAG, "part $from-$to failed", e)
                    false
                } finally {
                    conn?.disconnect()
                }
            }
        }
        val results = jobs.map { it.await() }
        results.all { it }
    }

    private fun downloadSingle(url: String, temp: File): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val open = openFollowingRedirects(url) ?: return false
            conn = open
            val total = open.contentLengthLong
            open.inputStream.use { input ->
                FileOutputStream(temp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0L) _progress.value = (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                    out.flush()
                }
            }
            return temp.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "download error", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Opens the asset url (public, no token) with GitHub headers, follows up to [MAX_REDIRECTS] redirects (plain
     * requests on the redirected hops) and returns a connection whose response code is 200 (or 206 when a
     * [rangeHeader] was sent), or null.
     */
    private fun openFollowingRedirects(startUrl: String, rangeHeader: String? = null): HttpURLConnection? {
        var url = startUrl
        var withAuth = !plainUrls
        for (hop in 0..MAX_REDIRECTS) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = UpdateConfig.TIMEOUT_MS
            conn.readTimeout = UpdateConfig.TIMEOUT_MS
            if (withAuth) {
                // public repo: no token; the Accept header makes an API asset url return the file itself
                conn.setRequestProperty("Accept", "application/octet-stream")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            if (rangeHeader != null) conn.setRequestProperty("Range", rangeHeader)
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK || (rangeHeader != null && code == 206)) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (code in 300..399 && !location.isNullOrBlank()) {
                url = location
                withAuth = false
                continue
            }
            Log.w(TAG, "asset download failed: HTTP $code at hop $hop")
            return null
        }
        Log.w(TAG, "too many redirects")
        return null
    }

    /** Whether the system lets this app launch the package installer (Android 8+ setting). */
    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Sends the user to the "install unknown apps" screen for this app. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "cannot open install settings", it) }
    }

    /** Launches the package installer for a downloaded APK. Returns false if it could not be started. */
    fun install(context: Context, apk: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot start installer", e)
            false
        }
    }
}
