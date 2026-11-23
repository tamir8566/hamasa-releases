package com.tamir.hamasa.domain.game

import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.SleepBand
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * "מקצוע לשמביולוק" (design spec: "לוחם / נווד / נזיר, משנה ציוד ובונוסים, מחליפים פעם בעונה").
 * See docs/professions.md. Pure logic, no Android types.
 *
 *  WARRIOR  (לוחם, strength):    +5% xp on GYM sessions; +1 boss damage on a GYM session that hit the boss.
 *  WANDERER (נווד, endurance):   +5% xp on WALK / RUN / YOGA; every 2nd finished walk or run: +1 map station.
 *  MONK     (נזיר, consistency): +5% xp on MINI / YOGA; +3% xp on any session after a short night (SleepBand.SHORT);
 *                                +3% chance that a streak shield does not burn (on top of gear SHIELD_SAVE).
 * Gear: an equipped item whose theme ([gearTheme]) matches the profession has its buff counted x1.5.
 *
 * Bonuses only add, never touch the coach's weights/reps/rest, and switching never removes anything.
 * Unlock at [UNLOCK_LEVEL] (= the map, where the journey starts). First choice any time; after that one change per
 * season. A season = a calendar quarter (the app has no other season concept: only monthly seasonal badges).
 */
enum class ProfessionId(val nameHe: String, val descriptionHe: String) {
    WARRIOR("לוחם", "כוח. חדר הכושר, שיאים ובוסים."),
    WANDERER("נווד", "סיבולת. הליכות, ריצות, יוגה והמפה."),
    MONK("נזיר", "עקביות והתאוששות. אימונים קצרים, יוגה, מגנים וימים אחרי לילה קצר.");

    /** [nameHe] in the hero's grammar (שמביולוקה: לוחמת / נוודת / נזירה). Use this for display. */
    val displayHe: String get() = CharacterNames.g(nameHe)
}

object Professions {
    const val UNLOCK_LEVEL = LevelGates.MAP
    const val SESSION_XP_PERCENT = 5f
    const val MONK_SHORT_NIGHT_XP_PERCENT = 3f
    const val MONK_SHIELD_SAVE_PERCENT = 3f
    const val GEAR_MULTIPLIER = 1.5f
    const val WARRIOR_BOSS_BONUS = 1
    /** The wanderer gets a map station on every [WANDERER_STATION_EVERY]th finished walk or run. */
    const val WANDERER_STATION_EVERY = 2

    fun byName(name: String): ProfessionId? = ProfessionId.values().firstOrNull { it.name == name }

    fun unlocked(level: Int): Boolean = level >= UNLOCK_LEVEL

    /** The profession that counts right now: the stored choice, once the level gate is open (levels never go down). */
    fun active(professionId: String, xp: Int): ProfessionId? =
        if (unlocked(LevelTable.levelFor(xp))) byName(professionId) else null

    // ------------------------------------------------------------------ gear

    /**
     * The profession an item belongs to: its highest stat requirement (upper/lower = strength -> WARRIOR,
     * endurance -> WANDERER, stability -> MONK). Ties go endurance, then stability, then strength.
     * Commons have no requirements and belong to nobody. Split: warrior 15, monk 15, wanderer 13 items.
     */
    fun gearTheme(item: GearItem): ProfessionId? {
        val strength = maxOf(item.requiresUpper, item.requiresLower)
        val best = maxOf(strength, maxOf(item.requiresStability, item.requiresEndurance))
        if (best <= 0) return null
        return when (best) {
            item.requiresEndurance -> ProfessionId.WANDERER
            item.requiresStability -> ProfessionId.MONK
            else -> ProfessionId.WARRIOR
        }
    }

    /** Multiplier on an equipped item's buff percent: [GEAR_MULTIPLIER] for the profession's own gear, else 1. */
    fun gearMultiplier(profession: ProfessionId?, item: GearItem): Float =
        if (profession != null && gearTheme(item) == profession) GEAR_MULTIPLIER else 1f

    /** Buffs of the profession itself, added to the gear sum (GameRepository.equippedBuffs). */
    fun extraBuffs(profession: ProfessionId?): Map<BuffType, Float> =
        if (profession == ProfessionId.MONK) mapOf(BuffType.SHIELD_SAVE to MONK_SHIELD_SAVE_PERCENT) else emptyMap()

    // ------------------------------------------------------------------ session xp

    fun sessionXpPercent(profession: ProfessionId?, type: SessionType, band: SleepBand): Float = when (profession) {
        null -> 0f
        ProfessionId.WARRIOR -> if (type == SessionType.GYM) SESSION_XP_PERCENT else 0f
        ProfessionId.WANDERER ->
            if (type == SessionType.WALK || type == SessionType.RUN || type == SessionType.YOGA) SESSION_XP_PERCENT else 0f
        ProfessionId.MONK ->
            (if (type == SessionType.MINI || type == SessionType.YOGA) SESSION_XP_PERCENT else 0f) +
                (if (band == SleepBand.SHORT) MONK_SHORT_NIGHT_XP_PERCENT else 0f)
    }

    /** Xp the profession adds on top of [baseXp] (the session xp before gear and multipliers). Never below 0. */
    fun sessionXp(profession: ProfessionId?, type: SessionType, band: SleepBand, baseXp: Int): Int {
        val pct = sessionXpPercent(profession, type, band)
        if (pct <= 0f || baseXp <= 0) return 0
        return (baseXp * pct / 100f).roundToInt().coerceAtLeast(0)
    }

    /** Warrior perk: extra boss damage for a GYM session that already hit the boss. */
    fun bossBonus(profession: ProfessionId?, type: SessionType, damage: Int): Int =
        if (profession == ProfessionId.WARRIOR && type == SessionType.GYM && damage > 0) WARRIOR_BOSS_BONUS else 0

    /** Wanderer perk: [enduranceDone] = finished walks + runs including this one. */
    fun wandererStation(profession: ProfessionId?, type: SessionType, enduranceDone: Int): Int =
        if (profession == ProfessionId.WANDERER && (type == SessionType.WALK || type == SessionType.RUN) &&
            enduranceDone > 0 && enduranceDone % WANDERER_STATION_EVERY == 0
        ) 1 else 0

    // ------------------------------------------------------------------ seasons (calendar quarters)

    fun seasonOf(day: Long): Int {
        val d = Days.of(day)
        return d.year * 4 + (d.monthValue - 1) / 3
    }

    fun seasonStart(day: Long): Long {
        val d = Days.of(day)
        val month = ((d.monthValue - 1) / 3) * 3 + 1
        return LocalDate.of(d.year, month, 1).toEpochDay()
    }

    fun nextSeasonStart(day: Long): Long = LocalDate.ofEpochDay(seasonStart(day)).plusMonths(3).toEpochDay()

    fun seasonNameHe(day: Long): String = when ((Days.of(day).monthValue - 1) / 3) {
        0 -> "עונת החורף"
        1 -> "עונת האביב"
        2 -> "עונת הקיץ"
        else -> "עונת הסתיו"
    }

    /** First choice is free; after that a change waits for a new season. [chosenDay] 0 = never chosen. */
    fun canSwitch(chosenDay: Long, today: Long): Boolean = chosenDay <= 0L || seasonOf(today) > seasonOf(chosenDay)

    /** First day a change is allowed, or 0 when it is allowed now. */
    fun nextSwitchDay(chosenDay: Long, today: Long): Long = if (canSwitch(chosenDay, today)) 0L else nextSeasonStart(chosenDay)

    // ------------------------------------------------------------------ text

    fun effectsHe(id: ProfessionId): List<String> = when (id) {
        ProfessionId.WARRIOR -> listOf(
            "+5% ניסיון על אימון כושר.",
            "+1 נזק לבוס בכל אימון כושר שפוגע בו.",
            "ציוד כוח: הבונוס שלו פי 1.5."
        )
        ProfessionId.WANDERER -> listOf(
            "+5% ניסיון על הליכה, ריצה ויוגה. גם מהשעון.",
            "כל הליכה או ריצה שנייה: תחנה נוספת במפה.",
            "ציוד סיבולת: הבונוס שלו פי 1.5."
        )
        ProfessionId.MONK -> listOf(
            "+5% ניסיון על אימון קצר ויוגה.",
            "+3% ניסיון על כל אימון אחרי לילה קצר.",
            "+3% סיכוי שמגן רצף לא נשרף.",
            "ציוד יציבות: הבונוס שלו פי 1.5."
        )
    }

    /** Which gear counts as the profession's own. */
    fun gearRuleHe(id: ProfessionId): String = when (id) {
        ProfessionId.WARRIOR -> "ציוד כוח: פריט שהדרישה הגבוהה שלו היא חזה/גב או רגליים."
        ProfessionId.WANDERER -> "ציוד סיבולת: פריט שהדרישה הגבוהה שלו היא סיבולת."
        ProfessionId.MONK -> "ציוד יציבות: פריט שהדרישה הגבוהה שלו היא יציבות."
    }

    /** Shambiuluk on the choice. */
    fun chosenLineHe(id: ProfessionId): String = CharacterNames.g(
        when (id) {
            ProfessionId.WARRIOR -> "נרשם. לוחם. הבוס כבר שמע. הוא מעמיד פנים שלא."
            ProfessionId.WANDERER -> "מקובל. נווד. כל צעד נספר. גם אלה שבדרך למקרר."
            ProfessionId.MONK -> "סגור. נזיר. שקט, נשימות, מגנים. המפלצת מבולבלת. גם אני קצת."
        }
    )

    fun lockedLineHe(): String = "מקצוע נפתח ברמה $UNLOCK_LEVEL, יחד עם המפה. קודם הולכים. אחר כך מחליטים מי הולך."
}
