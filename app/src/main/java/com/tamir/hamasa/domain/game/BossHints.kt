package com.tamir.hamasa.domain.game

/**
 * Shambiuluk's hint about each boss's weakness (BossWeakness). One line per boss, his voice: short sentences,
 * deadpan, a twist at the end. Shown under the weakness line on the map card, the home card and the workout bar.
 */
object BossHints {

    private val weekly: Map<String, String> = mapOf(
        "monster" to "המפלצת ניזונה מלילות קצרים וסטים חצויים. {u:תישן|תישני}. {u:תסיים|תסיימי}. היא תרעב.",
        "midweek_king" to "המלך שולט בשני, שלישי ורביעי. ברזל באמצע השבוע מפיל כתרים. גם כבדים.",
        "couch_siren" to "היא שרה רק למי שלא תכנן. {u:סגור|סגרי} את היום, {u:תן|תני} לבטן לעבוד. השיר נגמר.",
        "thin_legs_giant" to "רגלי קש וגב עקום. מכה בשניהם באותו אימון והוא מתקפל. כמו כיסא מתקפל.",
        "sly_merchant" to "הוא מוכר קיצורים. צלחת מצולמת וסטים עד הסוף. אין לו מה למכור לך.",
        "snooze_dragon" to "הדרקון ער רק בתחילת השבוע. {u:תקדים|תקדימי} אותו, אחרי לילה שלם. הוא ימשיך לנמנם. לנצח.",
        "excuse_goblin" to "תירוץ אחד הוא מכיר. שניים לא. אימון קטן שאינו מכון, אחרי סוג אחר השבוע. נגמרו לו.",
        "sugar_witch" to "היא שונאת חלבון. עוד יותר היא שונאת ברזל. ביחד זה רצח. של מכשפה.",
        "mirror_knight" to "המראה מראה חזה וכתפיים. {u:תן|תני} לה מה להראות. באותו אימון.",
        "rain_ogre" to "עשרים דקות בחוץ, ואימון כוח השבוע. העוג שונא אנשים יבשים שגם מרימים.",
        "weight_golem" to "גולם נשבר מלמטה ומלפנים. רגליים וחזה. אבן אחרי אבן.",
        "plateau_sphinx" to "החידה: שיא, או כל הסטים ביעד, אחרי לילה שלם. התשובה: {u:אתה|את}. שוב.",
        "phone_siren" to "יוגה והברזל באותו שבוע. בשניהם הטלפון בתיק. היא שרה לתיק.",
        "stairs_guardian" to "המדרגות נכבשות עם בטן חזקה וכתפיים. אותו אימון. הפסגה עדיין לא קיימת. אבל הוא כן נופל.",
        "old_self" to "האני הישן זוכר אותך {u:מתאמן|מתאמנת} פעם בשבוע, תמיד אותו דבר. יום אחרי יום, ומשהו אחר. הוא לא מזהה אותך."
    )

    private val events: Map<String, String> = mapOf(
        "hanukkah" to "נר כל יום. אימון כל יום. כל הסטים. הסופגניות לא מתנגדות. הן גם לא עוזרות.",
        "purim" to "המסכה נופלת כשמחליפים סוג אימון. ובטן. תמיד בטן.",
        "pesach" to "יציאה. יום אחרי יום. עשרים דקות לפחות. הדרקון נשאר במטבח.",
        "independence" to "רגליים וחלבון. המנגל מספק רק אחד מהם.",
        "tishrei" to "יוגה ושינה. השנה שעברה לא ידעה על שניהם. כאן ההבדל.",
        "summer" to "עשרים דקות הליכה, לילה שלם לפני. החום מתעצבן. {u:אתה|את} לא."
    )

    /** Hero line (CharacterNames.g), player tokens resolved. */
    fun forBoss(bossId: String): String = CharacterNames.g(weekly[bossId] ?: "יש לו חולשה. הוא מתבייש בה. {u:חפש|חפשי}.")

    fun forEvent(eventId: String): String = CharacterNames.g(events[eventId] ?: "גם לבוס של החג יש חולשה. הידד.")
}
