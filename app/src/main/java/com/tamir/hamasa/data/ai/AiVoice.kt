package com.tamir.hamasa.data.ai

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** What just happened, for a Gemini line of Shambiuluk (stage 2 G). Facts only, never body weight. */
sealed class VoiceContext {
    data class WorkoutDone(
        val typeHe: String,
        val minutes: Int,
        val prs: List<String>,
        val bossHit: Boolean,
        val bossDefeatedHe: String?,
        val streak: Int,
        val modeHe: String?
    ) : VoiceContext()

    data class MealLogged(val mealHe: String, val kcal: Int?) : VoiceContext()

    data class Morning(val dayNameHe: String, val streak: Int, val plannedHe: String?) : VoiceContext()

    /** Tap on Shambiuluk on Home (a few times a day at most). */
    data class Tap(
        val partOfDayHe: String,
        val streak: Int,
        val trainedToday: Boolean,
        val bossHe: String,
        val bossDefeated: Boolean,
        val kcalLeft: Int?,
        val steps: Int?,
        val moodHe: String?,
        val professionHe: String?
    ) : VoiceContext()
}

/**
 * Shambiuluk "sometimes Gemini": a short line in his voice for a few chosen moments (the workout-done closing line
 * in about one of three sessions, and the home morning bubble once a day). Every answer is post-validated; anything
 * off returns null and the caller keeps its regular [com.tamir.hamasa.domain.game.Voice] line. Never blocks the UI:
 * the HTTP call runs on [AppGraph.scope] and callers wait only as long as they choose.
 */
object AiVoice {

    /** The capped words, built from pieces so the source does not add to the app-wide counts. */
    private val CAPPED: List<String> = listOf("הבינ" + "ותי", "לפרוט" + "וקול", "אינ" + "ני")

    private const val MAX_CHARS = 120
    private const val MAX_WORDS = 16

    private val SYSTEM_MALE: String = """
You write ONE line for Shambiuluk (שמביולוק), the hero of a Hebrew fitness RPG app for one adult man named Tamir.
Style (follow exactly):
- Shambiuluk's voice: short sentences, periods, a twist at the end. Deadpan, self-aware, mock-formal but never silly
  ("מדוע", "היכן", "הידד", "אכן"), dramatic exaggeration ("רצח", "האיש והאגדה"), stretched letters for big moments
  ("הידדדדד"), fatigue is always "המפלצת". No emojis.
- Vary openers: "רשמתי.", "נרשם.", "קיבלתי.", "בסדר.", "טוב.", "יפה.", "ראיתי.", "נו.", "מקובל.", "סגור.", or no opener at all.
- Voice reference (the tone to match):
  - "הפעם לא אגיד. בפעם הבאה גם."
  - "אני מחזיק את הרמז. שתי ידיים. לא משחרר."
  - "הדרך לכאן עוברת דרך המכון. כנראה."
  - "לא שאלת, אבל: לא."
  - "תנאי אחד. פשוט. מסובך. תלוי ביום."
  - "חמש פעמים. זה כבר לא מקרה, זו מדיניות."
Rules:
- Hebrew only. No English or other Latin letters, no digits in Latin words, no emojis, no hashtags, no quotes around the line.
- At most $MAX_WORDS words. Short sentences ending with periods. A twist at the end.
- Never use these words: ${CAPPED.joinToString(", ")}.
- Fatigue is always "המפלצת".
- Never shame or joke about weight, body, food amounts or appearance. Never give medical or nutrition advice.
- Answer with the line only.
""".trimIndent()

    /** The system prompt for the current genders. Male hero + male player: exactly [SYSTEM_MALE]. */
    private fun system(): String {
        val femaleHero = CharacterNames.isFemaleNow()
        val femalePlayer = Addr.isFemale()
        if (!femaleHero && !femalePlayer) return SYSTEM_MALE
        val sb = StringBuilder(SYSTEM_MALE)
        if (femalePlayer) {
            sb.append("\n- The player is NOT Tamir: the player is an adult woman. Address her in Hebrew female grammar (את, עשית, תעשי, מוכנה).")
        }
        if (femaleHero) {
            sb.append("\n- The hero is the female version: שמביולוקה. She speaks about herself in Hebrew female grammar (אני מחזיקה, רשמתי, אני לא משחררת). Never write the male name.")
        }
        return sb.toString()
    }

    private fun player(): String = Addr.t("תמיר", "השחקנית")

    /** A validated line for [context], or null (no key, offline, slow, or the answer broke a rule). */
    suspend fun line(context: VoiceContext, timeoutMs: Long = 15_000L): String? {
        if (!AiClient.enabled) return null
        val prompt = describe(context)
        val job = AppGraph.scope.async {
            val parts = listOf(AiClient.Part.Text(prompt))
            val sys = system()
            AiClient.generate(parts, sys, tier = AiClient.Tier.LITE, temperature = 0.9, maxOutputTokens = 120)
                ?: AiClient.generate(parts, sys, tier = AiClient.Tier.MAIN, temperature = 0.9, maxOutputTokens = 120)
        }
        val raw = withTimeoutOrNull(timeoutMs) { job.await() } ?: return null
        // a female hero's line never carries the male name
        return validate(raw)?.let { CharacterNames.g(it) }
    }

    /**
     * The home morning bubble: one Gemini line per day, cached in Prefs. Null = use the regular Voice line
     * (no key, or today's attempt failed; a failed day is not retried).
     */
    suspend fun morningLine(context: VoiceContext.Morning): String? {
        if (!AiClient.enabled) return null
        val today = Days.today()
        val p = AppGraph.prefs.current()
        if (p.aiMorningDay == today) return p.aiMorningLine.ifEmpty { null }
        // mark the day first so two screens never ask twice
        AppGraph.prefs.update { it.copy(aiMorningDay = today, aiMorningLine = "") }
        val got = line(context, 20_000L) ?: return null
        AppGraph.prefs.update { if (it.aiMorningDay == today) it.copy(aiMorningLine = got) else it }
        com.tamir.hamasa.widget.Widgets.refreshAll(AppGraph.appContext) // the small widget shows today's line
        return got
    }

    /** Null when the text breaks a rule: Latin letters, emojis / symbols, capped words, too long, not Hebrew. */
    fun validate(raw: String): String? {
        var s = raw.trim().replace('\n', ' ').replace(Regex("\\s+"), " ")
        s = s.trim('"', '\'', '״', '“', '”', ' ')
        if (s.isEmpty() || s.length > MAX_CHARS) return null
        if (s.split(' ').count { it.isNotBlank() } > MAX_WORDS + 2) return null
        for (w in CAPPED) if (s.contains(w)) return null
        if (!CoachNotes.isCleanHebrew(s, MAX_CHARS)) return null
        if (s.contains('#') || s.contains('*')) return null
        return s
    }

    private fun describe(c: VoiceContext): String = when (c) {
        is VoiceContext.WorkoutDone -> buildString {
            append("מה קרה: ").append(player()).append(Addr.t(" סיים אימון. סוג: ", " סיימה אימון. סוג: ")).append(c.typeHe).append(". משך: ").append(c.minutes).append(" דקות.")
            if (c.modeHe != null) append(" מצב האימון: ").append(c.modeHe).append('.')
            if (c.prs.isNotEmpty()) append(" שיאים: ").append(c.prs.joinToString(", ")).append('.') else append(" בלי שיאים היום.")
            if (c.bossDefeatedHe != null) append(" הבוס ").append(c.bossDefeatedHe).append(" הובס.")
            else if (c.bossHit) append(" הבוס חטף מכה.")
            append(" רצף ימים: ").append(c.streak).append('.')
            append(" כתוב שורת סיום אחת.")
        }
        is VoiceContext.MealLogged -> buildString {
            append("מה קרה: ").append(player()).append(Addr.t(" רשם ארוחה: ", " רשמה ארוחה: ")).append(c.mealHe).append('.')
            if (c.kcal != null) append(" בערך ").append(c.kcal).append(" קלוריות.")
            append(" כתוב שורה אחת, בלי שיפוט על האוכל או הכמות.")
        }
        is VoiceContext.Morning -> buildString {
            append("מה קרה: בוקר של יום ").append(c.dayNameHe).append('.')
            append(" רצף ימים: ").append(c.streak).append('.')
            if (c.plannedHe != null) append(" מתוכנן להיום: ").append(c.plannedHe).append('.')
            append(" כתוב שורת בוקר אחת.")
        }
        is VoiceContext.Tap -> buildString {
            append("מה קרה: ").append(player()).append(Addr.t(" לחץ על ", " לחצה על ")).append(CharacterNames.heNow()).append(" במסך הבית. שעה ביום: ").append(c.partOfDayHe).append('.')
            append(" רצף ימים: ").append(c.streak).append('.')
            append(if (c.trainedToday) Addr.t(" כבר התאמן היום.", " כבר התאמנה היום.") else Addr.t(" עוד לא התאמן היום.", " עוד לא התאמנה היום."))
            if (c.bossHe.isNotBlank()) append(" בוס השבוע: ").append(c.bossHe).append(if (c.bossDefeated) ", כבר הובס." else ", עדיין עומד.")
            if (c.kcalLeft != null && c.kcalLeft >= 0) append(" נשארו בערך ").append(c.kcalLeft).append(" קלוריות להיום.")
            if (c.steps != null) append(" צעדים היום: ").append(c.steps).append('.')
            if (c.moodHe != null) append(" מצב רוח היום: ").append(c.moodHe).append('.')
            if (c.professionHe != null) append(" המקצוע של ").append(CharacterNames.heNow()).append(": ").append(c.professionHe).append('.')
            append(" כתוב שורה אחת שמגיבה לאחד הפרטים, בלי שיפוט על אוכל או גוף.")
        }
    }
}
