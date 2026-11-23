package com.tamir.hamasa.data.music

import android.content.Context
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** A chosen Spotify item: a track ("spotify:track:...") or a playlist ("spotify:playlist:..."), with its name. */
data class MusicChoice(val uri: String, val name: String, val total: Int = 0) {
    val isTrack: Boolean get() = uri.startsWith("spotify:track:")
}

data class MusicPrefs(
    val enabled: Boolean = true,
    val playlist: MusicChoice? = null,
    val boss: MusicChoice? = null,
    val lastSet: MusicChoice? = null,
    val resumeAfter: Boolean = false,
    /** Workout playlist order: false = in order from the first song, true = shuffle. */
    val shuffle: Boolean = true,
    /** Workout playlist repeat: "off" | "context" (playlist on loop) | "track" (one song on loop). */
    val repeat: String = "off"
)

/** The three places a choice goes. */
enum class MusicSlot(val key: String) { PLAYLIST("playlist"), BOSS("boss"), LAST_SET("last") }

/**
 * Music choices (non-secret) + per-session "already played" marks, in the private SharedPreferences "music".
 * Kept on this device only: after a new install he reconnects Spotify anyway, so syncing them gives nothing.
 */
object MusicSettings {
    private const val PREFS = "music"

    private fun sp() = AppGraph.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state: MutableStateFlow<MusicPrefs> by lazy { MutableStateFlow(read()) }
    val state: StateFlow<MusicPrefs> get() = _state

    private fun read(): MusicPrefs = try {
        val p = sp()
        MusicPrefs(
            enabled = p.getBoolean("enabled", true),
            playlist = readChoice(MusicSlot.PLAYLIST),
            boss = readChoice(MusicSlot.BOSS),
            lastSet = readChoice(MusicSlot.LAST_SET),
            resumeAfter = p.getBoolean("resumeAfter", false),
            shuffle = p.getBoolean("shuffle", true),
            repeat = p.getString("repeat", "off") ?: "off"
        )
    } catch (e: Exception) {
        MusicPrefs()
    }

    private fun readChoice(slot: MusicSlot): MusicChoice? {
        val p = sp()
        val uri = p.getString(slot.key + "_uri", null) ?: return null
        if (uri.isBlank()) return null
        return MusicChoice(uri, p.getString(slot.key + "_name", null).orEmpty(), p.getInt(slot.key + "_total", 0))
    }

    fun setEnabled(on: Boolean) {
        runCatching { sp().edit().putBoolean("enabled", on).apply() }
        _state.value = _state.value.copy(enabled = on)
    }

    fun setResumeAfter(on: Boolean) {
        runCatching { sp().edit().putBoolean("resumeAfter", on).apply() }
        _state.value = _state.value.copy(resumeAfter = on)
    }

    fun setShuffle(on: Boolean) {
        runCatching { sp().edit().putBoolean("shuffle", on).apply() }
        _state.value = _state.value.copy(shuffle = on)
    }

    fun setRepeat(state: String) {
        runCatching { sp().edit().putString("repeat", state).apply() }
        _state.value = _state.value.copy(repeat = state)
    }

    /** [choice] null = clear the slot. */
    fun setChoice(slot: MusicSlot, choice: MusicChoice?) {
        runCatching {
            val e = sp().edit()
            if (choice == null) {
                e.remove(slot.key + "_uri").remove(slot.key + "_name").remove(slot.key + "_total")
            } else {
                e.putString(slot.key + "_uri", choice.uri).putString(slot.key + "_name", choice.name).putInt(slot.key + "_total", choice.total)
            }
            e.apply()
        }
        val cur = _state.value
        _state.value = when (slot) {
            MusicSlot.PLAYLIST -> cur.copy(playlist = choice)
            MusicSlot.BOSS -> cur.copy(boss = choice)
            MusicSlot.LAST_SET -> cur.copy(lastSet = choice)
        }
    }

    // per-session marks: the id of the last session where this moment already happened
    internal fun mark(name: String): Long = runCatching { sp().getLong("mark_$name", -1L) }.getOrDefault(-1L)
    internal fun setMark(name: String, sessionId: Long) { runCatching { sp().edit().putLong("mark_$name", sessionId).apply() } }
}

/**
 * "מוזיקה שמגיבה": the workout's music moments, decided from the live list of sets.
 *
 * - Start: the workout playlist (random start position) when the session opens with no set done yet. When the
 *   very first set is already the heavy set: the playlist first, then the entrance song through the queue, so the
 *   playlist goes on after it.
 * - "כניסת בוס": the moment the heavy set becomes the next set to do (the previous set was just finished, so the
 *   entrance song plays through the rest before it). Heavy set = the first set of the compound exercise with the
 *   highest planned weight in this session (ties: the earlier one). It is the clearest "boss" of the workout and,
 *   unlike the boss bar, it exists in every session (the bar shows only when the weekly boss can be hit).
 * - When the heavy exercise is over: back to the workout playlist.
 * - Last set (exactly one set left): the "last set" track/playlist, a faster tempo.
 * - Finish: optionally back to the playlist.
 * The coach's easy cardio finisher ([FINISHER_ID], "walk") is ignored for the heavy set and for the last-set moment:
 * the last set is the last non-finisher set.
 * Each moment happens once per session (marks survive leaving and reopening the screen).
 * Everything is fire-and-forget on AppGraph.scope and never throws.
 */
object WorkoutMusic {
    private const val START = "start"
    private const val BOSS = "boss"
    private const val BOSS_DONE = "bossDone"
    private const val LAST = "last"
    private const val ERROR = "error"

    const val BOSS_LINE = "שיר כניסה. לבוס יש אחד. עכשיו גם לך."
    const val LAST_LINE = "הסט האחרון. הקצב עולה. המפלצת מבקשת להנמיך. לא."

    /** The coach's easy cardio finisher at the end of gym plans (CoachEngine.FINISHER_ID). */
    private const val FINISHER_ID = "walk"

    /** A music moment. Internal so the pure decision ([decideFrom]) can be tested without Spotify. */
    internal enum class Action { PLAYLIST, BOSS, PLAYLIST_THEN_BOSS, LAST }

    private val mutex = Mutex()
    /** One Spotify sequence at a time: two moments right after each other must not interleave their calls. */
    private val playMutex = Mutex()

    /** Why the last [safePlay] failed (read before any resume() can clear SpotifyPlayer.lastError). */
    @Volatile private var failReason: MusicError? = null

    /** Called with every new list of sets of the live workout. [onLine] gets one short line to show (any thread). */
    fun onSets(sessionId: Long, sets: List<SetLogEntity>, restActive: Boolean = false, onLine: (String) -> Unit) {
        if (!SpotifyConfig.enabled || sets.isEmpty()) return
        val snapshot = sets.toList()
        AppGraph.scope.launch {
            runCatching {
                val action = decide(sessionId, snapshot, restActive)
                if (action != null) playMutex.withLock { perform(sessionId, action, onLine) }
            }
        }
    }

    /** The workout was finished: back to the playlist when he asked for it. */
    fun onFinish() {
        if (!SpotifyConfig.enabled) return
        AppGraph.scope.launch {
            runCatching {
                val prefs = MusicSettings.state.value
                val pl = prefs.playlist
                if (prefs.enabled && prefs.resumeAfter && pl != null && SpotifyAuth.isConnected) {
                    playMutex.withLock { playWorkoutPlaylist(pl, prefs) }
                }
            }
        }
    }

    private suspend fun decide(sessionId: Long, sets: List<SetLogEntity>, restActive: Boolean): Action? = mutex.withLock {
        val prefs = MusicSettings.state.value
        if (!prefs.enabled || !SpotifyAuth.isConnected) return@withLock null
        val session = AppGraph.db.sessions().byId(sessionId) ?: return@withLock null
        if (session.status != SessionStatus.ACTIVE) return@withLock null
        val compound = HashMap<String, Boolean>()
        for (id in sets.map { it.exerciseId }.distinct()) {
            compound[id] = AppGraph.db.exercises().byId(id)?.isCompound == true
        }
        decideFrom(
            sessionId, sets, prefs,
            isCompound = { id -> compound[id] == true },
            mark = { name -> MusicSettings.mark(name) },
            setMark = { name, id -> MusicSettings.setMark(name, id) },
            restActive = restActive
        )
    }

    /**
     * The pure decision for one new list of sets: which moment (if any) happens now. [mark]/[setMark] are the
     * per-session marks ("this moment already happened in session N"). No Spotify, no database.
     */
    internal fun decideFrom(
        sessionId: Long,
        sets: List<SetLogEntity>,
        prefs: MusicPrefs,
        isCompound: (String) -> Boolean,
        mark: (String) -> Long,
        setMark: (String, Long) -> Unit,
        restActive: Boolean = false
    ): Action? {
        // same order as the workout screen: exercises by first appearance, sets by index
        val groups = sets.groupBy { it.exerciseId }.map { (id, list) -> id to list.sortedBy { it.setIndex } }
        val ordered = groups.flatMap { it.second }
        val incomplete = ordered.filter { !it.completed }
        val next = incomplete.firstOrNull()
        // sets that count for the "last set" moment: everything but the cardio finisher
        val incompleteMain = incomplete.filter { it.exerciseId != FINISHER_ID }
        val heavy = heavySet(groups, isCompound)
        val nextIsHeavy = next != null && heavy != null && next.id == heavy.id

        // the last-set song waits for the end of the rest before that set (Tamir): while the rest runs, nothing else
        // from the "next set" branch happens either; onSets is called again when the rest is over
        val waitForRest = restActive && incompleteMain.size == 1 && prefs.lastSet != null && mark(LAST) != sessionId
        var action: Action? = null
        if (mark(START) != sessionId) {
            setMark(START, sessionId)
            if (ordered.none { it.completed }) {
                if (nextIsHeavy && prefs.boss != null) {
                    action = if (prefs.playlist != null) Action.PLAYLIST_THEN_BOSS else Action.BOSS
                } else if (prefs.playlist != null) {
                    action = Action.PLAYLIST
                }
            }
        }
        if (action == null && next != null && !waitForRest) {
            if (nextIsHeavy && prefs.boss != null && mark(BOSS) != sessionId) {
                action = Action.BOSS
            } else if (incompleteMain.size == 1 && next.exerciseId != FINISHER_ID &&
                prefs.lastSet != null && mark(LAST) != sessionId
            ) {
                action = Action.LAST
            } else if (heavy != null && next.exerciseId != heavy.exerciseId &&
                mark(BOSS) == sessionId && mark(BOSS_DONE) != sessionId
            ) {
                setMark(BOSS_DONE, sessionId)
                if (prefs.playlist != null) action = Action.PLAYLIST
            }
        }
        when (action) {
            Action.BOSS, Action.PLAYLIST_THEN_BOSS -> {
                setMark(BOSS, sessionId)
                // the heavy set is also the very last one: the entrance song stays (no last-set song, and no
                // switch back to the playlist for the cardio finisher, same as after a last-set song)
                if (incompleteMain.size == 1) {
                    setMark(LAST, sessionId)
                    setMark(BOSS_DONE, sessionId)
                }
            }
            Action.LAST -> {
                setMark(LAST, sessionId)
                setMark(BOSS_DONE, sessionId)
            }
            else -> {}
        }
        return action
    }

    /** First set of the compound (non-mobility) exercise with the highest planned weight; ties keep the earlier one. */
    private fun heavySet(groups: List<Pair<String, List<SetLogEntity>>>, isCompound: (String) -> Boolean): SetLogEntity? {
        var best: SetLogEntity? = null
        var bestWeight = -1f
        for ((id, rows) in groups) {
            if (rows.isEmpty() || id == FINISHER_ID || ExerciseCatalog.isMobility(id)) continue
            if (!isCompound(id)) continue
            val w = rows.maxOf { it.targetWeight }
            if (w > bestWeight) {
                bestWeight = w
                best = rows.first()
            }
        }
        return best
    }

    /**
     * Never leaves him in silence: a random start position that Spotify refuses (the playlist's count includes
     * tracks that cannot play, or it shrank since it was picked) is tried again from the first song; if the new
     * item still does not play, whatever was playing before is resumed. A single song goes through the queue
     * ([SpotifyPlayer.playTrackKeepingContext]); when nothing is playing at all, [startContext] (the workout
     * playlist) is started first so there is a playlist to go on with after the song.
     */
    private suspend fun safePlay(uri: String, offset: Int?, startContext: (suspend () -> Boolean)? = null): Boolean {
        failReason = null
        if (uri.startsWith("spotify:track:")) {
            if (SpotifyPlayer.playTrackKeepingContext(uri, startContext)) return true
            failReason = SpotifyPlayer.lastError
            runCatching { SpotifyPlayer.resume() }
            return false
        }
        if (SpotifyPlayer.play(uri, offset)) return true
        failReason = SpotifyPlayer.lastError
        if (offset != null && SpotifyPlayer.play(uri, null)) { failReason = null; return true }
        runCatching { SpotifyPlayer.resume() }
        return false
    }

    private fun randomStart(choice: MusicChoice): Int? =
        if (!choice.isTrack && choice.total > 1) Random.nextInt(choice.total) else null

    /**
     * The workout playlist with his order and loop choices: shuffle = random start + shuffle on,
     * in order = from the first song with shuffle off (turned off BEFORE the play too: with shuffle still on,
     * Spotify would start the playlist at a random song). Then the repeat mode. Mode failures do not count as a
     * failed play (the music is already on). Used at the start, after the heavy exercise, at the finish and by
     * "נסה עכשיו".
     */
    suspend fun playWorkoutPlaylist(choice: MusicChoice, prefs: MusicPrefs): Boolean {
        val shuffle = prefs.shuffle && !choice.isTrack
        if (!shuffle) runCatching { SpotifyPlayer.setShuffle(false) }
        val start = if (prefs.shuffle) randomStart(choice) else null
        val ok = safePlay(choice.uri, start)
        if (ok) {
            SpotifyPlayer.setShuffle(shuffle)
            SpotifyPlayer.setRepeat(prefs.repeat)
        }
        return ok
    }

    private suspend fun perform(sessionId: Long, action: Action, onLine: (String) -> Unit) {
        val prefs = MusicSettings.state.value
        val choice = when (action) {
            Action.PLAYLIST -> prefs.playlist
            Action.BOSS, Action.PLAYLIST_THEN_BOSS -> prefs.boss
            Action.LAST -> prefs.lastSet
        } ?: return
        val pl = prefs.playlist
        // nothing playing when a single song is due: the workout playlist first, the song on top of it
        val startPlaylist: (suspend () -> Boolean)? = pl?.let { p -> suspend { playWorkoutPlaylist(p, prefs) } }
        val ok = when (action) {
            Action.PLAYLIST -> playWorkoutPlaylist(choice, prefs)
            Action.BOSS -> safePlay(choice.uri, null, startPlaylist)
            Action.PLAYLIST_THEN_BOSS -> {
                // the playlist first, so the entrance song (queued + skipped to) has a playlist to go back to
                val plOk = pl != null && playWorkoutPlaylist(pl, prefs)
                if (plOk) kotlinx.coroutines.delay(800)
                safePlay(choice.uri, null, startPlaylist)
            }
            Action.LAST -> safePlay(choice.uri, randomStart(choice), startPlaylist)
        }
        if (ok) {
            when (action) {
                Action.BOSS, Action.PLAYLIST_THEN_BOSS -> onLine(BOSS_LINE)
                Action.LAST -> onLine(LAST_LINE)
                Action.PLAYLIST -> {}
            }
        } else if (MusicSettings.mark(ERROR) != sessionId) {
            // one line per workout about a silent Spotify, not one per set
            MusicSettings.setMark(ERROR, sessionId)
            onLine(SpotifyPlayer.errorLineHe(failReason ?: SpotifyPlayer.lastError))
        }
    }
}
