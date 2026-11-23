package com.tamir.hamasa.domain.game

/**
 * The player's character. Prefs.characterVariant: "M" = שמביולוק (the original), "F" = שמביולוקה (his female
 * counterpart: same family, same deadpan, same war with המפלצת). Art: sh_anchor_N / shf_anchor_N
 * (ui/common/CharacterArt resolves and falls back to the male images while the female ones are missing).
 *
 * Voice lines: every hero line passes through [g]. For "M" it returns the line untouched (byte-identical);
 * for "F" it returns the female-grammar version from [FemaleLines], or at least swaps the name.
 * [current] is the process-wide variant: AppGraph keeps it in sync with Prefs; background entry points
 * (widget, reminders, notifications) call [set] with the stored value before building a line.
 */
object CharacterNames {
    const val MALE = "M"
    const val FEMALE = "F"

    /** The variant in use right now ("M" / "F"). Written by AppGraph (Prefs flow), onboarding and background entry points. */
    @Volatile
    var current: String = MALE
        private set

    fun normalize(variant: String?): String = if (variant == FEMALE) FEMALE else MALE

    fun isFemale(variant: String?): Boolean = normalize(variant) == FEMALE

    /** Hebrew display name of the character. */
    fun he(variant: String?): String = if (isFemale(variant)) "שמביולוקה" else "שמביולוק"

    fun set(variant: String?) {
        current = normalize(variant)
    }

    fun isFemaleNow(): Boolean = current == FEMALE

    /** Name of the current variant. */
    fun heNow(): String = he(current)

    /** [male] for "M", [female] for "F". For lines that are built with templates at the call site. */
    fun pick(male: String, female: String): String = if (current == FEMALE) female else male

    private val maleName = Regex("שמביולוק(?!ה)")

    /** Only the name: "שמביולוק" -> "שמביולוקה" (never doubles an existing "ה"). */
    fun femaleName(line: String): String = maleName.replace(line, "שמביולוקה")

    /**
     * The hero line in the current variant's grammar. Male: [line] unchanged. Female: the override from
     * [FemaleLines] (exact male line, templates included, e.g. "%s"), else the name swap. Safe to apply twice.
     * Apply it to a template BEFORE String.format, so the lookup sees the catalog line.
     */
    fun g(line: String): String {
        if (current != FEMALE) return Addr.x(line)
        // lookup by the line with every {u:..|..} token at its male text, so catalog keys stay plain male lines;
        // an override value may carry {u:..|..} tokens of its own
        val f = FemaleLines.forLine(Addr.male(line))
        if (f != null) return Addr.x(f)
        return Addr.x(femaleName(line))
    }

    fun gOrNull(line: String?): String? = if (line == null) null else g(line)
}
