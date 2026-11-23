package com.tamir.hamasa.domain.game

import kotlin.random.Random

/** What Shambiuluk knows when he is tapped on Home. Facts only; never body weight. */
data class TapContext(
    val hour: Int,
    val streak: Int,
    val trainedToday: Boolean,
    val activeSession: Boolean,
    val bossNameHe: String,
    /** Remaining boss hp as a fraction 0..1. */
    val bossHpFrac: Float,
    val bossDefeated: Boolean,
    /** kcal goal minus today's meals; null = no meal logged today. */
    val kcalLeft: Int?,
    /** Steps today from the watch; null = no watch. */
    val steps: Int?,
    /** Today's mood 1..5, 0 = not chosen. */
    val mood: Int,
    /** ProfessionId name ("WARRIOR" ...), null = none. */
    val professionId: String?
)

/**
 * "לחיצה על שמביולוק": a context line for the Home bubble. A category is drawn first (so the big generic pool does
 * not drown the context), then a line in it that is not among [recent] ids. Same voice as [Voice]. No capped words.
 */
object TapVoice {

    private val morning = listOf(
        "בוקר. אני ער כבר שעה. מתאמן בראש. {u:אתה מוזמן|את מוזמנת} להצטרף בגוף.",
        "שעת בוקר. הקפה מתעורר. המשקולות עוד ישנות. נעיר אותן.",
        "בוקר טוב. {u:אל תדאג|אל תדאגי}, לא אצעק. היום.",
        "בוקר. המפלצת עוד במיטה. זה היתרון היחיד שלנו. מנצלים.",
        "השעה מוקדמת. הנחישות מאחרת. נחכה לה בדלת."
    )
    private val noon = listOf(
        "צהריים. השמש למעלה. גם אני. מדוע לא {u:אתה|את}.",
        "אמצע היום. נקודה מצוינת לעשות משהו. או לתכנן לעשות. עדיף הראשון.",
        "צהריים. המפלצת אוהבת את השעה הזאת. אני פחות.",
        "שעת צהריים. אני רק מזכיר שאני קיים. זה הכול. בינתיים."
    )
    private val evening = listOf(
        "ערב. היום עוד לא נגמר. הוא רק מתחפש לנגמר.",
        "ערב טוב. זו השעה שבה הספה מתחילה לדבר. {u:אל תענה|אל תעני}.",
        "ערב. יש עוד זמן. לא הרבה. מספיק.",
        "ערב. אני עדיין פה. ערני. חשוד."
    )
    private val night = listOf(
        "לילה. מדוע {u:אתה ער|את ערה}? גם אני. נעמיד פנים שלא ראינו.",
        "שעה מאוחרת. המשקולות ישנות. {u:גם אתה אמור|גם את אמורה}.",
        "לילה. המפלצת עובדת במשמרת הזאת. {u:אל תיתן|אל תתני} לה שעות נוספות.",
        "מאוחר. שינה היא אימון. הכי קל שיש. ועדיין מפספסים אותו."
    )
    private val trainedYes = listOf(
        "התאמנת היום. ראיתי. אני עדיין מעכל.",
        "האימון של היום בבנק. עכשיו מותר לך להיות סתם {u:בן אדם|בת אדם}.",
        "היום כבר הרמת. מכאן מרימים רק מזלג. ומים.",
        "יש אימון היום. אכן. הלחיצה הזאת לא מוסיפה נקודות. ניסיון יפה.",
        "עשית את שלך היום. שאר היום הוא בונוס. {u:תתנהג|תתנהגי} בהתאם."
    )
    private val trainedNoDay = listOf(
        "עוד לא התאמנת היום. לא שאלתי. רק ציינתי.",
        "היום עוד פתוח. האימון עוד סגור. מישהו צריך לפתוח אותו.",
        "אין אימון היום. עדיין. המילה החשובה פה היא עדיין.",
        "הברזל לא זז היום. הוא סבלני. אני פחות."
    )
    private val trainedNoEvening = listOf(
        "ערב בלי אימון. עשר דקות זה גם משהו. ומשהו זה הרבה.",
        "היום כמעט נגמר. אימון זעיר עוד מציל אותו. רק אומר.",
        "עוד לא זזנו היום. הליכה קצרה. אני אספור אותה כמו מרתון."
    )
    private val streakLow = listOf(
        "רצף של אחד. כל רצף התחיל ככה. גם הארוכים.",
        "הרצף עוד צעיר. נשמור עליו כמו על צמח. מים ואימונים.",
        "מתחילים מאפס. אפס זה מספר נקי. נלכלך אותו."
    )
    private val streakMid = listOf(
        "%d ימים ברצף. השרשרת מתחילה לצלצל.",
        "רצף של %d. עוד לא אגדה. כבר לא צירוף מקרים.",
        "%d ימים. אני מתחיל להאמין. {u:אל תספר|אל תספרי} לאף אחד."
    )
    private val streakBig = listOf(
        "%d ימים ברצף. זה כבר לא מקרה, זה אופי.",
        "רצף של %d. הספה הגישה תלונה. נדחתה.",
        "%d ימים. הידדדד. אני רושם בכתב מסולסל.",
        "%d ברצף. המפלצת שאלה {u:אם אתה|אם את} בסדר. אמרתי לה שיותר מבסדר."
    )
    private val bossAlive = listOf(
        "%s עוד עומד. בקושי שרוט. זה הולך להשתנות.",
        "%s מסתכל עלינו מהמפה. נחזיר לו מבט. עם משקולות.",
        "הבוס של השבוע, %s, עדיין מלא חיים. מצב זמני."
    )
    private val bossLow = listOf(
        "%s מדמם נקודות. עוד אימון והוא מתחיל להתנצל.",
        "%s כבר מתנדנד. אני שומע אותו מחפש מחסה.",
        "חצי מ%s כבר נעלם. החצי השני מודאג."
    )
    private val bossDown = listOf(
        "%s הובס השבוע. אני עדיין מחייך. בפנים.",
        "%s נפל. המפה שקטה. חשוד, אבל נעים.",
        "השבוע ניצחנו את %s. הידדדד. עדיין."
    )
    private val kcalPlenty = listOf(
        "נשארו %d קלוריות להיום. מספר, לא משימה.",
        "עוד %d קלוריות פתוחות. {u:תבחר|תבחרי} משהו שגם השרירים יאהבו.",
        "%d קלוריות בקופה. אני רק השומר. לא הקופאי."
    )
    private val kcalFew = listOf(
        "נשארו %d קלוריות. ארוחה קטנה אחת ונסגר היום.",
        "עוד %d. מתקרבים ליעד. בלי לחץ. עם מזלג."
    )
    private val kcalOver = listOf(
        "היעד עבר ב-%d קלוריות. נרשם. מחר הדף מתחיל מאפס.",
        "קצת מעבר ליעד היום, %d. יום אחד לא קובע מגמה. המגמה קובעת."
    )
    private val kcalNone = listOf(
        "עוד לא נרשמה ארוחה היום. הצלחת שלך עדיין סוד.",
        "אין ארוחות ביומן היום. לא שופט. רק סקרן."
    )
    private val stepsLow = listOf(
        "%d צעדים היום. השעון שואל אם הכול בסדר. הליכה קצרה תרגיע אותו.",
        "%d צעדים. הרגליים מחכות להוראות."
    )
    private val stepsMid = listOf(
        "%d צעדים עד עכשיו. יפה. עוד קצת והשעון יתחיל להתרשם.",
        "%d צעדים. המסע ממשיך גם מחוץ למכון."
    )
    private val stepsHigh = listOf(
        "%d צעדים. המפה רוצה לדעת {u:לאן אתה הולך|לאן את הולכת}.",
        "%d צעדים היום. השעון ביקש הפסקה. סירבתי בשמך."
    )
    private val moodLow = listOf(
        "היום כבד. בסדר. אנחנו לא מנצחים אותו, רק עוברים אותו.",
        "מצב רוח אפור נרשם. עם מזג אוויר לא מתווכחים. מביאים מטרייה.",
        "יום קשה. מספיק לי {u:שאתה|שאת} פה. לגמרי מספיק."
    )
    private val moodHigh = listOf(
        "מצב רוח טוב נרשם. נשתמש בו לפני שהוא נגמר.",
        "יום טוב. אני מרגיש את זה עד כאן. כאן זה בתוך המסך.",
        "{u:אתה|את} במצב רוח מעולה. הבוס של השבוע כבר קיבל הודעה."
    )
    private val professionLines: Map<String, List<String>> = mapOf(
        "WARRIOR" to listOf(
            "לוחם לא שואל אם יש אימון. הוא שואל היכן המוט.",
            "לוחם. ככה כתוב בתעודה. התעודה מחכה להוכחות."
        ),
        "WANDERER" to listOf(
            "נווד. המפה פתוחה. הרגליים שלך הן העט.",
            "נווד לא ממהר. הוא פשוט לא עוצר."
        ),
        "MONK" to listOf(
            "נזיר. שקט, עקבי, קצת מסתורי. בעיקר עקבי.",
            "נזיר לא מתווכח עם המפלצת. הוא מחכה שתתעייף."
        )
    )
    private val active = listOf(
        "יש אימון פתוח. אני באמצע משפט. {u:אתה|את} באמצע סט.",
        "האימון עוד רץ. אני לא הולך לשום מקום. גם {u:אתה|את} לא."
    )
    private val generic = listOf(
        "לחצת עליי. נרשם. לא ברור לי מה עושים עם זה.",
        "כן. אני כאן. תמיד. זה בחוזה.",
        "{u:אל תלחץ|אל תלחצי} חזק. אני עשוי מדיו.",
        "שאלת משהו? לא שמעתי. אבל התשובה היא אימון.",
        "אני עומד על צוק כל היום. זה פחות קל ממה שזה נראה.",
        "מדוע לחצת? {u:אל תענה|אל תעני}. אני יודע. געגועים.",
        "אני מסתכל על הנוף. הנוף מסתכל עליך. שנינו מחכים.",
        "עוד לחיצה ואני מתחיל לגבות דמי שימוש.",
        "אני לא קישוט. אני גיבור. קישוט לא מזיע.",
        "שקט פה. שקט מדי. מישהו צריך להרים משהו.",
        "היכן היינו? אה כן. במסע. הגדול.",
        "אני חושב על משקולות. גם כשאני לא חושב.",
        "נגעת בגיבור. אין לזה השלכות. בינתיים.",
        "לחיצה אחת. בסדר. שתיים זה כבר שיחה.",
        "יש לי תוכנית. היא כוללת אותך. ובעיקר אותך.",
        "סגור. שמעתי. אעשה עם זה משהו. אחר כך."
    )

    /** One line and its id ("category:index") for the recent-ids list. */
    data class Pick(val id: String, val text: String)

    fun pick(c: TapContext, recent: List<String>, rnd: Random = Random.Default): Pick {
        // category name -> (pool, placeholder value or null)
        val cats = ArrayList<Triple<String, List<String>, Any?>>()
        when (c.hour) {
            in 5..11 -> cats.add(Triple("morning", morning, null))
            in 12..16 -> cats.add(Triple("noon", noon, null))
            in 17..21 -> cats.add(Triple("evening", evening, null))
            else -> cats.add(Triple("night", night, null))
        }
        when {
            c.activeSession -> cats.add(Triple("active", active, null))
            c.trainedToday -> cats.add(Triple("trainedYes", trainedYes, null))
            c.hour in 17..21 -> cats.add(Triple("trainedNoEve", trainedNoEvening, null))
            c.hour in 6..16 -> cats.add(Triple("trainedNo", trainedNoDay, null))
        }
        when {
            c.streak >= 7 -> cats.add(Triple("streakBig", streakBig, c.streak))
            c.streak >= 2 -> cats.add(Triple("streakMid", streakMid, c.streak))
            else -> cats.add(Triple("streakLow", streakLow, null))
        }
        if (c.bossNameHe.isNotBlank()) {
            when {
                c.bossDefeated -> cats.add(Triple("bossDown", bossDown, c.bossNameHe))
                c.bossHpFrac <= 0.5f -> cats.add(Triple("bossLow", bossLow, c.bossNameHe))
                else -> cats.add(Triple("bossAlive", bossAlive, c.bossNameHe))
            }
        }
        val left = c.kcalLeft
        when {
            left == null -> if (c.hour in 11..21) cats.add(Triple("kcalNone", kcalNone, null))
            left < 0 -> cats.add(Triple("kcalOver", kcalOver, -left))
            left <= 600 -> cats.add(Triple("kcalFew", kcalFew, left))
            else -> cats.add(Triple("kcalPlenty", kcalPlenty, left))
        }
        val steps = c.steps
        if (steps != null && c.hour >= 10) {
            when {
                steps >= 10_000 -> cats.add(Triple("stepsHigh", stepsHigh, steps))
                steps >= 3_000 -> cats.add(Triple("stepsMid", stepsMid, steps))
                else -> cats.add(Triple("stepsLow", stepsLow, steps))
            }
        }
        when (c.mood) {
            1, 2 -> cats.add(Triple("moodLow", moodLow, null))
            4, 5 -> cats.add(Triple("moodHigh", moodHigh, null))
        }
        c.professionId?.let { id -> professionLines[id]?.let { cats.add(Triple("prof$id", it, null)) } }
        cats.add(Triple("generic", generic, null))

        // Prefer categories that still have a line not in [recent]; draw one, then a fresh line inside it.
        val fresh = cats.filter { (name, pool, _) -> pool.indices.any { "$name:$it" !in recent } }
        val (name, pool, arg) = (if (fresh.isNotEmpty()) fresh else cats).random(rnd)
        val candidates = pool.indices.filter { "$name:$it" !in recent }.ifEmpty { pool.indices.toList() }
        val idx = candidates.random(rnd)
        val raw = CharacterNames.g(pool[idx])
        val text = when (arg) {
            is Int -> if (raw.contains("%d")) raw.format(arg) else raw
            is String -> if (raw.contains("%s")) raw.format(arg) else raw
            else -> raw
        }
        return Pick("$name:$idx", text)
    }

    /** Hebrew part of day for the Gemini prompt. */
    fun partOfDayHe(hour: Int): String = when (hour) {
        in 5..11 -> "בוקר"
        in 12..16 -> "צהריים"
        in 17..21 -> "ערב"
        else -> "לילה"
    }
}
