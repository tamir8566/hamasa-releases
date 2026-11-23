package com.tamir.hamasa.domain.game

/**
 * "כישורים מיוחדים" (spec v2: "בוחרים אחד משלושה: ריכוז (נקודות כפולות על שיא), סבלנות (מגן רצף), סקרנות (רמז לתג)").
 *  - FOCUS     passive: the record points of a session (25 per paid record) are paid twice.
 *  - PATIENCE  active, every [PATIENCE_DAYS] days: one streak shield, when the bag has room.
 *  - CURIOSITY active, every [CURIOSITY_DAYS] days: the hint of one hidden badge he does not have yet.
 * Open once the tree is open and its branches add up to [UNLOCK_TOTAL_LEVELS]. One skill at a time; it can be
 * changed every [SWITCH_DAYS] days. Pure, no Android types.
 */
enum class SkillId(val nameHe: String, val effectHe: String, val active: Boolean) {
    FOCUS("ריכוז", "נקודות כפולות על כל שיא.", false),
    PATIENCE("סבלנות", "מגן רצף אחד כל שבועיים, כשיש מקום בתיק.", true),
    CURIOSITY("סקרנות", "רמז לתג נסתר אחד בכל שבוע.", true)
}

object Skills {
    const val UNLOCK_TOTAL_LEVELS = 3
    const val SWITCH_DAYS = 30
    const val PATIENCE_DAYS = 14
    const val CURIOSITY_DAYS = 7

    fun byName(name: String): SkillId? = SkillId.values().firstOrNull { it.name == name }

    fun cooldownDays(id: SkillId): Int = when (id) {
        SkillId.FOCUS -> 0
        SkillId.PATIENCE -> PATIENCE_DAYS
        SkillId.CURIOSITY -> CURIOSITY_DAYS
    }

    /** Days until the active skill can be used again (0 = now). [usedDay] 0 = never used. */
    fun daysUntilReady(id: SkillId, usedDay: Long, today: Long): Int {
        val cd = cooldownDays(id)
        if (cd <= 0 || usedDay <= 0L) return 0
        return (usedDay + cd - today).toInt().coerceAtLeast(0)
    }

    /** Days until another skill may be chosen (0 = now). [chosenDay] 0 = never chosen. */
    fun daysUntilSwitch(chosenDay: Long, today: Long): Int {
        if (chosenDay <= 0L) return 0
        return (chosenDay + SWITCH_DAYS - today).toInt().coerceAtLeast(0)
    }

    fun chosenLineHe(id: SkillId): String = CharacterNames.g(
        when (id) {
            SkillId.FOCUS -> "ריכוז. כל שיא נספר פעמיים. אני סופר. בעיון."
            SkillId.PATIENCE -> "סבלנות. בחירה של מבוגרים. מפתיע."
            SkillId.CURIOSITY -> "סקרנות. מעכשיו אני מדליף. בשקט."
        }
    )

    fun shieldLineHe(): String = "מגן רצף נכנס לתיק. הסבלנות משתלמת. לפעמים."
    fun bagFullLineHe(): String = "התיק מלא. הסבלנות מחכה. היא טובה בזה."
    fun noHintLineHe(): String = "אין תג נסתר שנשאר לרמוז עליו. נדיר. מרשים."
}
