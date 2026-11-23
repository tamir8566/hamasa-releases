package com.tamir.hamasa.domain.game

import java.time.LocalDate

/** Facts the badge evaluator can look at. Built by GameRepository after each event. */
data class GameFacts(
    val doneSessions: Int,
    val gymSessions: Int,
    val yogaSessions: Int,
    val streak: Int,
    val bestStreak: Int,
    val prCount: Int,
    val prsThisSession: Int,
    val lastSessionDurationSec: Int,
    val lastSessionStartHour: Int,
    val lastSessionSleepHours: Float?,
    val lastSessionVolumeKg: Double,
    val lifetimeKg: Double,
    val distinctModesDone: Int,
    val bossesDefeated: Int,
    val mealsDaysLogged: Int,
    val returnedAfterDaysOff: Int,
    val lastSessionIsWeekend: Boolean,
    val lastSessionDayOfWeek: Int,     // 1=Monday..7=Sunday
    val branchLevels: Map<String, Int>,
    val newExerciseInLastSession: Boolean,
    val allRestsKept: Boolean,
    val monthlyYogaCount: Int,
    val level: Int,
    // --- stage 1 C (defaults keep older call sites compiling) ---
    /** Mode of the session that was just finished (null = none / not a session event). */
    val lastSessionModeId: String? = null,
    /** modeId -> number of finished sessions in that mode. */
    val modeCounts: Map<String, Int> = emptyMap(),
    /** Chests whose contents were applied (opened). */
    val chestsOpened: Int = 0,
    /** Consecutive days with a weigh-in, ending on the latest weigh-in day. */
    val weighInStreak: Int = 0,
    /** Saved progress photos (filled by the photo feature; 0 until then). */
    val progressPhotos: Int = 0,
    // --- stage 2 F ---
    /** Photo meals saved with total kcal within 10% of the AI estimate (Prefs.goodGuessMeals). */
    val goodGuessMeals: Int = 0,
    // --- coach season ---
    /** Weekly challenges completed, ever (Prefs.challengesCompleted). */
    val challengesCompleted: Int = 0
)

/**
 * 76 badges on 4 sheets (res/drawable-nodpi badges_1..badges_4), index 0-based row-major.
 *
 * Sheet geometry (cols x rows):
 *  1: 6x3  (18 cells, all used)
 *  2: 6x4  (24 cells; 0, 5, 17, 23 are duplicates and unused)       -> 20 badges
 *  3: 6x4  (24 cells; 5, 11, 17, 22 are duplicates/unused)          -> 20 badges
 *  4: 6x3  (18 secret badges, every cell is a locked "?" shape)      -> 18 badges
 *
 * Conditions that cannot be computed from [GameFacts] are listed in [unreachable] and are never
 * returned by [evaluate]; the integrator can unlock them directly via GameRepository.unlockBadge
 * when the relevant event happens (mode finished, chest opened, weigh-in, photo, reminder...).
 */
object BadgeCatalog {
    /** sheet number -> (columns, rows). Used by the UI to crop cells. */
    val sheetGrid: Map<Int, Pair<Int, Int>> = mapOf(
        1 to (6 to 3),
        2 to (6 to 4),
        3 to (6 to 4),
        4 to (6 to 3)
    )

    private val C = Rarity.COMMON
    private val R = Rarity.RARE
    private val L = Rarity.LEGENDARY

    val all: List<BadgeDef> = listOf(
        // ======================= sheet 1 (6x3) =======================
        BadgeDef("early_bird", "ציפור ראשונה", "לפני שהעיר מתעוררת. לידיעתך, גם לפני השמש.", C, hidden = false, iconSheet = 1, iconIndex = 0),
        BadgeDef("night_owl_reverse", "עוף לילה הפוך", "יש אנשים שהולכים לישון בשעה הזאת. {u:אתה הלכת|את הלכת} לאימון.", R, iconSheet = 1, iconIndex = 1),
        BadgeDef("first_blood", "הפגיון", "משהו קורה למי שמתחיל. הבינותי שהתחלת.", C, hidden = false, iconSheet = 1, iconIndex = 2),
        BadgeDef("reverse_day", "יום הפוך", "מסיימים במה שמתחילים בו. מדוע? כי ככה.", C, iconSheet = 1, iconIndex = 3),
        BadgeDef("fifth", "החמישית", "חמש פעמים. זה כבר לא מקרה, זו מדיניות.", C, hidden = false, iconSheet = 1, iconIndex = 4),
        BadgeDef("yogi", "היוגי", "עשר פעמים על המזרן. הגמישות עדיין בבדיקה.", R, iconSheet = 1, iconIndex = 5),
        BadgeDef("two_birds", "שתי ציפורים", "ברזל וגם מזרן. שתי ציפורים, אפס אבנים.", C, iconSheet = 1, iconIndex = 6),
        BadgeDef("patient", "הסבלן", "כל מנוחה נשמרה עד השנייה. סדר כזה אני לא רגיל לראות.", R, iconSheet = 1, iconIndex = 7),
        BadgeDef("prodigal_son", "חזרת {u:הבן האובד|הבת האובדת}", "שבוע נעלמת. חזרת. לא שואל שאלות.", R, iconSheet = 1, iconIndex = 8),
        BadgeDef("philosopher", "{u:הפילוסוף|הפילוסופית}", "שמונה מזרנים בחודש אחד. הגעת לשלווה. או משהו.", R, iconSheet = 1, iconIndex = 9),
        BadgeDef("right_guess", "הניחוש הנכון", "חמש צלחות, והניחוש החזיק. רק אומר: מזל, לא כישרון.", R, iconSheet = 1, iconIndex = 10),
        BadgeDef("full_week", "שבוע מלא", "שבעה ימים ברצף. השבוע נכנע.", C, hidden = false, iconSheet = 1, iconIndex = 11),
        BadgeDef("hundred", "מאה", "מאה אימונים. {u:האיש והאגדה|האישה והאגדה}. אין לי תוספות.", L, hidden = false, iconSheet = 1, iconIndex = 12),
        BadgeDef("the_rock", "הסלע", "שלושים ימי רצף. הסלע לא זז, הסלע מתאמן.", R, iconSheet = 1, iconIndex = 13),
        BadgeDef("the_car", "המכונית", "סך הכול הרמת משקל של כמה מכוניות. הרמת. לא נהגת.", R, iconSheet = 1, iconIndex = 14),
        BadgeDef("first_ton", "הטונה הראשונה", "אלף קילו הצטברו. טונה. אחת. ראשונה.", C, hidden = false, iconSheet = 1, iconIndex = 15),
        BadgeDef("mode_collector", "{u:אספן|אספנית} המצבים", "עשרה מצבי אימון שונים. אוסף מכובד, אם {u:תשאל|תשאלי} אותי. לא שאלת.", R, iconSheet = 1, iconIndex = 16),
        BadgeDef("balanced_branch", "הענף המאוזן", "אף ענף לא נשאר מאחור. העץ מרוצה. אני כמעט.", R, iconSheet = 1, iconIndex = 17),

        // ======================= sheet 2 (6x4) =======================
        BadgeDef("night_lifter", "אימון לילה", "ברזל אחרי החושך. הירח ראה הכול.", C, iconSheet = 2, iconIndex = 1),
        BadgeDef("quick_one", "הזריז", "נכנסת, הרמת, יצאת. מתחת לחצי שעה. מרשים, חשוד.", C, iconSheet = 2, iconIndex = 2),
        BadgeDef("summit", "הפסגה", "רמה עשר. יש דגל על ההר. של מי? שלך.", R, hidden = false, iconSheet = 2, iconIndex = 3),
        BadgeDef("chain_breaker", "{u:שובר|שוברת} השרשרת", "שבועיים של כלום, ואז חזרת. השרשרת נשברה. לטובה.", R, iconSheet = 2, iconIndex = 4),
        BadgeDef("phoenix", "עוף החול", "חודש של שקט, ואז קמת מהאפר. הידד. בשקט, אבל הידד.", L, iconSheet = 2, iconIndex = 6),
        BadgeDef("snail", "החילזון", "שעה וחצי באימון אחד. לאט. אבל הגעת.", C, iconSheet = 2, iconIndex = 7),
        BadgeDef("lightning", "הברק", "שלושה שיאים באימון אחד. ברק פגע שלוש פעמים. סטטיסטית בלתי אפשרי.", R, iconSheet = 2, iconIndex = 8),
        BadgeDef("no_couch", "לא הספה", "ארבעה-עשר ימים שהספה לא ניצחה. הספה מתלוננת.", C, iconSheet = 2, iconIndex = 9),
        BadgeDef("steak", "הסטייק", "שבוע של רישום ארוחות. אכן, גם אוכל זה חלק מהמסע.", C, iconSheet = 2, iconIndex = 10),
        BadgeDef("first_trophy", "הגביע הראשון", "השיא הראשון נשבר. הגביע קטן, הכוונה גדולה.", C, hidden = false, iconSheet = 2, iconIndex = 11),
        BadgeDef("camera", "המצלמה", "תמונת התקדמות. הראיות תועדו.", C, iconSheet = 2, iconIndex = 12),
        BadgeDef("sweat", "הטיפה", "עשרים וחמישה אימוני מכון. הזיעה הצטברה. הרצפה מעידה.", R, iconSheet = 2, iconIndex = 13),
        BadgeDef("well_slept", "השינה הטובה", "שמונה שעות שינה ואז אימון. המפלצת לא הוזמנה.", C, iconSheet = 2, iconIndex = 14),
        BadgeDef("first_stretch", "ההתמתחות", "המזרן הראשון. הגוף שאל שאלות. לא ענית.", C, hidden = false, iconSheet = 2, iconIndex = 15),
        BadgeDef("backpack", "התרמיל", "עשרים וחמישה אימונים. התרמיל ארוז. המסע באמת התחיל.", C, hidden = false, iconSheet = 2, iconIndex = 16),
        BadgeDef("journey", "המסע", "רמה חמש. המצפן מצביע קדימה. תמיד קדימה.", C, iconSheet = 2, iconIndex = 18),
        BadgeDef("pr_king", "{u:מלך|מלכת} השיאים", "עשרים וחמישה שיאים. גביע הזהב. {u:האיש והאגדה|האישה והאגדה}.", L, iconSheet = 2, iconIndex = 19),
        BadgeDef("crown", "הכתר", "רמה עשרים. הכתר מונח. אינני משתחווה, אבל כמעט.", L, iconSheet = 2, iconIndex = 20),
        BadgeDef("first_boss", "הבוס הראשון", "הבוס הראשון נפל. הגולגולת שלו למזכרת.", R, hidden = false, iconSheet = 2, iconIndex = 21),
        BadgeDef("persistent", "המתמיד", "שישים ימי רצף. שעון החול התייאש ממך.", L, iconSheet = 2, iconIndex = 22),

        // ======================= sheet 3 (6x4) =======================
        BadgeDef("sprout", "הנבט", "שלושה אימונים. משהו נבט. אני מסתכל בחשדנות.", C, hidden = false, iconSheet = 3, iconIndex = 0),
        BadgeDef("rain", "הגשם", "אימון בחודש הגשום ביותר. בחוץ רטוב, בפנים ברזל.", C, seasonMonth = 1, iconSheet = 3, iconIndex = 1),
        BadgeDef("snowflake", "השלג", "אימון באמצע החורף. הידיים קפואות, המשקולת לא.", C, seasonMonth = 2, iconSheet = 3, iconIndex = 2),
        BadgeDef("summer_sun", "השמש", "אימון בשיא הקיץ. המזגן ניצח, אבל גם {u:אתה|את}.", C, seasonMonth = 7, iconSheet = 3, iconIndex = 3),
        BadgeDef("first_chest", "התיבה הראשונה", "התיבה הראשונה נפתחה. מה היה בפנים? פרוטוקול.", C, iconSheet = 3, iconIndex = 4),
        BadgeDef("dice", "הקובייה", "חמישה מצבי אימון שונים. הקובייה התגלגלה. ההימור השתלם.", C, iconSheet = 3, iconIndex = 6),
        BadgeDef("joker_badge", "הג'וקר", "האימון שבו {u:אתה|את} קבעת הכול. ואני שתקתי. זה נרשם.", L, iconSheet = 3, iconIndex = 7),
        BadgeDef("heart", "הלב", "עשרים וחמישה מזרנים. הלב רגוע. הנשימה גם.", R, iconSheet = 3, iconIndex = 8),
        BadgeDef("scale", "המשקל", "עלית על המשקל שבעה בקרים ברצף. {u:אמיץ|אמיצה}. גם אם המספר לא.", C, iconSheet = 3, iconIndex = 9),
        BadgeDef("bicep", "הזרוע", "ענף הכתפיים והידיים ברמה שלוש. השרוול התחיל להתלונן.", R, iconSheet = 3, iconIndex = 10),
        BadgeDef("leg", "הרגל", "ענף הרגליים ברמה שלוש. המדרגות מפחדות.", R, iconSheet = 3, iconIndex = 12),
        BadgeDef("spine", "עמוד השדרה", "ענף הליבה ברמה שלוש. הגב ישר. הגאווה גם.", R, iconSheet = 3, iconIndex = 13),
        BadgeDef("lungs", "הריאות", "ענף הסיבולת ברמה שלוש. הנשימה נשארת. הידד.", R, iconSheet = 3, iconIndex = 14),
        BadgeDef("fire", "האש", "שלושים ימי רצף. האש דולקת. אינני כבאי.", R, iconSheet = 3, iconIndex = 15),
        BadgeDef("wolf", "הזאב", "חמישים אימוני מכון. הזאב מיילל. הברזל שותק.", R, iconSheet = 3, iconIndex = 16),
        BadgeDef("owl", "הינשוף", "אימון בשעות שבהן רק ינשופים ערים. {u:ואתה|ואת}.", R, iconSheet = 3, iconIndex = 18),
        BadgeDef("key", "המפתח", "רמה חמש-עשרה. דלתות נפתחות. לא שואל אילו.", R, iconSheet = 3, iconIndex = 19),
        BadgeDef("map_scroll", "המפה", "חמישה בוסים הובסו. המפה מתמלאת. הבוסים פחות.", L, iconSheet = 3, iconIndex = 20),
        BadgeDef("saturday_morning", "שבת בבוקר", "שבת. בוקר. מכון. כל השאר ישנים. {u:אתה|את} לא.", C, iconSheet = 3, iconIndex = 21),
        BadgeDef("mirror", "המראה", "כל הענפים ברמה שלוש לפחות. המראה הסכימה. לראשונה.", L, iconSheet = 3, iconIndex = 23),

        // ======================= sheet 4 (6x3) secret =======================
        BadgeDef("superset_king", "{u:מלך|מלכת} הסופר-סט", "אימון שלם של זוגות. בלי מנוחה. בלי רחמים.", R, iconSheet = 4, iconIndex = 0),
        BadgeDef("midnight", "חצות", "אימון שהתחיל בדיוק בחצות. מדוע? אין לי מושג. הידד.", R, iconSheet = 4, iconIndex = 1),
        BadgeDef("iron_will", "רצון ברזל", "פחות מחמש שעות שינה ובכל זאת באת. המפלצת הפסידה.", R, iconSheet = 4, iconIndex = 2),
        BadgeDef("double_pr", "שיא כפול", "שני שיאים באימון אחד. פעמיים. באותו יום.", C, iconSheet = 4, iconIndex = 3),
        BadgeDef("five_pr", "חמישה שיאים", "חמישה שיאים באימון אחד. רצח. אין מילה אחרת.", L, iconSheet = 4, iconIndex = 4),
        BadgeDef("thousand_tons", "אלף טון", "מיליון קילו הצטברו. אלף טונות. ברמה לא נורמלית.", L, iconSheet = 4, iconIndex = 5),
        BadgeDef("fifty", "חמישים", "חמישים אימונים. חצי הדרך למאה. או כפול עשרים וחמש.", R, iconSheet = 4, iconIndex = 6),
        BadgeDef("two_hundred", "מאתיים", "מאתיים אימונים. אין לי מה להגיד. זה נדיר אצלי.", L, iconSheet = 4, iconIndex = 7),
        BadgeDef("new_year", "השנה החדשה", "אימון בחודש של ראש השנה. התחלות, תפוחים, ברזל.", C, seasonMonth = 9, iconSheet = 4, iconIndex = 8),
        BadgeDef("purim", "המסכה", "אימון בחודש של פורים. בלי תחפושת. הגוף מספיק.", C, seasonMonth = 3, iconSheet = 4, iconIndex = 9),
        BadgeDef("freedom", "החירות", "אימון בחודש של חג החירות. יצאת מהספה לחופשי.", C, seasonMonth = 4, iconSheet = 4, iconIndex = 10),
        BadgeDef("light", "האור", "אימון בחודש של חג האורים. נר אחד, סט אחד.", C, seasonMonth = 12, iconSheet = 4, iconIndex = 11),
        BadgeDef("elephant", "הפיל", "משקל של פיל באימון אחד. הפיל לא ידע. עכשיו הוא יודע.", R, iconSheet = 4, iconIndex = 12),
        BadgeDef("whale", "הלווייתן", "משקל של לווייתן באימון אחד. הים שותק. אני גם.", L, iconSheet = 4, iconIndex = 13),
        BadgeDef("all_modes", "כל המצבים", "כל מצב אימון שקיים בוצע לפחות פעם אחת. האוסף שלם.", L, iconSheet = 4, iconIndex = 14),
        BadgeDef("hundred_days", "מאה ימים", "מאה ימי רצף. לא מבין איך. {u:האיש והאגדה|האישה והאגדה}, אכן.", L, iconSheet = 4, iconIndex = 15),
        BadgeDef("boss_hunter", "{u:צייד|ציידת} הבוסים", "עשרה בוסים הובסו. הבוסים פתחו קבוצת תמיכה.", L, iconSheet = 4, iconIndex = 16),
        BadgeDef("the_legend", "האיש והאגדה", "רמה שלושים. אין מה להוסיף. {u:האיש|האישה}. והאגדה.", L, iconSheet = 4, iconIndex = 17),

        // ======================= coach season: weekly challenges (facts) and seasonal event bosses (direct) =======================
        // Icons reuse unused duplicate cells (sheet 2 cell 0, sheet 3 cells 5 and 22) or the matching holiday icon.
        BadgeDef("challenge_4", "{u:צייד|ציידת} המשימות", "ארבעה אתגרים שבועיים. מישהו כאן אוהב משימות. חשוד.", R, iconSheet = 2, iconIndex = 0),
        BadgeDef("challenge_10", "{u:אדון|גבירת} האתגרים", "עשרה אתגרים שבועיים. האתגרים מתחילים לפחד. בצדק.", L, iconSheet = 3, iconIndex = 5),
        BadgeDef("event_hanukkah", "הנר השמיני", "מכשפת הסופגניות הובסה בחנוכה. שמונה נרות, אפס סופגניות. כמעט.", R, iconSheet = 4, iconIndex = 11),
        BadgeDef("event_purim", "בלי מסכה", "גובלין המסכות הובס בפורים. התחפושת שלך: {u:אדם מתאמן|אישה מתאמנת}. משכנע.", R, iconSheet = 4, iconIndex = 9),
        BadgeDef("event_pesach", "יציאת הספה", "דרקון החמץ הובס בפסח. יצאת לחירות. עם משקולות.", R, iconSheet = 4, iconIndex = 10),
        BadgeDef("event_independence", "עצמאות מהמנגל", "ענק המנגל הובס ביום העצמאות. הקציצה בטוחה. בערך.", R, iconSheet = 3, iconIndex = 22),
        BadgeDef("event_tishrei", "שנה חדשה, אותו ברזל", "האני של השנה שעברה הובס בחגי תשרי. הוא לא יחזור. עד השנה הבאה.", R, iconSheet = 4, iconIndex = 8),
        BadgeDef("event_summer", "נמס", "עוג החום הובס בשיא הקיץ. הוא נמס. {u:אתה|את} רק הזעת.", R, iconSheet = 3, iconIndex = 3)
    )

    /**
     * Badges whose condition cannot be derived from [GameFacts]. They are never returned by
     * [evaluate]; unlock them from the feature that owns the event:
     *  (none left; right_guess became reachable in stage 2 F)
     *
     * Wired through facts in stage 2 F:
     *  right_guess     - [RIGHT_GUESS_MEALS] photo meals saved within 10% of the AI kcal estimate (goodGuessMeals)
     *
     * Wired through facts in stage 1:
     *  reverse_day     - a session finished in mode "reverse"            (lastSessionModeId)
     *  camera          - a progress photo was saved                       (progressPhotos >= 1)
     *  first_chest     - the first chest was opened                       (chestsOpened >= 1, also unlocked directly)
     *  joker_badge     - a session finished in mode "joker"               (modeCounts)
     *  scale           - 7 consecutive days with a weigh-in               (weighInStreak, also checked on weigh-in)
     *  superset_king   - [SUPERSET_SESSIONS] sessions in mode "supersets" (modeCounts)
     */
    val unreachable: Set<String> = emptySet()

    /** Photo meals within 10% of the AI estimate needed for right_guess. */
    const val RIGHT_GUESS_MEALS = 5

    /** Finished "supersets" sessions needed for superset_king. */
    const val SUPERSET_SESSIONS = 3

    /**
     * How far along a numeric badge is, 0..1 (null = not a counting badge, no early hint).
     * Used by the badges screen to open a vague hint at half the condition (30% from level 9).
     */
    fun progress(id: String, f: GameFacts): Float? {
        fun r(have: Number, need: Number): Float {
            val n = need.toDouble()
            if (n <= 0.0) return 0f
            return (have.toDouble() / n).coerceIn(0.0, 1.0).toFloat()
        }
        fun branch(name: String): Int = f.branchLevels[name] ?: 0
        val allBranches = listOf("CHEST", "BACK", "LEGS", "SHOULDERS_ARMS", "CORE", "ENDURANCE")
        val minBranch = allBranches.minOf { branch(it) }
        return when (id) {
            "sprout" -> r(f.doneSessions, 3)
            "fifth" -> r(f.doneSessions, 5)
            "backpack" -> r(f.doneSessions, 25)
            "fifty" -> r(f.doneSessions, 50)
            "hundred" -> r(f.doneSessions, 100)
            "two_hundred" -> r(f.doneSessions, 200)
            "sweat" -> r(f.gymSessions, 25)
            "wolf" -> r(f.gymSessions, 50)
            "yogi" -> r(f.yogaSessions, 10)
            "heart" -> r(f.yogaSessions, 25)
            "philosopher" -> r(f.monthlyYogaCount, 8)
            "full_week" -> r(f.streak, 7)
            "no_couch" -> r(f.streak, 14)
            "fire" -> r(f.streak, 30)
            "the_rock" -> r(f.bestStreak, 30)
            "persistent" -> r(f.bestStreak, 60)
            "hundred_days" -> r(f.bestStreak, 100)
            "first_ton" -> r(f.lifetimeKg, 1_000.0)
            "the_car" -> r(f.lifetimeKg, 50_000.0)
            "thousand_tons" -> r(f.lifetimeKg, 1_000_000.0)
            "dice" -> r(f.distinctModesDone, 5)
            "mode_collector" -> r(f.distinctModesDone, 10)
            "all_modes" -> r(f.distinctModesDone, ModeCatalog.all.size)
            "pr_king" -> r(f.prCount, 25)
            "map_scroll" -> r(f.bossesDefeated, 5)
            "boss_hunter" -> r(f.bossesDefeated, 10)
            "journey" -> r(f.level, 5)
            "summit" -> r(f.level, 10)
            "key" -> r(f.level, 15)
            "crown" -> r(f.level, 20)
            "the_legend" -> r(f.level, 30)
            "steak" -> r(f.mealsDaysLogged, 7)
            "right_guess" -> r(f.goodGuessMeals, RIGHT_GUESS_MEALS)
            "challenge_4" -> r(f.challengesCompleted, 4)
            "challenge_10" -> r(f.challengesCompleted, 10)
            "superset_king" -> r(f.modeCounts["supersets"] ?: 0, SUPERSET_SESSIONS)
            "scale" -> r(f.weighInStreak, 7)
            "bicep" -> r(branch("SHOULDERS_ARMS"), 3)
            "leg" -> r(branch("LEGS"), 3)
            "spine" -> r(branch("CORE"), 3)
            "lungs" -> r(branch("ENDURANCE"), 3)
            "balanced_branch" -> r(minBranch, 2)
            "mirror" -> r(minBranch, 3)
            else -> null
        }
    }

    /** Progress at which the vague hint opens: half the condition, 30% from level 9. */
    fun hintThreshold(level: Int): Float = if (level >= 9) 0.3f else 0.5f

    /** The vague hint that opens at [hintThreshold]. Deliberately less clear than [BadgeDef.hintHe]. */
    fun vagueHintHe(id: String): String = when (id) {
        "sprout", "fifth", "backpack", "fifty", "hundred", "two_hundred" -> "משהו כאן סופר הופעות. חלק מהספירה כבר מאחוריך."
        "sweat", "wolf" -> "הברזל סופר אותך. לא מגלה עד כמה."
        "yogi", "heart", "philosopher" -> "המזרן זוכר כל פעם. הוא סבלני. יותר ממני."
        "full_week", "no_couch", "fire", "the_rock", "persistent", "hundred_days" -> "ימים רצופים. אמרתי יותר מדי."
        "first_ton", "the_car", "thousand_tons" -> "משהו כבד מצטבר כאן. קילו אחרי קילו."
        "dice", "mode_collector", "all_modes" -> "הקובייה מתגלגלת. כמה מהפרצופים שלה עוד חסרים."
        "pr_king" -> "שיאים נאספים במקום חשוך. חלק כבר שם."
        "map_scroll", "boss_hunter" -> "הבוסים מדברים עליך. בשקט. בינתיים."
        "journey", "summit", "key", "crown", "the_legend" -> "המדרגה הזאת תגיע. היא כבר רואה אותך."
        "steak" -> "גם הצלחת היא חלק מהסיפור. נרשם."
        "right_guess" -> "המספר על הצלחת והמספר בראש מתקרבים. עוד כמה פעמים."
        "challenge_4", "challenge_10" -> "משימות שבועיות נאספות. חלק כבר בכיס."
        "superset_king" -> "זוגות. בלי הפסקה ביניהם. עוד קצת."
        "scale" -> "משהו מחכה לך כל בוקר על הרצפה. {u:אתה כמעט תמיד בא|את כמעט תמיד באה}."
        else -> "ענף אחד צומח לכיוון הזה. או כמה."
    }

    fun byId(id: String): BadgeDef? = all.firstOrNull { it.id == id }

    fun bySheet(sheet: Int): List<BadgeDef> = all.filter { it.iconSheet == sheet }

    fun rarityNameHe(r: Rarity): String = when (r) {
        Rarity.COMMON -> "רגיל"
        Rarity.RARE -> "נדיר"
        Rarity.LEGENDARY -> "אגדי"
    }

    /** Returns ids of badges that are newly satisfied by these facts. [month] is 1..12 (today). */
    fun evaluate(facts: GameFacts, alreadyUnlocked: Set<String>): List<String> =
        evaluate(facts, alreadyUnlocked, LocalDate.now().monthValue)

    fun evaluate(facts: GameFacts, alreadyUnlocked: Set<String>, month: Int): List<String> {
        val out = mutableListOf<String>()
        fun check(id: String, cond: Boolean) { if (cond && id !in alreadyUnlocked && out.none { it == id }) out += id }
        fun branch(name: String): Int = facts.branchLevels[name] ?: 0
        val hasSession = facts.doneSessions >= 1
        val hour = facts.lastSessionStartHour
        val minutes = facts.lastSessionDurationSec / 60
        val sleep = facts.lastSessionSleepHours
        val allBranches = listOf("CHEST", "BACK", "LEGS", "SHOULDERS_ARMS", "CORE", "ENDURANCE")

        // ---- sheet 1 ----
        check("early_bird", hasSession && hour in 4..6)
        check("night_owl_reverse", hasSession && (hour == 23 || hour == 0 || hour == 1))
        check("first_blood", facts.doneSessions >= 1)
        check("fifth", facts.doneSessions >= 5)
        check("yogi", facts.yogaSessions >= 10)
        check("two_birds", facts.gymSessions >= 1 && facts.yogaSessions >= 1)
        check("patient", hasSession && facts.allRestsKept && minutes >= 45)
        check("prodigal_son", facts.returnedAfterDaysOff >= 7)
        check("philosopher", facts.monthlyYogaCount >= 8)
        check("full_week", facts.streak >= 7)
        check("hundred", facts.doneSessions >= 100)
        check("the_rock", facts.bestStreak >= 30)
        check("the_car", facts.lifetimeKg >= 50_000.0)
        check("first_ton", facts.lifetimeKg >= 1_000.0)
        check("mode_collector", facts.distinctModesDone >= 10)
        check("balanced_branch", allBranches.all { branch(it) >= 2 })

        // ---- sheet 2 ----
        check("night_lifter", hasSession && hour in 20..22)
        check("quick_one", hasSession && facts.lastSessionDurationSec in 60..(30 * 60))
        check("summit", facts.level >= 10)
        check("chain_breaker", facts.returnedAfterDaysOff >= 14)
        check("phoenix", facts.returnedAfterDaysOff >= 30)
        check("snail", hasSession && minutes >= 90)
        check("lightning", facts.prsThisSession >= 3)
        check("no_couch", facts.streak >= 14)
        check("steak", facts.mealsDaysLogged >= 7)
        check("first_trophy", facts.prCount >= 1)
        check("sweat", facts.gymSessions >= 25)
        check("well_slept", hasSession && sleep != null && sleep >= 8f)
        check("first_stretch", facts.yogaSessions >= 1)
        check("backpack", facts.doneSessions >= 25)
        check("journey", facts.level >= 5)
        check("pr_king", facts.prCount >= 25)
        check("crown", facts.level >= 20)
        check("first_boss", facts.bossesDefeated >= 1)
        check("persistent", facts.bestStreak >= 60)

        // ---- sheet 3 ----
        check("sprout", facts.doneSessions >= 3)
        check("rain", hasSession && month == 1)
        check("snowflake", hasSession && month == 2)
        check("summer_sun", hasSession && month == 7)
        check("dice", facts.distinctModesDone >= 5)
        check("heart", facts.yogaSessions >= 25)
        check("bicep", branch("SHOULDERS_ARMS") >= 3)
        check("leg", branch("LEGS") >= 3)
        check("spine", branch("CORE") >= 3)
        check("lungs", branch("ENDURANCE") >= 3)
        check("fire", facts.streak >= 30)
        check("wolf", facts.gymSessions >= 50)
        check("owl", hasSession && hour in 2..3)
        check("key", facts.level >= 15)
        check("map_scroll", facts.bossesDefeated >= 5)
        check("saturday_morning", hasSession && facts.lastSessionDayOfWeek == 6 && hour in 5..10)
        check("mirror", allBranches.all { branch(it) >= 3 })

        // ---- sheet 4 (secret) ----
        check("midnight", hasSession && hour == 0)
        check("iron_will", hasSession && sleep != null && sleep < 5f)
        check("double_pr", facts.prsThisSession >= 2)
        check("five_pr", facts.prsThisSession >= 5)
        check("thousand_tons", facts.lifetimeKg >= 1_000_000.0)
        check("fifty", facts.doneSessions >= 50)
        check("two_hundred", facts.doneSessions >= 200)
        check("new_year", hasSession && month == 9)
        check("purim", hasSession && month == 3)
        check("freedom", hasSession && month == 4)
        check("light", hasSession && month == 12)
        check("elephant", facts.lastSessionVolumeKg >= 6_000.0)
        check("whale", facts.lastSessionVolumeKg >= 12_000.0)
        check("all_modes", facts.distinctModesDone >= ModeCatalog.all.size)
        check("hundred_days", facts.bestStreak >= 100)
        check("boss_hunter", facts.bossesDefeated >= 10)
        check("the_legend", facts.level >= 30)

        // ---- stage 1 C: badges that were unreachable ----
        check("reverse_day", facts.lastSessionModeId == "reverse")
        check("joker_badge", (facts.modeCounts["joker"] ?: 0) >= 1)
        check("superset_king", (facts.modeCounts["supersets"] ?: 0) >= SUPERSET_SESSIONS)
        check("first_chest", facts.chestsOpened >= 1)
        check("scale", facts.weighInStreak >= 7)
        check("camera", facts.progressPhotos >= 1)
        // ---- stage 2 F ----
        check("right_guess", facts.goodGuessMeals >= RIGHT_GUESS_MEALS)
        // ---- coach season (event badges are unlocked directly by data/repo/EventBoss, only during the event) ----
        check("challenge_4", facts.challengesCompleted >= 4)
        check("challenge_10", facts.challengesCompleted >= 10)

        return out
    }
}
