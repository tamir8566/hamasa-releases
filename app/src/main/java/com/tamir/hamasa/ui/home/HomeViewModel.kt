package com.tamir.hamasa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.AiVoice
import com.tamir.hamasa.data.ai.AiClient
import com.tamir.hamasa.data.ai.VoiceContext
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.WeightEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.map.regionIndex
import com.tamir.hamasa.ui.map.journeyStations
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    val profile: StateFlow<Profile?> = AppGraph.prefs.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val latestWeight: StateFlow<WeightEntity?> = AppGraph.workouts.latestWeight
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeSession: StateFlow<SessionEntity?> = AppGraph.workouts.activeSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Current map region (0 = הכפר .. 10 = איי השמיים), the same formula the map uses: finished sessions / 10. */
    val currentRegion: StateFlow<Int> = journeyStations()   // same stations as the map (sessions + endurance bonus)
        .map { regionIndex(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        if (!pulledThisProcess) {
            viewModelScope.launch {
                runCatching {
                    // Never before onboarding, and never while the server mixes all users' rows together
                    // (the pull could otherwise restore someone else's data on a fresh profile).
                    if (!AppGraph.prefs.current().onboarded) return@runCatching
                    val cloud = com.tamir.hamasa.data.cloud.CloudSync
                    if (cloud.serverSeparatesUsers() != true) return@runCatching
                    pulledThisProcess = true
                    cloud.pullAll(AppGraph.appContext)
                    Unit
                }
            }
        }
        // a workout left running on an earlier day is closed like "ביטול אימון" (no rewards given or taken)
        viewModelScope.launch { runCatching { AppGraph.workouts.closeStaleActiveSessions() } }
    }

    private companion object {
        /** Restore from the cloud at most once per app process (a fresh install only needs it once). */
        @Volatile var pulledThisProcess = false
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /**
     * Stage 2 G: today's Gemini line for the morning bubble (cached in Prefs, one per day); null = the regular
     * Voice line. Runs on the app scope so a cached day is never lost when the screen goes away. Never blocks.
     */
    private val _aiMorning = MutableStateFlow<String?>(null)
    val aiMorning: StateFlow<String?> = _aiMorning

    init {
        if (AiClient.enabled) {
            AppGraph.scope.launch {
                val line = runCatching {
                    val p = AppGraph.prefs.current()
                    // no network line before the profile exists (onboarding not finished)
                    if (!p.onboarded) return@runCatching null
                    val today = Days.today()
                    val planned = if (p.lastCloseDay == today - 1L) plannedHe(p.plannedTomorrowType) else null
                    AiVoice.morningLine(VoiceContext.Morning(Days.hebrewDayName(today), p.streak, planned))
                }.getOrNull()
                if (line != null) _aiMorning.value = line
            }
        }
    }

    private fun plannedHe(typeName: String): String? = when (typeName) {
        "GYM" -> "מכון"
        "YOGA" -> "יוגה"
        "WALK" -> "הליכה"
        "MINI" -> "אימון זעיר"
        "RUN" -> "ריצה"
        else -> null
    }

    /** The user's own workouts ("האימונים שלי"); when not empty the start button offers a choice. */
    val templates: StateFlow<List<WorkoutTemplateEntity>> = AppGraph.workouts.templates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Starts one of the user's templates through the same session path as the coach (mode, goal, bonus all apply). */
    fun startTemplate(templateId: Long, onStarted: (Long) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val id = AppGraph.workouts.startFromTemplate(
                    templateId,
                    // today's sleep as the morning roll uses it (watch or his slider); null when unknown
                    runCatching { com.tamir.hamasa.data.health.WatchSleep.todayHours(AppGraph.prefs.current()) }.getOrNull()
                )
                onStarted(id)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Creates a GYM session from today's plan and returns its id through [onStarted]. */
    fun startGym(onStarted: (Long) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val p = AppGraph.prefs.current()
                val plan = AppGraph.workouts.planFor(SessionType.GYM, null)
                val id = AppGraph.workouts.startSession(plan, null, p.rollGoal.ifEmpty { null })
                onStarted(id)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Saves tomorrow's plan. The first close of each day grants +5 XP (spec "סגירת היום בערב | +5"). */
    fun closeDay(type: SessionType, hour: Int) {
        viewModelScope.launch {
            val today = Days.today()
            var firstToday = false
            AppGraph.prefs.update {
                firstToday = it.lastCloseDay != today
                it.copy(plannedTomorrowType = type.name, plannedTomorrowHour = hour, lastCloseDay = today)
            }
            if (firstToday) AppGraph.game.grant(5, 0, "close_day")
            // stage 2 F: meal-photo bonus and calorie hunt (each once per day)
            runCatching { com.tamir.hamasa.data.repo.MealGame.settleDay(today) }
        }
    }

    // ------------------------------------------------------------ small sessions (stage 1 A)

    /** Runs [build] (which creates a session and returns its id, or null) once at a time. */
    private fun launchStart(onStarted: (Long) -> Unit, build: suspend () -> Long?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val id = build()
                if (id != null) onStarted(id)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Starts a coach session of [type] (GYM, YOGA, MINI...). WALK goes through [startWalk]. */
    fun startType(type: SessionType, onStarted: (Long) -> Unit) = launchStart(onStarted) {
        val p = AppGraph.prefs.current()
        val plan = AppGraph.workouts.planFor(type, null)
        AppGraph.workouts.startSession(plan, null, p.rollGoal.ifEmpty { null })
    }

    /** "אימון זעיר": 10–15 minutes of home bodyweight work. */
    fun startMini(onStarted: (Long) -> Unit) = startType(SessionType.MINI, onStarted)

    /** "הליכה": one walk item of [minutes]. */
    fun startWalk(minutes: Int, onStarted: (Long) -> Unit) = launchStart(onStarted) {
        val plan = AppGraph.workouts.walkPlan(minutes)
        AppGraph.workouts.startSession(plan, null, null, notes = "walk")
    }

    /** "אימון מינימלי": about 20 minutes, today's compound lifts only. */
    fun startMinimal(onStarted: (Long) -> Unit) = launchStart(onStarted) {
        val plan = AppGraph.workouts.minimalPlan()
        AppGraph.workouts.startSession(plan, null, null, notes = "minimal")
    }

    /** "כפתור חירום": 12 minutes, limited to 2 per calendar month. [onNoneLeft] when the month is used up. */
    fun startEmergency(onStarted: (Long) -> Unit, onNoneLeft: () -> Unit) = launchStart(onStarted) {
        val id = AppGraph.workouts.startEmergency()
        if (id == null) onNoneLeft()
        id
    }
}
