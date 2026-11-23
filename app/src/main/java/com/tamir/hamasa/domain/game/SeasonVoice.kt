package com.tamir.hamasa.domain.game

/**
 * Shambiuluk's lines for the weekly challenge, the resting heart-rate warning and the deload week
 * (seasonal event lines live in [SeasonalEvents]). Kept out of Voice.kt on purpose (another agent owns it).
 * Deterministic picks (by week / day), so a line does not change on every recomposition.
 * Word caps of CLAUDE.md: none of the capped words are used here.
 */
object SeasonVoice {

    private fun pick(list: List<String>, seed: Long): String {
        val n = list.size
        val i = (((seed % n) + n) % n).toInt()
        return CharacterNames.g(list[i])
    }

    // ---------------------------------------------------------------- weekly challenge

    private val challengeNew = listOf(
        "אתגר חדש לשבוע. קטן. מרושע. בדיוק במידה.",
        "קיבלת משימה. החתמתי אותך בלי לשאול. מקובל.",
        "אתגר השבוע הגיע. הוא נראה תמים. הם תמיד נראים תמימים.",
        "משימה אחת. שבוע אחד. ההיסטוריה תשפוט. או אני. כנראה אני.",
        "השבוע יש אתגר. לא קל, לא קשה. אני פשוט מסתכל."
    )

    private val challengeProgress = listOf(
        "חלק כבר מאחוריך. החלק שנשאר מתנהג כאילו הוא לא שם לב.",
        "בדרך. האתגר מתחיל להזיע. {u:אתה|את} גם.",
        "התקדמות. רשמתי. לא מתלהב עדיין. כמעט."
    )

    private val challengeDone = listOf(
        "האתגר הושלם. הידדדדד. בשקט. אבל הידד.",
        "עשית את זה. האתגר מבקש פנסיה מוקדמת.",
        "סגור. האתגר נפל. לך זהב, לי הפתעה.",
        "בוצע. מי היה מאמין. אני. לרגע. ואז לא. ואז כן."
    )

    fun challengeNew(weekStart: Long): String = pick(challengeNew, weekStart / 7)

    fun challengeProgress(weekStart: Long): String = pick(challengeProgress, weekStart / 7)

    fun challengeDone(weekStart: Long): String = pick(challengeDone, weekStart / 7)

    // ---------------------------------------------------------------- resting heart rate

    private val hrWarn = listOf(
        "המפלצת מתקרבת. הלב דופק מהר גם במנוחה. היום בעדינות.",
        "הדופק במנוחה גבוה כמה ימים. המפלצת לא דופקת בדלת. היא כבר בסלון.",
        "הלב עובד שעות נוספות. גם בלילה. לא אישרתי לו. אולי יוגה."
    )

    fun restingHrWarning(day: Long): String = pick(hrWarn, day)

    // ---------------------------------------------------------------- deload week

    private val deloadOffer = listOf(
        "הג'ים ברו מציע שבוע קל. מציע לא להתווכח. הפעם אני בצד שלו.",
        "שבוע הורדת עומס. נשמע כמו ויתור. זה לא. זה תכנון."
    )

    private val deloadOn = listOf(
        "שבוע קל. המשקולות נחות. {u:אתה|את} לא. כמעט.",
        "שבוע התאוששות. השרירים בונים בשקט. אני שומר על הדלת.",
        "פחות סטים, אותו כבוד. הניסיון לא יורד. רק המשקל."
    )

    fun deloadOffer(day: Long): String = pick(deloadOffer, day)

    fun deloadActive(day: Long): String = pick(deloadOn, day)
}
