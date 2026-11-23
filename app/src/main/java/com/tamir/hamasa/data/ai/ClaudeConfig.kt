package com.tamir.hamasa.data.ai

/**
 * Claude (Anthropic Messages API) settings. The key comes from the ANTHROPIC_API_KEY repository secret through
 * BuildConfig (see app/build.gradle.kts); it is never written in the source. Blank key = Claude is off and
 * [AiClient] uses Gemini (or the offline behaviour when Gemini has no key either).
 */
object ClaudeConfig {
    val API_KEY: String = com.tamir.hamasa.BuildConfig.CLAUDE_KEY
    /** Vision + JSON + coach model (meal photos, coach notes, meal ideas). */
    const val MODEL = "claude-sonnet-5-5"
    /** Cheaper, faster model for short Shambiuluk lines and short coach texts. */
    const val MODEL_LITE = "claude-haiku-5-5"
    const val BASE_URL = "https://api.anthropic.com/v1/messages"
    /** The anthropic-version header (2023-06-01 still accepts temperature; 2026-10-21 rejects it). */
    const val API_VERSION = "2023-06-01"
    const val TIMEOUT_MS = 60_000

    val enabled: Boolean get() = API_KEY.isNotBlank()
}
