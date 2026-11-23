package com.tamir.hamasa.data.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Claude REST client (Anthropic Messages API, POST /v1/messages), no extra dependencies:
 * HttpURLConnection + org.json, same shape as [GeminiClient]. Every call returns null on any failure (no key,
 * offline, 429, overloaded, bad answer) so [AiClient] can fall back to Gemini and callers to their offline text.
 * Callers use [AiClient], not this object.
 */
object ClaudeClient {
    private const val TAG = "Claude"
    /** Last failure reason (HTTP code + API error, exception, empty answer) for diagnostics; null after a good answer. */
    @Volatile var lastError: String? = null

    private const val JSON_RULE =
        "Answer ONLY with one valid JSON object. No markdown, no code fences, no text before or after the JSON."

    private class Resp(val code: Int, val text: String)

    /**
     * Sends [parts] (plus an optional [system] prompt) and returns the model's text answer, or null.
     * With [json] = true the system prompt demands a bare JSON object (parse it yourself or use [AiClient.generateJson]).
     */
    suspend fun generate(
        parts: List<AiClient.Part>,
        system: String? = null,
        json: Boolean = false,
        model: String = ClaudeConfig.MODEL,
        temperature: Double = 0.4,
        maxOutputTokens: Int = 8192
    ): String? = withContext(Dispatchers.IO) { generateBlocking(parts, system, json, model, temperature, maxOutputTokens) }

    private fun generateBlocking(
        parts: List<AiClient.Part>,
        system: String?,
        json: Boolean,
        model: String,
        temperature: Double,
        maxOutputTokens: Int
    ): String? {
        if (!ClaudeConfig.enabled) {
            lastError = "no key"
            return null
        }
        val sys: String? = when {
            json && system != null -> system + "\n\n" + JSON_RULE
            json -> JSON_RULE
            else -> system
        }
        // Floor: Hebrew costs many tokens and a model that thinks first spends the same budget.
        var budget = maxOf(maxOutputTokens, 2048).coerceAtMost(16_000)
        var temp: Double? = temperature
        var attempt = 0
        while (attempt < 3) {
            attempt++
            val resp = post(buildBody(parts, sys, model, temp, budget))
            if (resp.code == -1) {
                lastError = resp.text
                return null
            }
            if (resp.code !in 200..299) {
                // Some models accept only the default sampling: ask again once without temperature.
                if (resp.code == 400 && temp != null && resp.text.contains("temperature")) {
                    temp = null
                    continue
                }
                Log.w(TAG, "HTTP ${resp.code}: ${resp.text.take(300)}")
                lastError = "HTTP ${resp.code}: ${apiError(resp.text)}"
                return null
            }
            val root = try {
                JSONObject(resp.text)
            } catch (e: Exception) {
                lastError = "bad response: ${resp.text.take(200)}"
                return null
            }
            val stop = root.optString("stop_reason")
            val content: JSONArray? = root.optJSONArray("content")
            val sb = StringBuilder()
            if (content != null) {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") sb.append(block.optString("text"))
                }
            }
            val text = sb.toString().trim()
            if (text.isEmpty() && stop == "max_tokens" && budget < 16_000) {
                // the whole budget went to thinking: one more try with more room
                budget = (budget * 4).coerceAtMost(16_000)
                continue
            }
            if (text.isEmpty()) {
                lastError = "empty answer, stop_reason=$stop"
                return null
            }
            if (stop == "refusal") {
                lastError = "refusal"
                return null
            }
            lastError = null
            return text
        }
        lastError = "no answer after retries"
        return null
    }

    private fun buildBody(parts: List<AiClient.Part>, system: String?, model: String, temperature: Double?, maxTokens: Int): JSONObject {
        val content = JSONArray()
        for (p in parts) {
            when (p) {
                is AiClient.Part.Text -> content.put(JSONObject().put("type", "text").put("text", p.text))
                is AiClient.Part.Image -> content.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", p.mime)
                            .put("data", Base64.encodeToString(p.bytes, Base64.NO_WRAP))
                    )
                )
                // Claude cannot watch a video; callers send videos to Gemini only (AiClient.generateJsonVideo)
                is AiClient.Part.Video -> content.put(JSONObject().put("type", "text").put("text", "Video link: " + p.url))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (!system.isNullOrBlank()) body.put("system", system)
        if (temperature != null) body.put("temperature", temperature.coerceIn(0.0, 1.0))
        return body
    }

    /** HTTP POST; code -1 = exception (text holds the reason). */
    private fun post(body: JSONObject): Resp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ClaudeConfig.BASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = ClaudeConfig.TIMEOUT_MS
                readTimeout = ClaudeConfig.TIMEOUT_MS
                doOutput = true
                setRequestProperty("content-type", "application/json; charset=utf-8")
                setRequestProperty("x-api-key", ClaudeConfig.API_KEY)
                setRequestProperty("anthropic-version", ClaudeConfig.API_VERSION)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Resp(code, text)
        } catch (e: Exception) {
            Log.w(TAG, "generate failed", e)
            Resp(-1, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** {"type":"error","error":{"type":"...","message":"..."}} -> "type: message" (or the raw start). */
    private fun apiError(text: String): String {
        return try {
            val err = JSONObject(text).optJSONObject("error")
            if (err != null) "${err.optString("type")}: ${err.optString("message").take(240)}" else text.take(300)
        } catch (e: Exception) {
            text.take(300)
        }
    }
}
