package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.db.AppDatabase
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WeightEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.db.decodeSets
import com.tamir.hamasa.data.db.SetSpec
import com.tamir.hamasa.data.db.encodeSets
import com.tamir.hamasa.data.prefs.Prefs
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.CoachContext
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.coach.ExerciseHistory
import com.tamir.hamasa.domain.coach.ExerciseTargets
import com.tamir.hamasa.domain.coach.HomeGear
import com.tamir.hamasa.domain.coach.HomeRequest
import com.tamir.hamasa.domain.game.MuscleHeat
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.domain.coach.SleepBand
import com.tamir.hamasa.domain.coach.WorkoutPlan
import com.tamir.hamasa.domain.coach.sleepBand
import com.tamir.hamasa.domain.game.BossCatalog
import com.tamir.hamasa.domain.game.BuffType
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.ModeRules
import com.tamir.hamasa.domain.game.Professions
import com.tamir.hamasa.domain.game.StatEffects
import com.tamir.hamasa.data.db.BossProgressEntity
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

class WorkoutRepository(private val db: AppDatabase, private val prefs: Prefs, private val game: GameRepository) {

    suspend fun seedIfNeeded() {
        // REPLACE only touches catalog ids. Custom rows use ids of the form "custom_<millis>"
        // (see newCustomId) so re-seeding never overwrites or deletes them.
        if (db.exercises().count() == 0) db.exercises().upsertAll(ExerciseCatalog.all)
        else db.exercises().upsertAll(ExerciseCatalog.all) // keep catalog fresh across versions
    }

    val exercises get() = db.exercises().all()

    // ------------------------------------------------------------ custom exercises

    val customExercises: Flow<List<ExerciseEntity>> get() = db.exercises().customs()

    suspend fun customExercisesOnce(): List<ExerciseEntity> = db.exercises().customsOnce()

    suspend fun exerciseById(id: String): ExerciseEntity? = db.exercises().byId(id)

    fun newCustomId(): String = "custom_" + System.currentTimeMillis().toString()

    /** Inserts or updates a custom exercise. The row is forced to isCustom = true. */
    suspend fun saveCustomExercise(e: ExerciseEntity) {
        db.exercises().upsert(e.copy(isCustom = true))
    }

    /** Deletes a custom exercise (catalog rows are never deleted). Logged sets keep the id as text. */
    suspend fun deleteCustomExercise(id: String) {
        db.exercises().deleteCustom(id)
    }
    val activeSession: Flow<SessionEntity?> get() = db.sessions().activeSession()
    val doneSessions get() = db.sessions().doneSessions()
    val latestWeight get() = db.weights().latest()
    val allWeights get() = db.weights().all()
    val totalVolume get() = db.sessions().totalVolume()
    fun sets(sessionId: Long) = db.sessions().setsFor(sessionId)
    fun session(id: Long) = db.sessions().observe(id)

    suspend fun logWeight(kg: Float, day: Long = Days.today()) {
        db.weights().upsert(WeightEntity(dateEpochDay = day, kg = kg))
        // badge "המשקל": seven days in a row with a weigh-in
        if (game.weighInStreak() >= 7) game.unlockBadge("scale")
        runCatching { WidgetUpdater.refresh(AppGraph.appContext) }
    }

    suspend fun history(): Map<String, ExerciseHistory> {
        val out = mutableMapOf<String, ExerciseHistory>()
        val modeOfSession = HashMap<Long, String>()
        for (e in db.exercises().allOnce()) {
            val recent = db.sessions().recentSetsFor(e.id, 12)
            if (recent.isEmpty()) continue
            val allGroups = recent.groupBy { it.sessionId }.values.toList()
            // Accessory sets done under a mode with its own targets (lighter sets, seconds instead of reps)
            // never drive progression; compounds are never changed by modes, so they always count.
            var bySession = allGroups
            if (!e.isCompound) {
                val clean = ArrayList<List<SetLogEntity>>()
                for (g in allGroups) {
                    val sid = g.first().sessionId
                    val mode = modeOfSession[sid] ?: (db.sessions().byId(sid)?.modeId ?: "").also { modeOfSession[sid] = it }
                    if (mode !in ModeRules.distortsAccessoryHistory) clean.add(g)
                }
                if (clean.isNotEmpty()) bySession = clean
            }
            val last = bySession.first().sortedBy { it.setIndex }
            val lastTwoHit = bySession.take(2).let { it.size == 2 && it.all { s -> s.all { x -> x.reps >= x.targetReps } } }
            val missed = last.any { it.reps < it.targetReps }
            val prev = if (bySession.size >= 2) bySession[1].sortedBy { it.setIndex } else emptyList()
            out[e.id] = ExerciseHistory(
                exerciseId = e.id,
                lastWeight = last.maxOf { it.weight },
                lastReps = last.first().reps,
                lastSets = last.size,
                allSetsHitTargetLastTwo = lastTwoHit,
                missedTargetLast = missed,
                bestWeight = db.sessions().bestWeight(e.id, 1) ?: 0f,
                lastSetReps = last.map { it.reps },
                lastTargetReps = last.first().targetReps,
                prevSetReps = prev.map { it.reps },
                prevWeight = if (prev.isEmpty()) 0f else prev.maxOf { it.weight }
            )
        }
        return out
    }

    suspend fun context(sleepHours: Float?): CoachContext {
        val p = prefs.current()
        val today = Days.today()
        val done = db.sessions().doneSessionsOnce()
        val lastGym = done.firstOrNull { it.type == SessionType.GYM }
        val gymDone = done.filter { it.type == SessionType.GYM }
        // doneSessionsOnce is newest-first, so the last element is the first finished session.
        val programStart = done.lastOrNull()?.dateEpochDay ?: today
        val noted = com.tamir.hamasa.data.ai.CoachNotes.active(p, today)
        // a note "אני בבית עם גומיות" uses the same home equipment model as the "אימון בבית" sheet
        val constraints = if (noted != null && HomeGear.noteMeansHome(noted)) HomeGear.fromNote(noted, homeRequestOf(p)) else noted
        return CoachContext(
            focus = p.focus,
            sleep = sleepBand(sleepHours),
            modeId = p.rollModeId.ifEmpty { "pr_day" },
            bodyWeightKg = db.weights().latestOnce()?.kg ?: p.startWeightKg,
            history = history(),
            branchLevels = game.branchLevels(),
            recentModes = done.take(6).mapNotNull { it.modeId },
            daysSinceLastGym = lastGym?.let { (today - it.dateEpochDay).toInt() } ?: 99,
            isWeekend = Days.isWeekend(today),
            todayEpochDay = today,
            programStartEpochDay = programStart,
            gymSessionsDone = gymDone.size,
            gymSessionsLast28Days = gymDone.count { today - it.dateEpochDay < 28L },
            exerciseOverrides = parseOverrides(p.exerciseOverridesCsv),
            overridesBlock = p.exerciseOverridesBlock,
            customExercises = db.exercises().customsOnce(),
            favoriteSession = if (p.rollModeId == "perfect") favoriteSession(gymDone) else emptyList(),
            constraints = constraints,
            deload = com.tamir.hamasa.domain.coach.Deload.activeOn(p.deloadWeekStart, today),
            // a note's temporary difficulty wins over the coach-screen setting while it holds
            difficulty = constraints?.difficultyOverride ?: com.tamir.hamasa.domain.coach.Difficulty.fromName(p.coachDifficulty),
            female = p.sex == "F" || (p.sex.isEmpty() && p.userGender == "F")
        )
    }

    /**
     * Mode "perfect": (exerciseId, completed sets) of the highest-rated finished gym session, newest first among
     * equal ratings, in the order the sets were logged. Empty when no gym session was ever rated.
     */
    private suspend fun favoriteSession(gymDone: List<SessionEntity>): List<Pair<String, Int>> {
        val best = gymDone.filter { it.rating in 1..5 }.maxByOrNull { it.rating * 1_000_000L + it.id } ?: return emptyList()
        val sets = db.sessions().setsForOnce(best.id).filter { it.completed }
        val order = ArrayList<String>()
        val counts = HashMap<String, Int>()
        for (s in sets) {
            if (s.exerciseId !in counts) order.add(s.exerciseId)
            counts[s.exerciseId] = (counts[s.exerciseId] ?: 0) + 1
        }
        return order.map { Pair(it, counts[it] ?: 1) }
    }

    /** "a>b,c>d" -> map. Malformed pieces are ignored. */
    private fun parseOverrides(csv: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (piece in csv.split(',')) {
            val parts = piece.split('>')
            if (parts.size != 2) continue
            val from = parts[0].trim()
            val to = parts[1].trim()
            if (from.isNotEmpty() && to.isNotEmpty() && from != to) out[from] = to
        }
        return out
    }

    /**
     * Remembers a user swap so the coach keeps [toExerciseId] in place of [fromExerciseId]
     * for the rest of the current block. Overrides are stored as base -> current, so swapping
     * an already-swapped exercise updates the same entry (no chains) and swapping back removes it.
     */
    suspend fun rememberSwap(fromExerciseId: String, toExerciseId: String) {
        if (fromExerciseId == toExerciseId) return
        val block = CoachEngine.blockId(context(null))
        prefs.update { p ->
            val map = if (p.exerciseOverridesBlock == block) parseOverrides(p.exerciseOverridesCsv).toMutableMap() else mutableMapOf()
            val base = map.entries.firstOrNull { it.value == fromExerciseId }?.key ?: fromExerciseId
            if (base == toExerciseId) map.remove(base) else map[base] = toExerciseId
            val csv = map.entries.joinToString(",") { "${it.key}>${it.value}" }
            p.copy(exerciseOverridesCsv = csv, exerciseOverridesBlock = block)
        }
    }

    // ------------------------------------------------------------ "אימון בבית" (domain/coach/HomeGear)

    /** His saved home sheet choices (gear, dumbbell / kettlebell weight, time, focus). */
    fun homeRequestOf(p: com.tamir.hamasa.data.prefs.Profile): HomeRequest =
        HomeGear.requestOf(p.homeGearCsv, p.homeDumbbellMaxKg, p.homeDumbbellFixed, p.homeKettlebellKg, p.homeMinutes, p.homeFocus)

    /** Remembers the sheet choices (Prefs + cloud profile). */
    suspend fun saveHomeRequest(r: HomeRequest) {
        prefs.update { p ->
            p.copy(
                homeGearCsv = HomeGear.toCsv(r.gear), homeDumbbellMaxKg = r.dumbbellMaxKg, homeDumbbellFixed = r.dumbbellFixed,
                homeKettlebellKg = r.kettlebellKg, homeMinutes = r.maxMinutes ?: 0, homeFocus = r.focus
            )
        }
        runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
    }

    /**
     * The coach context of a home session: today's context with the home equipment model of [req] (saved choices
     * when null). Used by "למה?" and the post-workout summary so a home session is explained with his real weights.
     */
    suspend fun homeContext(sleepHours: Float?, req: HomeRequest? = null): CoachContext {
        val ctx = context(sleepHours)
        val r = req ?: homeRequestOf(prefs.current())
        return ctx.copy(constraints = HomeGear.applyTo(ctx.constraints, r))
    }

    /** Coach context of a running / finished session: a home session keeps his home equipment (live swaps, "למה?"). */
    suspend fun contextForSession(s: SessionEntity?, sleepHours: Float?): CoachContext =
        if (s?.notes == HOME_NOTES) homeContext(sleepHours) else context(sleepHours)

    /** "בנה אימון": the coach's home plan for [req]. */
    suspend fun homePlan(req: HomeRequest, sleepHours: Float?): WorkoutPlan = CoachEngine.homePlan(context(sleepHours), req)

    /** "התחל": the home plan through the normal session path (rewards, boss, story, music), notes [HOME_NOTES]. */
    suspend fun startHome(plan: WorkoutPlan, sleepHours: Float?): Long {
        val goal = prefs.current().rollGoal.ifEmpty { null }
        return startSession(plan, sleepHours, goal, notes = HOME_NOTES)
    }

    suspend fun planFor(type: SessionType, sleepHours: Float?): WorkoutPlan {
        val ctx = context(sleepHours)
        return when (type) {
            SessionType.GYM -> CoachEngine.plan(ctx)
            SessionType.YOGA -> CoachEngine.yogaPlan(ctx)
            SessionType.WALK -> walkPlan(DEFAULT_WALK_MINUTES)
            SessionType.MINI -> CoachEngine.miniPlan(ctx)
            SessionType.RUN -> {
                val p = prefs.current()
                // a finished plan repeats its last session (same as RunCoach.notesFor)
                if (p.runWeek > com.tamir.hamasa.domain.coach.RunPlan.WEEKS) runPlan(com.tamir.hamasa.domain.coach.RunPlan.WEEKS, com.tamir.hamasa.domain.coach.RunPlan.DAYS_PER_WEEK)
                else runPlan(p.runWeek, p.runDay)
            }
        }
    }

    /**
     * "ריצה": the session [day] of week [week] of the 9-week run plan (domain/coach/RunPlan) as a RUN session with
     * ONE timed set of the "run_intervals" exercise (target = the whole session in seconds). The live screen shows
     * the interval steps from the session notes ("run:<week>:<day>", see RunCoach.notesFor). modeId "mini" like walks.
     */
    fun runPlan(week: Int, day: Int): WorkoutPlan {
        val w = week.coerceIn(1, com.tamir.hamasa.domain.coach.RunPlan.WEEKS)
        val d = day.coerceIn(1, com.tamir.hamasa.domain.coach.RunPlan.DAYS_PER_WEEK)
        val total = com.tamir.hamasa.domain.coach.RunPlan.totalSec(w, d)
        val e = ExerciseCatalog.byId(RUN_EXERCISE_ID)
        val item = PlannedExercise(
            exerciseId = RUN_EXERCISE_ID, nameHe = e?.nameHe ?: "ריצה בהפסקות", branch = e?.branch ?: Branch.ENDURANCE,
            sets = 1, reps = total, weightKg = 0f, restSec = 0,
            note = com.tamir.hamasa.domain.coach.RunPlan.summaryHe(w, d)
        )
        val minutes = (total + 30) / 60
        return WorkoutPlan(SessionType.RUN, "mini", "ריצה · " + com.tamir.hamasa.domain.coach.RunPlan.labelHe(w, d), listOf(item), minutes,
            "חמש דקות הליכה לחימום. ריצה איטית, בקצב שמאפשר לדבר. צעדים קצרים, נחיתה רכה. ההליכה בין הריצות היא חלק מהאימון.")
    }

    // ------------------------------------------------------------ small sessions (stage 1 A)

    /**
     * "הליכה": a WALK session with ONE item, the catalog "walk" exercise (unit "sec"), one set whose
     * target is [minutes] × 60 seconds. The user confirms (or edits) the real time when he finishes the set.
     * modeId "mini" so walks do not add a new id to the distinct-modes badges.
     */
    fun walkPlan(minutes: Int): WorkoutPlan {
        val m = minutes.coerceIn(5, 180)
        val e = ExerciseCatalog.byId(WALK_EXERCISE_ID)
        val item = PlannedExercise(
            exerciseId = WALK_EXERCISE_ID, nameHe = e?.nameHe ?: "הליכה", branch = e?.branch ?: Branch.ENDURANCE,
            sets = 1, reps = m * 60, weightKg = 0f, restSec = 0,
            note = "$m דקות. קצב שמאפשר לדבר, לא לשיר."
        )
        return WorkoutPlan(SessionType.WALK, "mini", "הליכה", listOf(item), m,
            "הליכה של $m דקות. בסוף מאשרים כמה זמן הלכת בפועל.")
    }

    /**
     * A walk he already did: a finished WALK session of [minutes] ending at [endedAtMillis] (today), made the same
     * way as a live walk (one completed timed set, finishSession for xp / streak / badges), so the calorie burn,
     * history and totals treat it like any other. Returns the new session id, or null when a workout is active now.
     */
    suspend fun logManualWalk(minutes: Int, endedAtMillis: Long, distanceM: Int = 0): Long? {
        if (runningSessionId() != null) return null
        val m = minutes.coerceIn(5, 180)
        val id = startSession(walkPlan(m), null, null, notes = "walk")
        val start = endedAtMillis - m * 60_000L
        db.sessions().byId(id)?.let { db.sessions().update(it.copy(startedAtMillis = start)) }
        val set = db.sessions().setsForOnce(id).firstOrNull()
        if (set != null) completeSetChecked(set, m * 60, 0f)
        // the distance he typed goes where the watch's distance would go, so the burn (weight × km × 60%) finds it
        if (distanceM > 0) runCatching {
            com.tamir.hamasa.data.health.WatchStore.putSession(id, com.tamir.hamasa.data.health.SessionWatch(0, 0, 0, distanceM))
        }
        finishSession(id, 3, false, endedAtMillis)
        return id
    }

    /**
     * "הוספת יוגה" on the coach screen: a yoga class he already did, [minutes] long, at [level] 0 light, 1 medium,
     * 2 hard (the level sets the calorie burn: hatha / vinyasa / power yoga). Saved like [logManualWalk]: a normal
     * finished YOGA session with one completed timed set. Null when a workout is active now.
     */
    suspend fun logManualYoga(minutes: Int, level: Int, endedAtMillis: Long): Long? {
        if (runningSessionId() != null) return null
        val m = minutes.coerceIn(5, 180)
        val levelHe = when (level) { 0 -> "קלה"; 2 -> "קשה"; else -> "בינונית" }
        val notes = when (level) { 0 -> "yoga:light"; 2 -> "yoga:hard"; else -> "yoga:medium" }
        val item = PlannedExercise(
            exerciseId = YOGA_CLASS_ID, nameHe = "שיעור יוגה", branch = Branch.ENDURANCE,
            sets = 1, reps = m * 60, weightKg = 0f, restSec = 0, note = "יוגה $levelHe, $m דקות."
        )
        val plan = WorkoutPlan(SessionType.YOGA, "yoga", "יוגה $levelHe", listOf(item), m, "יוגה $levelHe של $m דקות, נרשמה ידנית.")
        val id = startSession(plan, null, null, notes = notes)
        val start = endedAtMillis - m * 60_000L
        db.sessions().byId(id)?.let { db.sessions().update(it.copy(startedAtMillis = start)) }
        val set = db.sessions().setsForOnce(id).firstOrNull()
        if (set != null) completeSetChecked(set, m * 60, 0f)
        finishSession(id, 3, false, endedAtMillis)
        return id
    }

    /**
     * "אימון מינימלי": about 20 minutes for days without desire, built from today's coach plan:
     * its compound lifts only (up to 3), 2 sets each, the coach's own weights and reps. Counts like MINI.
     */
    suspend fun minimalPlan(): WorkoutPlan {
        val ctx = context(null)
        val full = CoachEngine.plan(ctx)
        val compounds = ArrayList<PlannedExercise>()
        val others = ArrayList<PlannedExercise>()
        for (ex in full.exercises) {
            if (ExerciseCatalog.isMobility(ex.exerciseId)) continue
            val e = ExerciseCatalog.byId(ex.exerciseId) ?: db.exercises().byId(ex.exerciseId)
            if (e?.isCompound == true) compounds.add(ex) else others.add(ex)
        }
        val picks = (compounds + others).take(3).map { ex ->
            ex.copy(sets = minOf(ex.sets, 2), restSec = ex.restSec.coerceAtMost(120), supersetWith = null,
                perSet = if (ex.perSet.isEmpty()) ex.perSet else ex.perSet.take(2))
        }
        if (picks.isEmpty()) return CoachEngine.miniPlan(ctx)
        return WorkoutPlan(SessionType.MINI, "mini", "אימון מינימלי", picks, 20,
            "עשרים דקות. התרגילים המורכבים של היום, שני סטים לכל אחד. אותם משקלים. הרצף נשמר.")
    }

    /**
     * "כפתור חירום": 12 minutes from the exercises of sessions he rated 5 stars
     * (fallback: his most frequent exercises; last fallback: the mini plan). Counts fully for the streak.
     */
    suspend fun emergencyPlan(): WorkoutPlan {
        val ctx = context(null)
        val done = db.sessions().doneSessionsOnce()
        val loved = done.filter { it.rating >= 5 }.take(20)
        var ids = frequentExerciseIds(loved)
        if (ids.size < 3) ids = (ids + frequentExerciseIds(done.take(30))).distinct()
        val picks = ArrayList<PlannedExercise>()
        for (id in ids) {
            if (picks.size >= 3) break
            val e = db.exercises().byId(id) ?: ExerciseCatalog.byId(id) ?: continue
            if (ctx.constraints?.blocks(e) == true) continue // a note to the coach rules it out for now
            val h = ctx.history[id]
            val reps = when (e.unit) {
                "sec" -> 30
                "reps" -> (h?.lastReps ?: 10).coerceIn(8, 15)
                else -> 6
            }
            val weight = if (e.unit == "kg") (h?.lastWeight ?: 0f) else 0f
            picks.add(PlannedExercise(e.id, e.nameHe, e.branch, sets = 2, reps = reps, weightKg = weight,
                restSec = if (e.isCompound) 90 else 45))
        }
        if (picks.isEmpty()) {
            return CoachEngine.miniPlan(ctx).copy(titleHe = "אימון חירום", estimatedMinutes = 12)
        }
        return WorkoutPlan(SessionType.MINI, "mini", "אימון חירום", picks, 12,
            "שתים עשרה דקות מהתרגילים שאהבת. שני סטים לכל תרגיל. נחשב לרצף במלואו.")
    }

    /** Completed-set counts per exercise over [sessions], most frequent first (ties keep recency). No walks, no holds. */
    private suspend fun frequentExerciseIds(sessions: List<SessionEntity>): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (s in sessions) {
            for (st in db.sessions().setsForOnce(s.id)) {
                if (!st.completed || st.exerciseId == WALK_EXERCISE_ID || ExerciseCatalog.isMobility(st.exerciseId)) continue
                counts[st.exerciseId] = (counts[st.exerciseId] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Starts an emergency workout and counts it against the month. Returns null when none are left. */
    suspend fun startEmergency(): Long? {
        val today = Days.today()
        // a workout is already running: open it, the month's count is not touched
        runningSessionId()?.let { return it }
        if (emergencyLeftFor(prefs.current(), today) <= 0) return null
        val plan = emergencyPlan()
        val key = monthKey(today)
        prefs.update { p ->
            val used = if (p.emergencyMonth == key) p.emergencyUsed else 0
            p.copy(emergencyMonth = key, emergencyUsed = used + 1)
        }
        return startSession(plan, null, null, notes = "emergency")
    }

    companion object {
        const val WALK_EXERCISE_ID = "walk"
        const val YOGA_CLASS_ID = "yoga_session"
        const val RUN_EXERCISE_ID = "run_intervals"
        const val DEFAULT_WALK_MINUTES = 30
        /** An ACTIVE session from an earlier day older than this is closed as abandoned (6 hours). */
        const val STALE_ACTIVE_MS = 6L * 60L * 60L * 1000L
        /** SessionEntity.notes of a session started from "אימון בבית". */
        const val HOME_NOTES = "home"
        const val EMERGENCY_PER_MONTH = 2
        /** At most this many records pay xp/gold in one session (they all still show). */
        const val PR_REWARD_CAP = 3
        /** Editable workout length range (editDuration), in minutes. */
        const val MIN_EDIT_MINUTES = 1
        const val MAX_EDIT_MINUTES = 300
        /** A finished workout lasts at least this long (a stray tap on "סיום" right after the start). */
        const val MIN_FINISH_SEC = 60

        /** "80" for whole kilos, "82.5" otherwise (same as the live record banner). */
        fun kgText(kg: Float): String {
            val whole = kg.toInt()
            return if (kg == whole.toFloat()) whole.toString() else String.format(java.util.Locale.US, "%.1f", kg)
        }

        fun monthKey(day: Long): Int = Days.of(day).let { it.year * 12 + it.monthValue }

        /** Emergency workouts left this calendar month (limit [EMERGENCY_PER_MONTH]). Pure: safe to call from UI. */
        fun emergencyLeftFor(p: com.tamir.hamasa.data.prefs.Profile, today: Long): Int {
            val used = if (p.emergencyMonth == monthKey(today)) p.emergencyUsed else 0
            return (EMERGENCY_PER_MONTH - used).coerceAtLeast(0)
        }
    }

    /** Id of the running (ACTIVE) session, newest first; null when no workout is running. */
    suspend fun activeSessionIdOnce(): Long? = db.sessions().activeSessionsOnce().firstOrNull()?.id

    /**
     * App start: ACTIVE sessions from earlier days are closed the same way "בטל אימון" closes one (status SKIPPED,
     * endedAt now). No rewards are granted or taken. Today's running workout stays open.
     */
    suspend fun closeStaleActiveSessions() {
        val today = Days.today()
        val now = System.currentTimeMillis()
        for (s in db.sessions().activeSessionsOnce()) {
            // a workout started at 23:30 and still running after midnight is not stale: 6 hours of grace
            val started = s.startedAtMillis
            val old = started == null || now - started > STALE_ACTIVE_MS
            if (s.dateEpochDay < today && old) abandonSession(s.id)
        }
    }

    /**
     * The running workout for every start button: stale ACTIVE sessions (earlier days) are closed first, so a
     * forgotten workout from yesterday is never reopened instead of starting today's.
     */
    private suspend fun runningSessionId(): Long? {
        closeStaleActiveSessions()
        return activeSessionIdOnce()
    }

    /**
     * Creates an ACTIVE session with its planned sets. Returns the session id. [notes] marks the origin ("template:<id>").
     * Only one workout runs at a time: when an ACTIVE session already exists its id is returned and nothing is created,
     * so every start button simply opens the running workout.
     */
    suspend fun startSession(plan: WorkoutPlan, sleepHours: Float?, goal: String?, notes: String = ""): Long = startMutex.withLock {
        // check + insert under one lock: two quick taps on two start buttons can never create two ACTIVE sessions
        runningSessionId()?.let { return@withLock it }
        val today = Days.today()
        // a run from any start button is the run plan's next session (the live screen reads its steps from the notes)
        val tagged = if (plan.type == SessionType.RUN && notes.isEmpty()) prefs.current().let { RunCoach.notesFor(it.runWeek, it.runDay) } else notes
        val id = db.sessions().insert(
            SessionEntity(dateEpochDay = today, type = plan.type, status = SessionStatus.ACTIVE, modeId = plan.modeId,
                dailyGoal = goal, startedAtMillis = System.currentTimeMillis(), sleepHours = sleepHours, notes = tagged)
        )
        val sets = plan.exercises.flatMap { ex ->
            if (ex.perSet.isEmpty()) {
                (0 until ex.sets).map { i ->
                    SetLogEntity(sessionId = id, exerciseId = ex.exerciseId, setIndex = i, targetReps = ex.reps, targetWeight = ex.weightKg)
                }
            } else {
                // The user's own per-set targets. A mode may add sets to accessory work (never to compounds,
                // see CoachEngine.applyModeToPlan); extra sets copy the last row. A mode that replaced the reps
                // of the first set (e.g. "timer") applies its reps to every set.
                val first = ex.perSet.first()
                val repsOverride: Int? = if (ex.reps != first.second) ex.reps else null
                val count = maxOf(ex.perSet.size, ex.sets)
                (0 until count).map { i ->
                    val row = ex.perSet[minOf(i, ex.perSet.size - 1)]
                    SetLogEntity(sessionId = id, exerciseId = ex.exerciseId, setIndex = i,
                        targetReps = repsOverride ?: row.second, targetWeight = row.first)
                }
            }
        }
        if (sets.isNotEmpty()) db.sessions().insertSets(sets)
        id
    }

    /** Serializes session starts (see [startSession]). */
    private val startMutex = kotlinx.coroutines.sync.Mutex()

    /** Serializes set completion / edits so a double tap can never complete the same row twice. */
    private val setMutex = kotlinx.coroutines.sync.Mutex()

    /** Same as [completeSetChecked]; a set that was already done counts as "no record". */
    suspend fun completeSet(set: SetLogEntity, reps: Int, weight: Float): Boolean =
        completeSetChecked(set, reps, weight) ?: false

    /**
     * Marks the set done. Returns whether it is a record, or null when the row was already completed (a double tap):
     * then nothing is written and no lifetime kg is added, so the caller skips xp, banner and rest as well.
     */
    suspend fun completeSetChecked(set: SetLogEntity, reps: Int, weight: Float): Boolean? = setMutex.withLock {
        val fresh = db.sessions().setById(set.id)
        if (fresh == null || fresh.completed) return@withLock null
        // A record must beat something: the first time he ever does an exercise sets the baseline, not a record.
        // Compared with earlier sessions only, so warming up within one session can't create a chain of "records".
        val firstTime = db.sessions().doneSetsBefore(fresh.exerciseId, fresh.sessionId) == 0
        val best = db.sessions().bestWeightBefore(fresh.exerciseId, reps.coerceAtLeast(1), fresh.sessionId) ?: 0f
        val alreadyPrHere = db.sessions().setsForOnce(fresh.sessionId).any { it.exerciseId == fresh.exerciseId && it.isPr && it.id != fresh.id }
        val isPr = !firstTime && !alreadyPrHere && weight > best && reps >= fresh.targetReps && weight > 0f
        db.sessions().updateSet(fresh.copy(reps = reps, weight = weight, completed = true, isPr = isPr))
        if (weight > 0f) prefs.update { it.copy(lifetimeKg = it.lifetimeKg + weight * reps) }
        isPr
    }

    /**
     * Fixes a set that was already completed (wrong reps / weight typed). The record flag is computed again the same
     * way as [completeSet]; the lifetime kg total moves by the difference. No new rewards (they belong to the
     * moment the set was done).
     */
    suspend fun editCompletedSet(set: SetLogEntity, reps: Int, weight: Float): Unit = setMutex.withLock {
        val old = db.sessions().setById(set.id) ?: set
        db.sessions().updateSet(old.copy(reps = reps, weight = weight, completed = true))
        // the record flags of this exercise in this session are decided again from all its done sets:
        // same rules as completeSet, one record per exercise per session, the heaviest qualifying set wins
        val firstTime = db.sessions().doneSetsBefore(old.exerciseId, old.sessionId) == 0
        val rows = db.sessions().setsForOnce(old.sessionId).filter { it.exerciseId == old.exerciseId && it.completed }
        var winner: SetLogEntity? = null
        if (!firstTime) {
            for (r in rows) {
                if (r.weight <= 0f || r.reps < r.targetReps) continue
                val best = db.sessions().bestWeightBefore(r.exerciseId, r.reps.coerceAtLeast(1), r.sessionId) ?: 0f
                if (r.weight <= best) continue
                val w = winner
                if (w == null || r.weight > w.weight || (r.weight == w.weight && r.setIndex < w.setIndex)) winner = r
            }
        }
        val win = winner
        for (r in rows) {
            val pr = win != null && r.id == win.id
            if (r.isPr != pr) db.sessions().updateSet(r.copy(isPr = pr))
        }
        val delta = weight * reps - old.weight * old.reps
        if (delta != 0f) prefs.update { it.copy(lifetimeKg = (it.lifetimeKg + delta).coerceAtLeast(0.0)) }
        Unit
    }

    suspend fun swapExercise(sessionId: Long, fromExerciseId: String, toExerciseId: String) {
        val sets = db.sessions().setsForOnce(sessionId).filter { it.exerciseId == fromExerciseId && !it.completed }
        val h = history()[toExerciseId]
        sets.forEach { db.sessions().updateSet(it.copy(exerciseId = toExerciseId, targetWeight = h?.lastWeight ?: 0f)) }
        rememberSwap(fromExerciseId, toExerciseId)
        if (sets.isNotEmpty()) recordUserSwap(sessionId, fromExerciseId, toExerciseId)
    }

    /**
     * Mode "surprise": once per session, ONE accessory exercise that was not started yet is swapped for another
     * exercise of the same muscle (the coach's own alternative, with its own progression targets). Never a compound.
     * Unlike a user swap it is NOT remembered for the block. Returns (fromId, toId), or null when nothing was swapped.
     */
    suspend fun surpriseSwap(sessionId: Long): Pair<String, String>? {
        if (prefs.current().surpriseSwapSessionId == sessionId) return null
        val sets = db.sessions().setsForOnce(sessionId)
        val groups = sets.groupBy { it.exerciseId }
        val untouched = ArrayList<String>()
        for ((id, list) in groups) {
            if (list.any { it.completed }) continue
            if (id == CoachEngine.FINISHER_ID) continue // the cardio finisher is never swapped
            val e = db.exercises().byId(id) ?: continue
            if (e.isCompound || ExerciseCatalog.isMobility(id)) continue
            untouched.add(id)
        }
        if (untouched.isEmpty()) return null
        val ctx = contextForSession(db.sessions().byId(sessionId), null)
        for (from in untouched.shuffled()) {
            var pick: PlannedExercise? = null
            for (a in CoachEngine.alternatives(from, ctx)) {
                if (a.exerciseId in groups.keys || a.exerciseId == CoachEngine.FINISHER_ID) continue
                val ae = db.exercises().byId(a.exerciseId) ?: ExerciseCatalog.byId(a.exerciseId) ?: continue
                if (ae.isCompound || ExerciseCatalog.isMobility(ae.id)) continue
                pick = a
                break
            }
            val to = pick ?: continue
            prefs.update { it.copy(surpriseSwapSessionId = sessionId) }
            recordSurpriseSwap(sessionId, from, to.exerciseId)
            val rows = groups[from].orEmpty().filter { !it.completed }
            for (r in rows) db.sessions().updateSet(r.copy(exerciseId = to.exerciseId, targetReps = to.reps, targetWeight = to.weightKg))
            return Pair(from, to.exerciseId)
        }
        return null
    }

    /** Finishes the session, computes rewards, badges, streak, boss damage. */
    /** [endedAtMillis]: the real end of a workout imported from the watch (data/health/WatchSync); null = now. */
    suspend fun finishSession(sessionId: Long, rating: Int, goalMet: Boolean, endedAtMillis: Long? = null): SessionRewards {
        val s = db.sessions().byId(sessionId) ?: error("no session")
        val p = prefs.current()
        val now = endedAtMillis ?: System.currentTimeMillis()
        // a sane length: at least a minute, at most what the duration edit allows
        val duration = ((now - (s.startedAtMillis ?: now)) / 1000L)
            .coerceIn(MIN_FINISH_SEC.toLong(), MAX_EDIT_MINUTES * 60L).toInt()
        val sets = db.sessions().setsForOnce(sessionId)
        val completed = sets.count { it.completed }
        // one record per exercise per session (the heaviest)
        val prs = sets.filter { it.isPr }.groupBy { it.exerciseId }.values.mapNotNull { list ->
            val st = list.maxByOrNull { it.weight } ?: return@mapNotNull null
            db.exercises().byId(st.exerciseId)?.nameHe?.let { "$it ${kgText(st.weight)} ק\"ג" }
        }
        // records still all show on the done screen, but at most PR_REWARD_CAP of them pay xp/gold per session
        val paidPrs = prs.size.coerceAtMost(PR_REWARD_CAP)

        // Sleep (spec): 5.5–7 h -> same workout, fewer sets, no records, 80%. Under 5.5 h the coach says yoga/rest;
        // if he chose the gym anyway he gets 80% and respect. A gym session is never below 80 (no stacking with
        // the "<70% of the sets" rule).
        val band = sleepBand(s.sleepHours)
        val sleepMedium = s.type == SessionType.GYM && band == SleepBand.MEDIUM
        val sleepShortGym = s.type == SessionType.GYM && band == SleepBand.SHORT
        val partialSets = sets.isNotEmpty() && completed < sets.size * 0.7
        var xp = when (s.type) {
            SessionType.GYM -> if (partialSets || sleepMedium || sleepShortGym) 80 else 100
            SessionType.YOGA, SessionType.WALK -> 50
            SessionType.MINI -> 30
            SessionType.RUN -> 70
        }
        if (goalMet) xp += 30
        // "בלי שיאים": after a medium night the records are still logged, but they give no +25 xp
        if (!sleepMedium) xp += 25 * paidPrs
        val sleepNoteHe = when {
            sleepShortGym -> com.tamir.hamasa.domain.game.CharacterNames.g("המפלצת אמרה לא. הלכת בכל זאת. מקבל.")
            sleepMedium && prs.isNotEmpty() -> "שינה בינונית. השיאים נרשמו, הבונוס שלהם לא. שמונים אחוז. המפלצת גובה עמלה."
            sleepMedium -> "שינה בינונית. שמונים אחוז מהנקודות. המפלצת גובה עמלה."
            else -> ""
        }
        // Equipped gear: a small percent on top of the base xp, applied BEFORE the morning double_xp bonus.
        // Buffs only ever add (never below +0) and never touch the coach's weights/reps.
        val buffs = game.equippedBuffs()
        var gearXp = (xp * (buffs[BuffType.XP] ?: 0f) / 100f).roundToInt().coerceAtLeast(0)
        // "מקצוע לשמביולוק": a small percent on the same base xp, by session type (Professions.sessionXp)
        var professionXp = Professions.sessionXp(Professions.active(p.professionId, p.xp), s.type, band, xp)
        xp += gearXp + professionXp
        if (p.rollBonus == "double_xp" && p.lastRollDay == s.dateEpochDay) {
            xp *= 2
            gearXp *= 2   // the gear share of the final total is doubled as well (shown on the done screen)
            professionXp *= 2
        }
        // "הקרב הקשה": the 2nd+ gym session of the week, Sunday..Thursday, doubles its xp once per week.
        val hardBattle = HardBattle.qualifies(s)
        if (hardBattle) {
            xp *= 2
            gearXp *= 2
            professionXp *= 2
            HardBattle.markUsed(s.dateEpochDay)
        }
        // mode "silence": +25 xp on trust (no headphones), on top of every multiplier
        val silenceBonusXp = if (s.type == SessionType.GYM && s.modeId == "silence") 25 else 0
        xp += silenceBonusXp
        // mode "race_clock": the previous finished session of the same type is the clock to beat
        val raceCompared = s.modeId == "race_clock"
        val racePrevSec: Int? = if (!raceCompared) null
            else db.sessions().doneSessionsOnce().firstOrNull { it.id != s.id && it.type == s.type && it.durationSec > 0 }?.durationSec
        val levelBefore = LevelTable.levelFor(p.xp)
        var gold = 5 + paidPrs * 5 + (if (goalMet) 5 else 0)
        val gearGold = (gold * (buffs[BuffType.GOLD] ?: 0f) / 100f).roundToInt().coerceAtLeast(0)
        gold += gearGold

        db.sessions().update(s.copy(status = SessionStatus.DONE, endedAtMillis = now, durationSec = duration, xpEarned = xp, goldEarned = gold, rating = rating))
        val shieldSaved = game.registerActivity(s.dateEpochDay)
        // "שרשרת מזל": +1 for a session on a consecutive day, back to 0 after a missed day ([p] is from before)
        game.advanceLuckChain(s.dateEpochDay, p)
        val newLevel = game.grant(xp, gold, "session:$sessionId")

        // "העץ מתחיל ברמה 4": the session that reached the tree's level is its start (it does not count itself)
        runCatching { game.ensureTreeStart() }
        // stats of Shambiuluk: monthly stability shields, boss damage/rounds, map stations
        game.grantMonthlyShields()
        val stats = game.stats()

        // boss of the week, in rounds: every completed set is a hit (see BossFights)
        // level gate: the weekly boss takes damage from level 6 (LevelGates.BOSS; hp already lost stays lost)
        val bossOpen = LevelGates.bossOpen(LevelTable.levelFor(prefs.current().xp))
        val bossResult = if (bossOpen) BossFights.applySession(s, sets, stats) else null
        val bossHit = bossResult != null && bossResult.damage > 0

        // endurance: extra map stations on top of the one every finished session gives
        // the journey starts when the map opens (level 6); that session is station 0
        val mapLevelNow = LevelTable.levelFor(prefs.current().xp)
        if (LevelGates.mapOpen(mapLevelNow) && prefs.current().mapStartDone < 0) {
            val doneNow = db.sessions().doneSessionsOnce().size
            prefs.update { it.copy(mapStartDone = doneNow, mapBonusStations = 0) }
        }
        val journeyOn = prefs.current().mapStartDone >= 0
        val extraStations = if (journeyOn) StatEffects.stationsPerSession(stats) - 1 else 0
        if (extraStations > 0) prefs.update { it.copy(mapBonusStations = it.mapBonusStations + extraStations) }
        // "מקצוע לשמביולוק": the wanderer's station on every second walk or run
        val professionStations = if (journeyOn) runCatching { ProfessionBook.wandererStation(s.type) }.getOrDefault(0) else 0

        // "תיבת אוצר אחרי כל אימון": every finished session drops a chest. Gym (or the morning "chest" bonus)
        // rolls the full table, yoga/walk/mini/run the common-weighted one.
        // Sunday..Thursday chests go sealed into the weekly box; Friday/Saturday chests open now.
        val commonChest = !(s.type == SessionType.GYM || p.rollBonus == "chest")
        val chestSealed = WeeklyBox.sealsChest(s.dateEpochDay)
        val chest = if (chestSealed) {
            game.rollSealedChest(s.dateEpochDay, commonWeighted = commonChest)
            null
        } else {
            game.rollChest(commonWeighted = commonChest)
        }
        var badges = game.evaluateBadges(sessionId)
        // mode "joker": the session he chose himself
        if (p.jokerPickDay == s.dateEpochDay && game.unlockBadge("joker_badge")) badges = badges + "joker_badge"
        if (s.type == SessionType.GYM) game.countSessionForEvents(sessionId)
        val luckyModeId = game.rollLuckyMode()
        // stage 2 H: v2 game ideas on a finished session (skill "ריכוז" pays the record points twice)
        runCatching { V2Game.afterFinish(sessionId) }
        // coach season: seasonal event boss (joins the weekly boss) and the weekly challenge
        runCatching { SeasonHooks.afterFinish(s, sets) }
        val levelAfter = LevelTable.levelFor(prefs.current().xp)
        val bet = runCatching { Bets.onSessionDone(s.dateEpochDay) }.getOrDefault(BetOutcome())
        val streak = prefs.current().streak
        runCatching { WidgetUpdater.refresh(AppGraph.appContext) }
        // stage 3: the watch heart rate of this session, when the watch is connected (async, never blocks)
        runCatching { com.tamir.hamasa.data.health.WatchSync.afterAppSession(sessionId) }
        return SessionRewards(xp, gold, if (levelAfter > levelBefore) levelAfter else newLevel, chest, badges, prs, streak, bossHit, gearXp = gearXp, gearGold = gearGold, shieldSaved = shieldSaved,
            betWonGold = bet.betWonGold, potReleasedGold = bet.potReleasedGold,
            hardBattle = hardBattle, boss = bossResult, chestSealed = chestSealed, sleepNoteHe = sleepNoteHe,
            mapStations = if (journeyOn) 1 + extraStations.coerceAtLeast(0) + professionStations else 0,
            levelFrom = levelBefore, raceCompared = raceCompared, raceDeltaSec = racePrevSec?.let { it - duration },
            luckyModeId = luckyModeId, silenceBonusXp = silenceBonusXp, professionXp = professionXp)
    }

    /**
     * Edits how long a finished app session took: durationSec and endedAt = startedAt + duration (start stays).
     * Clamped to 1..300 minutes. Watch-imported sessions are never edited. XP / gold / badges are NOT
     * recomputed (nothing taken, nothing granted); burn and history totals follow by themselves.
     * Returns false when nothing was written.
     */
    suspend fun editDuration(sessionId: Long, minutes: Int): Boolean {
        val s = db.sessions().byId(sessionId) ?: return false
        if (s.status != SessionStatus.DONE) return false
        if (com.tamir.hamasa.data.health.WatchSync.isWatchSession(s)) return false
        val sec = minutes.coerceIn(MIN_EDIT_MINUTES, MAX_EDIT_MINUTES) * 60
        val start = s.startedAtMillis
        val end: Long? = if (start != null) start + sec * 1000L else s.endedAtMillis
        db.sessions().updateDuration(sessionId, sec, end)
        val app = AppGraph.appContext
        runCatching { com.tamir.hamasa.data.health.WatchSync.afterDurationEdit(sessionId, end) }
        runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(app) }
        runCatching { com.tamir.hamasa.widget.Widgets.refreshAll(app) }
        return true
    }

    /**
     * Moves a finished app session to another day (owner wish 2026-11-11: a workout logged late, or on the wrong
     * day). The clock time stays: start and end move by whole days. Not later than today; watch-imported sessions
     * are never moved (they are matched to the watch by their time). XP / gold / badges / the streak are NOT
     * recomputed (nothing taken, nothing granted); history, burn and the weekly numbers follow by themselves.
     */
    suspend fun editDate(sessionId: Long, newEpochDay: Long): Boolean {
        val s = db.sessions().byId(sessionId) ?: return false
        if (s.status != SessionStatus.DONE) return false
        if (com.tamir.hamasa.data.health.WatchSync.isWatchSession(s)) return false
        if (newEpochDay > Days.today() || newEpochDay == s.dateEpochDay) return false
        val shift = (newEpochDay - s.dateEpochDay) * 86_400_000L
        db.sessions().update(
            s.copy(
                dateEpochDay = newEpochDay,
                startedAtMillis = s.startedAtMillis?.let { it + shift },
                endedAtMillis = s.endedAtMillis?.let { it + shift }
            )
        )
        val app = AppGraph.appContext
        runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(app) }
        runCatching { com.tamir.hamasa.widget.Widgets.refreshAll(app) }
        return true
    }

    suspend fun abandonSession(sessionId: Long) {
        db.sessions().byId(sessionId)?.let { db.sessions().update(it.copy(status = SessionStatus.SKIPPED, endedAtMillis = System.currentTimeMillis())) }
    }

    fun branchOf(exerciseId: String): Branch = CoachEngine.branchOf(exerciseId)

    // ------------------------------------------------------------ "האימונים שלי" (user templates)

    val templates: Flow<List<WorkoutTemplateEntity>> get() = db.templates().templates()

    /** Items of every template (for exercise counts on the list screen). */
    val templateItems: Flow<List<TemplateItemEntity>> get() = db.templates().allItems()

    suspend fun templateOnce(id: Long): WorkoutTemplateEntity? = db.templates().byId(id)

    suspend fun templateItemsOnce(id: Long): List<TemplateItemEntity> = db.templates().itemsOnce(id)

    /** Catalog + custom exercises, as seeded in the DB. For the exercise picker. */
    suspend fun allExercisesOnce(): List<ExerciseEntity> = db.exercises().allOnce()

    /** Inserts (id 0) or updates the template and replaces its items. Returns the template id. */
    suspend fun saveTemplate(template: WorkoutTemplateEntity, items: List<TemplateItemEntity>): Long =
        db.templates().saveWithItems(template, items)

    suspend fun deleteTemplate(id: Long) = db.templates().deleteWithItems(id)

    /** Prefix stored in SessionEntity.notes for sessions started from a template. */
    fun templateSessionNotes(templateId: Long): String = "template:$templateId"

    /** Template id of a session started from a template, or null. */
    fun templateIdOf(session: SessionEntity): Long? =
        if (session.notes.startsWith("template:")) session.notes.removePrefix("template:").toLongOrNull() else null

    /**
     * Builds a GYM plan from the user's template. Sets/reps/weights are HIS: the coach does not progress them.
     * A weight of 0 on a weighted exercise falls back to the coach's last known weight for that exercise.
     * Today's mode (the morning roll) is applied with the same accessory-only effects as a coach plan.
     */
    suspend fun planFromTemplate(templateId: Long, sleepHours: Float? = null): WorkoutPlan {
        val template = db.templates().byId(templateId) ?: error("no template")
        val items = db.templates().itemsOnce(templateId)
        val ctx = context(sleepHours)
        val exercises = mutableListOf<PlannedExercise>()
        for (item in items) {
            val e = db.exercises().byId(item.exerciseId) ?: ExerciseCatalog.byId(item.exerciseId) ?: continue
            val lastWeight = ctx.history[e.id]?.lastWeight ?: 0f
            val rows = decodeSets(item)
            val perSet: List<Pair<Float, Int>> = rows.map { r ->
                val w: Float = when {
                    e.unit != "kg" -> 0f
                    r.kg > 0f -> r.kg
                    else -> lastWeight
                }
                Pair(w, r.reps.coerceAtLeast(1))
            }
            val first = perSet.first()
            exercises.add(
                PlannedExercise(
                    exerciseId = e.id, nameHe = e.nameHe, branch = e.branch,
                    sets = perSet.size, reps = first.second, weightKg = first.first,
                    restSec = item.restSec.coerceAtLeast(0), note = item.note, perSet = perSet
                )
            )
        }
        var minutes = 5
        for (ex in exercises) minutes += (ex.sets * (75 + ex.restSec) + 30) / 60
        val base = WorkoutPlan(
            type = SessionType.GYM, modeId = ctx.modeId, titleHe = template.nameHe,
            exercises = exercises, estimatedMinutes = minutes.coerceIn(10, 120),
            coachLineHe = "אימון משלך. הסטים, החזרות והמשקלים כפי שקבעת. הג'ים ברו רק מציע."
        )
        return CoachEngine.applyModeToPlan(base, ctx)
    }

    /**
     * Starts a session from a template through the SAME startSession path as the coach,
     * so XP, gold, chests, badges, bosses and the streak behave exactly like a coach workout.
     */
    suspend fun startFromTemplate(templateId: Long, sleepHours: Float? = null): Long {
        runningSessionId()?.let { return it }
        val plan = planFromTemplate(templateId, sleepHours)
        val p = prefs.current()
        val goal = p.rollGoal.ifEmpty { null }
        val id = startSession(plan, sleepHours, goal, notes = templateSessionNotes(templateId))
        db.templates().markUsed(templateId, System.currentTimeMillis())
        return id
    }

    // ------------------------------------------------------------------ "האימונים שלי" from a finished session

    /** Exercises of a session in workout order (first appearance), each with its sets by index. */
    private suspend fun sessionGroups(sessionId: Long): List<Pair<String, List<SetLogEntity>>> =
        db.sessions().setsForOnce(sessionId)
            .groupBy { it.exerciseId }
            .map { (id, list) -> id to list.sortedBy { it.setIndex } }

    /** Per-set targets for a template from what was really done (done sets) or planned (sets not done). */
    private fun specsOf(rows: List<SetLogEntity>): List<SetSpec> = rows.map { r ->
        if (r.completed && r.reps > 0) SetSpec(r.weight.coerceAtLeast(0f), r.reps) else SetSpec(r.targetWeight.coerceAtLeast(0f), r.targetReps.coerceAtLeast(1))
    }

    private suspend fun defaultRestFor(exerciseId: String): Int {
        val e = db.exercises().byId(exerciseId) ?: ExerciseCatalog.byId(exerciseId)
        return when {
            e == null -> 90
            e.unit == "sec" -> 45
            e.isCompound -> 150
            else -> 90
        }
    }

    private suspend fun itemFromSets(templateId: Long, order: Int, exerciseId: String, rows: List<SetLogEntity>, restSec: Int?, note: String = ""): TemplateItemEntity {
        val specs = specsOf(rows)
        val first = specs.firstOrNull() ?: SetSpec(0f, 8)
        return TemplateItemEntity(
            templateId = templateId, orderIndex = order, exerciseId = exerciseId,
            sets = specs.size.coerceAtLeast(1), reps = first.reps, weightKg = first.kg,
            restSec = restSec ?: defaultRestFor(exerciseId), note = note, setsCsv = encodeSets(specs)
        )
    }

    /** Saves this session as a new template of his ("האימונים שלי"): same exercises, sets as he did them. Returns its id. */
    suspend fun saveSessionAsTemplate(sessionId: Long, nameHe: String): Long {
        val groups = sessionGroups(sessionId)
        val items = ArrayList<TemplateItemEntity>()
        groups.forEachIndexed { i, (id, rows) -> items.add(itemFromSets(0L, i, id, rows, null)) }
        val t = WorkoutTemplateEntity(nameHe = nameHe.trim().ifEmpty { "האימון שלי" }.take(40), createdAt = System.currentTimeMillis())
        val id = db.templates().saveWithItems(t, items)
        markSaveHandled(sessionId)
        return id
    }

    /** The end-of-workout save / update card was answered for this session (saved, updated or "keep"): never offered again. */
    fun isSaveHandled(sessionId: Long): Boolean =
        runCatching { surpriseMarks().getBoolean("save_$sessionId", false) }.getOrDefault(false)

    fun markSaveHandled(sessionId: Long) {
        runCatching { surpriseMarks().edit().putBoolean("save_$sessionId", true).apply() }
    }

    /** A finished template session whose exercises differ from the template: what changed, for "לעדכן את האימון?". */
    data class TemplateChange(val templateId: Long, val templateName: String, val linesHe: List<String>)

    /**
     * Mode "surprise" swaps recorded per session (toId -> fromId), so the "לעדכן את האימון?" card never bakes a
     * swap the game made into his template. Kept in a small SharedPreferences file owned by this repository.
     */
    private fun surpriseMarks() = AppGraph.appContext.getSharedPreferences("workout_marks", android.content.Context.MODE_PRIVATE)

    private fun recordSurpriseSwap(sessionId: Long, fromId: String, toId: String) {
        runCatching { surpriseMarks().edit().putString("surprise_$sessionId", "$toId>$fromId").apply() }
    }

    private fun surpriseSwapOf(sessionId: Long): Pair<String, String>? {
        val raw = runCatching { surpriseMarks().getString("surprise_$sessionId", null) }.getOrNull() ?: return null
        val parts = raw.split('>')
        return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) Pair(parts[0], parts[1]) else null
    }

    /**
     * His own swaps in this session ("החלפת תרגיל", or a coach suggestion he accepted), in order, as (fromId, toId).
     * Needed because a swap after some sets were done leaves both exercises in the session.
     */
    private fun recordUserSwap(sessionId: Long, fromId: String, toId: String) {
        runCatching {
            val prefs = surpriseMarks()
            val old = prefs.getString("swaps_$sessionId", "") ?: ""
            val entry = "$fromId>$toId"
            prefs.edit().putString("swaps_$sessionId", if (old.isEmpty()) entry else "$old;$entry").apply()
        }
    }

    private fun userSwapsOf(sessionId: Long): List<Pair<String, String>> {
        val raw = runCatching { surpriseMarks().getString("swaps_$sessionId", "") }.getOrNull() ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').mapNotNull { e ->
            val parts = e.split('>')
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) Pair(parts[0], parts[1]) else null
        }
    }

    /**
     * Template exercise -> the exercise he swapped it for (chains A -> B -> C give A -> C), only when the new one was
     * really in the session and is not in the template already. A swap after some sets of A were done still means
     * "C instead of A" (A's done sets stay in the session history, not in the template).
     */
    private fun replacementsOf(sessionId: Long, doneIds: Set<String>, planIds: Set<String>): LinkedHashMap<String, String> {
        val originOf = HashMap<String, String>()
        val out = LinkedHashMap<String, String>()
        // in swap order, so a chain keeps its last exercise: A -> B -> C maps A to C (B, if partly done, is an extra)
        for ((from, to) in userSwapsOf(sessionId)) {
            val origin = originOf[from] ?: from
            originOf[to] = origin
            if (origin in planIds && to in doneIds && to !in planIds) out[origin] = to
            else if (to == origin) out.remove(origin) // swapped back to the template's own exercise
        }
        return out
    }

    /**
     * The session's exercises in workout order with a surprise swap undone (its exercise counts as the one it
     * replaced). Rows of the same id are merged.
     */
    private suspend fun normalizedGroups(sessionId: Long): LinkedHashMap<String, List<SetLogEntity>> {
        val swap = surpriseSwapOf(sessionId) // (toId, fromId)
        val out = LinkedHashMap<String, List<SetLogEntity>>()
        for ((id, rows) in sessionGroups(sessionId)) {
            val key = if (swap != null && id == swap.first) swap.second else id
            out[key] = (out[key] ?: emptyList()) + rows
        }
        return out
    }

    /**
     * New written sets for template exercises he kept (same exercise, not swapped), from what he did today:
     * exerciseId -> (old sets, new sets), only where something changed. An exercise the template holds twice is
     * left alone (the session merges them), and so is accessory work done under a mode with its own targets.
     */
    private suspend fun valueUpdatesOf(
        sessionId: Long,
        items: List<TemplateItemEntity>,
        groups: Map<String, List<SetLogEntity>>,
        replacedKeys: Set<String>
    ): LinkedHashMap<String, Pair<List<SetSpec>, List<SetSpec>>> {
        val out = LinkedHashMap<String, Pair<List<SetSpec>, List<SetSpec>>>()
        val mode = db.sessions().byId(sessionId)?.modeId ?: ""
        val counts = items.groupingBy { it.exerciseId }.eachCount()
        for (item in items) {
            val id = item.exerciseId
            if ((counts[id] ?: 0) != 1 || id in replacedKeys) continue
            val rows = groups[id] ?: continue
            if (rows.none { it.completed && it.reps > 0 }) continue
            val e = db.exercises().byId(id) ?: ExerciseCatalog.byId(id)
            if (e != null && !e.isCompound && mode in ModeRules.distortsAccessoryHistory) continue
            val old = decodeSets(item)
            val new = TemplateValues.merge(old, rows, weighted = e?.unit == "kg")
            if (TemplateValues.differs(old, new)) out[id] = Pair(old, new)
        }
        return out
    }

    /**
     * What changed between the template and the session, compared by MEMBERSHIP only: the live workout cannot
     * reorder exercises, so a different order always comes from today's mode ("reverse" etc.) and is never offered.
     * A template with the same exercise twice is compared by distinct exercises (the session merges them).
     * Returns null when nothing he chose changed.
     */
    suspend fun templateChangeOf(sessionId: Long): TemplateChange? {
        val session = db.sessions().byId(sessionId) ?: return null
        val tid = templateIdOf(session) ?: return null
        val template = db.templates().byId(tid) ?: return null
        val items = db.templates().itemsOnce(tid).sortedBy { it.orderIndex }
        val doneIds = normalizedGroups(sessionId).keys.toList()
        val planIds = items.map { it.exerciseId }.distinct()
        val replaced = replacementsOf(sessionId, doneIds.toSet(), planIds.toSet())
        val removed = planIds.filter { it !in doneIds && it !in replaced.keys }
        val added = doneIds.filter { it !in planIds && it !in replaced.values }
        val values = valueUpdatesOf(sessionId, items, normalizedGroups(sessionId), replaced.keys)
        if (removed.isEmpty() && added.isEmpty() && replaced.isEmpty() && values.isEmpty()) return null
        suspend fun name(id: String) = (db.exercises().byId(id) ?: ExerciseCatalog.byId(id))?.nameHe ?: id
        val lines = ArrayList<String>()
        for ((id, pair) in values) {
            val timed = (db.exercises().byId(id) ?: ExerciseCatalog.byId(id))?.unit == "sec"
            lines.add(TemplateValues.lineHe(name(id), pair.first, pair.second, timed))
        }
        for ((origin, to) in replaced) lines.add(name(to) + " במקום " + name(origin))
        val pairs = minOf(removed.size, added.size)
        for (k in 0 until pairs) lines.add(name(added[k]) + " במקום " + name(removed[k]))
        for (k in pairs until added.size) lines.add("נוסף: " + name(added[k]))
        for (k in pairs until removed.size) lines.add("ירד: " + name(removed[k]))
        return TemplateChange(tid, template.nameHe, lines)
    }

    /**
     * Updates the template to the exercises he really did. The template keeps ITS order: every item whose exercise
     * stayed keeps its rest and note and takes today's sets (weights, reps) as [valueUpdatesOf] gives them
     * (duplicates are kept as stored); a swapped-in exercise takes the
     * place (and rest) of the first item of the exercise it replaced, with the sets as done today; extra exercises
     * go at the end. A surprise swap of mode "surprise" is not applied (the original stays).
     */
    suspend fun applySessionToTemplate(sessionId: Long, templateId: Long): Boolean {
        val template = db.templates().byId(templateId) ?: return false
        val items = db.templates().itemsOnce(templateId).sortedBy { it.orderIndex }
        val groups = normalizedGroups(sessionId)
        val doneIds = groups.keys
        val planIds = items.map { it.exerciseId }.toSet()
        val replaced = replacementsOf(sessionId, doneIds, planIds)
        val added = ArrayList(doneIds.filter { it !in planIds && it !in replaced.values })
        val values = valueUpdatesOf(sessionId, items, groups, replaced.keys)
        val handledRemoved = HashSet<String>()
        val out = ArrayList<TemplateItemEntity>()
        for (item in items) {
            val swappedTo = replaced[item.exerciseId]
            if (swappedTo != null) {
                // his own swap: the new exercise takes this item's place and rest (a duplicate item of it is dropped)
                if (handledRemoved.add(item.exerciseId)) {
                    out.add(itemFromSets(templateId, out.size, swappedTo, groups[swappedTo].orEmpty(), item.restSec))
                }
            } else if (item.exerciseId in doneIds) {
                val v = values[item.exerciseId]?.second
                if (v != null && v.isNotEmpty()) {
                    // the written sets follow what he did today (rest and note stay as he set them)
                    out.add(item.copy(id = 0L, orderIndex = out.size, sets = v.size, reps = v.first().reps, weightKg = v.first().kg, setsCsv = encodeSets(v)))
                } else {
                    out.add(item.copy(id = 0L, orderIndex = out.size))
                }
            } else if (handledRemoved.add(item.exerciseId) && added.isNotEmpty()) {
                val newId = added.removeAt(0)
                out.add(itemFromSets(templateId, out.size, newId, groups[newId].orEmpty(), item.restSec))
            }
        }
        for (newId in added) out.add(itemFromSets(templateId, out.size, newId, groups[newId].orEmpty(), null))
        if (out.isEmpty()) return false
        db.templates().saveWithItems(template, out)
        markSaveHandled(sessionId)
        return true
    }

    /** Coach suggestion applied by the user: target reps of the remaining (uncompleted) sets of one exercise. */
    suspend fun setPlannedReps(sessionId: Long, exerciseId: String, reps: Int) {
        val sets = db.sessions().setsForOnce(sessionId).filter { it.exerciseId == exerciseId && !it.completed }
        for (s in sets) db.sessions().updateSet(s.copy(targetReps = reps.coerceAtLeast(1)))
    }

    /** Coach suggestion "עולים במשקל": remaining (not completed) sets of one exercise get [weight] × [reps]. */
    suspend fun setPlannedLoad(sessionId: Long, exerciseId: String, weight: Float, reps: Int) {
        val sets = db.sessions().setsForOnce(sessionId).filter { it.exerciseId == exerciseId && !it.completed }
        for (s in sets) db.sessions().updateSet(s.copy(targetWeight = weight.coerceAtLeast(0f), targetReps = reps.coerceAtLeast(1)))
    }

    /** Coach suggestion applied by the user: adds [count] planned sets to one exercise, copying its last targets. */
    suspend fun addPlannedSets(sessionId: Long, exerciseId: String, count: Int) {
        if (count <= 0) return
        val sets = db.sessions().setsForOnce(sessionId).filter { it.exerciseId == exerciseId }.sortedBy { it.setIndex }
        val last = sets.lastOrNull() ?: return
        val rows = (1..count).map { i ->
            SetLogEntity(sessionId = sessionId, exerciseId = exerciseId, setIndex = last.setIndex + i, targetReps = last.targetReps, targetWeight = last.targetWeight)
        }
        db.sessions().insertSets(rows)
    }

    /** "+ הוסף סט" in the live workout: one more planned set of [exerciseId], copying its last targets. */
    suspend fun addOneSet(sessionId: Long, exerciseId: String) = addPlannedSets(sessionId, exerciseId, 1)

    /**
     * What the user did in each set (setIndex order) the last time he finished a session containing [exerciseId].
     * Only completed sets of DONE sessions. Empty when there is no such session.
     */
    suspend fun lastDoneSetsFor(exerciseId: String): List<SetLogEntity> {
        val recent = db.sessions().recentSetsFor(exerciseId, 60)
        if (recent.isEmpty()) return emptyList()
        val sessionIds = LinkedHashSet<Long>()
        for (r in recent) sessionIds.add(r.sessionId)
        for (sid in sessionIds) {
            val s = db.sessions().byId(sid) ?: continue
            if (s.status != SessionStatus.DONE) continue
            return recent.filter { it.sessionId == sid }.sortedBy { it.setIndex }
        }
        return emptyList()
    }

    /**
     * Completed sets of the last [count] DONE sessions containing [exerciseId], newest first, each in setIndex order.
     * Used by the template weight-up suggestion at difficulty "קל" (two sessions in a row at the top).
     */
    suspend fun lastDoneSessionsSetsFor(exerciseId: String, count: Int): List<List<SetLogEntity>> {
        val recent = db.sessions().recentSetsFor(exerciseId, 60)
        if (recent.isEmpty()) return emptyList()
        val sessionIds = LinkedHashSet<Long>()
        for (r in recent) sessionIds.add(r.sessionId)
        val out = ArrayList<List<SetLogEntity>>()
        for (sid in sessionIds) {
            if (out.size >= count) break
            val s = db.sessions().byId(sid) ?: continue
            if (s.status != SessionStatus.DONE) continue
            out.add(recent.filter { it.sessionId == sid && it.completed }.sortedBy { it.setIndex })
        }
        return out
    }

    // ------------------------------------------------------------ muscle heatmap (appended)

    /** Heat of one session (completed sets only). */
    suspend fun heatForSession(sessionId: Long): MuscleHeat.HeatResult {
        val sets = db.sessions().setsForOnce(sessionId)
        return heatFromSets(sets, MuscleHeat.FULL_SETS)
    }

    /** Heat of all DONE sessions whose day is in [fromDay]..[toDay] (epoch days, inclusive). */
    suspend fun heatForPeriod(fromDay: Long, toDay: Long): MuscleHeat.HeatResult {
        if (toDay < fromDay) return MuscleHeat.HeatResult.EMPTY
        val sessions = db.sessions().doneSessionsOnce().filter { it.dateEpochDay in fromDay..toDay }
        val sets = ArrayList<SetLogEntity>()
        for (s in sessions) sets.addAll(db.sessions().setsForOnce(s.id))
        return heatFromSets(sets, MuscleHeat.fullSetsFor(toDay - fromDay + 1))
    }

    private suspend fun heatFromSets(sets: List<SetLogEntity>, fullSets: Float): MuscleHeat.HeatResult {
        val byExercise = sets.filter { it.completed }.groupBy { it.exerciseId }
        if (byExercise.isEmpty()) return MuscleHeat.HeatResult.EMPTY
        val items = ArrayList<MuscleHeat.WorkedExercise>()
        for ((id, list) in byExercise) {
            // catalog ids resolve inside ExerciseTargets; custom ids need their stored target key
            val customTarget = if (ExerciseTargets.targetOf(id) == null) db.exercises().byId(id)?.target.orEmpty() else ""
            items.add(MuscleHeat.WorkedExercise(exerciseId = id, completedSets = list.size, customTarget = customTarget))
        }
        return MuscleHeat.compute(items, fullSets)
    }
}
