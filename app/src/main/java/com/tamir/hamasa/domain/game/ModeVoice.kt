package com.tamir.hamasa.domain.game

/**
 * Shambiuluk's lines for modes, the morning roll, surprise events and level gates.
 * Same voice as [Voice]: short, deadpan, mock-formal, a twist at the end. No emojis.
 * Avoids the capped words of CLAUDE.md entirely.
 */
object ModeVoice {

    private val luckyModeLines = listOf(
        "מצב חדש התגלה: %s. לא חיפשת. הוא מצא אותך.",
        "מצב חדש התגלה: %s. במזל. אני רושם את זה כמיומנות.",
        "מצב חדש התגלה: %s. נפל מהשמיים. אני לא שואל שאלות.",
        "מצב חדש התגלה: %s. בלי רמה, בלי תיבה. ככה זה עם אגדות."
    )

    fun luckyMode(modeNameHe: String): String = CharacterNames.g(luckyModeLines.random()).format(modeNameHe)

    // ------------------------------------------------------------------ race against the clock
    /** "בדקה", "ב-3 דקות", "ב-40 שניות". */
    fun byDuration(sec: Int): String {
        val s = if (sec < 0) -sec else sec
        if (s < 60) return "ב-$s שניות"
        val m = (s + 30) / 60
        return if (m == 1) "בדקה" else "ב-$m דקות"
    }

    private val raceWinLines = listOf(
        "ניצחת את השעון %s. השעון מבקש הסבר.",
        "ניצחת את השעון %s. הוא ממשיך לתקתק. בעלבון.",
        "ניצחת את השעון %s. הידד. בשקט, הוא רגיש."
    )
    private val raceLoseLines = listOf(
        "השעון ניצח הפעם, %s. הוא לא ינצח פעמיים.",
        "השעון לקח את הסיבוב, %s. נרשם. שעונים לא זוכרים. אני כן.",
        "השעון מוביל %s. רק הפעם. המפלצת עזרה לו."
    )

    /** [deltaSec] = previous duration minus today's (positive = faster today). Null = nothing to compare. */
    fun raceResult(deltaSec: Int?): String = when {
        deltaSec == null -> "אין זמן קודם להשוות. הזמן של היום הוא היריב של הפעם הבאה."
        deltaSec > 0 -> raceWinLines.random().format(byDuration(deltaSec))
        deltaSec == 0 -> "תיקו מדויק עם השעון. לשנייה. זה כבר חשוד."
        else -> raceLoseLines.random().format(byDuration(deltaSec))
    }

    // ------------------------------------------------------------------ surprise events
    private val eventDoneLines = listOf(
        "בוצע. נרשם. האירוע הבא כבר מתכנן משהו.",
        "סגור. עשית מה שלא היית {u:חייב|חייבת}. זה בדיוק העניין.",
        "הידד. אירוע הושלם. המפלצת לא הוזמנה ולא הגיעה.",
        "קיבלתי. הצעה, ביצוע, פרס. סדר עולמי תקין."
    )

    fun eventDone(): String = CharacterNames.g(eventDoneLines.random())

    fun eventRareShield(): String = "ובנוסף: מגן רצף. אירוע קשה, פרס נדיר. ככה זה עובד."
    fun eventRareChest(): String = "ובנוסף: תיבה. מדוע? כי מי שעושה סט ירידה באמצע אימון מגיע לו משהו."

    fun eventGaveUp(): String = listOf(
        "ויתרת. בסדר. אין פרס, אין עונש. האימון ממשיך.",
        "נו. לא היום. ההצעה חוזרת למגירה."
    ).random()

    // ------------------------------------------------------------------ mode "surprise"
    fun surpriseSwap(fromHe: String, toHe: String): String =
        "הפתעה, כמובטח: $fromHe יצא, $toHe נכנס. לא שאלתי. זו הייתה הנקודה."

    // ------------------------------------------------------------------ mode "joker"
    fun jokerPick(): String = CharacterNames.g(listOf(
        "ג'וקר. היום {u:אתה בוחר|את בוחרת}. אני רק מחזיק את הקלפים.",
        "ג'וקר. כל המצבים שפתחת על השולחן. {u:בחר|בחרי} בחכמה. או לא."
    ).random())

    // ------------------------------------------------------------------ sleep changed the roll
    fun rollChangedForSleep(): String = "שיניתי את המצב. עם לילה כזה לא משחקים בכבד. המפלצת מסכימה."

    // ------------------------------------------------------------------ level gates
    fun gateLocked(): String = CharacterNames.g(listOf(
        "סגור בינתיים. עוד קצת נקודות, והדלת נפתחת.",
        "נעול. לא לנצח. רק עד {u:שתגיע|שתגיעי} לשם."
    ).random())
}
