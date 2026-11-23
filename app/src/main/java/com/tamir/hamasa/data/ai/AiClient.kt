package com.tamir.hamasa.data.ai

import android.util.Log
import com.tamir.hamasa.data.AppGraph
import org.json.JSONObject

/**
 * The one door to the AI for the whole app. Picks the provider (Claude or Gemini) from Prefs.aiProvider:
 * "auto" / "claude" = Claude first, "gemini" = Gemini first. When the chosen provider fails (no answer, error,
 * bad JSON) the other one is tried. Callers pass a [Tier], never a model name. Every call returns null on any
 * failure so callers keep their offline text.
 *
 * Each provider is asked through the server first ([AiProxy], the Supabase Edge Function that holds the keys), and
 * only if the server is not there (not deployed yet, no network to it) directly with a key baked into this build
 * (BuildConfig, Tamir's own builds while BAKE_AI_KEYS is on). Order: proxy -> direct local key -> other provider.
 */
object AiClient {
    private const val TAG = "AiClient"

    /** MAIN = vision / JSON / coach model; LITE = cheaper model for short lines. */
    enum class Tier { MAIN, LITE }

    enum class Provider { CLAUDE, GEMINI }

    /** A part of the request: text, or an image as raw bytes with its mime type. */
    sealed class Part {
        data class Text(val text: String) : Part()
        data class Image(val bytes: ByteArray, val mime: String = "image/jpeg") : Part()
        /** A public video link (YouTube) the model watches itself. Only Gemini can: see [generateJsonVideo]. */
        data class Video(val url: String) : Part()
    }

    /** The AI features exist: the server proxy is on, or this build carries a key. */
    val enabled: Boolean get() = AiProxy.ON || ClaudeConfig.enabled || GeminiConfig.enabled

    /** True when the last good answer came through the server (proxy); false = directly with a local key. */
    @Volatile var lastViaProxy: Boolean = false

    /** Failure reason of the last [callOne] (read right after it returns). */
    @Volatile private var callError: String? = null

    /** Last failure reason ("Claude: HTTP 429: ..."); null after a good answer. */
    @Volatile var lastError: String? = null

    /** Provider that gave the last good answer; null = none yet in this run of the app. */
    @Volatile var lastProvider: Provider? = null

    /** Epoch millis of the last good answer (0 = none). */
    @Volatile var lastOkAt: Long = 0L

    /** Test hook: "claude" / "gemini" / "auto" overrides the Prefs choice; null = use Prefs. */
    @Volatile var forcedProvider: String? = null

    /** The order to try: the chosen provider first, then the other one; only providers with a key. */
    suspend fun order(): List<Provider> {
        val choice = forcedProvider ?: runCatching { AppGraph.prefs.current().aiProvider }.getOrNull() ?: "auto"
        return orderFor(choice)
    }

    fun orderFor(choice: String): List<Provider> {
        val all = when (choice) {
            "gemini" -> listOf(Provider.GEMINI, Provider.CLAUDE)
            else -> listOf(Provider.CLAUDE, Provider.GEMINI) // "auto" and "claude"
        }
        return all.filter { isEnabled(it) }
    }

    fun isEnabled(p: Provider): Boolean = AiProxy.ON || hasLocalKey(p)

    /** This build carries a key for [p] (direct calls possible). */
    fun hasLocalKey(p: Provider): Boolean = when (p) {
        Provider.CLAUDE -> ClaudeConfig.enabled
        Provider.GEMINI -> GeminiConfig.enabled
    }

    /**
     * Sends [parts] (plus an optional [system] instruction) and returns the model's text answer, or null.
     * With [json] = true the model is asked for a bare JSON object (see [generateJson]).
     */
    suspend fun generate(
        parts: List<Part>,
        system: String? = null,
        json: Boolean = false,
        tier: Tier = Tier.MAIN,
        temperature: Double = 0.4,
        maxOutputTokens: Int = 8192
    ): String? {
        val providers = order()
        if (providers.isEmpty()) {
            lastError = "no key"
            return null
        }
        val errors = ArrayList<String>()
        for (p in providers) {
            val out = callOne(p, parts, system, json, tier, temperature, maxOutputTokens)
            if (out != null) {
                markOk(p)
                return out
            }
            errors.add(label(p) + ": " + (callError ?: "no answer"))
        }
        lastError = errors.joinToString(" | ")
        return null
    }

    /** JSON answer parsed to a JSONObject (strips ``` fences and any text around the object). Falls back like [generate]. */
    suspend fun generateJson(parts: List<Part>, system: String? = null, tier: Tier = Tier.MAIN, temperature: Double = 0.4, maxOutputTokens: Int = 8192): JSONObject? {
        val providers = order()
        if (providers.isEmpty()) {
            lastError = "no key"
            return null
        }
        val errors = ArrayList<String>()
        for (p in providers) {
            val raw = callOne(p, parts, system, true, tier, temperature, maxOutputTokens)
            if (raw == null) {
                errors.add(label(p) + ": " + (callError ?: "no answer"))
                continue
            }
            val obj = parseJsonLoose(raw)
            if (obj != null) {
                markOk(p)
                return obj
            }
            Log.w(TAG, "bad json from ${label(p)}: ${raw.take(200)}")
            errors.add(label(p) + ": bad json")
        }
        lastError = errors.joinToString(" | ")
        return null
    }

    /**
     * Like [generateJson] but only with Gemini (the one provider that can watch a YouTube link). Null when Gemini
     * is not available or failed. Sets [lastError].
     */
    suspend fun generateJsonVideo(parts: List<Part>, system: String? = null, temperature: Double = 0.3): JSONObject? {
        if (!isEnabled(Provider.GEMINI)) {
            lastError = "no gemini"
            return null
        }
        val raw = callOne(Provider.GEMINI, parts, system, true, Tier.MAIN, temperature, 8192)
        if (raw == null) {
            lastError = label(Provider.GEMINI) + ": " + (callError ?: "no answer")
            return null
        }
        val obj = parseJsonLoose(raw)
        if (obj == null) {
            lastError = label(Provider.GEMINI) + ": bad json"
            return null
        }
        markOk(Provider.GEMINI)
        return obj
    }

    /** One provider: through the server first, then directly with a local key. Sets [callError] and [lastViaProxy]. */
    private suspend fun callOne(
        p: Provider,
        parts: List<Part>,
        system: String?,
        json: Boolean,
        tier: Tier,
        temperature: Double,
        maxOutputTokens: Int
    ): String? {
        callError = null
        var proxyError: String? = null
        if (AiProxy.available()) {
            val a = AiProxy.generate(p, parts, system, json, tier, temperature, maxOutputTokens)
            if (a.text != null) {
                lastViaProxy = true
                return a.text
            }
            proxyError = a.error ?: "proxy no answer"
            // The server reached the provider and it failed (quota, overloaded...): a direct call would fail the same way.
            if (a.reachedProvider || !hasLocalKey(p)) {
                callError = proxyError
                return null
            }
        }
        if (!hasLocalKey(p)) {
            callError = proxyError ?: "no key"
            return null
        }
        val out = callDirect(p, parts, system, json, tier, temperature, maxOutputTokens)
        if (out != null) {
            lastViaProxy = false
            return out
        }
        callError = errorOf(p) ?: "no answer"
        return null
    }

    private suspend fun callDirect(
        p: Provider,
        parts: List<Part>,
        system: String?,
        json: Boolean,
        tier: Tier,
        temperature: Double,
        maxOutputTokens: Int
    ): String? = when (p) {
        Provider.CLAUDE -> ClaudeClient.generate(
            parts, system, json,
            model = if (tier == Tier.LITE) ClaudeConfig.MODEL_LITE else ClaudeConfig.MODEL,
            temperature = temperature, maxOutputTokens = maxOutputTokens
        )
        Provider.GEMINI -> GeminiClient.generate(
            parts.map { toGemini(it) }, system, json,
            model = if (tier == Tier.LITE) GeminiConfig.MODEL_LITE else GeminiConfig.MODEL,
            temperature = temperature, maxOutputTokens = maxOutputTokens
        )
    }

    private fun toGemini(part: Part): GeminiClient.Part = when (part) {
        is Part.Text -> GeminiClient.Part.Text(part.text)
        is Part.Image -> GeminiClient.Part.Image(part.bytes, part.mime)
        is Part.Video -> GeminiClient.Part.Video(part.url)
    }

    private fun errorOf(p: Provider): String? = when (p) {
        Provider.CLAUDE -> ClaudeClient.lastError
        Provider.GEMINI -> GeminiClient.lastError
    }

    private fun markOk(p: Provider) {
        lastProvider = p
        lastOkAt = System.currentTimeMillis()
        lastError = null
    }

    fun label(p: Provider): String = when (p) {
        Provider.CLAUDE -> "Claude"
        Provider.GEMINI -> "Gemini"
    }

    /** Provider name for the Hebrew UI. */
    fun nameHe(p: Provider): String = when (p) {
        Provider.CLAUDE -> "Claude"
        Provider.GEMINI -> "ג'מיני"
    }

    /** A JSON object out of a model answer: removes ``` fences and anything before the first '{' / after the last '}'. */
    fun parseJsonLoose(raw: String): JSONObject? {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            s = s.trim().removeSuffix("```").trim()
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(s.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    /** [lastError] as Hebrew lines, one per provider that failed ("Claude: המפתח נדחה"). Empty = no error. */
    fun errorLinesHe(): List<String> {
        val raw = lastError ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(" | ").mapNotNull { piece ->
            val name = when {
                piece.startsWith("Claude:") -> "Claude"
                piece.startsWith("Gemini:") -> "ג'מיני"
                else -> null
            }
            val he = errorHe(piece) ?: return@mapNotNull null
            if (name != null) "$name: $he" else he
        }
    }

    /** The last error in plain Hebrew words for the settings card; null = no error. */
    fun errorHe(raw: String? = lastError): String? {
        if (raw.isNullOrBlank()) return null
        val r = raw.lowercase()
        return when {
            r.contains("proxy http 404") -> "השרת עוד לא הותקן"
            r.contains("proxy http 401") || r.contains("proxy http 403") -> "השרת דחה את הבקשה"
            r.contains("no key on server") -> "אין מפתח בשרת"
            r.contains("proxy http 400") -> "השרת דחה את הבקשה"
            r.contains("no key") -> "אין מפתח"
            r.contains("http 401") || r.contains("http 403") || r.contains("authentication") || r.contains("api key") -> "המפתח נדחה"
            r.contains("http 429") || r.contains("rate_limit") || r.contains("quota") -> "עברנו את המכסה. לנסות שוב מאוחר יותר"
            r.contains("http 529") || r.contains("overloaded") -> "השרת עמוס כרגע"
            r.contains("http 5") -> "תקלה בצד השרת"
            r.contains("http 400") || r.contains("http 404") -> "הבקשה נדחתה (דגם או פרמטר לא נתמך)"
            r.contains("unknownhost") || r.contains("connectexception") || r.contains("unable to resolve") -> "אין חיבור לרשת"
            r.contains("timeout") -> "לקח יותר מדי זמן"
            r.contains("bad json") || r.contains("bad response") -> "התשובה לא הייתה תקינה"
            r.contains("empty answer") || r.contains("no answer") -> "התשובה חזרה ריקה"
            r.contains("refusal") -> "המודל סירב לענות"
            else -> "תקלה לא מוכרת"
        }
    }
}
