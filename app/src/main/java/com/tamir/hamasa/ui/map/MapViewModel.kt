package com.tamir.hamasa.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.BossProgressEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Boss
import com.tamir.hamasa.domain.game.BossCatalog
import com.tamir.hamasa.data.repo.BossFights
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Stations passed on the journey: one per finished session plus the extra stations endurance gave
 * (Prefs.mapBonusStations, see StatEffects.stationsPerSession). Used by the map AND the home scene region.
 */
fun journeyStations(): Flow<Int> =
    combine(AppGraph.db.sessions().doneCount(), AppGraph.prefs.profile) { done, p ->
        // the journey starts when the map opens (level 6): sessions before that don't move him
        if (p.mapStartDone < 0) 0 else (done - p.mapStartDone).coerceAtLeast(0) + p.mapBonusStations.coerceAtLeast(0)
    }

/** The journey map: one station per finished session, and the boss of the current week. */
class MapViewModel : ViewModel() {
    /** Monday (epoch day) of the current week. */
    val weekStart: Long = Days.weekStartOf(Days.today())

    /** The boss this week, from the catalog rotation (same formula as WorkoutRepository). */
    val boss: Boss = BossCatalog.forWeek(weekStart / 7)

    /** Stations passed: finished sessions + endurance bonus stations. */
    val distance: StateFlow<Int> =
        journeyStations().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Progress row for this week, null until the first hit. */
    val progress: StateFlow<BossProgressEntity?> =
        AppGraph.db.game().bossForWeek(weekStart)
            .map { BossFights.normalize(boss, weekStart, it) }   // round-model HP (old rows scaled)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
