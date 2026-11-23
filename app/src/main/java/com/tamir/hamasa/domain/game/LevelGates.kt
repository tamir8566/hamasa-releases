package com.tamir.hamasa.domain.game

/**
 * What opens at which level (spec level table). Gates only ever open: a level never goes down, and a
 * player whose level already passed a gate has it open. Nothing he already has is taken away.
 */
object LevelGates {
    const val RARE_CHESTS = 3
    const val TREE = 4
    /** Bosses open together with the map (Tamir's choice: everything opens at level 6). */
    const val BOSS = 6
    const val SECOND_SHIELD = 5
    const val MAP = 6
    const val EARLY_HINTS = 9
    const val TITLE = 10

    const val TITLE_HE = "האיש והאגדה"

    fun rareChestsOpen(level: Int): Boolean = level >= RARE_CHESTS
    fun treeOpen(level: Int): Boolean = level >= TREE
    fun bossOpen(level: Int): Boolean = level >= BOSS
    fun mapOpen(level: Int): Boolean = level >= MAP
    fun earlyHintsOpen(level: Int): Boolean = level >= EARLY_HINTS

    /** Shambiuluk's first title, from level 10. */
    fun titleFor(level: Int): String? = if (level >= TITLE) CharacterNames.g(TITLE_HE) else null

    /** "פרק שני" of the map opens at level 10. */
    fun mapChapterHe(level: Int): String = if (level >= TITLE) "פרק שני" else "פרק ראשון"

    fun xpMissing(xp: Int, level: Int): Int = (LevelTable.xpForLevel(level) - xp).coerceAtLeast(0)

    fun treeTeaser(xp: Int): String = "העץ נפתח ברמה $TREE. עוד ${xpMissing(xp, TREE)} נקודות."
    fun mapTeaser(xp: Int): String = "המפה נפתחת ברמה $MAP. עוד ${xpMissing(xp, MAP)} נקודות."
    fun bossTeaser(xp: Int): String = "הבוס נפתח ברמה $BOSS, יחד עם המפה. עוד ${xpMissing(xp, BOSS)} נקודות."

    /** Lines for what opened exactly at [level]. */
    fun unlocksAt(level: Int): List<String> {
        // Explicit if-chain (not `when`): several gates share a level (BOSS == MAP == 6).
        val out = ArrayList<String>()
        if (level == RARE_CHESTS) out.add("תיבות נדירות ואגדיות נכנסות להגרלה.")
        if (level == TREE) out.add("עץ הכישורים נפתח לצפייה.")
        if (level == SECOND_SHIELD) out.add("מגן רצף נוסף.")
        if (level == MAP) out.add("המפה נפתחת.")
        if (level == BOSS) out.add("הבוס השבועי נכנס לזירה. מעכשיו הוא סופג מכות. רק במכות הנכונות.")
        if (level == MAP) out.add(CharacterNames.g("מקצוע לשמביולוק: לוחם, נווד או נזיר. בעץ."))
        if (level == EARLY_HINTS) out.add("רמזים לתגים הנסתרים נחשפים.")
        if (level == TITLE) {
            out.add("תואר ראשון: ${CharacterNames.g(TITLE_HE)}.")
            out.add("פרק חדש במפה.")
        }
        return out
    }

    /** Everything that opened above [fromLevel] up to and including [toLevel]. */
    fun unlocksBetween(fromLevel: Int, toLevel: Int): List<String> {
        if (toLevel <= fromLevel) return emptyList()
        val out = ArrayList<String>()
        var l = fromLevel + 1
        while (l <= toLevel) {
            out.addAll(unlocksAt(l))
            l++
        }
        return out
    }
}
