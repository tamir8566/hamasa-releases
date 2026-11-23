package com.tamir.hamasa.data.ai

import android.util.Base64
import android.util.Log
import com.tamir.hamasa.data.cloud.Account
import com.tamir.hamasa.data.cloud.CloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client of the Supabase Edge Function "ai" (supabase/functions/ai/index.ts, docs/ai.md), which holds the
 * Claude / Gemini keys so the APK does not have to. Same request shape as [ClaudeClient] / [GeminiClient]; the
 * function applies the same retry rules. Callers use [AiClient], not this object.
 */
object AiProxy {
    private const val TAG = "AiProxy"
    /** The function can retry upstream calls (up to 3 x 60 s on its side); Supabase stops a function at 150 s. */
    private const val READ_TIMEOUT_MS = 150_000
    private const val CONNECT_TIMEOUT_MS = 15_000
    /** After "not deployed / not allowed" answers, skip the proxy for this long and go straight to local keys. */
    private const val DOWN_PAUSE_MS = 10L * 60L * 1000L

    /** The proxy is always tried first (it costs one quick 404 while the function is not deployed yet). */
    const val ON = true

    @Volatile private var downUntil = 0L

    /**
     * Result of one proxy call. [text] = the answer (null = none). [reachedProvider] = the function ran and asked the
     * provider, so a direct call with a local key would most likely fail the same way. [error] is in the same format
     * as the direct clients ("HTTP 429: ...") or "proxy HTTP 404" when the function itself did not answer.
     */
    class Answer(val text: String?, val reachedProvider: Boolean, val error: String?)

    /** False for a while after the function was missing / refused us. */
    fun available(): Boolean =
        ON && System.currentTimeMillis() >= downUntil &&
            android.os.Build.FINGERPRINT != "robolectric" // JVM screenshot tests never call the real server

    suspend fun generate(
        provider: AiClient.Provider,
        parts: List<AiClient.Part>,
        system: String?,
        json: Boolean,
        tier: AiClient.Tier,
        temperature: Double,
        maxOutputTokens: Int
    ): Answer = withContext(Dispatchers.IO) {
        val code = Account.profileCodeOrNull()
        if (code.isNullOrBlank()) return@withContext Answer(null, false, "proxy no code")
        val body = JSONObject()
            .put("provider", if (provider == AiClient.Provider.CLAUDE) "claude" else "gemini")
            .put("tier", tier.name)
            .put("json", json)
            .put("temperature", temperature)
            .put("maxTokens", maxOutputTokens.coerceIn(1, 16_000))
        if (system != null) body.put("system", system)
        val arr = JSONArray()
        for (p in parts) {
            when (p) {
                is AiClient.Part.Text -> arr.put(JSONObject().put("text", p.text))
                is AiClient.Part.Image -> arr.put(
                    JSONObject()
                        .put("imageBase64", Base64.encodeToString(p.bytes, Base64.NO_WRAP))
                        .put("mime", p.mime)
                )
                is AiClient.Part.Video -> arr.put(JSONObject().put("videoUrl", p.url))
            }
        }
        body.put("parts", arr)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(CloudConfig.AI_FUNCTION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // the anon key is a valid JWT, so the function can keep "Verify JWT" on
                setRequestProperty("apikey", CloudConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer ${CloudConfig.SUPABASE_ANON_KEY}")
                setRequestProperty(CloudConfig.PROFILE_HEADER, code)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status == 200) {
                val root = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@withContext Answer(null, false, "proxy bad response: ${text.take(120)}")
                val answer = root.optString("text", "").trim()
                if (answer.isNotEmpty()) return@withContext Answer(answer, true, null)
                val err = if (root.isNull("error")) "empty answer" else root.optString("error", "empty answer")
                return@withContext Answer(null, true, err)
            }
            // 404 = function not deployed, 401/403 = JWT / permission problem: pause the proxy for a while
            if (status == 404 || status == 401 || status == 403) downUntil = System.currentTimeMillis() + DOWN_PAUSE_MS
            Log.w(TAG, "proxy HTTP $status: ${text.take(300)}")
            Answer(null, false, "proxy HTTP $status: ${errorField(text)}")
        } catch (e: Exception) {
            Log.w(TAG, "proxy failed", e)
            Answer(null, false, "proxy ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun errorField(text: String): String =
        runCatching { JSONObject(text).optString("error", "") }.getOrNull()?.ifBlank { null }?.take(200) ?: text.take(200)
}
