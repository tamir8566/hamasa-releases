package com.tamir.hamasa.domain.coach

/**
 * "רמת קושי" of the coach (setting on the coach screen, or a temporary override from a note to the coach).
 * Always inside Tamir's rules (docs/coach_logic.md, "רמת קושי"):
 *  - progression pace: EASY waits for a second session at the top of the range, HARD goes up one rep earlier;
 *  - rest: EASY +30 s, HARD −30 s (compounds never under 90 s, accessories never under 45 s);
 *  - sets: EASY takes the lower end (legs 1 per muscle, one accessory set less, at most 10), NORMAL / HARD keep his
 *    counts (they already are the upper end) under the 12-set cap;
 *  - intensity: a reps-in-reserve note on every working exercise.
 * Compounds never get a working target above 8 reps because of the difficulty.
 */
enum class Difficulty(val nameHe: String) {
    EASY("קל"),
    NORMAL("רגיל"),
    HARD("קשה");

    /** Reps-in-reserve note shown on exercise cards. */
    val rirNoteHe: String
        get() = when (this) {
            EASY -> "עוד 3 חזרות במאגר בכל סט"
            NORMAL -> "עוד 2 חזרות במאגר בכל סט"
            HARD -> "0 עד 1 חזרות במאגר, כמעט כישלון"
        }

    /** Seconds added to the coach's rest (negative = shorter). */
    val restDeltaSec: Int
        get() = when (this) {
            EASY -> 30
            NORMAL -> 0
            HARD -> -30
        }

    /** Working-set cap of a coach session. */
    val maxSets: Int
        get() = if (this == EASY) 10 else 12

    /** Reps in one set that trigger the "+5 ק\"ג, back to 8" suggestion in his own templates (12 = his rule). */
    val templateTopReps: Int
        get() = if (this == HARD) 11 else 12

    companion object {
        const val REST_FLOOR_COMPOUND = 90
        const val REST_FLOOR_ACCESSORY = 45

        fun fromName(name: String?): Difficulty = when (name) {
            "EASY" -> EASY
            "HARD" -> HARD
            else -> NORMAL
        }

        /**
         * Rest after applying the difficulty. The floor never raises a base that was already lower than it
         * (e.g. a template with 60 s rest on a compound stays at least as short as he set it).
         */
        fun adjustRest(base: Int, compound: Boolean, d: Difficulty): Int {
            if (d.restDeltaSec == 0 || base <= 0) return base
            val floor = if (compound) REST_FLOOR_COMPOUND else REST_FLOOR_ACCESSORY
            val v = base + d.restDeltaSec
            return if (d.restDeltaSec > 0) v else v.coerceAtLeast(minOf(base, floor))
        }
    }
}
