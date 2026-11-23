package com.tamir.hamasa.ui.home

import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.game.StreakRules
import com.tamir.hamasa.ui.common.sessionTypeLabelHe
import java.util.Locale

/** Days without activity after which Shambiuluk "sleeps" on the home scene. */
const val SLEEP_AFTER_DAYS = 3L

/**
 * What was planned at YESTERDAY's day close for [today], or null.
 * A plan is valid only for the day right after the close (lastCloseDay + 1).
 */
fun plannedForToday(p: Profile, today: Long): SessionType? {
    if (p.lastCloseDay == 0L || p.lastCloseDay + 1L != today) return null
    if (p.plannedTomorrowType.isEmpty()) return null
    return runCatching { SessionType.valueOf(p.plannedTomorrowType) }.getOrNull()
}

/** "תכננת: יוגה ב-07:00" */
fun plannedLabel(type: SessionType, hour: Int): String =
    "תכננת: ${sessionTypeLabelHe(type)} ב-${String.format(Locale.US, "%02d:00", hour.coerceIn(0, 23))}"

/** 3+ days since the last counted activity (never trained = awake). */
fun isAsleep(p: Profile, today: Long): Boolean =
    p.lastActiveDay != 0L && today - p.lastActiveDay >= SLEEP_AFTER_DAYS

/** True when training today would no longer save the current streak (the missed days cannot all be covered). */
fun isStreakBroken(p: Profile, today: Long): Boolean {
    if (p.streak <= 0 || p.lastActiveDay == 0L || today - p.lastActiveDay < 2L) return false
    return StreakRules.onActivity(p.lastActiveDay, today, p.streak, p.shields, p.restWeekUsed).broken
}

/** The small next step offered right after the streak broke (spec: "פשוט מציע את הצעד הקטן הבא"). */
const val NEXT_SMALL_STEP = "הצעד הבא קטן: אימון זעיר. עשר דקות. זה הכול."

private val sleepyLines = listOf(
    "זזז. אה. {u:אתה|את}. חלמתי על המכון. היה נחמד שם.",
    "נרדמתי בשמירה. אימון אחד ואני ער. גם {u:אתה|את}.",
    "ישנתי. כמה ימים. המפלצת כיסתה אותי בשמיכה. אדיבה מדי.",
    "אני ער. בערך. עוד רגע. אימון קטן יעשה את זה סופית.",
    "שקט כאן. חשוד. הנעליים מתגעגעות. אני לא. טוב, קצת."
)

fun sleepyLine(): String = com.tamir.hamasa.domain.game.CharacterNames.g(sleepyLines.random())
