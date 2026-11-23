package com.tamir.hamasa.domain.game

/**
 * The fifteen weekly bosses. Each boss is a bad habit in costume. What hurts it is its WEAKNESS, a combination
 * of two training ingredients (domain/game/BossWeakness; [Boss.howToHitHe] repeats its label, BossHints has
 * Shambiuluk's line). The old single-rule damagedBy() was replaced by the weakness grade (BossFights.applySession).
 *
 * Sheet / index map onto the drawables bosses_1..bosses_3, five portraits per sheet, left to right.
 */
object BossCatalog {
    /** Roster as written: texts may carry {u:male|female} player tokens. Read bosses through [all]. */
    private val raw: List<Boss> = listOf(
        // ----- sheet 1
        Boss(
            id = "monster", nameHe = "המפלצת",
            quoteHe = "עוד חמש דקות. עוד חמש. אין לזה סוף.",
            tauntHe = "ידעתי {u:שלא תבוא|שלא תבואי}. גם {u:אתה|את} ידעת.",
            defeatedHe = "המפלצת לא ניצחה. לפרוטוקול.",
            sheet = 1, index = 0, maxHp = 3,
            howToHitHe = "שינה מלאה + כל הסטים הושלמו"
        ),
        Boss(
            id = "midweek_king", nameHe = "מלך אמצע השבוע",
            quoteHe = "יום שלישי הוא שלי. תמיד היה שלי.",
            tauntHe = "{u:חזור|חזרי} ביום שישי כמו כולם.",
            defeatedHe = "אמצע השבוע נכבש. הכתר נפל.",
            sheet = 1, index = 1, maxHp = 2,
            howToHitHe = "אימון כוח בין שני לרביעי"
        ),
        Boss(
            id = "couch_siren", nameHe = "הסירנה של הספה",
            quoteHe = "{u:שב|שבי}. רק לרגע. הכריות כבר חמות.",
            tauntHe = "הידעת שיש עונה חדשה? כמובן שידעת.",
            defeatedHe = "הספה שרה לבדה הערב. אין קהל.",
            sheet = 1, index = 2, maxHp = 3,
            howToHitHe = "סגרת את היום + בטן באימון"
        ),
        Boss(
            id = "thin_legs_giant", nameHe = "הענק בעל רגלי הקש",
            quoteHe = "מי צריך רגליים? אני עומד על המוניטין.",
            tauntHe = "גם {u:אתה|את} דילגת על יום רגליים. אנחנו דומים.",
            defeatedHe = "הענק נפל. הרגליים, מסתבר, חשובות.",
            sheet = 1, index = 3, maxHp = 3,
            howToHitHe = "גב + רגליים באותו אימון"
        ),
        Boss(
            id = "sly_merchant", nameHe = "הסוחר הערמומי",
            quoteHe = "למה להתאמן? יש לי אבקה שעושה את זה בשבילך.",
            tauntHe = "קנית. שוב. אני מודה לך מעומק הארנק.",
            defeatedHe = "הסוחר נשאר עם הסחורה. אין קיצורי דרך.",
            sheet = 1, index = 4, maxHp = 5,
            howToHitHe = "ארוחה מצולמת באותו יום + כל הסטים הושלמו"
        ),

        // ----- sheet 2
        Boss(
            id = "snooze_dragon", nameHe = "דרקון הנודניק",
            quoteHe = "נודניק. נודניק. נודניק. אני אוהב את הצליל.",
            tauntHe = "הבוקר כבר נגמר. יש גם מחר. אולי.",
            defeatedHe = "הדרקון נרדם לבדו. השבוע התחיל בלעדיו.",
            sheet = 2, index = 0, maxHp = 3,
            howToHitHe = "אימון בראשון או שני + שינה מלאה"
        ),
        Boss(
            id = "excuse_goblin", nameHe = "גובלין התירוצים",
            quoteHe = "אין זמן. כואב הגב. יורד גשם. יש לי עוד.",
            tauntHe = "התירוץ הזה היה טוב. רשמתי אותו לעצמי.",
            defeatedHe = "הגובלין נשאר בלי תירוצים. מצב נדיר.",
            sheet = 2, index = 1, maxHp = 4,
            howToHitHe = "אימון שאינו מכון + סוג אימון שני השבוע"
        ),
        Boss(
            id = "sugar_witch", nameHe = "מכשפת הסוכר",
            quoteHe = "עוגייה אחת. מה כבר יקרה. {u:תרגיש|תרגישי} טוב, אני מבטיחה.",
            tauntHe = "הרגשת טוב דקה שלמה. אני יודעת.",
            defeatedHe = "המכשפה נשארה עם הסיר. היום היה נקי.",
            sheet = 2, index = 2, maxHp = 3,
            howToHitHe = "יעד חלבון + אימון כוח"
        ),
        Boss(
            id = "mirror_knight", nameHe = "אביר המראה",
            quoteHe = "{u:הסתכל. רק הסתכל|הסתכלי. רק הסתכלי}. כלום לא השתנה.",
            tauntHe = "המראה לא משקרת. היא רק לא סבלנית. כמוך.",
            defeatedHe = "האביר הביט במראה וראה אותך. הוא נסוג.",
            sheet = 2, index = 3, maxHp = 3,
            howToHitHe = "חזה + כתפיים באותו אימון"
        ),
        Boss(
            id = "rain_ogre", nameHe = "עוג הגשם",
            quoteHe = "בחוץ? היום? הרטיבות תחדור לעצמות.",
            tauntHe = "נשארת בבית. הגשם מודה לך.",
            defeatedHe = "העוג נרטב. {u:אתה|את} גם. ההבדל: {u:אתה|את} חזרת.",
            sheet = 2, index = 4, maxHp = 2,
            howToHitHe = "הליכה או ריצה של 20 דקות + אימון כוח השבוע"
        ),

        // ----- sheet 3
        Boss(
            id = "weight_golem", nameHe = "גולם המשקל",
            quoteHe = "אני כבד. {u:אתה|את} לא. זה הסיפור כולו.",
            tauntHe = "המוט לא זז. סתם מעדכן: גם {u:אתה|את} לא.",
            defeatedHe = "הגולם התפורר. המשקל הפך למספר בלבד.",
            sheet = 3, index = 0, maxHp = 4,
            howToHitHe = "רגליים + חזה באותו אימון"
        ),
        Boss(
            id = "plateau_sphinx", nameHe = "ספינקס הפלאטו",
            quoteHe = "חידה: מה לא עולה ולא יורד? {u:אתה|את}.",
            tauntHe = "אותו משקל. אותן חזרות. אותה תשובה.",
            defeatedHe = "הספינקס שתק. החידה נפתרה בכוח.",
            sheet = 3, index = 1, maxHp = 4,
            howToHitHe = "שיא או כל הסטים ביעד + שינה מלאה"
        ),
        Boss(
            id = "phone_siren", nameHe = "סירנת הטלפון",
            quoteHe = "רק {u:תבדוק|תבדקי} מה חדש. רק גלילה אחת. אני כאן.",
            tauntHe = "ארבעים דקות. לא התאמנת, אבל ראית הכול.",
            defeatedHe = "הסירנה שרה למסך כבוי. אף אחד לא ענה.",
            sheet = 3, index = 2, maxHp = 3,
            howToHitHe = "יוגה + אימון כוח באותו שבוע"
        ),
        Boss(
            id = "stairs_guardian", nameHe = "שומר המדרגות האינסופיות",
            quoteHe = "עוד מדרגה. ועוד אחת. אין פסגה. אני בדקתי.",
            tauntHe = "עצרת באמצע. המדרגות לא עוצרות.",
            defeatedHe = "השומר נשאר במדרגה שלו. {u:אתה|את} עלית.",
            sheet = 3, index = 3, maxHp = 5,
            howToHitHe = "בטן + כתפיים באותו אימון"
        ),
        Boss(
            id = "old_self", nameHe = "האני הישן",
            quoteHe = "אני זוכר אותך. {u:היית נוח|היית נוחה} יותר.",
            tauntHe = "{u:ברוך השב|ברוכה השבה}. שמרתי לך את המקום.",
            defeatedHe = "האני הישן נשאר מאחור. הוא לא מתלונן. הוא לא יכול.",
            sheet = 3, index = 4, maxHp = 5,
            howToHitHe = "יום אחרי יום + סוג אימון שני השבוע"
        )
    )

    private fun resolve(b: Boss): Boss =
        if (!b.quoteHe.contains("{u:") && !b.tauntHe.contains("{u:") && !b.defeatedHe.contains("{u:")) b
        else b.copy(quoteHe = Addr.x(b.quoteHe), tauntHe = Addr.x(b.tauntHe), defeatedHe = Addr.x(b.defeatedHe))

    /** Every boss, its quote / taunt / defeat line in the player's grammar (Addr). */
    val all: List<Boss> get() = raw.map { resolve(it) }

    fun byId(id: String): Boss? = raw.firstOrNull { it.id == id }?.let { resolve(it) }

    /** Weekly rotation over the whole roster. */
    fun forWeek(weekIndex: Long): Boss {
        val n = raw.size
        val i = ((weekIndex % n) + n) % n
        return resolve(raw[i.toInt()])
    }
}
