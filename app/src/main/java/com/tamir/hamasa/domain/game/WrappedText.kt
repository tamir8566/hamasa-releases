package com.tamir.hamasa.domain.game

import java.util.Locale

/**
 * The words of the "wrapped" stories (weekly and monthly summaries): one slide = (kicker, big, line, sub).
 * Pure text so the tests can read it. Shambiuluk's voice: short, deadpan, a twist at the end.
 */
object WrappedText {

    data class Slide(val kicker: String, val big: String, val line: String, val sub: String = "", val bigSmall: Boolean = false)

    fun n(v: Int): String = String.format(Locale.US, "%,d", v)

    // ---------------------------------------------------------------- week

    fun weekCover(from: String, to: String): Slide =
        Slide("השבוע שלך", "סיכום", "שבוע $from עד $to. הספירה נגמרה. המספרים נשארו.", "לחיצה מתקדמת. אין לאן לברוח.", bigSmall = true)

    fun sessions(n: Int, week: Boolean): Slide {
        val period = if (week) "השבוע" else "החודש"
        val line = when {
            n <= 0 -> "אפס. הספה ניצחה. בנוק-אאוט."
            n == 1 -> "אחד. זה לא רצף, זה רמז."
            n == 2 -> "שניים. הגוף שם לב. בקושי."
            n in 3..4 -> "זה כבר לא מקרה, זו מדיניות."
            n in 5..8 -> "המפלצת ביקשה חופש. לא קיבלה."
            else -> "האיש והאגדה. בעיקר האיש."
        }
        return Slide("אימונים $period", n.toString(), line)
    }

    /** Lifted volume with a picture he can hold: cars, elephants. */
    fun volume(kg: Double): Slide {
        val k = kg.toInt()
        val sub = when {
            k <= 0 -> ""
            k < 1_500 -> "בערך ${(k / 90).coerceAtLeast(1)} פעמים את המשקל שלך. בערך."
            k < 5_000 -> "זה ${String.format(Locale.US, "%.1f", k / 1_500f)} מכוניות משפחתיות. בלי הנוסעים."
            k < 50_000 -> "זה ${String.format(Locale.US, "%.1f", k / 5_000f)} פילים. פילים מבוגרים."
            else -> "זה ${(k / 40_000)} לווייתנים צעירים. הם לא ידעו."
        }
        val line = when {
            k <= 0 -> "לא הרמת כלום. הברזל נח. גם הוא התגעגע."
            k < 5_000 -> "ק\"ג עברו דרך הידיים שלך. כולם חזרו למקום."
            else -> "ק\"ג. הרצפה שקלה את זה. היא שתקה."
        }
        return Slide("נפח", n(k), line, sub)
    }

    fun records(n: Int): Slide = Slide(
        "שיאים חדשים", n.toString(),
        when {
            n <= 0 -> "אפס שיאים. השיאים הישנים ישנים בשקט. בינתיים."
            n == 1 -> "אחד. המספר הישן נמחק. בלי טקס."
            n <= 3 -> "השיאים הישנים התלוננו. לא עזר."
            else -> "רצח. הטבלה לא עומדת בקצב."
        }
    )

    fun topMuscle(nameHe: String?, sets: Int): Slide =
        if (nameHe == null || sets <= 0) Slide("השריר של השבוע", "אף אחד", "אף שריר לא עבד. כולם נחו. יותר מדי.", bigSmall = true)
        else Slide("השריר של השבוע", nameHe, "$sets סטים. השאר צפו מהצד.", "הוא לא ביקש. הוא קיבל.", bigSmall = true)

    fun boss(nameHe: String, defeated: Boolean, hpLeft: Int, maxHp: Int): Slide {
        val name = Addr.x(nameHe)
        return if (defeated) Slide("בוס השבוע", name, "הובס. לא היה לו סיכוי. היה לו רק שם.", bigSmall = true)
        else {
            val pct = if (maxHp > 0) (100 * (maxHp - hpLeft) / maxHp).coerceIn(0, 100) else 0
            Slide("בוס השבוע", name, "עוד עומד. $pct אחוז מהחיים שלו כבר אינם.", "הוא לא ישכח. גם אתה לא אמור.", bigSmall = true)
        }
    }

    fun calories(okDays: Int, judgedDays: Int, week: Boolean): Slide {
        if (judgedDays <= 0) return Slide("קלוריות", "?", "לא נרשם אוכל. הצלחת ריקה מפרטים. לא מאוכל.")
        val line = when {
            okDays == judgedDays -> "כל הימים בטווח. מדויק כמו שעון שוויצרי. עם רעב."
            okDays * 2 >= judgedDays -> "רוב הימים בטווח. השאר היו אנושיים."
            okDays == 0 -> "אף יום בטווח. התזונאי הדמיוני התפטר."
            else -> "חלק בטווח. חלק בתיאבון."
        }
        return Slide("ימים בטווח הקלוריות", "$okDays/$judgedDays", line, if (week) "" else "ימים עם רישום בלבד.")
    }

    fun streak(days: Int, best: Boolean): Slide = Slide(
        if (best) "הרצף הטוב" else "הרצף",
        days.toString(),
        when {
            days <= 0 -> "אפס ימים. הרצף מתחיל מחר. הוא תמיד מתחיל מחר."
            days < 7 -> "ימים ברצף. השבוע הבא בוחן את זה."
            days < 30 -> "ימים ברצף. זו כבר הרגל. הרגל טוב, לשם שינוי."
            else -> "ימים ברצף. זה כבר לא רצף, זה אופי."
        }
    )

    fun loot(xp: Int, gold: Int): Slide =
        Slide("נקודות וזהב", "${n(xp)} / ${n(gold)}", "נקודות ניסיון, ומטבעות זהב. הזהב לא אמיתי. העבודה כן.", bigSmall = true)

    fun weekEnd(verdictHe: String): Slide = Slide("המשפט של שמביולוק", "הידדדדד", verdictHe, "הפרטים המלאים מחכים למטה. לא לכולם יש סבלנות.", bigSmall = true)

    // ---------------------------------------------------------------- month

    fun monthCover(monthHe: String, year: Int): Slide =
        Slide("החודש שלך", monthHe, "$year. חודש שלם נכנס לכמה מסכים. לא בגלל שהוא היה קטן.", "לחיצה מתקדמת.", bigSmall = true)

    /** Weight change; [good] = toward his goal (decided by the caller from the goal direction). */
    fun weight(kgChange: Float?, startKg: Float?, endKg: Float?, good: Boolean): Slide {
        if (kgChange == null) return Slide("המשקל", "?", "לא מספיק שקילות החודש. המאזניים התגעגעו.")
        val abs = kotlin.math.abs(kgChange)
        val big = String.format(Locale.US, "%.1f", abs)
        val dir = when {
            kgChange <= -0.05f -> "ק\"ג ירדו"
            kgChange >= 0.05f -> "ק\"ג עלו"
            else -> "ק\"ג. יציב"
        }
        val line = when {
            abs < 0.05f -> "אותו משקל. המאזניים לא התרגשו. אתה החלטת אם זה טוב."
            good -> "$dir. בכיוון הנכון. הגוף הקשיב. סוף סוף."
            else -> "$dir. לא הכיוון שביקשת. הגוף שמע, לא הקשיב."
        }
        val sub = if (startKg != null && endKg != null) "מ-${String.format(Locale.US, "%.1f", startKg)} ל-${String.format(Locale.US, "%.1f", endKg)} ק\"ג." else ""
        return Slide("המשקל", big, line, sub)
    }

    fun bosses(names: List<String>): Slide =
        if (names.isEmpty()) Slide("בוסים שהובסו", "0", "אף בוס לא נפל החודש. הם מחכים. בסבלנות מרגיזה.")
        else Slide("בוסים שהובסו", names.joinToString("\n") { Addr.x(it) }, if (names.size == 1) "אחד נפל. הוא עוד לא קם." else "${names.size} נפלו. אף אחד לא קם.", bigSmall = true)

    fun badges(names: List<String>): Slide =
        if (names.isEmpty()) Slide("תגים חדשים", "0", "אף תג חדש. התגים הישנים מבריקים. מהשעמום.")
        else Slide("תגים חדשים", names.take(4).joinToString("\n"), if (names.size > 4) "ועוד ${names.size - 4}. החזה נגמר, התגים לא." else "נתפרו לחולצה. בדמיון. החולצה בסדר.", bigSmall = true)

    fun kcalAvg(avg: Int, goal: Int, days: Int): Slide {
        if (days <= 0) return Slide("קלוריות ליום", "?", "לא נרשמו ארוחות החודש. היומן רעב.")
        val diff = avg - goal
        val line = when {
            kotlin.math.abs(diff) <= 100 -> "ממוצע ליום, מול יעד של ${n(goal)}. דייקת. זה נדיר, לא להתרגל."
            diff > 0 -> "ממוצע ליום. ${n(diff)} מעל היעד. בכל יום. הם הצטברו בשקט."
            else -> "ממוצע ליום. ${n(-diff)} מתחת ליעד. הגוף רשם."
        }
        return Slide("קלוריות ליום", n(avg), line, "לפי $days ימים עם רישום.")
    }

    fun monthEnd(closingHe: String): Slide = Slide("המשפט של שמביולוק", "זהו", closingHe, "הפרטים המלאים מחכים למטה.", bigSmall = true)
}
