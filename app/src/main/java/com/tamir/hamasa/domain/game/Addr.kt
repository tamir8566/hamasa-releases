package com.tamir.hamasa.domain.game

/**
 * How the app addresses the PLAYER: "M" = לשון זכר (default, Tamir), "F" = לשון נקבה. Prefs.userGender.
 * Separate from the hero variant (CharacterNames: שמביולוק / שמביולוקה).
 *
 * Two ways to write a gendered string:
 *  - at the call site: Addr.t("לחץ כאן", "לחצי כאן");
 *  - inside a catalog / longer string: tokens {u:male|female}, e.g. "{u:אתה מוכן|את מוכנה}. מתחילים." and the
 *    string is resolved by [x] before it is shown. CharacterNames.g() resolves the tokens itself, so hero lines
 *    that go through g() need nothing more. A token text must not contain '|', '{' or '}'.
 * Male output is always exactly the male text, so for Tamir nothing changes.
 */
object Addr {
    const val MALE = "M"
    const val FEMALE = "F"

    /** Written by AppGraph (Prefs flow) and by onboarding / settings when the choice changes. */
    @Volatile
    var current: String = MALE
        private set

    fun set(gender: String?) {
        current = if (gender == FEMALE) FEMALE else MALE
    }

    fun isFemale(): Boolean = current == FEMALE

    /** [male] or [female] by the current choice. */
    fun t(male: String, female: String): String = if (current == FEMALE) female else male

    private val token = Regex("\\{u:([^|{}]*)\\|([^|{}]*)\\}")

    /** Resolves every {u:male|female} token for the current choice. Strings without tokens come back as they are. */
    fun x(s: String): String = if (!s.contains("{u:")) s else token.replace(s) { m -> if (current == FEMALE) m.groupValues[2] else m.groupValues[1] }

    fun xOrNull(s: String?): String? = if (s == null) null else x(s)

    /** Every token resolved to its MALE text (used as a lookup key). */
    fun male(s: String): String = if (!s.contains("{u:")) s else token.replace(s) { m -> m.groupValues[1] }
}
