package com.tamir.hamasa.domain.game

import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.coach.SleepBand
import kotlin.random.Random

/**
 * Rules around the workout modes that are not part of the coach's plan building:
 * which modes fit the week's focus and last night's sleep, how the morning roll weighs modes by
 * the star ratings of past sessions, and the short hints the live workout shows per exercise.
 */
object ModeRules {

    /** Modes that load the body hard. Never rolled after a short night. */
    val heavy: Set<String> = setOf("pr_day", "heavy_light", "ladder", "pyramid", "backwards", "marathon", "tut")

    /**
     * Modes whose accessory targets differ from the coach's progression (lighter sets, seconds instead of reps).
     * Their sessions are skipped when the coach reads accessory history, so a mode day never moves progression.
     */
    val distortsAccessoryHistory: Set<String> = setOf("timer", "tut", "heavy_light", "ladder", "pyramid", "backwards")

    /** Exercises done one side at a time (mode "mirror"). */
    val unilateral: Set<String> = setOf("lunges", "bulgarian_split_squat", "side_plank", "db_row", "db_curl", "hammer_curl")

    /** Pure-random share of the morning roll (ratings ignored). */
    const val PURE_RANDOM_SHARE = 0.20

    /** Mode used when nothing else fits: no load, no clock. */
    const val SAFE_FALLBACK = "silence"

    fun fitsSleep(id: String, band: SleepBand): Boolean {
        val m = ModeCatalog.byId(id) ?: return true
        return when (band) {
            SleepBand.FULL -> true
            SleepBand.MEDIUM -> id != "pr_day" && id != "short_rest" && id != "race_clock" && id != "marathon"
            SleepBand.SHORT -> id !in heavy && !m.timeBased
        }
    }

    fun fitsFocus(id: String, focus: Focus): Boolean = when (focus) {
        Focus.STRENGTH -> id != "timer" && id != "short_rest" && id != "race_clock" && id != "bodyweight" && id != "tut"
        Focus.BUILD_MUSCLE -> id != "timer"
        Focus.FLEXIBILITY -> id != "marathon" && id != "pr_day"
        else -> id != "minimalist"
    }

    /** Rating weight: average stars (pulled toward 3 when there are few ratings) mapped to 0.7..1.9. */
    fun ratingWeight(ratings: List<Int>): Double {
        val valid = ratings.filter { it in 1..5 }
        val avg = (valid.sum() + 3.0 * 2.0) / (valid.size + 2).toDouble()
        return 0.4 + 0.3 * avg
    }

    /**
     * Modes the roll may choose from, strictest first: fit the focus and the sleep and are not excluded;
     * then drops the focus rule; then the exclusions; the sleep rule is never dropped.
     */
    fun candidates(available: List<String>, focus: Focus, band: SleepBand, exclude: Set<String>): List<String> {
        val strict = available.filter { it !in exclude && fitsFocus(it, focus) && fitsSleep(it, band) }
        if (strict.isNotEmpty()) return strict
        val noFocus = available.filter { it !in exclude && fitsSleep(it, band) }
        if (noFocus.isNotEmpty()) return noFocus
        val sleepOnly = available.filter { fitsSleep(it, band) }
        if (sleepOnly.isNotEmpty()) return sleepOnly
        return listOf(SAFE_FALLBACK)
    }

    /** 20% pure random; otherwise weighted by the star ratings of past sessions done with each mode. */
    fun pick(candidates: List<String>, ratingsByMode: Map<String, List<Int>>, rnd: Random): String {
        if (candidates.isEmpty()) return SAFE_FALLBACK
        if (rnd.nextDouble() < PURE_RANDOM_SHARE) return candidates[rnd.nextInt(candidates.size)]
        val weights = candidates.map { ratingWeight(ratingsByMode[it] ?: emptyList()) }
        var r = rnd.nextDouble() * weights.sum()
        var i = 0
        while (i < candidates.size) {
            r -= weights[i]
            if (r <= 0.0) return candidates[i]
            i++
        }
        return candidates.last()
    }

    /** One short instruction under an exercise in the live workout, or null. Accessory work only. */
    fun liveHint(modeId: String?, exerciseId: String, isCompound: Boolean, unit: String): String? {
        if (modeId == null) return null
        if (modeId == "mirror" && exerciseId in unilateral) return "מתחילים בצד החלש. הצד החזק עושה אותן חזרות, לא יותר."
        if (isCompound) return null
        return when (modeId) {
            "tut" -> "שלוש שניות למטה, שלוש שניות למעלה. בכל חזרה."
            "timer" -> if (unit == "sec") null else "היום היעד בשניות: 40 שניות עבודה בכל סט."
            "ladder" -> "סולם: היעד משתנה מסט לסט, עולים במשקל."
            "pyramid" -> "פירמידה: עולים במשקל עד האמצע ויורדים בחזרה."
            "heavy_light" -> "כבד, קל, כבד. היעד של כל סט שונה."
            "backwards" -> "הסט הראשון הכבד ביותר. משם יורדים."
            "fixed_weight" -> "המשקל לא זז היום. רק החזרות."
            "one_more" -> "בסט האחרון חזרה אחת יותר."
            else -> null
        }
    }
}
