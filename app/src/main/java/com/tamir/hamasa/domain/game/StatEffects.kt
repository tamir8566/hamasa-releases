package com.tamir.hamasa.domain.game

import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.domain.tree.TreeModel

/**
 * What the four derived stats of Shambiuluk (TreeModel.Stats) do in the game. Pure logic, one place,
 * so the workout screen, the repositories and the tree screen always agree.
 *
 *  כוח עליון  -> damage of an upper-body set in a boss fight (1 + upper)
 *  כוח תחתון  -> damage of a leg set (1 + lower) and the size of the bag: max shields = 3 + lower / 3
 *  יציבות     -> defense in boss fights (shown only) and +1 streak shield per level at the start of each month
 *  סיבולת     -> rounds per boss fight (6 + 2 x endurance), map stations per session (1 + endurance / 3)
 *                and a shorter rest on accessory sets (-5 s per level, never below 45 s, never on compounds)
 *
 * Effects only ever add; nothing here takes anything away from the player and nothing touches the
 * coach's weights or reps.
 */
object StatEffects {
    /** Boss HP in the round model = catalog maxHp x this. A beginner (6 rounds of 1) beats a 3-HP boss in 3 sessions, like before. */
    const val BOSS_HP_SCALE = 5
    const val BASE_ROUNDS = 6
    const val BASE_SHIELDS = 3
    const val DEFAULT_REST_SEC = 120
    const val MIN_ACCESSORY_REST_SEC = 45

    val ZERO: TreeModel.Stats = TreeModel.Stats(0, 0, 0, 0)

    fun scaledMaxHp(boss: Boss): Int = boss.maxHp * BOSS_HP_SCALE

    /** Damage of one completed set. Upper-body branches use upper strength, legs use lower strength, the rest hit for 1. */
    fun damagePerHit(branch: Branch?, s: TreeModel.Stats): Int = when (branch) {
        Branch.LEGS -> 1 + s.lower.coerceAtLeast(0)
        Branch.CHEST, Branch.BACK, Branch.SHOULDERS_ARMS -> 1 + s.upper.coerceAtLeast(0)
        else -> 1
    }

    fun rounds(s: TreeModel.Stats): Int = BASE_ROUNDS + 2 * s.endurance.coerceAtLeast(0)

    fun defense(s: TreeModel.Stats): Int = s.stability.coerceAtLeast(0)

    fun maxShields(s: TreeModel.Stats): Int = BASE_SHIELDS + s.lower.coerceAtLeast(0) / 3

    fun monthlyShields(s: TreeModel.Stats): Int = s.stability.coerceAtLeast(0)

    fun stationsPerSession(s: TreeModel.Stats): Int = 1 + s.endurance.coerceAtLeast(0) / 3

    /** Rest after an accessory (non-compound) set. Compounds always keep [baseSec]. */
    fun accessoryRestSec(s: TreeModel.Stats, baseSec: Int = DEFAULT_REST_SEC): Int {
        if (baseSec <= MIN_ACCESSORY_REST_SEC) return baseSec
        return (baseSec - 5 * s.endurance.coerceAtLeast(0)).coerceAtLeast(MIN_ACCESSORY_REST_SEC)
    }

    /** Result of one session against the boss. [hits] = sets that counted (at most [rounds]). */
    data class Battle(val damage: Int, val hits: Int, val rounds: Int)

    /**
     * [setBranches] = branch of every completed set, in the order they were done.
     * Every set is a hit until the rounds run out. A session that has no planned sets at all
     * (walk, run, yoga without logging) counts as one full fight of base hits.
     */
    fun battle(setBranches: List<Branch?>, s: TreeModel.Stats, sessionHasNoSets: Boolean): Battle {
        val r = rounds(s)
        if (sessionHasNoSets) return Battle(damage = r, hits = r, rounds = r)
        var damage = 0
        var hits = 0
        for (b in setBranches) {
            if (hits >= r) break
            damage += damagePerHit(b, s)
            hits++
        }
        return Battle(damage, hits, r)
    }

    // ------------------------------------------------------------------ one short line per stat (tree screen)

    fun upperLineHe(s: TreeModel.Stats): String = "מכה בבוס: ${1 + s.upper.coerceAtLeast(0)} נזק"

    fun lowerLineHe(s: TreeModel.Stats): String = "רגליים: ${1 + s.lower.coerceAtLeast(0)} נזק. תיק: ${maxShields(s)} מגנים"

    fun stabilityLineHe(s: TreeModel.Stats): String {
        val n = monthlyShields(s)
        return if (n <= 0) "הגנה 0. מגן חודשי מרמה 1" else "הגנה $n. +$n מגנים בכל חודש"
    }

    fun enduranceLineHe(s: TreeModel.Stats): String =
        "${rounds(s)} סיבובים. ${stationsPerSession(s)} תחנות לאימון. מנוחת עזר ${accessoryRestSec(s)} שנ'"
}
