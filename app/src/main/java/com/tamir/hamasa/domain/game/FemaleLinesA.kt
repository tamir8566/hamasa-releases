package com.tamir.hamasa.domain.game

/**
 * More female-hero overrides, same rules as [FemaleLines] (key = exact male line with capped words as tokens
 * {A} / {H} / {L} and every {u:..|..} token at its male text; value = female-hero line, may carry {u:..|..}).
 * Split into several files only so parallel edits do not collide.
 */
object FemaleLinesA {
    val lines: HashMap<String, String> = hashMapOf(
        // BadgeCatalog
        "שלושים ימי רצף. האש דולקת. {A} כבאי." to "שלושים ימי רצף. האש דולקת. אני לא כבאית."
    )
}
