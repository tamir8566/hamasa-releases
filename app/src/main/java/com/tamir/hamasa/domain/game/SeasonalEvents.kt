package com.tamir.hamasa.domain.game

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "אירועים עונתיים": holiday weeks with a festive boss that JOINS the weekly boss (the weekly rotation is never
 * touched). Every event boss reuses an existing boss portrait ([baseBossId] in BossCatalog: no new art) with a
 * festive name and quote. Its limited-time badge can only be earned by beating it during the event.
 *
 * Dates (Israel): a verified table for 2026..2030 (computed from the Hebrew calendar and checked against the known
 * dates: Hanukkah 2026 = 5.12, Purim 2026 = 3.3, Pesach 2026 = 2.4, Yom HaAtzmaut 2026 = 22.4, Rosh Hashana
 * 2026 = 12.9, Sukkot 2026 = 26.9). Other years fall back to android.icu.util.HebrewCalendar (API 24+).
 *
 * Windows: Hanukkah 8 days from 25 Kislev; Purim 3 days before to 3 after; Pesach 7 days from 15 Nisan;
 * Independence Day from the day itself for 7 days (never on Memorial Day); Tishrei: Rosh Hashana + 6 days and
 * Sukkot through Simchat Torah (8 days), one boss for both; Summer 20..26 August (no fast day falls there).
 */
object SeasonalEvents {

    data class Event(
        val id: String,
        /** Holiday name for the banner. */
        val nameHe: String,
        val bossNameHe: String,
        /** BossCatalog id whose portrait is reused. */
        val baseBossId: String,
        /** Raw texts may carry {u:male|female} player tokens; read them through [quoteHe] / [defeatedHe] / [bannerHe]. */
        private val quoteRaw: String,
        private val defeatedRaw: String,
        /** Shambiuluk's line on the banner. */
        private val bannerRaw: String,
        /** The limited-time badge (BadgeCatalog). */
        val badgeId: String,
        val maxHp: Int
    ) {
        /** Resolved for the player's grammar (Addr). */
        val quoteHe: String get() = Addr.x(quoteRaw)
        val defeatedHe: String get() = Addr.x(defeatedRaw)
        /** In the hero's grammar (CharacterNames), player tokens resolved. */
        val bannerHe: String get() = CharacterNames.g(bannerRaw)
    }

    /** One run of an event in a given year. [start]..[end] are epoch days, inclusive. */
    data class Window(val event: Event, val year: Int, val ranges: List<LongRange>) {
        val key: String get() = "${event.id}:$year"
        fun contains(day: Long): Boolean = ranges.any { day in it }
        /** Days left in the range that contains [day], counting [day]; 0 when outside. */
        fun daysLeft(day: Long): Int {
            val r = ranges.firstOrNull { day in it } ?: return 0
            return (r.last - day + 1).toInt()
        }
        /** Last day of the whole event (the boss state belongs to it). */
        val lastDay: Long get() = ranges.maxOf { it.last }
    }

    /** Reward of an event boss (once per event and year). */
    const val DEFEAT_XP = 150
    const val DEFEAT_GOLD = 40

    val hanukkah = Event(
        id = "hanukkah", nameHe = "חנוכה", bossNameHe = "מכשפת הסופגניות", baseBossId = "sugar_witch",
        quoteRaw = "סופגנייה לכל נר. שמונה נרות. {u:תעשה|תעשי} את החשבון.",
        defeatedRaw = "המכשפה נשארה עם המגש. הנרות דולקים בלעדיה.",
        bannerRaw = "שמונה ימים. שמונה נרות. מכשפה אחת. הסופגניות לא נחשבות כנשק.",
        badgeId = "event_hanukkah", maxHp = 24
    )
    val purim = Event(
        id = "purim", nameHe = "פורים", bossNameHe = "גובלין המסכות", baseBossId = "excuse_goblin",
        quoteRaw = "התחפשתי לתירוץ. לא זיהית אותי. אף פעם לא מזהים.",
        defeatedRaw = "המסכה נפלה. מתחתיה עוד תירוץ. גם הוא נפל.",
        bannerRaw = "גובלין בתחפושת. מדוע הוא מחופש לתירוץ? כי זה מה שהוא. בלי מסכה.",
        badgeId = "event_purim", maxHp = 24
    )
    val pesach = Event(
        id = "pesach", nameHe = "פסח", bossNameHe = "דרקון החמץ", baseBossId = "snooze_dragon",
        quoteRaw = "עוד פרוסה לפני שמבערים. רק אחת. מסורת.",
        defeatedRaw = "הדרקון בוער. מסתבר שגם הוא חמץ.",
        bannerRaw = "חג החירות. יוצאים מהספה. הדרקון נשאר במצרים. או לפחות במטבח.",
        badgeId = "event_pesach", maxHp = 24
    )
    val independence = Event(
        id = "independence", nameHe = "יום העצמאות", bossNameHe = "ענק המנגל", baseBossId = "thin_legs_giant",
        quoteRaw = "מנגל משש בבוקר עד הלילה. זה גם אימון. לא?",
        defeatedRaw = "הענק נפל ליד הגחלים. המנגל ממשיך. בלעדיו.",
        bannerRaw = "עצמאות. גם מהמנגל. ענק אחד עומד בין הקציצה לבינך.",
        badgeId = "event_independence", maxHp = 24
    )
    val tishrei = Event(
        id = "tishrei", nameHe = "חגי תשרי", bossNameHe = "האני של השנה שעברה", baseBossId = "old_self",
        quoteRaw = "שנה חדשה. אני אותו דבר. {u:בוא, תצטרף|בואי, תצטרפי}.",
        defeatedRaw = "השנה שעברה נשארה מאחור. היא מבקשת לא להתקשר.",
        bannerRaw = "תפוח, דבש, סוכה. והאני של השנה שעברה מחכה בפתח. נו.",
        badgeId = "event_tishrei", maxHp = 30
    )
    val summer = Event(
        id = "summer", nameHe = "שיא הקיץ", bossNameHe = "עוג החום", baseBossId = "rain_ogre",
        quoteRaw = "שלושים וחמש מעלות. המזגן קורא בשמך.",
        defeatedRaw = "העוג נמס. המזגן מתגעגע. {u:אתה|את} לא.",
        bannerRaw = "שיא הקיץ. העוג החליף גשם בחום. אותו עוג. אותה עצלות.",
        badgeId = "event_summer", maxHp = 24
    )

    val all: List<Event> = listOf(hanukkah, purim, pesach, independence, tishrei, summer)

    fun byId(id: String): Event? = all.firstOrNull { it.id == id }

    /** Gregorian year -> first days (epoch) of the holidays that year. */
    private class YearDates(
        val purim: Long, val pesach: Long, val independence: Long,
        val roshHashana: Long, val sukkot: Long, val hanukkah: Long
    )

    private fun d(y: Int, m: Int, day: Int): Long = LocalDate.of(y, m, day).toEpochDay()

    private val table: Map<Int, YearDates> = mapOf(
        2026 to YearDates(d(2026, 3, 3), d(2026, 4, 2), d(2026, 4, 22), d(2026, 9, 12), d(2026, 9, 26), d(2026, 12, 5)),
        2027 to YearDates(d(2027, 3, 23), d(2027, 4, 22), d(2027, 5, 12), d(2027, 10, 2), d(2027, 10, 16), d(2027, 12, 25)),
        2028 to YearDates(d(2028, 3, 12), d(2028, 4, 11), d(2028, 5, 2), d(2028, 9, 21), d(2028, 10, 5), d(2028, 12, 13)),
        2029 to YearDates(d(2029, 3, 1), d(2029, 3, 31), d(2029, 4, 19), d(2029, 9, 10), d(2029, 9, 24), d(2029, 12, 2)),
        2030 to YearDates(d(2030, 3, 19), d(2030, 4, 18), d(2030, 5, 8), d(2030, 9, 28), d(2030, 10, 12), d(2030, 12, 21))
    )

    private val cache = HashMap<Int, YearDates?>()

    private fun datesFor(year: Int): YearDates? {
        table[year]?.let { return it }
        synchronized(cache) {
            if (cache.containsKey(year)) return cache[year]
            val v = runCatching { computeIcu(year) }.getOrNull()
            cache[year] = v
            return v
        }
    }

    /** Hebrew calendar fallback (android.icu, API 24+). Null on any failure. */
    private fun computeIcu(year: Int): YearDates? {
        val spring = year + 3760   // Hebrew year of Purim / Pesach / Iyar in this Gregorian year
        val autumn = year + 3761   // Hebrew year that starts in September / October of this Gregorian year
        val purim = icuDay(spring, android.icu.util.HebrewCalendar.ADAR, 14) ?: return null
        val pesach = icuDay(spring, android.icu.util.HebrewCalendar.NISAN, 15) ?: return null
        var atzmaut = icuDay(spring, android.icu.util.HebrewCalendar.IYAR, 5) ?: return null
        atzmaut = when (LocalDate.ofEpochDay(atzmaut).dayOfWeek) {
            DayOfWeek.FRIDAY -> atzmaut - 1
            DayOfWeek.SATURDAY -> atzmaut - 2
            DayOfWeek.MONDAY -> atzmaut + 1
            else -> atzmaut
        }
        val rh = icuDay(autumn, android.icu.util.HebrewCalendar.TISHRI, 1) ?: return null
        val sukkot = icuDay(autumn, android.icu.util.HebrewCalendar.TISHRI, 15) ?: return null
        val hanukkah = icuDay(autumn, android.icu.util.HebrewCalendar.KISLEV, 25) ?: return null
        return YearDates(purim, pesach, atzmaut, rh, sukkot, hanukkah)
    }

    private fun icuDay(hYear: Int, month: Int, day: Int): Long? = try {
        val cal = android.icu.util.HebrewCalendar(hYear, month, day)
        Instant.ofEpochMilli(cal.timeInMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    } catch (t: Throwable) {
        null
    }

    /** Every event window of one Gregorian year (Hanukkah may run into January of the next year). */
    fun windowsFor(year: Int): List<Window> {
        val y = datesFor(year) ?: return emptyList()
        val summerStart = d(year, 8, 20)
        return listOf(
            Window(purim, year, listOf((y.purim - 3)..(y.purim + 3))),
            Window(pesach, year, listOf(y.pesach..(y.pesach + 6))),
            Window(independence, year, listOf(y.independence..(y.independence + 6))),
            Window(summer, year, listOf(summerStart..(summerStart + 6))),
            Window(tishrei, year, listOf(y.roshHashana..(y.roshHashana + 6), y.sukkot..(y.sukkot + 7))),
            Window(hanukkah, year, listOf(y.hanukkah..(y.hanukkah + 7)))
        )
    }

    /** The event running on [day], or null. */
    fun activeOn(day: Long): Window? {
        val year = LocalDate.ofEpochDay(day).year
        for (y in listOf(year, year - 1)) {
            for (w in windowsFor(y)) if (w.contains(day)) return w
        }
        return null
    }

    /** The next event that starts after [day] within [withinDays] days (for a small teaser), or null. */
    fun upcoming(day: Long, withinDays: Int = 7): Pair<Window, Long>? {
        val year = LocalDate.ofEpochDay(day).year
        var best: Pair<Window, Long>? = null
        for (y in listOf(year, year + 1)) {
            for (w in windowsFor(y)) {
                val start = w.ranges.minOf { it.first }
                if (start > day && start - day <= withinDays && (best == null || start < best.second)) best = w to start
            }
        }
        return best
    }
}
