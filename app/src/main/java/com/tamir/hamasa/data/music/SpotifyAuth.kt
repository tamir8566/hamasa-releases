package com.tamir.hamasa.data.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.Addr
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Spotify login with Authorization Code + PKCE (no client secret), token exchange and refresh.
 *
 * Tokens live ONLY on this device: a small JSON file in noBackupFilesDir (excluded from Android Auto Backup),
 * never in DataStore, never in CloudSync, never in the data export.
 */
object SpotifyAuth {
    private const val TAG = "SpotifyAuth"
    private const val TOKEN_FILE = "spotify_tokens.json"
    private const val PKCE_FILE = "spotify_pkce.json"

    private val _connected = MutableStateFlow(false)
    /** True while a refresh token is stored. */
    val connected: StateFlow<Boolean> = _connected

    private val _status = MutableStateFlow<String?>(null)
    /** Last login result line for the settings card (Hebrew). Null = nothing to say. */
    val status: StateFlow<String?> = _status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val lock = Mutex()
    private var loaded = false
    private var accessToken: String = ""
    private var refreshToken: String = ""
    private var expiresAtMillis: Long = 0L

    // ------------------------------------------------------------------ storage (no-backup dir, on-device only)

    private fun ctx(): Context = AppGraph.appContext

    private fun tokenFile(): File = File(ctx().noBackupFilesDir, TOKEN_FILE)
    private fun pkceFile(): File = File(ctx().noBackupFilesDir, PKCE_FILE)

    /** Reads the stored tokens once (cheap; safe to call often). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val f = tokenFile()
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    accessToken = o.optString("access", "")
                    refreshToken = o.optString("refresh", "")
                    expiresAtMillis = o.optLong("expiresAt", 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "token file unreadable", e)
            }
            _connected.value = refreshToken.isNotBlank()
            loaded = true
        }
    }

    val isConnected: Boolean get() { ensureLoaded(); return refreshToken.isNotBlank() }

    private fun saveTokens() {
        try {
            val o = JSONObject().put("access", accessToken).put("refresh", refreshToken).put("expiresAt", expiresAtMillis)
            tokenFile().writeText(o.toString())
        } catch (e: Exception) {
            Log.w(TAG, "token save failed", e)
        }
        _connected.value = refreshToken.isNotBlank()
    }

    private fun clearTokens() {
        accessToken = ""
        refreshToken = ""
        expiresAtMillis = 0L
        runCatching { tokenFile().delete() }
        _connected.value = false
    }

    // ------------------------------------------------------------------ PKCE

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun randomUrlString(byteCount: Int): String {
        val b = ByteArray(byteCount)
        SecureRandom().nextBytes(b)
        return base64Url(b)
    }

    /** S256: BASE64URL(SHA-256(ASCII(code_verifier))), no padding. */
    private fun challengeOf(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Opens Spotify's login page in the browser. The answer comes back to [SpotifyCallbackActivity] through
     * [SpotifyConfig.REDIRECT_URI]. Returns false when there is no client id or nothing can open the page.
     */
    fun beginLogin(context: Context): Boolean {
        if (!SpotifyConfig.enabled) return false
        return try {
            val verifier = randomUrlString(64)          // 86 chars, inside the 43..128 range
            val state = randomUrlString(16)
            pkceFile().writeText(JSONObject().put("verifier", verifier).put("state", state).toString())
            val url = SpotifyConfig.AUTHORIZE_URL +
                "?response_type=code" +
                "&client_id=" + enc(SpotifyConfig.CLIENT_ID) +
                "&redirect_uri=" + enc(SpotifyConfig.REDIRECT_URI) +
                "&scope=" + enc(SpotifyConfig.SCOPES) +
                "&code_challenge_method=S256" +
                "&code_challenge=" + enc(challengeOf(verifier)) +
                "&state=" + enc(state)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            _status.value = "נפתח דף ההתחברות של ספוטיפיי. מאשרים, וחוזרים לכאן."
            true
        } catch (e: Exception) {
            Log.w(TAG, "beginLogin failed", e)
            _status.value = "לא נמצא דפדפן לפתיחת דף ההתחברות."
            false
        }
    }

    /** Called by [SpotifyCallbackActivity] with the redirect. Exchanges the code in the background. */
    fun handleRedirect(uri: Uri) {
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (error != null || code.isNullOrBlank()) {
            _status.value = if (error == "access_denied") "ההתחברות בוטלה. אפשר לנסות שוב." else Addr.t("ספוטיפיי לא אישר את ההתחברות. נסה שוב.", "ספוטיפיי לא אישר את ההתחברות. נסי שוב.")
            return
        }
        AppGraph.scope.launch {
            _busy.value = true
            try {
                val pending = runCatching { JSONObject(pkceFile().readText()) }.getOrNull()
                val verifier = pending?.optString("verifier").orEmpty()
                val expectedState = pending?.optString("state").orEmpty()
                if (verifier.isBlank() || expectedState != state) {
                    _status.value = Addr.t("תשובת ההתחברות לא תואמת. נסה שוב מההתחלה.", "תשובת ההתחברות לא תואמת. נסי שוב מההתחלה.")
                    return@launch
                }
                val ok = exchange(code, verifier)
                runCatching { pkceFile().delete() }
                _status.value = if (ok) "מחובר לספוטיפיי. יפה." else Addr.t("ההתחברות נכשלה. בדוק רשת ונסה שוב.", "ההתחברות נכשלה. בדקי רשת ונסי שוב.")
            } finally {
                _busy.value = false
            }
        }
    }

    fun disconnect() {
        AppGraph.scope.launch {
            lock.withLock { ensureLoaded(); clearTokens() }
            _status.value = "נותק. ספוטיפיי כבר לא שומע אותנו."
        }
    }

    fun clearStatus() { _status.value = null }

    // ------------------------------------------------------------------ token endpoint

    private suspend fun exchange(code: String, verifier: String): Boolean {
        val json = postForm(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to SpotifyConfig.REDIRECT_URI,
                "client_id" to SpotifyConfig.CLIENT_ID,
                "code_verifier" to verifier
            )
        ) ?: return false
        return lock.withLock {
            ensureLoaded()
            val access = json.optString("access_token", "")
            val refresh = json.optString("refresh_token", "")
            if (access.isBlank() || refresh.isBlank()) return@withLock false
            accessToken = access
            refreshToken = refresh
            expiresAtMillis = System.currentTimeMillis() + (json.optLong("expires_in", 3600L) - 60L) * 1000L
            saveTokens()
            true
        }
    }

    /**
     * A valid access token, refreshing it when it is about to expire. Null when not connected or the refresh failed.
     * [forceRefresh] = true after the API answered 401.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        if (!SpotifyConfig.enabled) return null
        return lock.withLock {
            ensureLoaded()
            if (refreshToken.isBlank()) return@withLock null
            if (!forceRefresh && accessToken.isNotBlank() && System.currentTimeMillis() < expiresAtMillis) {
                return@withLock accessToken
            }
            val json = postForm(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to SpotifyConfig.CLIENT_ID
                )
            )
            if (json == null) {
                if (lastTokenErrorWasInvalidGrant) clearTokens()   // revoked or expired: he must connect again
                return@withLock null
            }
            val access = json.optString("access_token", "")
            if (access.isBlank()) return@withLock null
            accessToken = access
            // the answer may not carry a new refresh token: keep the old one then
            val newRefresh = json.optString("refresh_token", "")
            if (newRefresh.isNotBlank()) refreshToken = newRefresh
            expiresAtMillis = System.currentTimeMillis() + (json.optLong("expires_in", 3600L) - 60L) * 1000L
            saveTokens()
            accessToken
        }
    }

    @Volatile private var lastTokenErrorWasInvalidGrant = false

    /** POST application/x-www-form-urlencoded to the token endpoint. Null on any failure. Never throws. */
    private suspend fun postForm(params: Map<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        lastTokenErrorWasInvalidGrant = false
        var conn: HttpURLConnection? = null
        try {
            val body = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }.toByteArray(Charsets.UTF_8)
            conn = (URL(SpotifyConfig.TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = SpotifyConfig.TIMEOUT_MS
                readTimeout = SpotifyConfig.TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "token HTTP $code: ${text.take(300)}")
                if (code == 400 && text.contains("invalid_grant")) lastTokenErrorWasInvalidGrant = true
                return@withContext null
            }
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "token request failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
