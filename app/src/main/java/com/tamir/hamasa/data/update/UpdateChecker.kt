package com.tamir.hamasa.data.update

import android.content.Context
import android.util.Log
import com.tamir.hamasa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The newest published build, as described by the GitHub "latest release" endpoint. */
data class ReleaseInfo(
    /** Build number parsed from the tag `build-<n>`. */
    val build: Int,
    val htmlUrl: String,
    /** Release notes; may be empty. */
    val notes: String,
    /** Public download url of the APK asset (browser_download_url, or the API url as a fallback), or null if the release has no APK. */
    val assetUrl: String?
) {
    val isNewerThanInstalled: Boolean get() = build > BuildConfig.VERSION_CODE
}

/**
 * Queries GitHub for the latest release of the public [UpdateConfig.REPO], without a token. Never throws: any
 * network or HTTP problem (no network, 404, rate limit, bad JSON) is logged and reported as null.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "lastCheck"
    private val tagPattern = Regex("build-(\\d+)")

    /** The repo is public: always on. */
    val enabled: Boolean get() = UpdateConfig.REPO.isNotBlank()

    /** Epoch millis until which GitHub said "rate limit exceeded" (unauthenticated: 60 requests / hour / IP). */
    @Volatile var rateLimitedUntil: Long = 0L
        private set

    /** True while the GitHub rate limit is in force: checks are skipped. */
    val rateLimited: Boolean get() = System.currentTimeMillis() < rateLimitedUntil

    fun lastCheck(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(context: Context, atMillis: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CHECK, atMillis).apply()
    }

    /** True when the feature is enabled and the last automatic check is older than the interval. */
    fun isDue(context: Context): Boolean {
        if (!enabled || rateLimited) return false
        return System.currentTimeMillis() - lastCheck(context) >= UpdateConfig.CHECK_INTERVAL_MS
    }

    /** Fetches the latest release. Returns null when disabled or on any failure. */
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        if (!enabled || rateLimited) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://api.github.com/repos/${UpdateConfig.REPO}/releases/latest").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = UpdateConfig.TIMEOUT_MS
            conn.readTimeout = UpdateConfig.TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                // Unauthenticated rate limit: wait until GitHub's reset time (header, epoch seconds), at least 15 minutes.
                val remaining = conn.getHeaderField("x-ratelimit-remaining")
                val resetSec = conn.getHeaderField("x-ratelimit-reset")?.toLongOrNull() ?: 0L
                if (code == 429 || remaining == "0") {
                    val now = System.currentTimeMillis()
                    rateLimitedUntil = maxOf(now + 15L * 60L * 1000L, resetSec * 1000L).coerceAtMost(now + 2L * 60L * 60L * 1000L)
                    Log.w(TAG, "GitHub rate limit, next check after $rateLimitedUntil")
                    return@withContext null
                }
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "latest release failed: HTTP $code ${readAll(conn.errorStream)}")
                return@withContext null
            }
            val body = readAll(conn.inputStream)
            parse(body)
        } catch (e: Exception) {
            Log.w(TAG, "latest release error", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(json: String): ReleaseInfo? {
        return try {
            val o = JSONObject(json)
            val tag = o.optString("tag_name", "")
            val build = tagPattern.find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (build == null) {
                Log.w(TAG, "unexpected tag_name: $tag")
                return null
            }
            val htmlUrl = o.optString("html_url", "")
            val notes = if (o.isNull("body")) "" else o.optString("body", "")
            var assetUrl: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name", "") == UpdateConfig.ASSET_NAME) {
                        // public repo: the browser url (github.com, no API quota) works without a token
                        assetUrl = a.optString("browser_download_url", "").ifBlank { a.optString("url", "") }.ifBlank { null }
                        break
                    }
                }
            }
            ReleaseInfo(build = build, htmlUrl = htmlUrl, notes = notes.trim(), assetUrl = assetUrl)
        } catch (e: Exception) {
            Log.w(TAG, "parse error", e)
            null
        }
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }
}
