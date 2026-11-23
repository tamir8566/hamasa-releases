package com.tamir.hamasa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.health.WatchSleep
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.SleepBand
import com.tamir.hamasa.domain.coach.sleepBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RollUiState(
    val profile: Profile? = null,
    val rolled: Boolean = false,
    val sleepHours: Float = 7f,
    val flipped: List<Boolean> = listOf(false, false, false),
    val starting: Boolean = false,
    /** The user's own workouts; when not empty "יוצאים" first asks which workout. */
    val templates: List<WorkoutTemplateEntity> = emptyList(),
    /** Every mode he has open (the "joker" picker). */
    val availableModes: List<String> = emptyList(),
    /** Last night's sleep ruled the rolled mode out and a fitting one replaced it. */
    val modeChangedForSleep: Boolean = false
)

class MorningRollViewModel : ViewModel() {
    private val _state = MutableStateFlow(RollUiState())
    val state: StateFlow<RollUiState> = _state

    /** Today's sleep in Prefs (watch or slider) last applied to the screen. */
    private var lastSleepKey: String? = null
    /** The slider is being dragged: a Prefs update must not yank it back. */
    private var sleepDragging = false
    /** Slider value when the drag / tap began: releasing it on the same value is not a manual choice. */
    private var sleepBeforeDrag: Float? = null

    init {
        viewModelScope.launch {
            val today = Days.today()
            // last night's sleep first: the watch gets about 3 seconds (a slower read still lands later)
            runCatching { WatchSleep.quickSync(AppGraph.appContext) }
            runCatching { applySleepFrom(AppGraph.prefs.current()) }
            val band = sleepBand(_state.value.sleepHours)
            val modes = AppGraph.game.availableModeIds()
            AppGraph.game.rollMorning(today, modes, band = band)
            // rolled earlier today with another night in mind: fit it now (no-op when it already fits)
            val changed = band != SleepBand.FULL && AppGraph.game.fitRollToSleep(today, band)
            _state.update { it.copy(rolled = true, availableModes = modes, modeChangedForSleep = it.modeChangedForSleep || changed) }
        }
        viewModelScope.launch {
            AppGraph.prefs.profile.collect { p ->
                _state.update { it.copy(profile = p) }
                applySleepFrom(p)
            }
        }
        viewModelScope.launch {
            AppGraph.workouts.templates.collect { list -> _state.update { it.copy(templates = list) } }
        }
    }

    /** Starts one of the user's templates with today's sleep hours; mode, goal and bonus apply like a coach workout. */
    fun startTemplate(templateId: Long, onStarted: (Long) -> Unit) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                val hours = _state.value.sleepHours
                fitModeToSleep()
                val id = AppGraph.workouts.startFromTemplate(templateId, hours)
                onStarted(id)
            } finally {
                _state.update { it.copy(starting = false) }
            }
        }
    }

    /** Today's sleep from Prefs (the watch, or the coach screen's slider) when it changed there. */
    private fun applySleepFrom(p: Profile) {
        val key = "${Days.today()}|${p.sleepDay}|${p.sleepHoursSet}|${p.sleepManual}"
        if (key == lastSleepKey) return
        lastSleepKey = key
        if (sleepDragging) return
        val hours = WatchSleep.todayHours(p) ?: WatchSleep.DEFAULT_HOURS
        if (hours != _state.value.sleepHours) applyHours(hours)
    }

    /** The slider moves (not stored yet; [sleepCommitted] stores it when released). */
    fun setSleep(hours: Float) {
        if (!sleepDragging) sleepBeforeDrag = _state.value.sleepHours
        sleepDragging = true
        applyHours(hours)
    }

    /** Slider released: his value holds for today (coach screen too) and wins over the watch. */
    fun sleepCommitted() {
        sleepDragging = false
        val before = sleepBeforeDrag
        sleepBeforeDrag = null
        val hours = _state.value.sleepHours
        // a stray tap that left the value as it was must not lock out the watch for today
        if (before == null || before == hours) {
            val p = _state.value.profile
            val stored = if (p != null) WatchSleep.todayHours(p) ?: WatchSleep.DEFAULT_HOURS else hours
            if (stored != hours) applyHours(stored)
            return
        }
        viewModelScope.launch { runCatching { WatchSleep.setManual(hours) } }
    }

    private fun applyHours(hours: Float) {
        val before = sleepBand(_state.value.sleepHours)
        _state.update { it.copy(sleepHours = hours) }
        val after = sleepBand(hours)
        if (after != before && _state.value.rolled) {
            viewModelScope.launch {
                if (AppGraph.game.fitRollToSleep(Days.today(), after)) _state.update { it.copy(modeChangedForSleep = true) }
            }
        }
    }

    /** Applies last night's sleep to the rolled mode before a workout starts (no-op when it already fits). */
    private suspend fun fitModeToSleep() {
        if (AppGraph.game.fitRollToSleep(Days.today(), sleepBand(_state.value.sleepHours))) {
            _state.update { it.copy(modeChangedForSleep = true) }
        }
    }

    /** Mode "joker": he picks today's mode from every mode he has open. */
    fun pickJokerMode(modeId: String) {
        viewModelScope.launch { AppGraph.game.pickJokerMode(Days.today(), modeId) }
    }

    fun flip(index: Int) = _state.update { s ->
        if (index !in s.flipped.indices || s.flipped[index]) s
        else s.copy(flipped = s.flipped.mapIndexed { i, f -> if (i == index) true else f })
    }

    /** "אימון מינימלי" from the chooser: about 20 minutes of today's compound lifts. */
    fun startMinimal(onStarted: (Long) -> Unit) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                val hours = _state.value.sleepHours
                val plan = AppGraph.workouts.minimalPlan()
                val id = AppGraph.workouts.startSession(plan, hours, null, notes = "minimal")
                onStarted(id)
            } finally {
                _state.update { it.copy(starting = false) }
            }
        }
    }

    fun start(type: SessionType, onStarted: (Long) -> Unit) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                val hours = _state.value.sleepHours
                fitModeToSleep()
                val p = AppGraph.prefs.current()
                val plan = AppGraph.workouts.planFor(type, hours)
                val id = AppGraph.workouts.startSession(plan, hours, p.rollGoal.ifEmpty { null })
                onStarted(id)
            } finally {
                _state.update { it.copy(starting = false) }
            }
        }
    }
}
