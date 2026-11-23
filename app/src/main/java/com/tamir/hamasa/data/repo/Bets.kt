package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days

/** What happened to the bet and the monster pot when a session was finished. 0 = nothing. */
data class BetOutcome(val betWonGold: Int = 0, val potReleasedGold: Int = 0)

/**
 * "הימור על עצמך": at day close he stakes gold that he will show up tomorrow.
 *  - a session finished on that day   -> the stake comes back doubled
 *  - the day passed without a session -> the stake moves into "קופת המפלצת" (Prefs.monsterPot)
 *  - a full week (Sunday..Saturday) with sessions on [FULL_WEEK_DAYS] different days releases the pot back to him.
 * The stake is taken from his gold when the bet is placed (ledger reason "bet:place").
 */
object Bets {
    val STAKES = listOf(0, 20, 50, 100)
    const val FULL_WEEK_DAYS = 4

    /** Places a bet on tomorrow. Returns false when a bet is already open, the stake is 0, or gold is missing. */
    suspend fun place(stake: Int, today: Long = Days.today()): Boolean {
        if (stake <= 0) return false
        val p = AppGraph.prefs.current()
        if (p.betStake > 0) return false
        if (!AppGraph.game.spendGold(stake, "bet:place")) return false
        AppGraph.prefs.update { it.copy(betStake = stake, betDay = today + 1) }
        return true
    }

    /**
     * Moves an expired bet (its day is over and no session was finished on it) into the monster pot.
     * Safe to call any number of times (home screen open, reminder worker).
     */
    suspend fun settleMissed(today: Long = Days.today()) {
        val p = AppGraph.prefs.current()
        if (p.betStake <= 0 || p.betDay >= today) return
        val showed = AppGraph.db.sessions().doneBetween(p.betDay, p.betDay) > 0
        if (showed) {
            // finished on that day but was not resolved (should not happen): he still wins.
            val stake = p.betStake
            AppGraph.prefs.update { it.copy(betStake = 0, betDay = 0L) }
            AppGraph.game.grant(0, stake * 2, "bet:win")
            return
        }
        AppGraph.prefs.update { it.copy(monsterPot = it.monsterPot + it.betStake, betStake = 0, betDay = 0L) }
    }

    /** Called by WorkoutRepository.finishSession after the session is marked DONE. */
    suspend fun onSessionDone(day: Long): BetOutcome {
        settleMissed(day)
        var won = 0
        val p = AppGraph.prefs.current()
        if (p.betStake > 0 && p.betDay == day) {
            won = p.betStake * 2
            AppGraph.prefs.update { it.copy(betStake = 0, betDay = 0L) }
            AppGraph.game.grant(0, won, "bet:win")
        }
        var released = 0
        val pot = AppGraph.prefs.current().monsterPot
        if (pot > 0) {
            val from = Days.weekStartOf(day)
            val days = AppGraph.db.sessions().doneSessionsOnce()
                .filter { it.dateEpochDay in from..(from + 6) }
                .map { it.dateEpochDay }
                .toSet().size
            if (days >= FULL_WEEK_DAYS) {
                released = pot
                AppGraph.prefs.update { it.copy(monsterPot = 0) }
                AppGraph.game.grant(0, released, "pot:release")
            }
        }
        return BetOutcome(won, released)
    }
}
