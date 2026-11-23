package com.tamir.hamasa.domain.game

/**
 * More female-hero overrides, same rules as [FemaleLines] (key = exact male line with capped words as tokens
 * {A} / {H} / {L} and every {u:..|..} token at its male text; value = female-hero line, may carry {u:..|..}).
 * Split into several files only so parallel edits do not collide.
 */
object FemaleLinesC {
    val lines: HashMap<String, String> = hashMapOf(
        // ui/energy/EnergyScreen
        "שמביולוק סופר קלוריות. יד אחת לשריפה, יד אחת לצלחת. שתיהן עייפות." to "שמביולוקה סופרת קלוריות. יד אחת לשריפה, יד אחת לצלחת. שתיהן עייפות."
    )
}
