package com.tamir.hamasa.ui.workout

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.repo.BossFacts
import com.tamir.hamasa.data.repo.BossFights
import com.tamir.hamasa.data.repo.HardBattle
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.BossPhase
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.domain.game.BossPhases
import com.tamir.hamasa.domain.game.BossHints
import com.tamir.hamasa.domain.game.BossReturn
import com.tamir.hamasa.domain.game.BossWeakness
import com.tamir.hamasa.domain.game.WeakGrade
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.StatEffects
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.domain.game.Weakness
import com.tamir.hamasa.domain.tree.TreeModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The slim boss bar at the top of the live workout. [hp] is the projected HP after the sets done so far. */
data class BossBarState(
    val nameHe: String,
    val hp: Int,
    val maxHp: Int,
    /** HP when the session started (what is stored now; the damage is saved when the workout is finished). */
    val startHp: Int,
    val hits: Int,
    val rounds: Int,
    val defense: Int,
    /** Damage of one upper-body / leg set, for the small explanation line. */
    val upperHit: Int,
    val lowerHit: Int,
    val taunt: String,
    /** "קרבות בוס בשלבים": current phase, what decides the damage now, and the line of a phase entered in this session. */
    val phase: BossPhase = BossPhase.STRENGTH,
    val phaseRule: String = "",
    val phaseLine: String? = null,
    /** "אויב חוזר": times beaten in earlier weeks (0 = first meeting). */
    val returningTimes: Int = 0,
    /** "כל בוס נפגע משילוב אחר": "חולשה: ...", what is present/missing so far, Shambiuluk's hint, the grade now. */
    val weaknessHe: String = "",
    val weaknessStatusHe: String = "",
    val hintHe: String = "",
    val grade: WeakGrade = WeakGrade.FULL
) {
    val roundsLeft: Int get() = (rounds - hits).coerceAtLeast(0)
    val defeated: Boolean get() = hp <= 0
}

/**
 * Live boss fight + "הקרב הקשה" flag for one workout. Pure bookkeeping on top of [BossFights]; the real
 * damage is applied once, in WorkoutRepository.finishSession. Not thread-safe by design: used from the
 * view model's main-thread coroutines only.
 */
class WorkoutBattle(private val sessionId: Long) {
    private val _bar = MutableStateFlow<BossBarState?>(null)
    val bar: StateFlow<BossBarState?> = _bar

    private val _hardBattle = MutableStateFlow(false)
    val hardBattle: StateFlow<Boolean> = _hardBattle

    /** Stats of Shambiuluk at the start of the workout (rest timer, damage, rounds). */
    var stats: TreeModel.Stats = StatEffects.ZERO
        private set

    private var loaded = false
    private var pendingSets: List<SetLogEntity>? = null
    private var session: SessionEntity? = null
    private var weakness: Weakness? = null

    suspend fun load() {
        val s = AppGraph.db.sessions().byId(sessionId) ?: return
        stats = runCatching { AppGraph.game.stats() }.getOrDefault(StatEffects.ZERO)
        if (s.status == SessionStatus.ACTIVE) {
            _hardBattle.value = runCatching { HardBattle.qualifies(s) }.getOrDefault(false)
            val (boss, progress) = BossFights.forDay(s.dateEpochDay)
            // level gate: the weekly boss takes damage from level 6 (LevelGates.BOSS, same check as finishSession)
            val bossOpen = LevelGates.bossOpen(LevelTable.levelFor(AppGraph.prefs.current().xp))
            // the bar shows whenever the boss is open and standing: the weakness tells what this session must contain
            if (bossOpen && !progress.defeated) {
                session = s
                val wk = BossWeakness.forBoss(boss.id)
                weakness = wk
                val facts = runCatching { BossFacts.collect(s, emptyList()) }.getOrNull()
                val returning = runCatching { BossFights.returningTimes(boss, progress.weekEpoch) }.getOrDefault(0)
                val phase = BossPhases.phaseOf(progress.hp, progress.maxHp)
                _bar.value = BossBarState(
                    nameHe = boss.nameHe,
                    hp = progress.hp,
                    maxHp = progress.maxHp.coerceAtLeast(1),
                    startHp = progress.hp,
                    hits = 0,
                    rounds = StatEffects.rounds(stats),
                    defense = StatEffects.defense(stats),
                    upperHit = 1 + stats.upper,
                    lowerHit = 1 + stats.lower,
                    taunt = if (returning > 0) "\"" + CharacterNames.g(BossReturn.lineHe(boss.id)) + "\"" else Voice.bossTaunt(boss.nameHe),
                    phase = phase,
                    phaseRule = Addr.x(BossPhases.ruleHe(phase, stats)),
                    returningTimes = returning,
                    weaknessHe = Addr.x(BossWeakness.lineHe(wk)),
                    weaknessStatusHe = if (facts != null) Addr.x(BossWeakness.statusHe(wk, facts)) else "",
                    hintHe = CharacterNames.g(BossHints.forBoss(boss.id)),
                    grade = if (facts != null) BossWeakness.grade(wk, facts) else WeakGrade.PARTIAL
                )
            }
        }
        loaded = true
        pendingSets?.let { onSets(it) }
        pendingSets = null
    }

    /** Recomputes the projected HP from the completed sets (every set is a hit until the rounds run out). */
    suspend fun onSets(sets: List<SetLogEntity>) {
        if (!loaded) { pendingSets = sets; return }
        val cur = _bar.value ?: return
        // "כל בוס נפגע משילוב אחר": the weakness grade with the sets done so far decides the multiplier
        val s = session
        val wk = weakness
        val facts = if (s != null) runCatching { BossFacts.collect(s, sets) }.getOrNull() else null
        val grade = if (wk != null && facts != null) BossWeakness.grade(wk, facts) else cur.grade
        val (num, den) = BossWeakness.weeklyMult(grade)
        val battle = BossPhases.battle(BossFights.branchesOf(sets), stats, cur.startHp, cur.maxHp, sessionHasNoSets = false,
            multNum = num, multDen = den)
        val phase = battle.phaseAfter
        _bar.value = cur.copy(
            grade = grade,
            weaknessStatusHe = if (wk != null && facts != null) Addr.x(BossWeakness.statusHe(wk, facts)) else cur.weaknessStatusHe,
            hp = (cur.startHp - battle.damage).coerceAtLeast(0),
            hits = battle.hits,
            phase = phase,
            phaseRule = Addr.x(BossPhases.ruleHe(phase, stats)),
            phaseLine = if (phase != battle.phaseBefore) CharacterNames.gOrNull(BossPhases.enterLineHe(phase)) else null
        )
    }
}
