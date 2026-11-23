package com.tamir.hamasa.domain.game

/**
 * More female-hero overrides, same rules as [FemaleLines] (key = exact male line with capped words as tokens
 * {A} / {H} / {L} and every {u:..|..} token at its male text; value = female-hero line, may carry {u:..|..}).
 * Split into several files only so parallel edits do not collide.
 */
object FemaleLinesB {
    val lines: HashMap<String, String> = hashMapOf(
        // Voice: secret badge teaser
        "אם הייתי מספר, זה היה סתם תג. ככה זה סוד." to "אם הייתי מספרת, זה היה סתם תג. ככה זה סוד.",
        // TapVoice: evening, no workout yet
        "היום כמעט נגמר. אימון זעיר עוד מציל אותו. רק אומר." to "היום כמעט נגמר. אימון זעיר עוד מציל אותו. רק אומרת."
    )
}
