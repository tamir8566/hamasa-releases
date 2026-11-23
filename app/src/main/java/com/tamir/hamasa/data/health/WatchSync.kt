package com.tamir.hamasa.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.repo.WorkoutRepository
import com.tamir.hamasa.domain.energy.HrPoint
import com.tamir.hamasa.domain.energy.HrZones
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stage 3: pulls the Galaxy Watch data into the app. Two sources: Samsung Health DIRECTLY ([SamsungHealth], first
 * whenever it is connected: sleep, heart rate, workouts, steps) and Health Connect ([HealthConnect], the fallback
 * for whatever Samsung does not deliver).
 *
 * - Steps of today and yesterday (+ missing days of the last week), with the part inside logged walk/run
 *   sessions, into [WatchStore] (energy screen, home chip).
 * - Watch workouts the app did not log are ADDED as finished sessions through WorkoutRepository.finishSession,
 *   so xp, gold, chests, streak, map, boss, professions and badges work exactly like an app session.
 *   Rules: never twice (Samsung uid "sh:<uid>" / Health Connect id "hc:<id>" in session notes + the handled list
 *   in WatchStore; while Samsung's workouts are readable, Health Connect's are not imported at all), never
 *   older than the moment the link was enabled, never while an app workout is open, and a watch workout that
 *   overlaps an app session is not imported: the app session gets its heart rate instead.
 * - Average / max heart rate (and the watch's calories / distance) for finished app sessions.
 *
 * - Last night's sleep (optional permission, [WatchSleep]): fills today's sleep slider unless he set it by hand.
 *
 * Every failure is swallowed (logged), nothing here may crash the app or the worker.
 */
object WatchSync {
    private const val TAG = "WatchSync"
    /** Session notes of a workout imported from Health Connect: "hc:<id>". */
    const val NOTES_PREFIX = "hc:"
    /** Session notes of a workout imported directly from Samsung Health: "sh:<uid>" (also its key in "seen"). */
    const val SH_NOTES_PREFIX = "sh:"
    /** Background (worker) syncs at most every 30 minutes; the app in front at most every 5. */
    private const val BACKGROUND_GAP_MS = 30L * 60L * 1000L
    private const val FOREGROUND_GAP_MS = 5L * 60L * 1000L
    /** Shorter watch sessions are noise (auto-detection, a stopped timer). */
    private const val MIN_IMPORT_MINUTES = 10
    private const val LOOKBACK_MS = 7L * 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L

    private val mutex = Mutex()

    fun isWatchSession(s: SessionEntity): Boolean =
        s.notes.startsWith(NOTES_PREFIX) || s.notes.startsWith(SH_NOTES_PREFIX)

    /** Fire-and-forget sync (app start / resume). */
    fun syncSoon(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        AppGraph.scope.launch { runCatching { sync(app, force = force, background = false) } }
    }

    /**
     * Refreshes status and, when connected and due, steps / workouts / heart rate.
     * Returns true when a sync actually ran.
     */
    suspend fun sync(context: Context, force: Boolean = false, background: Boolean = false): Boolean {
        val app = context.applicationContext
        // a forced sync (button, permission result) waits for a running one; the others just skip
        if (force) mutex.lock() else if (!mutex.tryLock()) return false
        try {
            val status = HealthConnect.status(app)
            val hcConnected = status == HcStatus.AVAILABLE && HealthConnect.isConnected(app)
            val sh = SamsungHealth.refresh(app)
            WatchStore.setStatus(status, hcConnected, sh)
            if (!hcConnected && !sh.connected) {
                // no watch link: the phone's own night still fills the sleep slider (usage access)
                runCatching { WatchSleep.phoneOnly(app) }.onFailure { Log.w(TAG, "phone sleep", it) }
                return false
            }
            val now = System.currentTimeMillis()
            val st = WatchStore.current()
            // Permissions granted outside the app (Health Connect settings): the link starts now.
            if (st.linkedAt <= 0L) WatchStore.markLinked(now)
            val gap = if (background) BACKGROUND_GAP_MS else FOREGROUND_GAP_MS
            if (!force && now - st.lastSync in 0 until gap) {
                // throttled, but tonight's sleep is still missing: one cheap sleep read (Samsung Health writes
                // the night some time after he wakes up; a background run may also have failed to read it)
                if (!background && WatchSleep.dueWhileThrottled(now)) {
                    runCatching { WatchSleep.sync(app, background = false) }.onFailure { Log.w(TAG, "sleep", it) }
                }
                return false
            }

            // optional permission: last night's sleep -> today's sleep slider (manual wins). First: one small
            // read, before the many step / heart rate calls can use up the Health Connect quota.
            runCatching { WatchSleep.sync(app, background = background) }.onFailure { Log.w(TAG, "sleep", it) }
            var changed = false
            changed = runCatching { importWorkouts(app, now) }.onFailure { Log.w(TAG, "import", it) }.getOrDefault(false) || changed
            changed = runCatching { attachToAppSessions(app, now) }.onFailure { Log.w(TAG, "hr", it) }.getOrDefault(false) || changed
            changed = runCatching { backfillRecentZones(app, now) }.onFailure { Log.w(TAG, "zones backfill", it) }.getOrDefault(false) || changed
            runCatching { syncSteps(app) }.onFailure { Log.w(TAG, "steps", it) }
            // optional permission: daily resting heart rate (coach fatigue signal)
            runCatching { RestingHr.sync(app) }.onFailure { Log.w(TAG, "resting hr", it) }
            WatchStore.markSynced(System.currentTimeMillis())
            if (changed) runCatching { CloudSync.pushSoon(app) }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "sync", t)
            return false
        } finally {
            mutex.unlock()
        }
    }

    /** After the permission screen: mark the link (if new) and sync right away. */
    fun onPermissionsResult(context: Context, granted: Set<String>) {
        val app = context.applicationContext
        AppGraph.scope.launch {
            runCatching {
                if (granted.containsAll(HealthConnect.REQUIRED) && WatchStore.current().linkedAt <= 0L) {
                    WatchStore.markLinked(System.currentTimeMillis())
                }
                sync(app, force = true, background = false)
            }
        }
    }

    /** After "חבר ישירות" (settings card) granted something: mark the link (if new) and sync right away. */
    fun onSamsungConnected(context: Context) {
        val app = context.applicationContext
        AppGraph.scope.launch {
            runCatching {
                if (WatchStore.current().linkedAt <= 0L) WatchStore.markLinked(System.currentTimeMillis())
                sync(app, force = true, background = false)
            }
        }
    }

    /**
     * Right after an app session is finished (WorkoutRepository.finishSession): try to attach the watch heart
     * rate now and once more a few minutes later (Samsung Health writes to Health Connect with a delay).
     * The regular sync keeps trying for a few hours after that.
     */
    fun afterAppSession(sessionId: Long) {
        AppGraph.scope.launch {
            runCatching {
                val app = AppGraph.appContext
                if (!WatchStore.current().connected) return@runCatching
                if (attachOne(app, sessionId, System.currentTimeMillis())) CloudSync.pushSoon(app)
                if (WatchStore.hrFor(sessionId) == null) {
                    delay(4L * 60L * 1000L)
                    if (attachOne(app, sessionId, System.currentTimeMillis())) CloudSync.pushSoon(app)
                }
            }
        }
    }

    /**
     * The length of an app session was edited (WorkoutRepository.editDuration); its end stamp in the db is new.
     * Heart rate already attached stays as it is (no re-fetch). A session that ended less than 6 h ago and has
     * no heart rate: the "watch not worn" marker (if any) is dropped, so the next sync reads the new window
     * (attachToAppSessions always reads the stamps from the db).
     */
    fun afterDurationEdit(sessionId: Long, newEndMillis: Long?) {
        val end = newEndMillis ?: return
        if (System.currentTimeMillis() - end >= 6L * HOUR_MS) return
        val cur = WatchStore.current().sessions[sessionId] ?: return
        if (cur.hasHr) return
        WatchStore.removeSession(sessionId)
    }

    // ---------------------------------------------------------------- workouts

    /** Watch exercise type -> session type; null = not imported. */
    fun typeFor(exerciseType: Int): SessionType? = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> SessionType.RUN

        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> SessionType.WALK

        ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> SessionType.YOGA

        // Strength from the watch has no sets: a short session (counts for the streak, map, endurance is
        // not touched) instead of a fake gym session that would mislead the coach's progression.
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> SessionType.MINI

        // Breathing and motor sports are not workouts for the game.
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING,
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING,
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING,
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> null

        // Cycling, elliptical, stairs, rowing, swimming, ball games, "other workout": endurance (walk-like).
        else -> SessionType.WALK
    }

    /** "ריצה 19:05, 32 דק'" for the import diagnostic. */
    private fun diagLabel(w: Candidate): String {
        val t = java.time.Instant.ofEpochMilli(w.startMillis).atZone(ZoneId.systemDefault())
        val kind = w.type?.let { titleFor(it, w.walkTitle) } ?: "אחר"
        return String.format(java.util.Locale.US, "%s %02d:%02d, %d דק'", kind, t.hour, t.minute, w.minutes)
    }

    private fun titleFor(type: SessionType, walkTitle: Boolean): String = when (type) {
        SessionType.RUN -> "ריצה"
        SessionType.YOGA -> "יוגה"
        SessionType.MINI -> "אימון כוח"
        SessionType.GYM -> "אימון"
        SessionType.WALK -> if (walkTitle) "הליכה" else "אימון סיבולת"
    }

    private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean = aStart < bEnd && aEnd > bStart

    private fun localDay(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** Returns true when something was written. */
    private suspend fun importWorkouts(app: Context, now: Long): Boolean {
        val db = AppGraph.db
        val st = WatchStore.current()
        val linkedAt = st.linkedAt
        if (linkedAt <= 0L) return false
        // Never while an app workout is open (its end time is unknown yet; the overlap rule needs it).
        val all = db.sessions().allSessionsOnce()
        if (all.any { it.status == SessionStatus.ACTIVE }) {
            WatchStore.setImportDiag("אימונים מהשעון: ממתינים. יש אימון פתוח באפליקציה (\"המשך אימון\" בבית). כשהוא ייסגר, הם ייכנסו.")
            return false
        }
        // A row left half-written by a killed import: retire it (its id is not in "seen", so it is retried).
        for (s in all) {
            if (s.status == SessionStatus.PLANNED && isWatchSession(s)) {
                db.sessions().update(s.copy(status = SessionStatus.SKIPPED, endedAtMillis = now))
            }
        }
        val since = maxOf(linkedAt, now - LOOKBACK_MS)
        val workouts = candidates(app, since, now)
        if (workouts.isEmpty()) {
            val src = if (SamsungHealth.canRead(SamsungHealth.Kind.EXERCISE)) "סמסונג הלת'" else "הלת' קונקט"
            val err = if (SamsungHealth.lastDiag.isNotBlank()) " (${SamsungHealth.lastDiag.take(80)})" else ""
            WatchStore.setImportDiag("אימונים מהשעון: לא נמצאו אימונים ב-7 הימים האחרונים דרך $src$err.")
            return false
        }
        val diag = ArrayList<String>()

        val doneNotes = all.filter { it.status == SessionStatus.DONE && isWatchSession(it) }.map { it.notes }.toHashSet()
        var changed = false
        val imported = ArrayList<String>()
        for (w in workouts) {
            val label = diagLabel(w)
            if (w.seenKey.isBlank()) continue
            if (w.noteId in doneNotes) { diag.add("$label: כבר יובא"); continue }
            if (w.seenKey in WatchStore.current().seen) { diag.add("$label: טופל בעבר"); continue }
            if (w.ownApp) { WatchStore.markSeen(w.seenKey); diag.add("$label: נרשם מהאפליקציה"); continue }
            // Only what started after the link was enabled. No retro flood of rewards.
            if (w.startMillis < linkedAt || w.endMillis > now || w.endMillis <= w.startMillis) {
                if (w.startMillis < linkedAt) { WatchStore.markSeen(w.seenKey); diag.add("$label: לפני החיבור") }
                else diag.add("$label: עוד לא הסתיים")
                continue
            }
            if (w.minutes < MIN_IMPORT_MINUTES) { WatchStore.markSeen(w.seenKey); diag.add("$label: קצר מ-$MIN_IMPORT_MINUTES דקות"); continue }

            // Overlap with a finished session (fresh list: earlier imports of this loop count too).
            val done = db.sessions().doneSessionsOnce()
            val overlap = done.firstOrNull { s ->
                val a = s.startedAtMillis ?: return@firstOrNull false
                val b = s.endedAtMillis ?: return@firstOrNull false
                overlaps(w.startMillis, w.endMillis, a, b)
            }
            if (overlap != null) {
                // Logged in the app: the app session keeps its rewards and gets the watch heart rate.
                // Overlapping another imported session: a duplicate record (two sources), skipped.
                if (!isWatchSession(overlap) && WatchStore.hrFor(overlap.id) == null) {
                    val a = overlap.startedAtMillis ?: w.startMillis
                    val b = overlap.endedAtMillis ?: w.endMillis
                    if (attachRange(app, overlap.id, a, b, w.known)) changed = true
                }
                WatchStore.markSeen(w.seenKey)
                diag.add("$label: חופף לאימון שנרשם באפליקציה")
                continue
            }

            val type = w.type
            if (type == null) { WatchStore.markSeen(w.seenKey); diag.add("$label: סוג שלא נספר כאימון"); continue }
            val line = importOne(app, w, type)
            if (line == null) { diag.add("$label: הייבוא נכשל"); continue }
            WatchStore.markSeen(w.seenKey)
            imported.add(line)
            diag.add("$label: יובא")
            changed = true
        }
        WatchStore.setImportDiag("אימונים מהשעון (7 ימים): " + diag.takeLast(4).joinToString(" · "))
        if (imported.isNotEmpty()) {
            val text = if (imported.size == 1) "מהשעון: ${imported[0]}" else "מהשעון: ${imported.size} אימונים. האחרון: ${imported.last()}"
            WatchStore.setLastImport(text, localDay(now))
        }
        return changed
    }

    /**
     * One watch workout from either source. [noteId] goes into the session notes ("sh:<uid>" / "hc:<id>"),
     * [seenKey] into WatchStore.seen ("sh:<uid>" / the bare Health Connect id, as before). [known]: what Samsung
     * measured itself (heart rate, kcal, distance), null for Health Connect.
     */
    private class Candidate(
        val noteId: String,
        val seenKey: String,
        val type: SessionType?,
        val walkTitle: Boolean,
        val startMillis: Long,
        val endMillis: Long,
        val ownApp: Boolean,
        val known: SessionWatch?
    ) {
        val minutes: Int get() = ((endMillis - startMillis) / 60000L).toInt()
    }

    /**
     * Samsung Health first: when its workouts are readable, Health Connect's are not imported at all (the same
     * workout would come twice). Health Connect only when Samsung is not connected or its read failed.
     */
    private suspend fun candidates(app: Context, since: Long, now: Long): List<Candidate> {
        val sh = if (SamsungHealth.canRead(SamsungHealth.Kind.EXERCISE)) SamsungHealth.exerciseSessions(app, since, now) else null
        if (sh != null) {
            return sh.map { w ->
                Candidate(
                    noteId = w.id, seenKey = w.id, type = w.type, walkTitle = w.walkTitle,
                    startMillis = w.startMillis, endMillis = w.endMillis, ownApp = false,
                    known = SessionWatch(w.avgHr, w.maxHr, w.kcal, w.distanceM)
                )
            }
        }
        return HealthConnect.exerciseSessions(app, since, now).map { w ->
            Candidate(
                noteId = NOTES_PREFIX + w.id, seenKey = w.id, type = typeFor(w.exerciseType),
                walkTitle = w.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING ||
                    w.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
                startMillis = w.startMillis, endMillis = w.endMillis,
                ownApp = w.originPackage == app.packageName, known = null
            )
        }
    }

    /**
     * Writes one watch workout as a session and closes it through finishSession (the normal reward path).
     * Returns a short Hebrew line for the UI, or null when it failed.
     */
    private suspend fun importOne(app: Context, w: Candidate, type: SessionType): String? {
        val db = AppGraph.db
        val day = localDay(w.startMillis)
        val durationSec = ((w.endMillis - w.startMillis) / 1000L).toInt().coerceAtLeast(60)
        // PLANNED (not ACTIVE) so the home screen never flashes "המשך אימון" while the row is being written.
        val id = db.sessions().insert(
            SessionEntity(
                dateEpochDay = day, type = type, status = SessionStatus.PLANNED,
                modeId = if (type == SessionType.YOGA) "yoga" else "mini",
                startedAtMillis = w.startMillis, notes = w.noteId
            )
        )
        if (type == SessionType.WALK) {
            // Same shape as an app walk: one completed timed set of the "walk" exercise (a hit on the boss).
            db.sessions().insertSets(
                listOf(
                    SetLogEntity(
                        sessionId = id, exerciseId = WorkoutRepository.WALK_EXERCISE_ID, setIndex = 0,
                        targetReps = durationSec, targetWeight = 0f, reps = durationSec, weight = 0f, completed = true
                    )
                )
            )
        }
        // Watch data first, so the done-screen math (burn) sees it. Samsung's own numbers for its workout first.
        val known = w.known
        val hr = if (known != null && known.avgHr > 0) HrSummary(known.avgHr, maxOf(known.maxHr, known.avgHr))
        else SamsungHealth.heartRate(app, w.startMillis, w.endMillis) ?: HealthConnect.heartRate(app, w.startMillis, w.endMillis)
        val kcal = known?.kcal?.takeIf { it > 0 } ?: HealthConnect.kcal(app, w.startMillis, w.endMillis)
        val dist = known?.distanceM?.takeIf { it > 0 } ?: HealthConnect.distanceMeters(app, w.startMillis, w.endMillis)
        val zones = zonesFor(app, type, w.startMillis, w.endMillis, "imp$id")
        WatchStore.putSession(id, SessionWatch(hr?.avg ?: 0, hr?.max ?: 0, kcal ?: 0, dist ?: 0, zones))

        val rewards = try {
            AppGraph.workouts.finishSession(id, rating = 0, goalMet = false, endedAtMillis = w.endMillis)
        } catch (t: Throwable) {
            Log.w(TAG, "finish import", t)
            runCatching { db.sessions().byId(id)?.let { db.sessions().update(it.copy(status = SessionStatus.SKIPPED)) } }
            return null
        }
        val minutes = durationSec / 60
        return "${titleFor(type, w.walkTitle)} של $minutes דקות. +${rewards.xp} ניסיון, +${rewards.gold} זהב."
    }

    // ---------------------------------------------------------------- zones for older sessions

    /**
     * Walks / runs of the last 14 days that have watch heart rate but no zones (imported before zones existed, or
     * the samples were not ready yet) get their zones now. True when at least one changed.
     */
    private suspend fun backfillRecentZones(app: Context, now: Long): Boolean {
        var changed = false
        val ids = WatchStore.current().sessions.filter { (_, w) -> w.hasHr && !w.hasZones }.keys.toList()
        for (id in ids) {
            val s = runCatching { AppGraph.db.sessions().byId(id) }.getOrNull() ?: continue
            val end = s.endedAtMillis ?: continue
            if (now - end > 14L * 24L * HOUR_MS) continue
            if (backfillZones(app, id)) changed = true
        }
        return changed
    }

    /**
     * Zones for one finished WALK / RUN session that has watch heart rate but no zones yet (the session detail
     * screen calls this on open). Keeps the stored avg / max / kcal / distance. Never throws; true when stored.
     */
    suspend fun backfillZones(context: Context, sessionId: Long): Boolean {
        return try {
            val app = context.applicationContext
            val w = WatchStore.current().sessions[sessionId] ?: return false
            if (!w.hasHr || w.hasZones) return false
            val s = AppGraph.db.sessions().byId(sessionId) ?: return false
            if (s.type != SessionType.WALK && s.type != SessionType.RUN) return false
            val a = s.startedAtMillis ?: return false
            val b = s.endedAtMillis ?: return false
            val zones = zonesFor(app, s.type, a, b, "s$sessionId")
            if (zones.size != HrZones.ZONES || zones.none { it > 0 }) return false
            WatchStore.putSession(sessionId, w.copy(zonesMin = zones))
            runCatching { CloudSync.pushSoon(app) }
            true
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "zones backfill one", t)
            false
        }
    }

    // ---------------------------------------------------------------- heart rate for app sessions

    /** Finished app sessions of the last two days without watch data get it (until 6 h after they ended). */
    private suspend fun attachToAppSessions(app: Context, now: Long): Boolean {
        val linkedAt = WatchStore.current().linkedAt
        var changed = false
        val recent = AppGraph.db.sessions().doneSessionsOnce().filter { s ->
            val end = s.endedAtMillis
            !isWatchSession(s) && end != null && end >= linkedAt && now - end < 2L * 24L * HOUR_MS &&
                !WatchStore.current().sessions.containsKey(s.id)
        }
        for (s in recent) if (attachOne(app, s.id, now)) changed = true
        return changed
    }

    private suspend fun attachOne(app: Context, sessionId: Long, now: Long): Boolean {
        val s = AppGraph.db.sessions().byId(sessionId) ?: return false
        if (isWatchSession(s) || s.status != SessionStatus.DONE) return false
        if (WatchStore.current().sessions.containsKey(sessionId)) return false
        val a = s.startedAtMillis ?: return false
        val b = s.endedAtMillis ?: return false
        if (attachRange(app, sessionId, a, b)) return true
        // Still nothing hours later: the watch was not worn. Remember that, stop asking.
        if (now - b > 6L * HOUR_MS) WatchStore.putSession(sessionId, SessionWatch(0, 0, 0, 0))
        return false
    }

    /**
     * Heart rate (and kcal / distance) of an app session's time range: Samsung Health's samples first, then the
     * overlapping Samsung workout's own numbers ([known]), then Health Connect. False = no heart rate anywhere yet.
     */
    private suspend fun attachRange(app: Context, sessionId: Long, start: Long, end: Long, known: SessionWatch? = null): Boolean {
        val fromKnown = if (known != null && known.avgHr > 0) HrSummary(known.avgHr, maxOf(known.maxHr, known.avgHr)) else null
        val hr = SamsungHealth.heartRate(app, start, end) ?: fromKnown ?: HealthConnect.heartRate(app, start, end) ?: return false
        val kcal = known?.kcal?.takeIf { it > 0 } ?: HealthConnect.kcal(app, start, end) ?: 0
        val dist = known?.distanceM?.takeIf { it > 0 } ?: HealthConnect.distanceMeters(app, start, end) ?: 0
        // walks / runs: minutes per heart rate zone from the raw samples
        val type = runCatching { AppGraph.db.sessions().byId(sessionId)?.type }.getOrNull()
        val zones = if (type != null) zonesFor(app, type, start, end, "att$sessionId") else emptyList()
        WatchStore.putSession(sessionId, SessionWatch(hr.avg, hr.max, kcal, dist, zones))
        return true
    }

    /**
     * Minutes in heart rate zones 1..5 (HrZones) of a WALK / RUN session's time range, from the raw samples:
     * Samsung Health directly first, Health Connect's HeartRateRecord samples otherwise. HRmax = 220 - age
     * (profile). Empty for other session types, when there are no samples, or when anything fails.
     */
    private suspend fun zonesFor(app: Context, type: SessionType, start: Long, end: Long, tag: String = ""): List<Int> {
        if (type != SessionType.WALK && type != SessionType.RUN) return emptyList()
        if (end <= start) return emptyList()
        var diag = "$tag ${type.name} ${(end - start) / 60_000L}m"
        val out = try {
            // the workout's own heart rate log first (dense), then the heart rate type, then Health Connect;
            // a source with too few points for the time range (sparse all-day samples) is passed over
            val minPoints = (((end - start) / 60_000L) / 2L).toInt().coerceIn(5, 60)
            val log = SamsungHealth.exerciseHrPoints(app, start, end)
            diag += " log=" + (log?.size?.toString() ?: "null")
            var points: List<HrPoint>? = log?.takeIf { it.size >= minPoints }
            if (points == null) {
                val hr = SamsungHealth.hrPoints(app, start, end)
                diag += " hr=" + (hr?.size?.toString() ?: "null")
                points = hr?.takeIf { it.size >= minPoints }
            }
            if (points == null) {
                val hc = HealthConnect.hrPoints(app, start, end)
                diag += " hc=" + (hc?.size?.toString() ?: "null")
                points = hc?.takeIf { it.size >= minPoints }
            }
            if (points == null) {
                emptyList()
            } else {
                val age = runCatching { AppGraph.prefs.current().age }.getOrDefault(0)
                val res = HrZones.computeForAge(points, age)
                if (res.isEmpty) emptyList() else res.minutes
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "zones", t)
            diag += " err=" + (t.javaClass.simpleName)
            emptyList()
        }
        diag += " -> " + (if (out.isEmpty()) "none" else out.joinToString("/"))
        WatchStore.addZonesDiag(diag)
        return out
    }

    // ---------------------------------------------------------------- steps

    private suspend fun syncSteps(app: Context) {
        val today = LocalDate.now().toEpochDay()
        val have = WatchStore.current().steps
        for (day in (today - 6)..today) {
            // today and yesterday always (yesterday's final count); older days only when missing
            if (day < today - 1 && have.containsKey(day)) continue
            // Samsung Health directly first, Health Connect when Samsung is not connected or failed
            val total = SamsungHealth.stepsForDay(app, day) ?: HealthConnect.stepsForDay(app, day) ?: continue
            val inWorkouts = stepsInWorkouts(app, day)
            WatchStore.putSteps(day, DaySteps(total.toInt(), inWorkouts))
        }
    }

    /** Steps inside the finished walk / run sessions of [day] (they are already counted as workout burn). */
    /** Test hook: fixed step count per session. */
    @Volatile var stepsOverride: ((Long) -> Int?)? = null

    /** Steps taken during one walk / run session (null = not a walk / run, unknown time, or no step source). */
    suspend fun stepsForSession(app: Context, sessionId: Long): Int? {
        stepsOverride?.let { return it(sessionId) }
        val s = AppGraph.db.sessions().byId(sessionId) ?: return null
        if (s.type != SessionType.WALK && s.type != SessionType.RUN) return null
        val a = s.startedAtMillis ?: return null
        val b = s.endedAtMillis ?: return null
        val n = SamsungHealth.stepsBetween(app, a, b)
            ?: HealthConnect.stepsBetween(app, Instant.ofEpochMilli(a), Instant.ofEpochMilli(b))
            ?: return null
        return n.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private suspend fun stepsInWorkouts(app: Context, day: Long): Int {
        var sum = 0L
        for (s in AppGraph.db.sessions().doneSessionsOnce()) {
            if (s.dateEpochDay != day) continue
            if (s.type != SessionType.WALK && s.type != SessionType.RUN) continue
            val a = s.startedAtMillis ?: continue
            val b = s.endedAtMillis ?: continue
            sum += SamsungHealth.stepsBetween(app, a, b)
                ?: HealthConnect.stepsBetween(app, Instant.ofEpochMilli(a), Instant.ofEpochMilli(b)) ?: 0L
        }
        return sum.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
