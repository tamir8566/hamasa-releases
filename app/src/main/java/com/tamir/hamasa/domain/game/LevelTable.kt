package com.tamir.hamasa.domain.game

/** XP thresholds from the spec. Level 1 = 0 xp. */
object LevelTable {
    private val thresholds = listOf(0, 300, 700, 1200, 1800, 2600, 3500, 4600, 5900, 7500)
    fun levelFor(xp: Int): Int {
        var lvl = 1
        for ((i, t) in thresholds.withIndex()) if (xp >= t) lvl = i + 1
        if (xp >= thresholds.last()) lvl = 10 + (xp - thresholds.last()) / 1800
        return lvl
    }
    fun xpForLevel(level: Int): Int = if (level <= thresholds.size) thresholds[level - 1] else thresholds.last() + (level - 10) * 1800
    fun progress(xp: Int): Float {
        val l = levelFor(xp); val a = xpForLevel(l); val b = xpForLevel(l + 1)
        return ((xp - a).toFloat() / (b - a)).coerceIn(0f, 1f)
    }
}
