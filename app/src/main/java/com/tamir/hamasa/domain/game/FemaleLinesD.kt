package com.tamir.hamasa.domain.game

/**
 * More female-hero overrides, same rules as [FemaleLines] (key = exact male line with capped words as tokens
 * {A} / {H} / {L} and every {u:..|..} token at its male text; value = female-hero line, may carry {u:..|..}).
 * Split into several files only so parallel edits do not collide.
 */
object FemaleLinesD {
    val lines: HashMap<String, String> = hashMapOf(
        // ------------------------------------------------------------------ ShopRules (ui/shop/ShopViewModel.kt)
        "לבוש. מרגיש כבד. מתאים לי." to "לבושה. מרגישה כבדה. מתאים לי.",
        "לבוש. היכן המראה?" to "לבושה. היכן המראה?",
        // ------------------------------------------------------------------ Voice (hidden-badge lines)
        "זה דורש משהו. לא אומר מה. ייקח זמן." to "זה דורש משהו. לא אומרת מה. ייקח זמן."
    )
}
