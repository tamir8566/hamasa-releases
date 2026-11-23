package com.tamir.hamasa.data.music

import android.util.Log
import com.tamir.hamasa.domain.game.Addr
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Why the last player call failed (for one short Hebrew line). */
enum class MusicError { NOT_CONNECTED, NO_DEVICE, PREMIUM, NETWORK, OTHER }

data class SpotifyDevice(val id: String, val name: String, val type: String, val isActive: Boolean, val restricted: Boolean)
data class SpotifyPlaylist(val uri: String, val name: String, val total: Int)
data class SpotifyTrack(val uri: String, val name: String, val artist: String)
data class SpotifyPlayback(val isPlaying: Boolean, val itemName: String, val deviceName: String)

/**
 * Spotify Web API player calls over HttpURLConnection + org.json (same style as GeminiClient).
 * Every call is suspend, runs on IO, and NEVER throws: failures return null/false/empty and set [lastError].
 * Playback control needs Spotify Premium (Tamir has it) and a Spotify app that was opened at least once lately.
 */
object SpotifyPlayer {
    private const val TAG = "SpotifyPlayer"

    @Volatile var lastError: MusicError? = null
        private set

    private class Resp(val code: Int, val text: String)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** One authorized request; on 401 refreshes the token once and retries. Null = no token or network failure. */
    private suspend fun call(method: String, path: String, body: JSONObject? = null): Resp? {
        val token = SpotifyAuth.accessToken() ?: run { lastError = MusicError.NOT_CONNECTED; return null }
        val first = raw(method, path, body, token) ?: run { lastError = MusicError.NETWORK; return null }
        if (first.code != 401) return first
        val fresh = SpotifyAuth.accessToken(forceRefresh = true) ?: run { lastError = MusicError.NOT_CONNECTED; return null }
        return raw(method, path, body, fresh) ?: run { lastError = MusicError.NETWORK; null }
    }

    private suspend fun raw(method: String, path: String, body: JSONObject?, token: String): Resp? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(SpotifyConfig.API_BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = SpotifyConfig.TIMEOUT_MS
                readTimeout = SpotifyConfig.TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (method == "PUT" || method == "POST") {
                // always send a body (empty = Content-Length: 0), otherwise Spotify may answer 411
                val bytes = (body?.toString() ?: "").toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                if (body != null) conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) Log.w(TAG, "$method $path -> HTTP $code: ${text.take(200)}")
            Resp(code, text)
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun errorOf(r: Resp): MusicError = when {
        r.code == 404 -> MusicError.NO_DEVICE
        r.code == 403 && r.text.contains("PREMIUM", ignoreCase = true) -> MusicError.PREMIUM
        r.code == 401 -> MusicError.NOT_CONNECTED
        else -> MusicError.OTHER
    }

    // ------------------------------------------------------------------ reading

    /** GET /me/player/devices. Empty on failure or when no Spotify app is awake. */
    suspend fun devices(): List<SpotifyDevice> {
        val r = call("GET", "/me/player/devices") ?: return emptyList()
        if (r.code !in 200..299) { lastError = errorOf(r); return emptyList() }
        return try {
            val arr = JSONObject(r.text).optJSONArray("devices") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = d.optString("id", "")
                if (id.isBlank() || id == "null") null
                else SpotifyDevice(id, d.optString("name"), d.optString("type"), d.optBoolean("is_active"), d.optBoolean("is_restricted"))
            }
        } catch (e: Exception) { emptyList() }
    }

    /** GET /me/player. Null when nothing is playing anywhere (204) or on failure. */
    suspend fun playback(): SpotifyPlayback? {
        val r = call("GET", "/me/player") ?: return null
        if (r.code == 204 || r.text.isBlank()) return null
        if (r.code !in 200..299) { lastError = errorOf(r); return null }
        return try {
            val o = JSONObject(r.text)
            SpotifyPlayback(
                isPlaying = o.optBoolean("is_playing"),
                itemName = o.optJSONObject("item")?.optString("name").orEmpty(),
                deviceName = o.optJSONObject("device")?.optString("name").orEmpty()
            )
        } catch (e: Exception) { null }
    }

    /** GET /me/playlists (his own and followed), up to 200. */
    suspend fun myPlaylists(): List<SpotifyPlaylist> {
        val out = ArrayList<SpotifyPlaylist>()
        var offset = 0
        while (offset < 200) {
            val r = call("GET", "/me/playlists?limit=50&offset=$offset") ?: break
            if (r.code !in 200..299) { lastError = errorOf(r); break }
            val parsed: JSONArray? = try { JSONObject(r.text).optJSONArray("items") } catch (e: Exception) { null }
            val items = parsed ?: break
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val uri = p.optString("uri", "")
                if (!uri.startsWith("spotify:")) continue
                // the track count object was renamed "tracks" -> "items" in the February 2026 development-mode API
                val countObj = p.optJSONObject("items") ?: p.optJSONObject("tracks")
                out.add(SpotifyPlaylist(uri, p.optString("name"), countObj?.optInt("total", 0) ?: 0))
            }
            if (items.length() < 50) break
            offset += 50
        }
        return out
    }

    /** GET /search?type=track. Development mode allows at most 10 results per request. */
    suspend fun searchTracks(query: String): List<SpotifyTrack> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val r = call("GET", "/search?type=track&limit=10&q=" + enc(q)) ?: return emptyList()
        if (r.code !in 200..299) { lastError = errorOf(r); return emptyList() }
        return try {
            val arr = JSONObject(r.text).optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = t.optString("uri", "")
                if (!uri.startsWith("spotify:track:")) return@mapNotNull null
                val artists = t.optJSONArray("artists")
                val artist = if (artists != null && artists.length() > 0) artists.optJSONObject(0)?.optString("name").orEmpty() else ""
                SpotifyTrack(uri, t.optString("name"), artist)
            }
        } catch (e: Exception) { emptyList() }
    }

    // ------------------------------------------------------------------ control

    /**
     * Plays [uri]: a single track ("spotify:track:...") or a context (playlist/album, optional start [offsetPosition]).
     * 404 NO_ACTIVE_DEVICE → retries on the phone (or the first usable device) with device_id, which also transfers
     * playback there. Returns false when no Spotify app is awake: the user must open Spotify once.
     */
    suspend fun play(uri: String, offsetPosition: Int? = null): Boolean {
        val body = JSONObject()
        if (uri.startsWith("spotify:track:")) {
            body.put("uris", JSONArray().put(uri))
        } else {
            body.put("context_uri", uri)
            if (offsetPosition != null && offsetPosition > 0) body.put("offset", JSONObject().put("position", offsetPosition))
        }
        val r = call("PUT", "/me/player/play", body) ?: return false
        if (r.code in 200..299) { lastError = null; return true }
        if (r.code != 404) { lastError = errorOf(r); return false }
        val device = pickDevice() ?: run { lastError = MusicError.NO_DEVICE; return false }
        val r2 = call("PUT", "/me/player/play?device_id=" + enc(device.id), body) ?: return false
        if (r2.code in 200..299) { lastError = null; return true }
        lastError = errorOf(r2)
        return false
    }

    /** PUT /me/player/shuffle. Regular shuffle only: Spotify's public API has no smart shuffle. */
    suspend fun setShuffle(on: Boolean): Boolean = simple("PUT", "/me/player/shuffle?state=" + (if (on) "true" else "false"))

    /** PUT /me/player/repeat. [state] = "off" | "context" (the whole playlist) | "track" (one song). */
    suspend fun setRepeat(state: String): Boolean {
        val s = if (state == "context" || state == "track") state else "off"
        return simple("PUT", "/me/player/repeat?state=$s")
    }

    /** POST /me/player/queue: [trackUri] plays after the current song, then the playlist goes on. */
    suspend fun queue(trackUri: String): Boolean = simple("POST", "/me/player/queue?uri=" + enc(trackUri))

    /** Repeat mode and now-playing track uri from GET /me/player; null when nothing is active or on failure. */
    private class PlayerState(val repeat: String, val itemUri: String)

    private suspend fun playerState(): PlayerState? {
        val r = call("GET", "/me/player") ?: return null
        if (r.code == 204 || r.text.isBlank() || r.code !in 200..299) return null
        return try {
            val o = JSONObject(r.text)
            PlayerState(o.optString("repeat_state", "off"), o.optJSONObject("item")?.optString("uri").orEmpty())
        } catch (e: Exception) { null }
    }

    /**
     * One song NOW without throwing away the playlist: queued, then skipped to. When it ends, the playlist that was
     * playing simply continues (no silence after the song).
     *
     * - Nothing active at all: [startContext] (the workout playlist) is started first, then the song goes on top of
     *   it the same way. Without one, a plain play of the song.
     * - Repeat "track" would loop the song forever, so repeat becomes "context" first (the next playlist moment puts
     *   his own setting back).
     * - After the skip the now-playing item is checked a few times. A different song (one he queued himself is
     *   ahead of ours) is accepted: music is playing and ours comes right after it. The song is NEVER restarted with
     *   a plain play while a playlist is active: that throws the playlist away and leaves silence after the song.
     * - The queue call refused while something plays: a plain play of the song (the only way left to hear it).
     */
    suspend fun playTrackKeepingContext(trackUri: String, startContext: (suspend () -> Boolean)? = null): Boolean {
        try {
            var before = playerState()
            if (before == null && startContext != null && startContext()) {
                // the playlist was just started: give the player a moment before queueing on top of it
                kotlinx.coroutines.delay(800)
                before = playerState()
            }
            if (before?.repeat == "track") setRepeat("context")
            if (before != null && queue(trackUri)) {
                // the queue call returns before the player has it: a short pause, then skip to it
                kotlinx.coroutines.delay(400)
                if (next()) {
                    for (i in 0 until 3) {
                        kotlinx.coroutines.delay(500)
                        if (playerState()?.itemUri == trackUri) break
                    }
                    lastError = null
                    return true
                }
            }
            return play(trackUri)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "playTrackKeepingContext failed", e)
            lastError = MusicError.OTHER
            return false
        }
    }

    /** PUT /me/player/play with no body: resumes whatever was playing last, on the active device. */
    suspend fun resume(): Boolean = simple("PUT", "/me/player/play")

    /** POST /me/player/next. */
    suspend fun next(): Boolean = simple("POST", "/me/player/next")

    /** PUT /me/player/pause. */
    suspend fun pause(): Boolean = simple("PUT", "/me/player/pause")

    /** PUT /me/player (transfer playback to [deviceId] and start playing there). */
    suspend fun transferTo(deviceId: String): Boolean {
        val body = JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", true)
        val r = call("PUT", "/me/player", body) ?: return false
        if (r.code in 200..299) { lastError = null; return true }
        lastError = errorOf(r)
        return false
    }

    private suspend fun simple(method: String, path: String): Boolean {
        val r = call(method, path) ?: return false
        if (r.code in 200..299) { lastError = null; return true }
        lastError = errorOf(r)
        return false
    }

    /** The active device, else the phone, else the first device that accepts commands. */
    private suspend fun pickDevice(): SpotifyDevice? {
        val list = devices().filter { !it.restricted }
        return list.firstOrNull { it.isActive }
            ?: list.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
            ?: list.firstOrNull()
    }

    /** Hebrew line for [err], in Shambiuluk's dry tone. */
    fun errorLineHe(err: MusicError?): String = when (err) {
        MusicError.NOT_CONNECTED -> "ספוטיפיי לא מחובר. ההגדרות מחכות."
        MusicError.NO_DEVICE -> Addr.t("ספוטיפיי ישן. פתח אותו פעם אחת בטלפון, והוא יתעורר.", "ספוטיפיי ישן. פתחי אותו פעם אחת בטלפון, והוא יתעורר.")
        MusicError.PREMIUM -> "ספוטיפיי דורש פרימיום לשליטה בנגינה. זה לא אני. זה הם."
        MusicError.NETWORK -> "אין רשת. המוזיקה תחכה."
        else -> "ספוטיפיי סירב. בלי הסבר. אופייני."
    }
}
