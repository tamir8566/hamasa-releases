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
 * Minimal Gemini REST client (generateContent), no extra dependencies: HttpURLConnection + org.json.
 * Every call returns null on any failure (no key, offline, quota 429, bad JSON) so callers can fall back.
 * This package is the second sanctioned network place besides data/cloud and data/update.
 */
object GeminiClient {
    private const val TAG = "Gemini"
    /** Last failure reason (HTTP code, exception, empty answer) for diagnostics; null after a good answer. */
    @Volatile var lastError: String? = null

    /** A part of the request: text, or an image as raw bytes with its mime type. */
    sealed class Part {
        data class Text(val text: String) : Part()
        data class Image(val bytes: ByteArray, val mime: String = "image/jpeg") : Part()
        /** A public YouTube link: Gemini watches the video itself. */
        data class Video(val url: String) : Part()
    }

    /**
     * Sends [parts] (plus an optional [system] instruction) and returns the model's text answer, or null.
     * With [json] = true the model is asked for application/json output (parse with JSONObject yourself).
     */
    suspend fun generate(
        parts: List<Part>,
        system: String? = null,
        json: Boolean = false,
        model: String = GeminiConfig.MODEL,
        temperature: Double = 0.4,
        maxOutputTokens: Int = 8192
    ): String? = withContext(Dispatchers.IO) {
        if (!GeminiConfig.enabled) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject()
            val partsJson = JSONArray()
            for (p in parts) {
                when (p) {
                    is Part.Text -> partsJson.put(JSONObject().put("text", p.text))
                    is Part.Image -> partsJson.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", p.mime).put("data", Base64.encodeToString(p.bytes, Base64.NO_WRAP))
                        )
                    )
                    is Part.Video -> partsJson.put(JSONObject().put("file_data", JSONObject().put("file_uri", p.url)))
                }
            }
            body.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partsJson)))
            if (system != null) {
                body.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            }
            val gen = JSONObject().put("temperature", temperature).put("maxOutputTokens", maxOf(maxOutputTokens, 2048)) // floor: thinking tokens share this budget
            if (json) gen.put("responseMimeType", "application/json")
            // Gemini 3.x models think before answering; thinking tokens count against maxOutputTokens, so keep it low
            // (otherwise the answer can be cut off empty with finishReason MAX_TOKENS).
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            body.put("generationConfig", gen)

            conn = (URL(GeminiConfig.BASE_URL + model + ":generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = GeminiConfig.TIMEOUT_MS
                readTimeout = GeminiConfig.TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", GeminiConfig.API_KEY)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code: ${text.take(300)}")
                lastError = "HTTP $code: ${text.take(300)}"
                return@withContext null
            }
            val root = JSONObject(text)
            val cand = root.optJSONArray("candidates")?.optJSONObject(0) ?: return@withContext null
            val outParts = cand.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext null
            val sb = StringBuilder()
            for (i in 0 until outParts.length()) {
                val part = outParts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                sb.append(part.optString("text"))
            }
            lastError = if (sb.isBlank()) "empty answer, finishReason=${cand.optString("finishReason")}" else null
            sb.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "generate failed", e)
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Convenience: JSON answer parsed to a JSONObject (strips ``` fences if the model added them). */
    suspend fun generateJson(parts: List<Part>, system: String? = null, model: String = GeminiConfig.MODEL): JSONObject? {
        val raw = generate(parts, system, json = true, model = model) ?: return null
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(cleaned) } catch (e: Exception) { Log.w(TAG, "bad json: ${cleaned.take(200)}"); null }
    }
}
