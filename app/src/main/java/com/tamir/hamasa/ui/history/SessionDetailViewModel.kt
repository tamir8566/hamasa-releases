package com.tamir.hamasa.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.SetSpec
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.db.encodeSets
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.game.MuscleHeat
import com.tamir.hamasa.ui.common.HebrewDates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One completed set row of the detail table. [oneRm] is null for bodyweight / seconds sets. */
data class DetailSetRow(
    val number: Int,
    val weight: Float,
    val reps: Int,
    val oneRm: Float?,
    val isPr: Boolean
)

data class DetailExercise(
    val exerciseId: String,
    val nameHe: String,
    /** "kg" | "sec" | "reps" */
    val unit: String,
    val rows: List<DetailSetRow>,
    val plannedSets: Int,
    val sessionBest1Rm: Float?,
    val allTimeBest1Rm: Float?
)

data class SessionDetailState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val session: SessionEntity? = null,
    val bodyWeightKg: Float? = null,
    val volumeKg: Double = 0.0,
    val heat: MuscleHeat.HeatResult? = null,
    val exercises: List<DetailExercise> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null
)

/** Epley estimate rounded to 0.5 kg. Null unless weight > 0 and reps >= 1. */
fun epley1Rm(weight: Float, reps: Int): Float? {
    if (weight <= 0f || reps < 1) return null
    return roundHalf(weight * (1f + reps / 30f))
}

fun roundHalf(v: Float): Float = (v * 2f).roundToInt() / 2f

class SessionDetailViewModel(private val sessionId: Long) : ViewModel() {

    private val _state = MutableStateFlow(SessionDetailState())
    val state: StateFlow<SessionDetailState> = _state

    /** Raw data kept for building a template. */
    private var sets: List<SetLogEntity> = emptyList()
    private var exerciseRows: Map<String, ExerciseEntity> = emptyMap()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val db = AppGraph.db
        val session = db.sessions().byId(sessionId)
        if (session == null) {
            _state.value = SessionDetailState(loading = false, missing = true)
            return
        }
        val all = db.sessions().setsForOnce(sessionId)
        sets = all
        // Order of first appearance (sets are stored in the order they were planned / added).
        val order = LinkedHashMap<String, MutableList<SetLogEntity>>()
        for (s in all) order.getOrPut(s.exerciseId) { mutableListOf() }.add(s)

        val exMap = HashMap<String, ExerciseEntity>()
        for (id in order.keys) {
            val e = db.exercises().byId(id) ?: ExerciseCatalog.byId(id)
            if (e != null) exMap[id] = e
        }
        exerciseRows = exMap

        val blocks = ArrayList<DetailExercise>()
        for ((id, list) in order) {
            val e = exMap[id]
            val unit = e?.unit ?: "kg"
            val done = list.filter { it.completed }.sortedBy { it.setIndex }
            val rows = done.mapIndexed { i, s ->
                DetailSetRow(
                    number = i + 1,
                    weight = s.weight,
                    reps = s.reps,
                    oneRm = if (unit == "kg") epley1Rm(s.weight, s.reps) else null,
                    isPr = s.isPr
                )
            }
            val sessionBest = rows.mapNotNull { it.oneRm }.maxOrNull()
            val allTime = if (unit == "kg") db.sessions().bestEpley(id)?.let { roundHalf(it.toFloat()) } else null
            blocks.add(
                DetailExercise(
                    exerciseId = id,
                    nameHe = e?.nameHe ?: "תרגיל שנמחק",
                    unit = unit,
                    rows = rows,
                    plannedSets = list.size,
                    sessionBest1Rm = sessionBest,
                    allTimeBest1Rm = allTime
                )
            )
        }

        val volume = all.filter { it.completed && it.weight > 0f }.sumOf { (it.weight * it.reps).toDouble() }
        val weight = db.weights().latestOnOrBefore(session.dateEpochDay)?.kg
        val heat = AppGraph.workouts.heatForSession(sessionId)

        _state.value = SessionDetailState(
            loading = false,
            session = session,
            bodyWeightKg = weight,
            volumeKg = volume,
            heat = heat,
            exercises = blocks
        )
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Template items from this session: per exercise (in order) every completed set as its own row
     * (weight × reps, in set order), stored in setsCsv. Exercises with no completed set fall back to their
     * planned per-set targets (only when nothing at all was completed; otherwise they are skipped).
     */
    private fun buildItems(): List<TemplateItemEntity> {
        val order = LinkedHashMap<String, MutableList<SetLogEntity>>()
        for (s in sets) order.getOrPut(s.exerciseId) { mutableListOf() }.add(s)
        val anyCompleted = sets.any { it.completed }
        val out = ArrayList<TemplateItemEntity>()
        for ((id, list) in order) {
            val e = exerciseRows[id] ?: continue
            val isKg = e.unit == "kg"
            val done = list.filter { it.completed }.sortedBy { it.setIndex }
            val specs: List<SetSpec> = if (done.isNotEmpty()) {
                done.map { SetSpec(if (isKg) it.weight.coerceAtLeast(0f) else 0f, it.reps.coerceAtLeast(1)) }
            } else {
                if (anyCompleted) continue   // skipped in the session: not copied
                list.sortedBy { it.setIndex }.map { SetSpec(if (isKg) it.targetWeight.coerceAtLeast(0f) else 0f, it.targetReps.coerceAtLeast(1)) }
            }
            if (specs.isEmpty()) continue
            val rest = when (e.unit) {
                "sec" -> 30
                "reps" -> 60
                else -> if (e.isCompound) 150 else 75
            }
            val last = specs.last()
            out.add(
                TemplateItemEntity(
                    templateId = 0L,
                    orderIndex = out.size,
                    exerciseId = id,
                    // legacy fields: set count + the last set (older readers); setsCsv holds every set
                    sets = specs.size,
                    reps = last.reps,
                    weightKg = last.kg,
                    restSec = rest,
                    setsCsv = encodeSets(specs)
                )
            )
        }
        return out
    }

    private suspend fun saveAsTemplate(): Long? {
        val session = _state.value.session ?: return null
        val items = buildItems()
        if (items.isEmpty()) return null
        val name = "אימון מ-" + HebrewDates.shortDate(session.dateEpochDay)
        val template = WorkoutTemplateEntity(nameHe = name, createdAt = System.currentTimeMillis())
        return AppGraph.workouts.saveTemplate(template, items)
    }

    /** "העתק לאימון שלי". */
    /** Moves the workout to another day (not later than today). Rewards stay; the screen shows the new date. */
    fun editDate(newEpochDay: Long) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = runCatching { AppGraph.workouts.editDate(sessionId, newEpochDay) }.getOrDefault(false)
            val fresh = if (ok) runCatching { AppGraph.db.sessions().byId(sessionId) }.getOrNull() else null
            _state.update { st ->
                if (fresh != null) st.copy(busy = false, session = fresh, message = "התאריך עודכן.")
                else st.copy(busy = false, message = "התאריך לא נשמר.")
            }
        }
    }

    /** Edits the workout length (1..300 minutes). Rewards stay as they are; the screen shows the new value. */
    fun editDuration(minutes: Int) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = runCatching { AppGraph.workouts.editDuration(sessionId, minutes) }.getOrDefault(false)
            val fresh = if (ok) runCatching { AppGraph.db.sessions().byId(sessionId) }.getOrNull() else null
            _state.update { st ->
                if (fresh != null) st.copy(busy = false, session = fresh)
                else st.copy(busy = false, message = com.tamir.hamasa.domain.game.Addr.t("המשך לא נשמר. נסה שוב.", "המשך לא נשמר. נסי שוב."))
            }
        }
    }

    fun copyToTemplates() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val msg = try {
                val id = saveAsTemplate()
                if (id == null) "אין כאן מה להעתיק. האימון ריק." else "נשמר באימונים שלי. מחכה שם בסבלנות."
            } catch (t: Throwable) {
                "משהו נתקע בדרך. לא נשמר."
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    /** "התחל שוב": saves a template the same way and starts it; [onStarted] gets the new session id. */
    fun startAgain(onStarted: (Long) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var startedId: Long? = null
            val msg: String? = try {
                // a forgotten workout from an earlier day is closed first, the same as every start button
                runCatching { AppGraph.workouts.closeStaleActiveSessions() }
                val active = AppGraph.workouts.activeSession.first()
                if (active != null) {
                    "יש כבר אימון פתוח. קודם מסיימים אותו."
                } else {
                    val templateId = saveAsTemplate()
                    if (templateId == null) {
                        "אין כאן מה להתחיל. האימון ריק."
                    } else {
                        startedId = AppGraph.workouts.startFromTemplate(templateId, null)
                        null
                    }
                }
            } catch (t: Throwable) {
                com.tamir.hamasa.domain.game.Addr.t("משהו נתקע בדרך. נסה שוב.", "משהו נתקע בדרך. נסי שוב.")
            }
            _state.update { it.copy(busy = false, message = msg) }
            val id = startedId
            if (id != null) onStarted(id)
        }
    }
}
