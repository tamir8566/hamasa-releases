package com.tamir.hamasa.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.game.ModeVoice
import com.tamir.hamasa.domain.game.StatEffects
import com.tamir.hamasa.domain.game.SurpriseEvent
import com.tamir.hamasa.domain.game.SurpriseEvents
import com.tamir.hamasa.domain.game.Voice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * One advisory line from the coach during a workout the user built himself ("הג'ים ברו מציע").
 * [kind] decides what "החל" does; INFO lines have no apply action. Never applied automatically.
 */
data class CoachSuggestion(
    val key: String,
    val textHe: String,
    val kind: Kind,
    val exerciseId: String = "",
    val toExerciseId: String = "",
    val value: Int = 0,
    val weight: Float = 0f
) {
    enum class Kind { SWAP, REPS, ADD_SETS, WEIGHT_UP, INFO }
    val canApply: Boolean get() = kind != Kind.INFO
}

/** A surprise event of this workout: [accepted] = false while it is only offered. */
data class LiveSurpriseEvent(
    val event: SurpriseEvent,
    val exerciseId: String,
    val exerciseNameHe: String,
    val accepted: Boolean
) {
    val taskHe: String get() = event.task(exerciseNameHe)
}

/** State for one live workout session. */
class WorkoutViewModel(val sessionId: Long) : ViewModel() {

    /** Coach suggestions for a template session (empty for coach workouts). */
    private val _suggestions = MutableStateFlow<List<CoachSuggestion>>(emptyList())
    val suggestions: StateFlow<List<CoachSuggestion>> = _suggestions
    private var suggestionsBuilt = false

    val session: StateFlow<SessionEntity?> =
        AppGraph.workouts.session(sessionId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sets: StateFlow<List<SetLogEntity>> =
        AppGraph.workouts.sets(sessionId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** exerciseId -> entity, filled lazily as sets arrive (and after swaps). */
    private val _exercises = MutableStateFlow<Map<String, ExerciseEntity>>(emptyMap())
    val exercises: StateFlow<Map<String, ExerciseEntity>> = _exercises

    /** Seconds left in the current rest. 0 = no rest running. */
    private val _restRemaining = MutableStateFlow(0)
    val restRemaining: StateFlow<Int> = _restRemaining

    /** Incremented each time a rest timer reaches zero on its own (not skipped). UI vibrates on change. */
    private val _restEnded = MutableStateFlow(0)
    val restEnded: StateFlow<Int> = _restEnded

    /** Golden PR banner text, auto-cleared. */
    private val _prBanner = MutableStateFlow<String?>(null)
    val prBanner: StateFlow<String?> = _prBanner

    /** The surprise event: offered (accepted = false) or running (accepted = true); null = none. */
    private val _event = MutableStateFlow<LiveSurpriseEvent?>(null)
    val event: StateFlow<LiveSurpriseEvent?> = _event

    /** Result line of a completed event (reward, rare extra). Null = nothing to show. */
    private val _eventResult = MutableStateFlow<String?>(null)
    val eventResult: StateFlow<String?> = _eventResult

    /** One-off line of today's mode during the workout (mode "surprise" swapped an exercise). */
    private val _modeNote = MutableStateFlow<String?>(null)
    val modeNote: StateFlow<String?> = _modeNote

    /** Mode "pr_day": id of the set marked "ניסיון שיא" (last set of the first compound). Null = none. */
    private val _prAttemptSetId = MutableStateFlow<Long?>(null)
    val prAttemptSetId: StateFlow<Long?> = _prAttemptSetId

    private var surpriseTried = false
    private var restFocus: Focus? = null
    private var restDifficulty: com.tamir.hamasa.domain.coach.Difficulty = com.tamir.hamasa.domain.coach.Difficulty.NORMAL
    private var templateRests: Map<String, Int> = emptyMap()

    /** The interval timer of a plan run (RUN sessions with notes "run:..."); its ui stays null for other sessions. */
    val run = RunLiveState(sessionId, viewModelScope)

    /** Today's difficulty line for the live exercise cards of a gym session ("רמה רגילה · עוד 2 חזרות במאגר בכל סט"). */
    private val _difficultyLine = MutableStateFlow<String?>(null)
    val difficultyLine: StateFlow<String?> = _difficultyLine

    init {
        // mode "pr_day": keep the "ניסיון שיא" mark on the right set as sets arrive or get swapped
        viewModelScope.launch {
            sets.collect { list ->
                val s = session.value ?: AppGraph.db.sessions().byId(sessionId)
                _prAttemptSetId.value = if (s?.modeId == "pr_day") runCatching { prAttemptOf(list) }.getOrNull() else null
            }
        }
    }

    /** Swap dialog: exercise being swapped + alternatives (null = closed). */
    private val _swapFrom = MutableStateFlow<String?>(null)
    val swapFrom: StateFlow<String?> = _swapFrom
    private val _alternatives = MutableStateFlow<List<PlannedExercise>>(emptyList())
    val alternatives: StateFlow<List<PlannedExercise>> = _alternatives
    private val _swapLoading = MutableStateFlow(false)
    val swapLoading: StateFlow<Boolean> = _swapLoading

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private var restJob: Job? = null
    private var bannerJob: Job? = null

    /** "סט סודי": id of this session's secret set (stable once chosen), null = none. */
    private val _secretSetId = MutableStateFlow<Long?>(null)
    val secretSetId: StateFlow<Long?> = _secretSetId

    /** Shambiuluk's line for today's mood, shown at the start of the workout. Null = no mood chosen. */
    private val _moodLine = MutableStateFlow<String?>(null)
    val moodLine: StateFlow<String?> = _moodLine

    /** Live boss fight (bar at the top) and the "הקרב הקשה" flag. */
    private val battle = WorkoutBattle(sessionId)
    val bossBar: StateFlow<BossBarState?> = battle.bar
    val hardBattle: StateFlow<Boolean> = battle.hardBattle

    /** Stage 2 H: "אימון מסופר" (the chapter revealed set by set) and "צל של אתמול" (the ghost of last time). */
    private val v2Live = WorkoutV2Live(sessionId)
    val story: StateFlow<StoryLive?> = v2Live.story
    val ghosts: StateFlow<Map<String, List<com.tamir.hamasa.data.repo.GhostSet>>> = v2Live.ghosts

    init {
        viewModelScope.launch { runCatching { v2Live.load() } }
        viewModelScope.launch { sets.collect { list -> runCatching { v2Live.onSets(list) } } }
    }

    /** "מוזיקה שמגיבה": one short line when the boss entrance / last-set song starts, or when Spotify stays silent. */
    private val _musicNote = MutableStateFlow<String?>(null)
    val musicNote: StateFlow<String?> = _musicNote
    private var musicNoteJob: Job? = null

    init {
        // Spotify moments (start / heavy set / last set) are decided from the live sets; all network is fire-and-forget
        viewModelScope.launch {
            sets.collect { list ->
                runCatching { com.tamir.hamasa.data.music.WorkoutMusic.onSets(sessionId, list, restActive = musicRestActive()) { line -> showMusicNote(line) } }
            }
        }
    }

    /** True from a set being completed until its rest has started (the sets list can arrive before the timer does). */
    @Volatile private var restGate = false

    /** The last-set song waits for the end of the rest: true while a rest runs or is about to start. */
    private fun musicRestActive(): Boolean = restGate || _restRemaining.value > 0

    /** The rest is over (or skipped): the moments that waited for it may happen now. */
    private fun musicRecheck() {
        viewModelScope.launch {
            runCatching {
                com.tamir.hamasa.data.music.WorkoutMusic.onSets(sessionId, sets.value, restActive = musicRestActive()) { line -> showMusicNote(line) }
            }
        }
    }

    /** May be called from a background thread: hops to the view model's scope. */
    private fun showMusicNote(text: String) {
        viewModelScope.launch {
            musicNoteJob?.cancel()
            _musicNote.value = com.tamir.hamasa.domain.game.CharacterNames.g(text)
            musicNoteJob = viewModelScope.launch {
                delay(8000)
                _musicNote.value = null
            }
        }
    }

    fun dismissMusicNote() {
        musicNoteJob?.cancel()
        _musicNote.value = null
    }

    init {
        WristCard.attach(this)
        viewModelScope.launch { runCatching { battle.load() } }
        viewModelScope.launch {
            sets.collect { list ->
                val known = _exercises.value
                val missing = list.map { it.exerciseId }.distinct().filter { it !in known }
                if (missing.isNotEmpty()) {
                    val add = mutableMapOf<String, ExerciseEntity>()
                    for (id in missing) {
                        AppGraph.db.exercises().byId(id)?.let { add[id] = it }
                    }
                    if (add.isNotEmpty()) _exercises.value = _exercises.value + add
                }
                maybeBuildSuggestions(list)
                runCatching { battle.onSets(list) }
                runCatching { updateWrist(list) }
                if (_secretSetId.value == null) _secretSetId.value = SecretSet.pick(sessionId, list, _exercises.value)
            }
        }
        viewModelScope.launch {
            val p = AppGraph.prefs.current()
            if (p.moodDay == com.tamir.hamasa.domain.Days.today()) _moodLine.value = Voice.forMood(p.mood)
            val s = AppGraph.db.sessions().byId(sessionId)
            if (s != null && s.type == SessionType.GYM) {
                val d = com.tamir.hamasa.data.ai.CoachNotes.effectiveDifficulty(p, s.dateEpochDay)
                _difficultyLine.value = "רמה ${d.nameHe} · ${d.rirNoteHe}"
            }
        }
    }

    // ------------------------------------------------------------------ coach suggestions (template sessions)

    /**
     * Builds the suggestions once, as soon as the session row and its sets are both known, and only while nothing
     * was done yet: a session that already has a completed set gets none (his rule: suggestions only at the start).
     */
    private suspend fun maybeBuildSuggestions(sets: List<SetLogEntity>) {
        if (suggestionsBuilt || sets.isEmpty()) return
        if (sets.any { it.completed }) { suggestionsBuilt = true; return }
        val s = session.value ?: AppGraph.db.sessions().byId(sessionId) ?: return
        if (AppGraph.workouts.templateIdOf(s) == null) return
        suggestionsBuilt = true
        val out = runCatching { buildSuggestions(s, sets) }.getOrDefault(emptyList())
        _suggestions.value = out
    }

    private fun branchNameHe(b: Branch): String = when (b) {
        Branch.CHEST -> "חזה"
        Branch.BACK -> "גב"
        Branch.LEGS -> "רגליים"
        Branch.SHOULDERS_ARMS -> "כתפיים וידיים"
        Branch.CORE -> "בטן"
        Branch.ENDURANCE -> "סיבולת"
    }

    /**
     * 1–3 advisory lines from the coach's rules (docs/coach_logic.md): a familiar alternative for an
     * exercise never done, the 5–8 rep range for weighted work (8–15 for bodyweight), and the per-muscle
     * set counts of a session (chest 3, back 4, abs 1). Order of priority: reps, sets, swap.
     */
    private suspend fun buildSuggestions(s: SessionEntity, sets: List<SetLogEntity>): List<CoachSuggestion> {
        val ctx = AppGraph.workouts.contextForSession(s, s.sleepHours)
        val out = mutableListOf<CoachSuggestion>()
        val groups = sets.groupBy { it.exerciseId }
        val entities = mutableMapOf<String, ExerciseEntity>()
        for (id in groups.keys) {
            val e = AppGraph.db.exercises().byId(id) ?: continue
            entities[id] = e
        }

        // a) load progression: never "fewer reps" on its own. Only when he reached TOP_REPS at a weight
        //    (last time he did this exercise), the coach offers more weight with fewer reps.
        // difficulty: "קשה" offers it at 11 reps, "קל" only after two sessions in a row at 12 with that weight
        val topReps = ctx.difficulty.templateTopReps
        val easy = ctx.difficulty == com.tamir.hamasa.domain.coach.Difficulty.EASY
        for ((id, list) in groups) {
            if (ctx.deload) break                                    // deload week: no weight-up suggestions
            val e = entities[id] ?: continue
            if (e.unit != "kg") continue
            val planned = list.firstOrNull { !it.completed } ?: continue
            val lastTwo = AppGraph.workouts.lastDoneSessionsSetsFor(id, 2)
            val last = lastTwo.firstOrNull().orEmpty().filter { it.completed && it.weight > 0f }
            val top = last.filter { it.reps >= topReps }.maxByOrNull { it.weight } ?: continue
            if (easy) {
                val before = lastTwo.getOrNull(1).orEmpty()
                if (before.none { it.reps >= topReps && it.weight >= top.weight }) continue
            }
            if (planned.targetWeight > top.weight) continue          // he already planned more weight
            out.add(weightUpSuggestion(id, e, top.weight, top.reps))
            if (out.size >= 3) return out
        }

        // b) per-muscle working sets in a session (compounds and isolation of that muscle together)
        val rule = mapOf(Branch.CHEST to 3, Branch.BACK to 4, Branch.CORE to 1)
        for ((branch, want) in rule) {
            val ids = groups.keys.filter { entities[it]?.branch == branch && !ExerciseCatalog.isMobility(it) }
            if (ids.isEmpty()) continue
            val have = ids.sumOf { groups[it]?.size ?: 0 }
            val name = branchNameHe(branch)
            if (have < want) {
                val main = ids.firstOrNull { entities[it]?.isCompound == true } ?: ids.first()
                val mainName = entities[main]?.nameHe ?: main
                out.add(
                    CoachSuggestion(
                        key = "sets_${branch.name}",
                        textHe = "$name מקבל אצלך $want סטים באימון, כאן יש $have. להוסיף ${want - have} ל$mainName?",
                        kind = CoachSuggestion.Kind.ADD_SETS, exerciseId = main, value = want - have
                    )
                )
            } else if (have > want + 1) {
                out.add(
                    CoachSuggestion(
                        key = "over_${branch.name}",
                        textHe = "$name: $have סטים, הג'ים ברו עובד עם $want. יותר מזה לא נותן הרבה.",
                        kind = CoachSuggestion.Kind.INFO
                    )
                )
            }
            if (out.size >= 3) return out
        }

        // c) a familiar alternative for an exercise never done before
        for (id in groups.keys) {
            val e = entities[id] ?: continue
            if (ctx.history.containsKey(id) || ExerciseCatalog.isMobility(id)) continue
            val alt = CoachEngine.alternatives(id, ctx).firstOrNull { a -> a.exerciseId !in groups.keys && ctx.history.containsKey(a.exerciseId) }
                ?: continue
            out.add(
                CoachSuggestion(
                    key = "swap_$id",
                    textHe = com.tamir.hamasa.domain.game.Addr.t("במקום ${e.nameHe} נסה ${alt.nameHe}, תרגיל שאתה כבר מכיר ויש לו משקל עבודה.", "במקום ${e.nameHe} נסי ${alt.nameHe}, תרגיל שאת כבר מכירה ויש לו משקל עבודה."),
                    kind = CoachSuggestion.Kind.SWAP, exerciseId = id, toExerciseId = alt.exerciseId
                )
            )
            if (out.size >= 3) return out
        }
        return out
    }

    /** "+5 ק\"ג, פחות חזרות": dumbbells go up 2 kg per hand, everything else 5 kg; reps go back to [RESET_REPS]. */
    private fun weightUpSuggestion(id: String, e: ExerciseEntity, atWeight: Float, reps: Int): CoachSuggestion {
        val step = if (e.equipment == "dumbbell") 2f else 5f
        val next = atWeight + step
        return CoachSuggestion(
            key = "up_$id",
            textHe = "${e.nameHe}: הגעת ל-$reps חזרות ב-${formatKg(atWeight)} ק\"ג. עולים ל-${formatKg(next)} ק\"ג ויורדים ל-$RESET_REPS חזרות?",
            kind = CoachSuggestion.Kind.WEIGHT_UP, exerciseId = id, value = RESET_REPS, weight = next
        )
    }

    /**
     * Live: a set just reached TOP_REPS at the planned weight → offer the jump for the remaining sets.
     * Not called since 2026-11-08 (suggestions only at the start); kept for the end-of-workout template update.
     */
    @Suppress("unused")
    private suspend fun maybeWeightUp(set: SetLogEntity, reps: Int, weight: Float) {
        if (weight <= 0f) return
        val s = session.value ?: AppGraph.db.sessions().byId(sessionId) ?: return
        if (AppGraph.workouts.templateIdOf(s) == null) return   // coach workouts progress through the coach itself
        val p = AppGraph.prefs.current()
        // deload week: no weight-up suggestions
        if (com.tamir.hamasa.domain.coach.Deload.activeOn(p.deloadWeekStart, s.dateEpochDay)) return
        val d = com.tamir.hamasa.data.ai.CoachNotes.effectiveDifficulty(p, s.dateEpochDay)
        if (reps < d.templateTopReps) return
        val e = AppGraph.db.exercises().byId(set.exerciseId) ?: return
        if (e.unit != "kg") return
        // difficulty "קל": the last finished session must have reached the top with this weight too
        if (d == com.tamir.hamasa.domain.coach.Difficulty.EASY) {
            val before = AppGraph.workouts.lastDoneSessionsSetsFor(set.exerciseId, 1).firstOrNull().orEmpty()
            if (before.none { it.reps >= d.templateTopReps && it.weight >= weight }) return
        }
        val remaining = AppGraph.db.sessions().setsForOnce(sessionId).any { it.exerciseId == set.exerciseId && !it.completed && it.id != set.id }
        if (!remaining) return
        val sg = weightUpSuggestion(set.exerciseId, e, weight, reps)
        if (_suggestions.value.none { it.key == sg.key }) _suggestions.value = listOf(sg) + _suggestions.value
    }

    /** "החל": applies the suggestion to the live session (remaining sets only) and removes it from the list. */
    fun applySuggestion(sg: CoachSuggestion) {
        _suggestions.value = _suggestions.value.filter { it.key != sg.key }
        viewModelScope.launch {
            when (sg.kind) {
                CoachSuggestion.Kind.SWAP -> AppGraph.workouts.swapExercise(sessionId, sg.exerciseId, sg.toExerciseId)
                CoachSuggestion.Kind.REPS -> AppGraph.workouts.setPlannedReps(sessionId, sg.exerciseId, sg.value)
                CoachSuggestion.Kind.WEIGHT_UP -> AppGraph.workouts.setPlannedLoad(sessionId, sg.exerciseId, sg.weight, sg.value)
                CoachSuggestion.Kind.ADD_SETS -> AppGraph.workouts.addPlannedSets(sessionId, sg.exerciseId, sg.value)
                CoachSuggestion.Kind.INFO -> {}
            }
        }
    }

    /** "לא תודה". */
    fun dismissSuggestion(sg: CoachSuggestion) {
        _suggestions.value = _suggestions.value.filter { it.key != sg.key }
    }

    /** Last "+ הוסף סט" tap per exercise (millis): a second tap within [ADD_SET_DEBOUNCE_MS] is a double tap. */
    private val lastAddSetAt = HashMap<String, Long>()

    /** "+ הוסף סט": one more planned set of this exercise, copying its last set's targets. */
    fun addSet(exerciseId: String) {
        val now = System.currentTimeMillis()
        val last = lastAddSetAt[exerciseId]
        if (last != null && now - last < ADD_SET_DEBOUNCE_MS) return
        lastAddSetAt[exerciseId] = now
        viewModelScope.launch { AppGraph.workouts.addOneSet(sessionId, exerciseId) }
    }

    /** Sets whose "סיימתי סט" is being saved right now (a double tap must not complete one twice). */
    private val completingSetIds = HashSet<Long>()

    fun exerciseName(id: String): String = _exercises.value[id]?.nameHe ?: id

    /** [restSec] null = the rest of the plan: the template's own rest or the coach's, adjusted by today's mode. */
    fun completeSet(set: SetLogEntity, reps: Int, weight: Float, restSec: Int? = null) {
        if (set.completed || !completingSetIds.add(set.id)) return
        // a set started while the rest still had more than REST_GRACE_SEC left = the rest was not kept
        if (_restRemaining.value > REST_GRACE_SEC) markRestBroken()
        restGate = true
        viewModelScope.launch {
            // null = the row was already done (double tap): no xp, no banner, no new rest
            val result: Boolean? = try {
                AppGraph.workouts.completeSetChecked(set, reps, weight)
            } finally {
                completingSetIds.remove(set.id)
            }
            val isPr = result ?: run { restGate = false; return@launch }
            if (set.id == _secretSetId.value && reps >= SecretSet.targetReps(set)) AppGraph.game.grant(SecretSet.XP, 0, "secret_set")
            if (isPr) {
                val what = exerciseName(set.exerciseId) + " " + formatKg(weight) + " ק\"ג"
                showPr(Voice.pr(what))
            }
            val ex = _exercises.value[set.exerciseId]
            val compound = ex?.isCompound ?: true
            // endurance shortens the rest of accessory sets only (never compounds), never below 45 s
            val baseRest = restSec ?: liveRestFor(set.exerciseId, ex)
            // a planned rest of 0 (the cardio finisher) stays 0: no timer at all
            startRest(if (baseRest <= 0) 0 else if (compound) baseRest else StatEffects.accessoryRestSec(battle.stats, baseRest))
            // surprise event: accessory sets only, never a compound lift
            if (!compound) runCatching { maybeSurpriseEvent(set) }
            runCatching { maybeModeSurprise() }
            // his rule (2026-11-08): the Gym Bro suggests only BEFORE the first set; from here on he keeps quiet
            suggestionsBuilt = true
            if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
        }
    }

    /** Fix reps / weight of a set that is already marked done. */
    fun editCompletedSet(set: SetLogEntity, reps: Int, weight: Float) {
        viewModelScope.launch { AppGraph.workouts.editCompletedSet(set, reps, weight) }
    }

    private fun showPr(text: String) {
        bannerJob?.cancel()
        _prBanner.value = text
        bannerJob = viewModelScope.launch {
            delay(5000)
            _prBanner.value = null
        }
    }

    fun dismissPr() {
        bannerJob?.cancel()
        _prBanner.value = null
    }

    fun startRest(sec: Int) {
        // an accepted event stops the rest on purpose: no timer while he is doing it
        if (_event.value?.accepted == true) { stopRest(); musicRecheck(); return }
        restJob?.cancel()
        _restRemaining.value = sec
        restGate = false
        if (sec <= 0) { musicRecheck(); return }
        // counted against the clock, so a locked phone (slower ticks) never stretches the rest
        val endAt = System.currentTimeMillis() + sec * 1000L
        val ctx = AppGraph.appContext
        RestAlerts.show(ctx, endAt, nextSetLine())
        restJob = viewModelScope.launch {
            while (true) {
                val left = endAt - System.currentTimeMillis()
                if (left <= 0L) break
                _restRemaining.value = ((left + 999L) / 1000L).toInt()
                delay(minOf(left, 250L))
            }
            _restRemaining.value = 0
            RestAlerts.cancel(ctx)
            RestAlerts.ping(ctx)
            _restEnded.value = _restEnded.value + 1
            musicRecheck()
        }
    }

    /** The wrist card (WristCard): the next open set of this ACTIVE session, gone when nothing is left. */
    private suspend fun updateWrist(list: List<SetLogEntity>) {
        // gym workouts only (a walk is one long timed set)
        val s = session.value ?: AppGraph.db.sessions().byId(sessionId) ?: return
        if (s.type != SessionType.GYM || s.status != com.tamir.hamasa.data.db.SessionStatus.ACTIVE) return
        if (list.isEmpty()) return
        val ctx = AppGraph.appContext
        val next = WristCard.nextSet(list)
        if (next == null) {
            WristCard.clear(ctx)
            return
        }
        val ex = _exercises.value[next.exerciseId]
        WristCard.show(ctx, WristCard.Card(sessionId, next.id, exerciseName(next.exerciseId), WristCard.lineFor(next, list, ex)))
    }

    override fun onCleared() {
        WristCard.detach(this)
        super.onCleared()
    }

    /** "הבא: סקוואט, סט 3" for the lock-screen timer; "" when nothing is left. */
    private fun nextSetLine(): String {
        val list = sets.value
        val next = list.groupBy { it.exerciseId }.values.flatMap { g -> g.sortedBy { it.setIndex } }.firstOrNull { !it.completed }
            ?: return "הסט האחרון מאחוריך"
        return "הבא: " + exerciseName(next.exerciseId) + ", סט " + (next.setIndex + 1)
    }

    /** "דלג על המנוחה": also records that this session's rests were not all kept (badge "הסבלן"). */
    fun skipRest() {
        if (_restRemaining.value > REST_GRACE_SEC) markRestBroken()
        stopRest()
        musicRecheck()
    }

    /** Stops the timer without judging the rest (finishing or abandoning the workout). */
    private fun stopRest() {
        restJob?.cancel()
        restJob = null
        _restRemaining.value = 0
        RestAlerts.cancel(AppGraph.appContext)
    }

    private fun markRestBroken() {
        viewModelScope.launch { AppGraph.prefs.update { it.copy(restBrokenSessionId = sessionId) } }
    }

    /** "קיבלתי": the offer becomes the running event (an "extra set" event adds its set right away). */
    fun acceptEvent() {
        val cur = _event.value ?: return
        if (cur.accepted) return
        _event.value = cur.copy(accepted = true)
        stopRest()
        viewModelScope.launch {
            AppGraph.game.eventAccepted()
            if (cur.event.addsSet) AppGraph.workouts.addOneSet(sessionId, cur.exerciseId)
        }
    }

    /** "לא היום": the offer goes away. Nothing is lost. */
    fun declineEvent() {
        _event.value = null
    }

    /** "עשיתי": grants the event (and maybe a rare extra) and shows the result. */
    fun completeEvent() {
        val cur = _event.value ?: return
        if (!cur.accepted) return
        _event.value = null
        viewModelScope.launch {
            val result = AppGraph.game.completeEvent(cur.event)
            val parts = ArrayList<String>()
            parts.add(com.tamir.hamasa.domain.game.CharacterNames.g(ModeVoice.eventDone()))
            parts.add("+${cur.event.xp} ניסיון, +${cur.event.gold} זהב.")
            if (result.first.isNotEmpty()) parts.add(result.first)
            result.second?.let { parts.add(Voice.chest(it.rarity)) }
            _eventResult.value = parts.joinToString(" ")
        }
    }

    /** "לא עשיתי" on a running event: no reward, no penalty. */
    fun giveUpEvent() {
        _event.value = null
    }

    fun dismissEventResult() {
        _eventResult.value = null
    }

    /**
     * Decides (once per session, see GameRepository.planEvent) whether this session gets its surprise event,
     * then opens it when the planned accessory set is done. GYM sessions only.
     */
    private suspend fun maybeSurpriseEvent(set: SetLogEntity) {
        if (_event.value != null || ExerciseCatalog.isMobility(set.exerciseId)) return
        if (set.exerciseId == CoachEngine.FINISHER_ID) return   // the cardio finisher never gets an event
        val s = session.value ?: AppGraph.db.sessions().byId(sessionId) ?: return
        if (s.type != SessionType.GYM) return
        val all = AppGraph.db.sessions().setsForOnce(sessionId)
        var accTotal = 0
        var accDone = 0
        for (r in all) {
            val e = _exercises.value[r.exerciseId] ?: AppGraph.db.exercises().byId(r.exerciseId) ?: continue
            if (e.isCompound || ExerciseCatalog.isMobility(r.exerciseId) || r.exerciseId == CoachEngine.FINISHER_ID) continue
            accTotal++
            if (r.completed) accDone++
        }
        AppGraph.game.planEvent(sessionId, accTotal)
        if (!AppGraph.game.takeEventIfDue(sessionId, accDone)) return
        val ev = SurpriseEvents.pick(s.modeId)
        _event.value = LiveSurpriseEvent(ev, set.exerciseId, exerciseName(set.exerciseId), accepted = false)
    }

    /** Mode "surprise": about a third of the way in, one accessory not started yet is swapped (once per session). */
    private suspend fun maybeModeSurprise() {
        if (surpriseTried) return
        val s = session.value ?: AppGraph.db.sessions().byId(sessionId) ?: return
        if (s.modeId != "surprise") {
            surpriseTried = true
            return
        }
        val all = AppGraph.db.sessions().setsForOnce(sessionId)
        val done = all.count { it.completed }
        if (all.isEmpty() || done * 3 < all.size) return
        surpriseTried = true
        val swap = AppGraph.workouts.surpriseSwap(sessionId) ?: return
        val fromName = exerciseName(swap.first)
        val toName = AppGraph.db.exercises().byId(swap.second)?.nameHe ?: swap.second
        _modeNote.value = com.tamir.hamasa.domain.game.CharacterNames.g(ModeVoice.surpriseSwap(fromName, toName))
    }

    fun dismissModeNote() {
        _modeNote.value = null
    }

    /**
     * Rest after a set of [exerciseId]: a template's own rest, otherwise the coach's rest for the exercise and
     * the focus; then today's mode (race against the clock, short rest). Non-gym sessions keep their short rests.
     */
    private suspend fun liveRestFor(exerciseId: String, ex: ExerciseEntity?): Int {
        val s = session.value ?: AppGraph.db.sessions().byId(sessionId)
        if (s != null && s.type != SessionType.GYM) return if (s.type == SessionType.YOGA) 40 else 45
        if (restFocus == null) {
            val p = AppGraph.prefs.current()
            restFocus = p.focus
            restDifficulty = com.tamir.hamasa.data.ai.CoachNotes.effectiveDifficulty(p, s?.dateEpochDay ?: com.tamir.hamasa.domain.Days.today())
            val tid = s?.let { AppGraph.workouts.templateIdOf(it) }
            if (tid != null) {
                templateRests = runCatching { AppGraph.workouts.templateItemsOnce(tid).associate { Pair(it.exerciseId, it.restSec) } }
                    .getOrDefault(emptyMap())
            }
        }
        // the coach's cardio finisher has no rest after it (planned rest 0), unless his own template set one
        if (exerciseId == CoachEngine.FINISHER_ID && (templateRests[exerciseId] ?: 0) <= 0) return 0
        val e = ex ?: AppGraph.db.exercises().byId(exerciseId)
        return CoachEngine.liveRestSec(e, restFocus ?: Focus.LOSE_WEIGHT, s?.modeId, templateRests[exerciseId], restDifficulty)
    }

    /** Mode "pr_day": the last set of the first compound lift in the session. */
    private suspend fun prAttemptOf(list: List<SetLogEntity>): Long? {
        val order = LinkedHashMap<String, MutableList<SetLogEntity>>()
        for (r in list) order.getOrPut(r.exerciseId) { mutableListOf() }.add(r)
        for ((id, rows) in order) {
            val e = _exercises.value[id] ?: AppGraph.db.exercises().byId(id) ?: continue
            if (!e.isCompound) continue
            return rows.maxByOrNull { it.setIndex }?.id
        }
        return null
    }

    fun openSwap(exerciseId: String) {
        _swapFrom.value = exerciseId
        _alternatives.value = emptyList()
        _swapLoading.value = true
        viewModelScope.launch {
            // a home session offers only what his home gear allows
            val ctx = AppGraph.workouts.contextForSession(session.value, session.value?.sleepHours)
            // never offer an exercise that is already in this session
            val inSession = AppGraph.db.sessions().setsForOnce(sessionId).map { it.exerciseId }.toSet()
            _alternatives.value = CoachEngine.alternatives(exerciseId, ctx).filter { it.exerciseId !in inSession }
            _swapLoading.value = false
        }
    }

    fun closeSwap() {
        _swapFrom.value = null
        _alternatives.value = emptyList()
        _swapLoading.value = false
    }

    fun swapTo(toExerciseId: String) {
        val from = _swapFrom.value ?: return
        closeSwap()
        viewModelScope.launch {
            AppGraph.workouts.swapExercise(sessionId, from, toExerciseId)
        }
    }

    /** [weightKg]: "כמה אתה שוקל" typed at the end; non-null = today's weigh-in (same as the weight screen). */
    fun finish(rating: Int, weightKg: Float? = null, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                stopRest()
                runCatching { WristCard.clear(AppGraph.appContext) }
                if (weightKg != null) runCatching { AppGraph.workouts.logWeight(weightKg) }
                runCatching { run.beforeFinish() }
                // the goal of the day is no longer asked: it is met when every planned set was done (shown on the done screen)
                val goalMet = GoalOfDay.met(AppGraph.db.sessions().setsForOnce(sessionId))
                val rewards = AppGraph.workouts.finishSession(sessionId, rating, goalMet)
                // the run plan moves (or repeats the week) after a plan run
                runCatching { run.afterFinish() }
                CloudSync.pushSoon(AppGraph.appContext)
                RewardsHolder.last = rewards
                runCatching { com.tamir.hamasa.data.music.WorkoutMusic.onFinish() }
                onDone()
            } finally {
                _busy.value = false
            }
        }
    }

    fun abandon(onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                stopRest()
                runCatching { WristCard.clear(AppGraph.appContext) }
                AppGraph.workouts.abandonSession(sessionId)
                onDone()
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        const val DEFAULT_REST_SEC = 120
        /** Seconds of rest that may be cut without counting as "not kept". */
        const val REST_GRACE_SEC = 10
        /** Reaching this many reps at a weight is the signal to go up in weight (his own templates). */
        const val TOP_REPS = 12
        /** Reps to restart from after the weight goes up. */
        const val RESET_REPS = 5
        /** A second "+ הוסף סט" on the same exercise within this time is ignored (double tap). */
        const val ADD_SET_DEBOUNCE_MS = 500L
    }
}

/** "80" for whole kilos, "82.5" otherwise. */
fun formatKg(kg: Float): String {
    val whole = kg.toInt()
    return if (kg == whole.toFloat()) whole.toString() else String.format(java.util.Locale.US, "%.1f", kg)
}

/** mm:ss or h:mm:ss. */
fun formatClock(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
}
