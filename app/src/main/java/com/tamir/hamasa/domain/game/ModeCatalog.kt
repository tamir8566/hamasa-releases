package com.tamir.hamasa.domain.game

/**
 * 24 workout modes. The first 8 are open from day one; the rest open by level (unlockLevel)
 * or drop from chests (fromChest). Ids are plain ascii snake_case and are stored in the DB,
 * so never rename an existing id.
 */
object ModeCatalog {
    /** Catalog as written: descriptions may carry {u:male|female} player tokens. Read modes through [all]. */
    private val raw: List<WorkoutMode> = listOf(
        // ---- open from the start -------------------------------------------------------------
        WorkoutMode("pr_day", "שיא היום", "בתרגיל הכבד הראשון הסט האחרון הוא ניסיון שיא: אותו משקל, כמה חזרות נקיות שאפשר. שאר האימון רגיל.", true),
        WorkoutMode("race_clock", "מרוץ נגד השעון", "אותו אימון, מנוחות קצרות יותר, ובסוף משווים לזמן של האימון הקודם מאותו סוג.", true, timeBased = true),
        WorkoutMode("one_more", "חזרה אחת יותר", "הסט האחרון בכל תרגיל עזר מקבל חזרה נוספת. התרגילים הכבדים לא נוגעים.", true),
        WorkoutMode("reverse", "אימון הפוך", "סדר התרגילים הפוך: מסיימים במה שבדרך כלל מתחילים בו.", true),
        WorkoutMode("supersets", "הכול סופר-סטים", "התרגילים מתבצעים בזוגות, אחד אחרי השני, בלי מנוחה ביניהם.", true),
        WorkoutMode("bodyweight", "הגוף", "רק משקל גוף. בלי ברזל, בלי מכונות, בלי תירוצים.", true, requiresGym = false),
        WorkoutMode("machines", "המכונה", "רק מכונות. היום הברזל החופשי נח.", true),
        WorkoutMode("silence", "השקט", "בלי אוזניות. רק {u:אתה|את}, הברזל, והמוזיקה של חדר הכושר. על הכבוד: מי שמסיים ככה מקבל 25 נקודות נוספות.", true),

        // ---- opened by level -----------------------------------------------------------------
        WorkoutMode("ladder", "הסולם", "בכל תרגיל עזר מתחילים קל עם יותר חזרות ועולים במשקל מסט לסט. כמו סולם, רק כבד.", false, unlockLevel = 2),
        WorkoutMode("fixed_weight", "המשקל הקבוע", "המשקל לא זז היום: כל תרגיל עזר נשאר במשקל של הפעם הקודמת בכל הסטים, ומוסיפים חזרות.", false, unlockLevel = 4),
        WorkoutMode("timer", "הטיימר", "כל סט נמשך זמן קבוע במקום מספר חזרות. השעון מחליט מתי נגמר.", false, unlockLevel = 6, timeBased = true),
        WorkoutMode("heavy_light", "הכבד והקל", "בתרגילי העזר: סט כבד ואחריו סט קל יותר עם יותר חזרות. ככה לסירוגין.", false, unlockLevel = 8),
        WorkoutMode("stranger", "הזר", "תרגיל אחד שמעולם לא עשית נכנס לאימון במקום תרגיל קבוע.", false, unlockLevel = 8),
        WorkoutMode("short_rest", "המנוחה הקצרה", "כל זמני המנוחה מתקצרים בחצי. נושמים פחות, מסיימים מהר.", false, unlockLevel = 10, timeBased = true),
        WorkoutMode("twin", "התאומים", "כל תרגיל עזר מתחלק לשתי גרסאות: חצי מהסטים ברגילה, וחצי בגרסה אחרת לאותו שריר.", false, unlockLevel = 12),
        WorkoutMode("marathon", "המרתון", "אימון ארוך: סט נוסף בכל תרגיל עזר, אבל עם מנוחות מלאות.", false, unlockLevel = 14, timeBased = true),

        // ---- drop from chests ----------------------------------------------------------------
        WorkoutMode("backwards", "האחורי", "בתרגילי העזר הסט הראשון הוא הכבד ביותר, ומשם המשקל יורד והחזרות עולות.", false, fromChest = true),
        WorkoutMode("pyramid", "הפירמידה", "בתרגילי העזר עולים במשקל עד הסט האמצעי ויורדים בחזרה. פסגה באמצע.", false, fromChest = true),
        WorkoutMode("tut", "זמן תחת מתח", "בתרגילי העזר כל חזרה איטית: שלוש שניות למטה, שלוש שניות למעלה. המשקל יורד, הסבל עולה.", false, fromChest = true),
        WorkoutMode("surprise", "ההפתעה", "הג'ים ברו מחליף תרגיל עזר אחד באמצע האימון בלי להגיד מראש איזה.", false, fromChest = true),
        WorkoutMode("perfect", "האימון המושלם", "האימון הכי אהוב עליך לפי הדירוגים: התרגילים של האימון שקיבל הכי הרבה כוכבים. נדיר.", false, fromChest = true),
        WorkoutMode("mirror", "המראה", "כל תרגיל חד-צדדי מתחיל מהצד החלש, והצד החזק עושה אותן חזרות, לא יותר. לפחות תרגיל עזר אחד יהיה חד-צדדי.", false, fromChest = true),
        WorkoutMode("minimalist", "המינימליסט", "שלושה תרגילים בלבד, אבל כל אחד מהם עד הסוף.", false, fromChest = true),
        WorkoutMode("joker", "ג'וקר", "{u:אתה בוחר|את בוחרת} את המצב של היום, מכל המצבים שפתחת. היום הכול שלך. אגדי.", false, fromChest = true)
    )

    /** Every mode, its description in the player's grammar (Addr). */
    val all: List<WorkoutMode>
        get() = raw.map { m -> if (m.descriptionHe.contains("{u:")) m.copy(descriptionHe = Addr.x(m.descriptionHe)) else m }

    fun byId(id: String): WorkoutMode? = all.firstOrNull { it.id == id }

    /** Modes that are open at [level] without any chest. */
    fun openAtLevel(level: Int): List<WorkoutMode> = all.filter { it.unlockedByDefault || (it.unlockLevel != null && it.unlockLevel <= level) }

    /** Modes that only ever come out of chests. */
    val chestOnly: List<WorkoutMode> get() = all.filter { it.fromChest }
}
