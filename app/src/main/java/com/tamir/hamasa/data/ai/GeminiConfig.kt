package com.tamir.hamasa.data.ai

/**
 * Gemini API settings (stage 2). The key is Tamir's own free AI Studio key; the app is single-user and the repo
 * is private. When [API_KEY] is blank every AI feature falls back to the offline behaviour.
 */
object GeminiConfig {
    val API_KEY: String = com.tamir.hamasa.BuildConfig.GEMINI_KEY
    /** Vision + JSON model for meal photos and coach notes. */
    const val MODEL = "gemini-3.6-flash"
    /** Cheaper model for short Shambiuluk lines. Falls back to [MODEL] if it errors. */
    const val MODEL_LITE = "gemini-3.5-flash-lite"
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val TIMEOUT_MS = 60_000

    val enabled: Boolean get() = API_KEY.isNotBlank()
}
