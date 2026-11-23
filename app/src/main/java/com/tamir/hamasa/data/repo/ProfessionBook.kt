package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.ProfessionId
import com.tamir.hamasa.domain.game.Professions

/**
 * "מקצוע לשמביולוק": the stored choice (Prefs.professionId / professionChosenDay) and the one hook that needs the DB
 * (the wanderer's map station). The rules live in domain/game/Professions.kt; see docs/professions.md.
 */
object ProfessionBook {
    suspend fun unlocked(): Boolean = Professions.unlocked(LevelTable.levelFor(AppGraph.prefs.current().xp))

    /** The profession that counts now, or null (none chosen, or the level gate is still closed). */
    suspend fun active(): ProfessionId? {
        val p = AppGraph.prefs.current()
        return Professions.active(p.professionId, p.xp)
    }

    /**
     * Picks [id]. The first choice is free; a change waits for the next season (calendar quarter).
     * Nothing is taken away: gear, levels and badges stay; only which bonuses count changes.
     * Returns Shambiuluk's line, or null when nothing changed.
     */
    suspend fun choose(id: ProfessionId, today: Long = Days.today()): String? {
        if (!unlocked()) return null
        var ok = false
        AppGraph.prefs.update { p ->
            val cur = Professions.byName(p.professionId)
            if (cur == id) return@update p
            if (cur != null && !Professions.canSwitch(p.professionChosenDay, today)) return@update p
            ok = true
            p.copy(professionId = id.name, professionChosenDay = today)
        }
        if (ok) runCatching { CloudSync.pushSoon(AppGraph.appContext) }
        return if (ok) Professions.chosenLineHe(id) else null
    }

    /**
     * Wanderer perk, called by WorkoutRepository.finishSession once the session row is DONE: every second
     * finished walk or run (watch-imported rows included, they are DONE sessions too) gives +1 map station.
     * Returns the stations added (0 or 1). Only while the journey is on (mapStartDone >= 0).
     */
    suspend fun wandererStation(type: SessionType): Int {
        if (type != SessionType.WALK && type != SessionType.RUN) return 0
        val p = AppGraph.prefs.current()
        if (p.mapStartDone < 0) return 0
        val profession = Professions.active(p.professionId, p.xp)
        val done = AppGraph.db.sessions().doneSessionsOnce().count { it.type == SessionType.WALK || it.type == SessionType.RUN }
        val add = Professions.wandererStation(profession, type, done)
        if (add > 0) AppGraph.prefs.update { it.copy(mapBonusStations = it.mapBonusStations + add) }
        return add
    }
}
