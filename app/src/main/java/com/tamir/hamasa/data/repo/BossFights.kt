package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.BossProgressEntity
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Boss
import com.tamir.hamasa.domain.game.BossCatalog
import com.tamir.hamasa.domain.game.BossPhase
import com.tamir.hamasa.domain.game.BossPhases
import com.tamir.hamasa.domain.game.BossReturn
import com.tamir.hamasa.domain.game.BossWeakness
import com.tamir.hamasa.domain.game.WeakGrade
import com.tamir.hamasa.domain.game.Professions
import com.tamir.hamasa.domain.game.StatEffects
import com.tamir.hamasa.domain.tree.TreeModel

/** What one session did to the boss of its week. */
data class BossResult(
    val bossId: String,
    val nameHe: String,
    val damage: Int,
    val hits: Int,
    val rounds: Int,
    val hpBefore: Int,
    val hpAfter: Int,
    val maxHp: Int,
    /** The boss fell in this session. */
    val defeatedNow: Boolean,
    /** "קרבות בוס בשלבים": phase when the session started / after its hits. */
    val phaseBefore: BossPhase = BossPhase.STRENGTH,
    val phaseAfter: BossPhase = BossPhase.STRENGTH,
    /** "אויב חוזר": how many times this boss was beaten in earlier weeks (0 = first meeting). */
    val returningTimes: Int = 0,
    /** "כל בוס נפגע משילוב אחר": how this session matched the boss's weakness, and the weakness line. */
    val grade: WeakGrade = WeakGrade.FULL,
    val weaknessHe: String = ""
)

/**
 * Boss fights in rounds (spec: "כל סט שהושלם הוא מכה, הנזק נקבע לפי נתוני שמביולוק, והסיבולת קובעת כמה סיבובים יש לך").
 *  - BossWeakness decides how hard a session hits: every boss has a two-ingredient weakness; both = every hit
 *    double, one = half, none = no damage (domain/game/BossWeakness, facts from BossFacts);
 *  - every completed set is a hit; damage per hit from BossPhases: strength above 2/3 HP, endurance down to 1/3,
 *    stability below ("קרבות בוס בשלבים");
 *  - a boss beaten in an earlier week comes back with more HP and its own line (BossReturn, "אויב חוזר");
 *  - rounds per session = 6 + 2 x endurance; hits beyond that do not count;
 *  - HP = catalog maxHp x StatEffects.BOSS_HP_SCALE. Rows saved before the round model (maxHp equal to the
 *    catalog value) are scaled up proportionally the first time they are read, so the bar keeps its fill.
 */
object BossFights {

    /** The boss of the week that contains [day] and its progress (rescaled when needed, not yet saved). */
    suspend fun forDay(day: Long): Pair<Boss, BossProgressEntity> {
        val week = Days.weekStartOf(day)
        val boss = BossCatalog.forWeek(week / 7)
        val row = AppGraph.db.game().bossForWeekOnce(week)
        val n = normalize(boss, week, row)
        if (row != null) return boss to n
        // "אויב חוזר": a fresh week of a boss beaten before starts stronger (a started week is never raised)
        val times = returningTimes(boss, week)
        if (times <= 0) return boss to n
        val max = BossReturn.maxHpFor(boss, times)
        return boss to n.copy(hp = max, maxHp = max)
    }

    /**
     * "אויב חוזר": defeats of [boss] in weeks before [week]. First reconciles Prefs.bossDefeatsCsv with the stored
     * row of an earlier week (a defeat made by any path, e.g. meal photos, is still on that row until the new
     * week's row replaces it), so every defeat is counted once.
     */
    suspend fun returningTimes(boss: Boss, week: Long): Int {
        val old = runCatching { AppGraph.db.game().bossesOnce() }.getOrDefault(emptyList())
            .firstOrNull { it.bossId == boss.id && it.defeated && it.weekEpoch < week }
        if (old != null) {
            val rec = BossReturn.parse(AppGraph.prefs.current().bossDefeatsCsv)[boss.id]
            if (rec == null || rec.lastWeek < old.weekEpoch) {
                AppGraph.prefs.update { it.copy(bossDefeatsCsv = BossReturn.recordDefeat(it.bossDefeatsCsv, boss.id, old.weekEpoch)) }
            }
        }
        return BossReturn.timesBeatenBefore(AppGraph.prefs.current().bossDefeatsCsv, boss.id, week)
    }

    /** Fresh row for the week, or the stored one scaled to the round model. Pure; used by the map too. */
    fun normalize(boss: Boss, week: Long, row: BossProgressEntity?): BossProgressEntity {
        val scaledMax = StatEffects.scaledMaxHp(boss)
        return when {
            row == null -> BossProgressEntity(boss.id, week, scaledMax, scaledMax)
            !row.defeated && row.maxHp < scaledMax && row.maxHp > 0 -> {
                // old 1-HP-per-session row: keep the same fraction of life
                val factor = scaledMax / row.maxHp
                row.copy(hp = (row.hp * factor).coerceIn(0, scaledMax), maxHp = scaledMax)
            }
            else -> row
        }
    }

    /** How [s] (with [sets] so far) matches the weakness of [boss]. */
    suspend fun gradeOf(boss: Boss, s: SessionEntity, sets: List<SetLogEntity>): WeakGrade =
        BossWeakness.grade(BossWeakness.forBoss(boss.id), BossFacts.collect(s, sets))

    /** Can a finished [s] hit [boss] at all (at least one ingredient of its weakness)? */
    suspend fun canHit(boss: Boss, s: SessionEntity, sets: List<SetLogEntity>): Boolean = gradeOf(boss, s, sets) != WeakGrade.NONE

    /** Branch of every completed set, in the order the sets were logged. */
    suspend fun branchesOf(sets: List<SetLogEntity>): List<Branch?> {
        val cache = HashMap<String, Branch?>()
        val out = ArrayList<Branch?>()
        for (st in sets.filter { it.completed }.sortedBy { it.id }) {
            val b = if (cache.containsKey(st.exerciseId)) cache[st.exerciseId]
            else AppGraph.db.exercises().byId(st.exerciseId)?.branch.also { cache[st.exerciseId] = it }
            out.add(b)
        }
        return out
    }

    /**
     * Applies a finished session to the boss of its week. Returns null when the boss is already down; a session
     * without any ingredient of the weakness returns a 0-damage result (grade NONE). Grants the defeat reward once.
     */
    suspend fun applySession(s: SessionEntity, sets: List<SetLogEntity>, stats: TreeModel.Stats): BossResult? {
        val (boss, progress) = forDay(s.dateEpochDay)
        if (progress.defeated) return null
        // "כל בוס נפגע משילוב אחר": the session (as saved now: DONE, real duration) against the boss's weakness
        val fresh = AppGraph.db.sessions().byId(s.id) ?: s
        val weakness = BossWeakness.forBoss(boss.id)
        val grade = BossWeakness.grade(weakness, BossFacts.collect(fresh, sets))
        if (grade == WeakGrade.NONE) {
            // no ingredient of the weakness: no damage, but the done screen still says what was missing
            if (progress != AppGraph.db.game().bossForWeekOnce(progress.weekEpoch)) AppGraph.db.game().upsertBoss(progress)
            val r = runCatching { returningTimes(boss, progress.weekEpoch) }.getOrDefault(0)
            val ph = BossPhases.phaseOf(progress.hp, progress.maxHp)
            return BossResult(boss.id, boss.nameHe, 0, 0, StatEffects.rounds(stats), progress.hp, progress.hp, progress.maxHp, false,
                ph, ph, r, grade, weakness.labelHe)
        }
        val (num, den) = BossWeakness.weeklyMult(grade)
        // "קרבות בוס בשלבים": the stat that decides the damage follows the boss's HP (BossPhases)
        val rawBattle = BossPhases.battle(branchesOf(sets), stats, progress.hp, progress.maxHp, sessionHasNoSets = sets.isEmpty(),
            multNum = num, multDen = den)
        // "מקצוע לשמביולוק": the warrior adds +1 damage to a gym session that hit (never more than the HP left)
        val profile = AppGraph.prefs.current()
        val professionBonus = Professions.bossBonus(Professions.active(profile.professionId, profile.xp), s.type, rawBattle.damage)
            .coerceAtMost((progress.hp - rawBattle.damage).coerceAtLeast(0))
        val battle = if (professionBonus > 0) {
            val dealt = rawBattle.damage + professionBonus
            rawBattle.copy(damage = dealt, phaseAfter = BossPhases.phaseOf((progress.hp - dealt).coerceAtLeast(0), progress.maxHp))
        } else rawBattle
        val returning = runCatching { returningTimes(boss, progress.weekEpoch) }.getOrDefault(0)
        if (battle.damage <= 0) {
            if (progress != AppGraph.db.game().bossForWeekOnce(progress.weekEpoch)) AppGraph.db.game().upsertBoss(progress)
            return BossResult(boss.id, boss.nameHe, 0, 0, battle.rounds, progress.hp, progress.hp, progress.maxHp, false,
                battle.phaseBefore, battle.phaseAfter, returning, grade, weakness.labelHe)
        }
        val hp = (progress.hp - battle.damage).coerceAtLeast(0)
        val defeated = hp == 0
        AppGraph.db.game().upsertBoss(progress.copy(hp = hp, defeated = defeated))
        if (defeated) {
            // progress.defeated is false here (checked above), so this is a fresh defeat
            AppGraph.prefs.update {
                it.copy(
                    bossesDefeatedTotal = it.bossesDefeatedTotal + 1,
                    bossDefeatsCsv = BossReturn.recordDefeat(it.bossDefeatsCsv, boss.id, progress.weekEpoch)
                )
            }
            AppGraph.game.grant(200, 50, "boss:${boss.id}")
        }
        return BossResult(boss.id, boss.nameHe, battle.damage, battle.hits, battle.rounds, progress.hp, hp, progress.maxHp, defeated,
            battle.phaseBefore, battle.phaseAfter, returning, grade, weakness.labelHe)
    }
}
