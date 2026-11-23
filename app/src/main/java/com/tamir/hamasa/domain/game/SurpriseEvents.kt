package com.tamir.hamasa.domain.game

import kotlin.random.Random

/**
 * A small offer in the middle of a workout, right after an accessory set. Never about a compound lift.
 * [taskHe] may contain one %s for the name of the accessory exercise that was just done.
 * [needsGym] = needs dumbbells or a bar (not offered in mode "bodyweight").
 * [addsSet] = accepting adds one planned set to that accessory exercise.
 */
data class SurpriseEvent(
    val id: String,
    val difficulty: Int,
    val taskHe: String,
    val lineHe: String,
    val needsGym: Boolean = false,
    val addsSet: Boolean = false
) {
    val xp: Int get() = when (difficulty) { 1 -> 20; 2 -> 40; else -> 60 }
    val gold: Int get() = when (difficulty) { 1 -> 5; 2 -> 12; else -> 20 }
    val isHard: Boolean get() = difficulty >= 3

    fun task(exerciseNameHe: String): String {
        val t = Addr.x(taskHe)
        return if (t.contains("%s")) t.format(exerciseNameHe) else t
    }

    fun difficultyHe(): String = when (difficulty) { 1 -> "קל"; 2 -> "בינוני"; else -> "קשה" }
}

/**
 * Spec: about one event per 3 to 4 workouts, after a random accessory set, at most one per session.
 * Rewards: 20 / 40 / 60 xp and 5 / 12 / 20 gold by difficulty; a completed hard event has a 25% chance
 * of a rare extra (a shield or a chest).
 */
object SurpriseEvents {

    const val RARE_EXTRA_CHANCE = 0.25

    val all: List<SurpriseEvent> = listOf(
        // ---- easy
        SurpriseEvent("plank30", 1, "פלאנק 30 שניות. עכשיו, לפני המנוחה.", "הצעה קטנה. שלושים שניות. אף אחד לא נפגע מפלאנק. כמעט."),
        SurpriseEvent("wall_sit45", 1, "ישיבה על הקיר, 45 שניות.", "יש קיר. יש אותך. יש הצעה."),
        SurpriseEvent("half_weight10", 1, "עוד 10 חזרות ב%s, בחצי מהמשקל.", "חצי משקל. כבוד שלם. עסקה הוגנת."),
        SurpriseEvent("dead_hang20", 1, "תלייה חופשית על מוט מתח, 20 שניות.", "{u:תלה|תלי} את עצמך על מוט. בכבוד. עשרים שניות.", needsGym = true),
        // ---- medium
        SurpriseEvent("plus5", 2, "עוד 5 חזרות ב%s, עכשיו, באותו משקל.", "עוד חמש. לא שאלה. הצעה. עם פרס."),
        SurpriseEvent("extra_set", 2, "סט נוסף ב%s. הוא נכנס לרשימה ברגע {u:שתסכים|שתסכימי}.", "עוד סט אחד. הוא כבר מחכה בתור.", addsSet = true),
        SurpriseEvent("farmer60", 2, "החזקת חקלאי: שתי משקולות כבדות, 60 שניות, גב זקוף.", "{u:תחזיק|תחזיקי} משהו כבד. דקה. זה הכול.", needsGym = true),
        SurpriseEvent("slow_neg", 2, "עוד 5 חזרות ב%s, ירידה של 4 שניות בכל אחת.", "לאט. לאט מאוד. המפלצת שונאת את זה."),
        SurpriseEvent("iso20", 2, "החזקה סטטית ב%s: 20 שניות באמצע התנועה, באותו משקל.", "לא זזים. מחזיקים. הברזל מתבלבל."),
        // ---- hard
        SurpriseEvent("drop_set", 3, "סט ירידה ב%s: מיד עכשיו, 20 אחוז פחות משקל, עד שנשארות 2 חזרות ברזרבה.", "סט ירידה. נשמע מפחיד. הוא באמת מפחיד. הפרס בהתאם."),
        SurpriseEvent("plank60", 3, "פלאנק 60 שניות. בלי לרדת על הברכיים.", "דקה שלמה. אני סופר, {u:אתה מחזיק|את מחזיקה}. חלוקת עבודה הוגנת."),
        SurpriseEvent("farmer2x40", 3, "הליכת חקלאי: שני סיבובים של 40 שניות עם משקולות כבדות, 30 שניות מנוחה ביניהם.", "הולכים עם ברזל. פעמיים. זו לא הליכה, זו הצהרה.", needsGym = true)
    )

    fun byId(id: String): SurpriseEvent? = all.firstOrNull { it.id == id }

    /**
     * Chance that a gym session gets an event, by gym sessions since the last one (Tamir: "one every workout or
     * every second workout"): 50% right after an event, then certain. So never two sessions in a row without one.
     */
    fun sessionChance(sessionsSinceEvent: Int): Double = if (sessionsSinceEvent <= 0) 0.5 else 1.0

    fun pick(modeId: String?, rnd: Random = Random.Default): SurpriseEvent {
        val pool = if (modeId == "bodyweight") all.filter { !it.needsGym } else all
        return pool[rnd.nextInt(pool.size)]
    }
}
