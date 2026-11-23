package com.tamir.hamasa.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.CoachWhy
import com.tamir.hamasa.data.health.WatchSleep
import com.tamir.hamasa.domain.coach.HomeGear
import com.tamir.hamasa.domain.coach.HomeRequest
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.domain.coach.WorkoutPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the "אימון בבית" sheet: his choices (remembered in Prefs) and the plan the coach built from them. */
data class HomeWorkoutUiState(
    val loaded: Boolean = false,
    /** [HomeGear] keys; body weight is always in. */
    val gear: Set<String> = setOf(HomeGear.BODY),
    /** "עד כמה ק\"ג?" as typed. */
    val dumbbellKgText: String = "",
    val dumbbellFixed: Boolean = false,
    val kettlebellKgText: String = "",
    /** 20 / 30 / 45 / 60; 0 = not chosen. */
    val minutes: Int = 0,
    val focus: String = HomeGear.FOCUS_FULL,
    /** Built plan; null until "בנה אימון" (and again after any change). */
    val plan: WorkoutPlan? = null,
    val building: Boolean = false,
    val starting: Boolean = false
) {
    /** The request the coach builds from. */
    fun request(): HomeRequest {
        val hasDb = HomeGear.DUMBBELL in gear
        val dbKg = if (hasDb) HomeGear.parseKg(dumbbellKgText) else 0f
        return HomeRequest(
            gear = gear + HomeGear.BODY,
            dumbbellMaxKg = dbKg,
            dumbbellFixed = hasDb && dumbbellFixed && dbKg > 0f,
            kettlebellKg = if (HomeGear.KETTLEBELL in gear) HomeGear.parseKg(kettlebellKgText) else 0f,
            maxMinutes = if (minutes > 0) minutes else null,
            focus = focus
        )
    }
}

class HomeWorkoutViewModel : ViewModel() {
    private val _state = MutableStateFlow(HomeWorkoutUiState())
    val state: StateFlow<HomeWorkoutUiState> = _state

    /** Sleep the plan is built with: the coach screen's slider, else today's value in Prefs, else the default. */
    private var sleepHours: Float = WatchSleep.DEFAULT_HOURS
    private var buildJob: Job? = null

    /** Called each time the sheet opens: his saved choices, no plan yet. */
    fun open(sleepFromScreen: Float?) {
        buildJob?.cancel()
        _state.update { it.copy(plan = null, building = false, starting = false) }
        viewModelScope.launch {
            val p = AppGraph.prefs.current()
            sleepHours = sleepFromScreen ?: WatchSleep.todayHours(p) ?: WatchSleep.DEFAULT_HOURS
            val r = AppGraph.workouts.homeRequestOf(p)
            _state.update {
                it.copy(
                    loaded = true,
                    gear = r.gear + HomeGear.BODY,
                    dumbbellKgText = HomeGear.kgText(r.dumbbellMaxKg),
                    dumbbellFixed = r.dumbbellFixed,
                    kettlebellKgText = HomeGear.kgText(r.kettlebellKg),
                    minutes = r.maxMinutes ?: 0,
                    focus = r.focus
                )
            }
        }
    }

    fun toggleGear(key: String) {
        if (key == HomeGear.BODY) return // body weight is always there
        _state.update { s ->
            val g = if (key in s.gear) s.gear - key else s.gear + key
            s.copy(gear = g, plan = null)
        }
    }

    private fun cleanKg(text: String): String = text.filter { it.isDigit() || it == '.' || it == ',' }.take(5)

    fun setDumbbellKg(text: String) {
        _state.update { it.copy(dumbbellKgText = cleanKg(text), plan = null) }
    }

    fun setDumbbellFixed(fixed: Boolean) {
        _state.update { it.copy(dumbbellFixed = fixed, plan = null) }
    }

    fun setKettlebellKg(text: String) {
        _state.update { it.copy(kettlebellKgText = cleanKg(text), plan = null) }
    }

    /** Tapping the chosen time again clears it (no limit). */
    fun setMinutes(m: Int) {
        _state.update { it.copy(minutes = if (it.minutes == m) 0 else m, plan = null) }
    }

    fun setFocus(f: String) {
        _state.update { it.copy(focus = f, plan = null) }
    }

    /** "בנה אימון": saves the choices and asks the coach for the plan. */
    fun build() {
        val s = _state.value
        if (s.building || s.starting) return
        val req = s.request()
        _state.update { it.copy(building = true) }
        buildJob?.cancel()
        buildJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { AppGraph.workouts.saveHomeRequest(req) }
            val plan = runCatching { AppGraph.workouts.homePlan(req, sleepHours) }.getOrNull()
            _state.update { it.copy(plan = plan, building = false) }
        }
    }

    /** "התחל": the normal session path (rewards, boss, story, music), notes "home". */
    fun start(onStarted: (Long) -> Unit) {
        val s = _state.value
        val plan = s.plan ?: return
        if (s.starting || plan.exercises.isEmpty()) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val id = runCatching { AppGraph.workouts.startHome(plan, sleepHours) }.getOrNull()
            withContext(Dispatchers.Main) {
                _state.update { it.copy(starting = false) }
                if (id != null) onStarted(id)
            }
        }
    }

    /** "למה?" of a previewed exercise, explained with his home weights. */
    suspend fun why(p: PlannedExercise): String =
        CoachWhy.forPlanned(p, sleepHours, home = true, homeRequest = _state.value.request())
}
