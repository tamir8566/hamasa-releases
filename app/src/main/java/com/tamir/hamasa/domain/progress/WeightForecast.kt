package com.tamir.hamasa.domain.progress

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Goal forecast from the real weight trend (pure logic, no IO).
 * Least-squares line over the weigh-ins of the last [WINDOW_DAYS] days. Needs at least [MIN_POINTS] weigh-ins
 * spread over at least [MIN_SPAN_DAYS] days; otherwise it says how many more are needed.
 * Only reads weights. Never touches the body stage (that is BodyStage, from the latest weight only).
 */
object WeightForecast {
    const val WINDOW_DAYS = 28L
    const val MIN_POINTS = 4
    const val MIN_SPAN_DAYS = 10L
    /** Weekly change (kg) closer to zero than this counts as "flat". */
    private const val FLAT_KG_WEEK = 0.1f
    /** Beyond this many days the date is not worth printing. */
    private const val MAX_DAYS = 3L * 365L

    sealed class Result {
        /** Not enough data yet: [morePoints] weigh-ins and/or [moreDays] days of spread are missing. */
        data class NeedMore(val morePoints: Int, val moreDays: Int) : Result()
        /** Fitted weight is already at the goal (at or below it for a loss goal, at or above it for a gain goal). */
        data class AtGoal(val weeklyKg: Float, val weeklyPct: Float, val gain: Boolean = false) : Result()
        /**
         * Moving toward the goal: reached around [eta]. [weeklyKg] is negative for a loss goal and positive
         * for a gain goal ([gain] = true). The name is historical: for a gain goal the line goes up.
         */
        data class Down(val weeklyKg: Float, val weeklyPct: Float, val eta: LocalDate?, val days: Long, val gain: Boolean = false) : Result()
        /**
         * Flat or moving away from the goal: no date, a gentle factual line. [up] = the line goes up
         * (away for a loss goal); [gain] = true for a gain goal, where "away" means going down (up = false).
         */
        data class FlatOrUp(val weeklyKg: Float, val weeklyPct: Float, val up: Boolean, val gain: Boolean = false) : Result()
    }

    /** True when [weeklyKg] is flat (neither toward nor away from the goal). */
    private fun isFlat(weeklyKg: Float): Boolean = weeklyKg > -FLAT_KG_WEEK && weeklyKg < FLAT_KG_WEEK

    /**
     * [points] = (epochDay, kg) in any order. [gain] = the goal is above (bulk): the direction comes from the
     * caller (EnergyData.directionOf == GAIN); default false = a loss goal, the original behaviour.
     */
    fun compute(points: List<Pair<Long, Float>>, goalKg: Float, today: Long, gain: Boolean = false): Result {
        val recent = points.filter { it.first in (today - WINDOW_DAYS + 1)..today }.sortedBy { it.first }
        val span = if (recent.size >= 2) recent.last().first - recent.first().first else 0L
        if (recent.size < MIN_POINTS || span < MIN_SPAN_DAYS) {
            return Result.NeedMore(
                morePoints = (MIN_POINTS - recent.size).coerceAtLeast(0),
                moreDays = (MIN_SPAN_DAYS - span).coerceAtLeast(0L).toInt()
            )
        }
        // Least squares on x = day offset from the first point (keeps the numbers small).
        val x0 = recent.first().first
        val n = recent.size.toDouble()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((d, kg) in recent) {
            val x = (d - x0).toDouble()
            val y = kg.toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val den = n * sxx - sx * sx
        if (den == 0.0) return Result.NeedMore(0, MIN_SPAN_DAYS.toInt())
        val slope = (n * sxy - sx * sy) / den            // kg per day
        val intercept = (sy - slope * sx) / n
        val fitToday = (intercept + slope * (today - x0)).toFloat()
        val weeklyKg = (slope * 7.0).toFloat()
        val weeklyPct = if (fitToday > 0f) abs(weeklyKg) / fitToday * 100f else 0f

        if (gain) {
            if (fitToday >= goalKg) return Result.AtGoal(weeklyKg, weeklyPct, gain = true)
            if (weeklyKg < FLAT_KG_WEEK) return Result.FlatOrUp(weeklyKg, weeklyPct, up = false, gain = true)
            val days = ((goalKg - fitToday) / slope).toLong().coerceAtLeast(1L)
            val eta = if (days > MAX_DAYS) null else LocalDate.ofEpochDay(today + days)
            return Result.Down(weeklyKg, weeklyPct, eta, days, gain = true)
        }
        if (fitToday <= goalKg) return Result.AtGoal(weeklyKg, weeklyPct)
        if (weeklyKg > -FLAT_KG_WEEK) return Result.FlatOrUp(weeklyKg, weeklyPct, up = weeklyKg >= FLAT_KG_WEEK)
        val days = ((fitToday - goalKg) / (-slope)).toLong().coerceAtLeast(1L)
        val eta = if (days > MAX_DAYS) null else LocalDate.ofEpochDay(today + days)
        return Result.Down(weeklyKg, weeklyPct, eta, days)
    }

    private val MONTHS = listOf(
        "ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני",
        "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"
    )

    /** "מרץ" for 3. */
    fun monthHe(month: Int): String = MONTHS[(month - 1).coerceIn(0, 11)]

    /** "מרץ 2027". */
    fun monthYearHe(date: LocalDate): String = monthHe(date.monthValue) + " " + date.year

    /** 0.6 -> "0.6", 1.0 -> "1". */
    fun fmt1(v: Float): String {
        val r = Math.round(abs(v) * 10f) / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else String.format(Locale.US, "%.1f", r)
    }

    /** Main line for the weight screen. */
    fun headline(r: Result, goalKg: Float): String = when (r) {
        is Result.NeedMore -> needMoreText(r)
        is Result.AtGoal -> com.tamir.hamasa.domain.game.Addr.t("לפי המגמה אתה כבר ביעד, ", "לפי המגמה את כבר ביעד, ") + "${fmt1(goalKg)} ק\"ג. עכשיו שומרים."
        is Result.Down ->
            if (r.eta == null) "בקצב הזה ${fmt1(goalKg)} ק\"ג רחוקים מעבר לשלוש שנים. כל האצה קטנה מקרבת את התאריך."
            else com.tamir.hamasa.domain.game.Addr.t("בקצב הזה תגיע ל-", "בקצב הזה תגיעי ל-") + "${fmt1(goalKg)} בערך ב${monthYearHe(r.eta)}."
        is Result.FlatOrUp ->
            if (r.gain) {
                if (isFlat(r.weeklyKg)) "בארבעת השבועות האחרונים המשקל יציב. אין תאריך יעד כרגע. הוא יופיע כשהקו יתחיל לעלות."
                else "בארבעת השבועות האחרונים הקו ירד מעט. זה מידע, לא פסק דין. התחזית תחזור כשהקו יפנה למעלה."
            } else if (r.up) "בארבעת השבועות האחרונים הקו עלה מעט. זה מידע, לא פסק דין. התחזית תחזור כשהקו יפנה למטה."
            else "בארבעת השבועות האחרונים המשקל יציב. אין תאריך יעד כרגע. הוא יופיע כשהקו יתחיל לרדת."
    }

    /** Weekly rate line, or null when there is no rate yet. */
    fun rateLine(r: Result): String? {
        val (kg, pct) = when (r) {
            is Result.NeedMore -> return null
            is Result.AtGoal -> r.weeklyKg to r.weeklyPct
            is Result.Down -> r.weeklyKg to r.weeklyPct
            is Result.FlatOrUp -> r.weeklyKg to r.weeklyPct
        }
        val dir = when {
            kg <= -FLAT_KG_WEEK -> "יורד"
            kg >= FLAT_KG_WEEK -> "עולה"
            else -> return "קצב: כמעט ללא שינוי בשבוע."
        }
        return "קצב: $dir ${fmt1(kg)} ק\"ג בשבוע (${fmt1(pct)}% ממשקל הגוף)."
    }

    /** Compact line for Home, or null when there is nothing worth a line there. */
    fun homeLine(r: Result, goalKg: Float): String? = when (r) {
        is Result.Down -> if (r.eta != null) "בקצב הזה: ${fmt1(goalKg)} ק\"ג בערך ב${monthYearHe(r.eta)}" else null
        is Result.AtGoal -> "לפי המגמה: ביעד. שומרים."
        is Result.NeedMore -> null
        is Result.FlatOrUp -> null
    }

    private fun needMoreText(r: Result.NeedMore): String = when {
        r.morePoints > 0 && r.moreDays > 0 ->
            "לתחזית צריך עוד ${countHe(r.morePoints)} ופיזור של עוד ${r.moreDays} ימים."
        r.morePoints > 0 -> "עוד ${countHe(r.morePoints)} ותהיה תחזית."
        else -> "השקילות צפופות מדי. עוד ${r.moreDays} ימים של שקילות ותהיה תחזית."
    }

    private fun countHe(n: Int): String = if (n == 1) "שקילה אחת" else "$n שקילות"
}
