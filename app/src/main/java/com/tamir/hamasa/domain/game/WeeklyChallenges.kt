package com.tamir.hamasa.domain.game

/**
 * "אתגר שבועי": one challenge per Sunday..Saturday week, chosen deterministically from the challenges his data
 * can actually measure (no steps without the watch, no meal challenges without a food log...). Pure logic;
 * the counting and the reward live in data/repo/ChallengeCenter.
 */
object WeeklyChallenges {

    /** Reward of a completed weekly challenge (granted once per week through GameRepository.grant). */
    const val REWARD_GOLD = 40
    const val REWARD_XP = 60

    const val BADGE_FOUR = "challenge_4"
    const val BADGE_TEN = "challenge_10"

    /** What a challenge needs to be measurable. */
    enum class Need { NONE, MEALS, PHOTO_MEALS, WATCH_STEPS, RECORDS, GYM_REGULAR, SLEEP_REPORTS }

    data class Challenge(
        val id: String,
        val titleHe: String,
        /** One factual line: what counts. */
        val howHe: String,
        val target: Int,
        val need: Need = Need.NONE,
        /** Records / volume chasing: never in a deload week. */
        val pushesLoad: Boolean = false
    )

    /** What his data allows (data/repo/ChallengeCenter). */
    data class Eligibility(
        val hasMeals: Boolean,
        val hasPhotoMeals: Boolean,
        val hasWatchSteps: Boolean,
        val gymSessionsEver: Int,
        val gymLast28: Int,
        val sleepReportsLast28: Int,
        val deloadWeek: Boolean
    )

    /** Counts of the current week so far (data/repo/ChallengeCenter). */
    data class WeekCounts(
        val gymSessions: Int = 0,
        val activeDays: Int = 0,
        val midweekGym: Int = 0,
        val weekendSessions: Int = 0,
        val yoga: Int = 0,
        val walks: Int = 0,
        val earlySessions: Int = 0,
        val distinctTypes: Int = 0,
        val fullGymSessions: Int = 0,
        val sleepGoodSessions: Int = 0,
        val legsSessions: Int = 0,
        val records: Int = 0,
        val workingSets: Int = 0,
        val weighIns: Int = 0,
        val proteinDays: Int = 0,
        val kcalDays: Int = 0,
        val photoDays: Int = 0,
        val mealDays: Int = 0,
        val maxSteps: Int = 0,
        val steps10kDays: Int = 0
    )

    val all: List<Challenge> = listOf(
        Challenge("protein_3", "שלושה ימים עם יעד חלבון", "יום נחשב כשהחלבון ביומן הגיע ליעד היומי", 3, Need.MEALS),
        Challenge("midweek_gym", "אימון אמצע שבוע", "אימון מכון ביום שני, שלישי או רביעי", 1),
        Challenge("weigh_2", "שתי שקילות בשבוע", "שני ימים שונים עם שקילה", 2),
        Challenge("steps_15k", "15,000 צעדים ביום אחד", "יום אחד עם 15,000 צעדים מהשעון", 1, Need.WATCH_STEPS),
        Challenge("yoga_1", "יוגה אחת", "אימון יוגה אחד השבוע", 1),
        Challenge("kcal_4", "ארבעה ימים ביעד הקלוריות", "יום סגור עם שתי ארוחות לפחות, בטווח של 10% מהיעד", 4, Need.MEALS),
        Challenge("record_1", "שבירת שיא אחד", "שיא חדש באחד התרגילים", 1, Need.RECORDS, pushesLoad = true),
        Challenge("active_4", "ארבעה ימים פעילים", "ארבעה ימים שונים עם אימון מכל סוג", 4),
        Challenge("photo_2", "צילום כל הארוחות ביומיים", "יום סגור עם שלוש ארוחות לפחות, כולן מצולמות", 2, Need.PHOTO_MEALS),
        Challenge("walk_2", "שתי הליכות", "שתי הליכות השבוע, מהאפליקציה או מהשעון", 2),
        Challenge("early_1", "אימון לפני תשע בבוקר", "אימון שהתחיל לפני 09:00", 1),
        Challenge("full_session", "אימון מכון שלם", "אימון מכון שבו כל הסטים המתוכננים הושלמו", 1),
        Challenge("steps_10k_3", "שלושה ימים של 10,000 צעדים", "שלושה ימים עם 10,000 צעדים לפחות מהשעון", 3, Need.WATCH_STEPS),
        Challenge("gym_3", "שלושה אימוני מכון", "שלושה אימוני מכון השבוע", 3, Need.GYM_REGULAR),
        Challenge("types_3", "שלושה סוגי אימון", "שלושה סוגים שונים: מכון, יוגה, הליכה, ריצה, אימון זעיר", 3),
        Challenge("meals_5", "יומן אוכל חמישה ימים", "חמישה ימים עם שתי ארוחות רשומות לפחות", 5, Need.MEALS),
        Challenge("sleep_2", "שני אימונים אחרי שינה טובה", "שני אימונים שלפניהם דווחו שבע שעות שינה לפחות", 2, Need.SLEEP_REPORTS),
        Challenge("legs_1", "יום רגליים", "אימון מכון עם תרגיל רגליים", 1),
        Challenge("weekend_1", "אימון בסוף השבוע", "אימון ביום שישי או בשבת", 1),
        Challenge("sets_30", "שלושים סטים בשבוע", "שלושים סטים שהושלמו באימוני מכון", 30, Need.GYM_REGULAR, pushesLoad = true)
    )

    fun byId(id: String): Challenge? = all.firstOrNull { it.id == id }

    fun eligible(e: Eligibility): List<Challenge> = all.filter { c ->
        if (c.pushesLoad && e.deloadWeek) return@filter false
        when (c.need) {
            Need.NONE -> true
            Need.MEALS -> e.hasMeals
            Need.PHOTO_MEALS -> e.hasPhotoMeals
            Need.WATCH_STEPS -> e.hasWatchSteps
            Need.RECORDS -> e.gymSessionsEver >= 3
            Need.GYM_REGULAR -> e.gymLast28 >= 8
            Need.SLEEP_REPORTS -> e.sleepReportsLast28 >= 3
        }
    }

    /**
     * The challenge of the week that starts on [weekStart]: the eligible list rotates one step per week. Never the same
     * as [previousId] (last week's) when there is a choice.
     */
    fun pick(weekStart: Long, e: Eligibility, previousId: String): Challenge {
        val list = eligible(e).ifEmpty { all.filter { it.need == Need.NONE && !it.pushesLoad } }
        val n = list.size
        val week = weekStart / 7L
        var i = (((week % n) + n) % n).toInt()
        if (n > 1 && list[i].id == previousId) i = (i + 1) % n
        return list[i]
    }

    fun progress(c: Challenge, w: WeekCounts): Int = when (c.id) {
        "protein_3" -> w.proteinDays
        "midweek_gym" -> w.midweekGym
        "weigh_2" -> w.weighIns
        "steps_15k" -> if (w.maxSteps >= 15_000) 1 else 0
        "yoga_1" -> w.yoga
        "kcal_4" -> w.kcalDays
        "record_1" -> w.records
        "active_4" -> w.activeDays
        "photo_2" -> w.photoDays
        "walk_2" -> w.walks
        "early_1" -> w.earlySessions
        "full_session" -> w.fullGymSessions
        "steps_10k_3" -> w.steps10kDays
        "gym_3" -> w.gymSessions
        "types_3" -> w.distinctTypes
        "meals_5" -> w.mealDays
        "sleep_2" -> w.sleepGoodSessions
        "legs_1" -> w.legsSessions
        "weekend_1" -> w.weekendSessions
        "sets_30" -> w.workingSets
        else -> 0
    }.coerceIn(0, c.target)
}
