package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Boss
import com.tamir.hamasa.domain.game.BossCatalog
import com.tamir.hamasa.domain.game.BossWeakness
import com.tamir.hamasa.domain.game.WeakGrade
import com.tamir.hamasa.domain.game.SeasonalEvents
import com.tamir.hamasa.domain.game.StatEffects
import java.util.concurrent.ConcurrentHashMap

/**
 * The seasonal event boss (domain/game/SeasonalEvents). It JOINS the weekly boss: BossFights and the weekly
 * rotation are not touched. State lives in Prefs.eventBossState ("eventId:year|hp|maxHp|defeated").
 *
 * Damage: every finished session of any type during the event hits, one damage per completed set (at least 1, at
 * most the rounds of StatEffects.rounds), times the event's weakness (BossWeakness.forEvent: none = half,
 * one ingredient = normal, both = double). HP 24 (30 for the two Tishrei weeks), so two or
 * three regular sessions in the holiday week beat it. The defeat pays [SeasonalEvents.DEFEAT_XP] /
 * [SeasonalEvents.DEFEAT_GOLD] once and unlocks the event's limited-time badge.
 */
object EventBoss {

    data class State(val key: String, val hp: Int, val maxHp: Int, val defeated: Boolean)

    /** What one session did to the event boss (for the done screen). */
    data class Hit(
        val eventNameHe: String,
        val bossNameHe: String,
        val damage: Int,
        val hpAfter: Int,
        val maxHp: Int,
        val defeatedNow: Boolean,
        val defeatedHe: String,
        val badgeId: String
    )

    private val hits = ConcurrentHashMap<Long, Hit>()

    fun hitFor(sessionId: Long): Hit? = hits[sessionId]

    fun parse(s: String): State? {
        val p = s.split('|')
        if (p.size != 4 || p[0].isBlank()) return null
        val hp = p[1].toIntOrNull() ?: return null
        val max = p[2].toIntOrNull() ?: return null
        return State(p[0], hp.coerceAtLeast(0), max.coerceAtLeast(1), p[3] == "1")
    }

    fun encode(st: State): String = "${st.key}|${st.hp}|${st.maxHp}|${if (st.defeated) "1" else "0"}"

    /** The boss portrait an event reuses. */
    fun portraitOf(e: SeasonalEvents.Event): Boss? = BossCatalog.byId(e.baseBossId)

    private fun fresh(w: SeasonalEvents.Window): State = State(w.key, w.event.maxHp, w.event.maxHp, false)

    /** The running event and its boss state (fresh when this event has not been hit yet). Pure read. */
    fun current(csv: String, today: Long = Days.today()): Pair<SeasonalEvents.Window, State>? {
        val w = SeasonalEvents.activeOn(today) ?: return null
        val st = parse(csv)?.takeIf { it.key == w.key } ?: fresh(w)
        return w to st
    }

    /** Called from the finished-session hook. Null when no event runs on the session's day or the boss is down. */
    suspend fun applySession(s: SessionEntity, sets: List<SetLogEntity>): Hit? {
        val w = SeasonalEvents.activeOn(s.dateEpochDay) ?: return null
        val rounds = runCatching { StatEffects.rounds(AppGraph.game.stats()) }.getOrDefault(StatEffects.BASE_ROUNDS)
        val base = sets.count { it.completed }.coerceAtLeast(1).coerceAtMost(rounds.coerceAtLeast(1))
        // "כל בוס נפגע משילוב אחר": the event boss has a weakness too (softer: none = half, one = normal, both = double)
        val fresh = runCatching { AppGraph.db.sessions().byId(s.id) }.getOrNull() ?: s
        val grade = runCatching { BossWeakness.grade(BossWeakness.forEvent(w.event.id), BossFacts.collect(fresh, sets)) }
            .getOrDefault(WeakGrade.PARTIAL)
        val (num, den) = BossWeakness.eventMult(grade)
        val damage = (base * num / den).coerceAtLeast(1)
        var hit: Hit? = null
        AppGraph.prefs.update { p ->
            val cur = parse(p.eventBossState)?.takeIf { it.key == w.key } ?: fresh(w)
            if (cur.defeated) p
            else {
                val dealt = damage.coerceAtMost(cur.hp)
                val hp = cur.hp - dealt
                val down = hp <= 0
                hit = Hit(w.event.nameHe, w.event.bossNameHe, dealt, hp, cur.maxHp, down, w.event.defeatedHe, w.event.badgeId)
                p.copy(eventBossState = encode(cur.copy(hp = hp, defeated = down)))
            }
        }
        val h = hit ?: return null
        if (h.defeatedNow) {
            AppGraph.game.grant(SeasonalEvents.DEFEAT_XP, SeasonalEvents.DEFEAT_GOLD, "event:${w.key}")
            runCatching { AppGraph.game.unlockBadge(w.event.badgeId) }
        }
        hits[s.id] = h
        return h
    }
}
