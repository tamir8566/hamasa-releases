package com.tamir.hamasa.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.CoachNotes
import com.tamir.hamasa.data.ai.CoachWhy
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.health.WatchSleep
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.CoachConstraints
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.domain.coach.WorkoutPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CoachUiState(
    val profile: Profile? = null,
    val sleepHours: Float = 7f,
    val yogaInstead: Boolean = false,
    val plan: WorkoutPlan? = null,
    val loading: Boolean = true,
    val starting: Boolean = false,
    /** Exercise id whose alternatives are open, plus the list. Null when closed. */
    val altFor: String? = null,
    val alternatives: List<PlannedExercise> = emptyList(),
    /** The user's own exercises by id (for units, descriptions and names the catalog does not know). */
    val customExercises: Map<String, ExerciseEntity> = emptyMap(),
    /** Calorie goal suggested after a focus change (asked once per focus); null = nothing to ask. */
    val kcalSuggestion: Int? = null,
    // --- stage 2 G: note to the coach ---
    val noteText: String = "",
    val noteSending: Boolean = false,
    /** The coach's reply to the last note (shown under the card until dismissed); null = none. */
    val noteReply: String? = null,
    /** The last note asked for a doctor / physio line. */
    val noteDoctor: Boolean = false,
    /** Limits active today (from Prefs); null = none. */
    val constraints: CoachConstraints? = null,
    /** Details dialog of the active limits is open. */
    val constraintsOpen: Boolean = false,
    /** The running coach: next plan session, rest day, progress (data/repo/RunCoach); null until loaded. */
    val runStatus: com.tamir.hamasa.data.repo.RunStatus? = null,
    /** Today's difficulty (a note's override or the setting). */
    val difficulty: com.tamir.hamasa.domain.coach.Difficulty = com.tamir.hamasa.domain.coach.Difficulty.NORMAL,
    /** "אימון בבית" sheet is open (HomeWorkoutDialog). */
    val homeOpen: Boolean = false
)

class CoachViewModel : ViewModel() {
    private val _state = MutableStateFlow(CoachUiState())
    val state: StateFlow<CoachUiState> = _state

    private var buildJob: Job? = null
    private var lastFocus: Focus? = null
    private var kcalHandledFocus: String? = null
    private var lastConstraintsKey: String? = null
    /** Today's sleep in Prefs (watch or slider) last applied to the screen. */
    private var lastSleepKey: String? = null
    /** The slider is being dragged: a Prefs update must not yank it back. */
    private var sleepDragging = false
    /** Slider value when the drag / tap began: releasing it on the same value is not a manual choice. */
    private var sleepBeforeDrag: Float? = null
    /** Note whose "יוגה היום" preference already switched the yoga toggle (once per note). */
    private var yogaPrefHandled: String? = null

    init {
        viewModelScope.launch {
            AppGraph.prefs.profile.collect { p ->
                val focusChanged = lastFocus != null && lastFocus != p.focus
                lastFocus = p.focus
                // note limits: a new / cleared / expired note changes today's plan
                // an accepted / cancelled deload week changes it too
                val constraintsKey = "${p.coachConstraintsJson}|${p.coachConstraintsUntil}|${p.deloadWeekStart}|${p.coachDifficulty}"
                val constraintsChanged = lastConstraintsKey != null && lastConstraintsKey != constraintsKey
                lastConstraintsKey = constraintsKey
                val active = CoachNotes.active(p, Days.today())
                _state.update {
                    it.copy(
                        profile = p, constraints = active,
                        runStatus = com.tamir.hamasa.data.repo.RunCoach.status(p, Days.today()),
                        difficulty = CoachNotes.effectiveDifficulty(p, Days.today())
                    )
                }
                // a note "יוגה היום": the yoga toggle starts on (once per note; he can switch it back)
                if (active != null && active.sessionType == "YOGA" && yogaPrefHandled != p.coachConstraintsJson) {
                    yogaPrefHandled = p.coachConstraintsJson
                    if (!_state.value.yogaInstead) {
                        _state.update { it.copy(yogaInstead = true) }
                        rebuild()
                    }
                }
                // last night's sleep: from the watch (WatchSleep) or set earlier today on either slider
                var sleepChanged = false
                val sleepKey = "${Days.today()}|${p.sleepDay}|${p.sleepHoursSet}|${p.sleepManual}"
                if (sleepKey != lastSleepKey) {
                    lastSleepKey = sleepKey
                    val hours = WatchSleep.todayHours(p) ?: WatchSleep.DEFAULT_HOURS
                    if (!sleepDragging && hours != _state.value.sleepHours) {
                        _state.update { it.copy(sleepHours = hours) }
                        sleepChanged = true
                    }
                }
                if (_state.value.plan == null || focusChanged || constraintsChanged || sleepChanged) rebuild()
                if (p.kcalAskedFocus != p.focus.name) checkKcalSuggestion(p)
            }
        }
        // the running coach: late heart rate of the last run and watch runs that may count for the plan
        viewModelScope.launch(Dispatchers.IO) { runCatching { com.tamir.hamasa.data.repo.RunCoach.sync() } }
        viewModelScope.launch {
            var first = true
            AppGraph.workouts.customExercises.collect { list ->
                _state.update { it.copy(customExercises = list.associateBy { e -> e.id }) }
                // A new / edited / deleted custom exercise can change today's plan.
                if (!first) rebuild()
                first = false
            }
        }
    }

    fun setSleep(hours: Float) {
        if (!sleepDragging) sleepBeforeDrag = _state.value.sleepHours
        sleepDragging = true
        _state.update { it.copy(sleepHours = hours) }
    }

    /**
     * Called when the slider is released, so we do not rebuild on every pixel. His value is stored for today
     * (morning roll too) and wins over the watch.
     */
    fun sleepCommitted() {
        sleepDragging = false
        val before = sleepBeforeDrag
        sleepBeforeDrag = null
        val hours = _state.value.sleepHours
        // a stray tap (e.g. while scrolling) that left the value as it was must not lock out the watch for today
        if (before == null || before == hours) {
            val p = _state.value.profile
            val stored = if (p != null) WatchSleep.todayHours(p) ?: WatchSleep.DEFAULT_HOURS else hours
            if (stored != hours) _state.update { it.copy(sleepHours = stored) }
            rebuild()
            return
        }
        viewModelScope.launch { runCatching { WatchSleep.setManual(hours) } }
        rebuild()
    }

    fun setYoga(on: Boolean) {
        _state.update { it.copy(yogaInstead = on) }
        rebuild()
    }

    fun setFocus(f: Focus) {
        viewModelScope.launch { AppGraph.prefs.update { it.copy(focus = f) } }
    }

    /** "רמת קושי": stored in Prefs (and the cloud); the profile flow rebuilds the plan. */
    fun setDifficulty(d: com.tamir.hamasa.domain.coach.Difficulty) {
        viewModelScope.launch {
            AppGraph.prefs.update { it.copy(coachDifficulty = d.name) }
            runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
        }
    }

    /** "התחל ריצה": the next session of the run plan. */
    fun startRun(onStarted: (Long) -> Unit) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val id = runCatching { com.tamir.hamasa.data.repo.RunCoach.startNext() }.getOrNull()
            withContext(Dispatchers.Main) {
                _state.update { it.copy(starting = false) }
                if (id != null) onStarted(id)
            }
        }
    }

    /** A note "הליכה היום": a walk of the note's minutes (default 30). */
    fun startWalk(onStarted: (Long) -> Unit) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        val minutes = _state.value.constraints?.maxMinutes ?: com.tamir.hamasa.data.repo.WorkoutRepository.DEFAULT_WALK_MINUTES
        viewModelScope.launch(Dispatchers.IO) {
            val id = runCatching {
                val plan = AppGraph.workouts.walkPlan(minutes)
                AppGraph.workouts.startSession(plan, null, null, notes = "walk")
            }.getOrNull()
            withContext(Dispatchers.Main) {
                _state.update { it.copy(starting = false) }
                if (id != null) onStarted(id)
            }
        }
    }

    /**
     * The focus changed since the calorie goal was last offered: suggest a goal for the new focus, once.
     * The very first focus (fresh install / before this feature) is recorded silently. Never overwrites by itself.
     */
    private fun checkKcalSuggestion(p: Profile) {
        // already asked (dialog open) or just answered and the prefs write is still on its way
        if (_state.value.kcalSuggestion != null || kcalHandledFocus == p.focus.name) return
        viewModelScope.launch(Dispatchers.IO) {
            val focusName = p.focus.name
            // the energy calculator owns an automatic goal: record the focus silently, no question
            if (p.kcalAskedFocus.isEmpty() || p.kcalAuto) {
                AppGraph.prefs.update { it.copy(kcalAskedFocus = focusName) }
                return@launch
            }
            val bw = AppGraph.db.weights().latestOnce()?.kg ?: p.startWeightKg
            val kcal = CoachEngine.suggestedKcal(p.focus, bw)
            if (kcal == p.kcalGoal) {
                AppGraph.prefs.update { it.copy(kcalAskedFocus = focusName) }
            } else {
                _state.update { it.copy(kcalSuggestion = kcal) }
            }
        }
    }

    /** "לעדכן יעד קלוריות ל-X?": yes stores the goal; either answer closes the question for this focus. */
    fun answerKcal(accept: Boolean) {
        val kcal = _state.value.kcalSuggestion ?: return
        kcalHandledFocus = _state.value.profile?.focus?.name
        _state.update { it.copy(kcalSuggestion = null) }
        viewModelScope.launch {
            AppGraph.prefs.update { cur ->
                if (accept) cur.copy(kcalGoal = kcal, kcalAskedFocus = cur.focus.name, kcalAuto = false)
                else cur.copy(kcalAskedFocus = cur.focus.name)
            }
            if (accept) com.tamir.hamasa.widget.Widgets.refreshAll(AppGraph.appContext)
        }
    }

    // ------------------------------------------------------------ note to the coach (stage 2 G)

    fun setNote(text: String) {
        _state.update { it.copy(noteText = text.take(600)) }
    }

    /** Sends the note: Gemini (or the offline keyword rules) -> validated limits -> Prefs. The profile flow rebuilds the plan. */
    fun sendNote() {
        val s = _state.value
        val note = s.noteText.trim()
        if (note.isEmpty() || s.noteSending) return
        _state.update { it.copy(noteSending = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val customs = runCatching { AppGraph.workouts.customExercisesOnce() }.getOrDefault(emptyList())
            val result = CoachNotes.interpret(note, customs, Days.today())
            val c = result.constraints
            if (c != null) runCatching { CoachNotes.save(c) }
            _state.update {
                it.copy(
                    noteSending = false,
                    noteText = if (c != null) "" else it.noteText,
                    noteReply = result.replyHe.ifEmpty { null },
                    noteDoctor = result.needsDoctor
                )
            }
        }
    }

    fun dismissNoteReply() {
        _state.update { it.copy(noteReply = null, noteDoctor = false) }
    }

    /** "אימון בבית": opens the equipment sheet. */
    fun openHome() {
        _state.update { it.copy(homeOpen = true) }
    }

    fun closeHome() {
        _state.update { it.copy(homeOpen = false) }
    }

    fun openConstraints() {
        _state.update { it.copy(constraintsOpen = true) }
    }

    fun closeConstraints() {
        _state.update { it.copy(constraintsOpen = false) }
    }

    /** "בטל": the limits end now; the plan goes back to normal. */
    fun clearConstraints() {
        _state.update { it.copy(constraintsOpen = false, noteReply = null, noteDoctor = false) }
        viewModelScope.launch(Dispatchers.IO) { CoachNotes.clear() }
    }

    /** "למה?" on a planned exercise of the preview. Never throws (template fallback inside). */
    suspend fun why(p: PlannedExercise): String = CoachWhy.forPlanned(p, _state.value.sleepHours)

    fun rebuild() {
        buildJob?.cancel()
        buildJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true) }
            val s = _state.value
            val type = if (s.yogaInstead) SessionType.YOGA else SessionType.GYM
            val plan = AppGraph.workouts.planFor(type, s.sleepHours)
            _state.update { it.copy(plan = plan, loading = false) }
        }
    }

    fun openAlternatives(exerciseId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = AppGraph.workouts.context(_state.value.sleepHours)
            val alts = CoachEngine.alternatives(exerciseId, ctx)
            _state.update { it.copy(altFor = exerciseId, alternatives = alts) }
        }
    }

    fun closeAlternatives() {
        _state.update { it.copy(altFor = null, alternatives = emptyList()) }
    }

    /** Replaces the exercise [fromId] in the preview with [alt], keeping its position and sets/rest. */
    fun swap(fromId: String, alt: PlannedExercise) {
        // The coach keeps this choice for the rest of the training block.
        viewModelScope.launch(Dispatchers.IO) { AppGraph.workouts.rememberSwap(fromId, alt.exerciseId) }
        _state.update { s ->
            val plan = s.plan ?: return@update s.copy(altFor = null, alternatives = emptyList())
            val list = plan.exercises.map { e ->
                if (e.exerciseId == fromId) alt.copy(sets = e.sets, restSec = e.restSec, supersetWith = e.supersetWith)
                else if (e.supersetWith == fromId) e.copy(supersetWith = alt.exerciseId)
                else e
            }
            s.copy(plan = plan.copy(exercises = list), altFor = null, alternatives = emptyList())
        }
    }

    fun start(onStarted: (Long) -> Unit) {
        val s = _state.value
        val plan = s.plan ?: return
        if (s.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val goal = s.profile?.rollGoal?.takeIf { it.isNotEmpty() }
            val id = AppGraph.workouts.startSession(plan, s.sleepHours, goal)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(starting = false) }
                onStarted(id)
            }
        }
    }
}
