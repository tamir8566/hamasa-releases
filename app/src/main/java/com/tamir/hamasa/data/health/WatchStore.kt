package com.tamir.hamasa.data.health

import android.content.Context
import android.content.SharedPreferences
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Steps of one day: [total] from Health Connect, [inWorkouts] the part inside logged walk/run sessions. */
data class DaySteps(val total: Int, val inWorkouts: Int) {
    /** Steps outside workouts (those are already counted as workout burn). */
    val outside: Int get() = (total - inWorkouts).coerceAtLeast(0)
}

/**
 * Watch data attached to one session (by session id). 0 = unknown.
 * [zonesMin]: minutes in heart rate zones 1..5 (HrZones), walks / runs only; empty = not measured.
 */
data class SessionWatch(
    val avgHr: Int,
    val maxHr: Int,
    val kcal: Int,
    val distanceM: Int,
    val zonesMin: List<Int> = emptyList()
) {
    val hasHr: Boolean get() = avgHr > 0
    val hasZones: Boolean get() = zonesMin.size == 5 && zonesMin.any { it > 0 }
}

data class WatchState(
    val status: HcStatus = HcStatus.UNAVAILABLE,
    /** Some source is linked: Health Connect ([hcConnected]) or Samsung Health directly ([shStatus]). */
    val connected: Boolean = false,
    /** Health Connect is available and the required read permissions are granted. */
    val hcConnected: Boolean = false,
    /** The direct Samsung Health link (SamsungHealth). Not persisted: refreshed on every sync. */
    val shStatus: ShStatus = ShStatus.UNAVAILABLE,
    /** Moment the link was enabled; watch workouts that started earlier are never imported. 0 = never. */
    val linkedAt: Long = 0L,
    val lastSync: Long = 0L,
    val steps: Map<Long, DaySteps> = emptyMap(),
    val sessions: Map<Long, SessionWatch> = emptyMap(),
    /** Health Connect exercise ids already handled (imported, merged into an app session, or skipped). */
    val seen: List<String> = emptyList(),
    /** Last import, for the settings card and the home chip ("" = none). */
    val lastImportHe: String = "",
    val lastImportDay: Long = 0L,
    /** Minutes asleep per night from the watch, keyed by the day he woke up (last ~40 days, WatchSleep). */
    val sleep: Map<Long, Int> = emptyMap(),
    /** Night (wake-up day) -> start and end of the sleep in millis, for "מ-23:40 עד 06:50". */
    val sleepSpans: Map<Long, Pair<Long, Long>> = emptyMap(),
    /** Settings card diagnostic line about sleep in Health Connect (Hebrew, "" = never checked). */
    val sleepDiagHe: String = "",
    /** Settings card diagnostic of the last workout import: what was found in the window and why each one was skipped. */
    val importDiagHe: String = "",
    /** Debug only, never shown: data origins, stage types, last error of the sleep read. */
    val sleepDiagDebug: String = ""
)

/**
 * Local store of the watch link (SharedPreferences "watch", like CloudSync's "cloud"), exposed as a StateFlow
 * for the UI. Steps, per-session watch data and the handled ids also ride along in the cloud profile row
 * (CloudSync.profileJson / pullAll). [WatchState.linkedAt] stays local: a new phone has to connect again.
 */
object WatchStore {
    private const val PREFS = "watch"
    private const val K_LINKED = "linkedAt"
    private const val K_SYNC = "lastSync"
    private const val K_STEPS = "steps"
    private const val K_SESSIONS = "sessions"
    /** Heart rate zones per session, a separate key so the old "sessions" format stays as it was. */
    private const val K_ZONES = "sessionZones"
    private const val K_SEEN = "seen"
    private const val K_ZONES_DIAG = "zonesDiag"
    private const val K_IMPORT = "lastImport"
    private const val K_IMPORT_DAY = "lastImportDay"
    private const val K_SLEEP = "sleep"
    private const val K_SLEEP_SPANS = "sleepSpans"
    private const val K_SLEEP_DIAG = "sleepDiag"
    private const val K_SLEEP_DEBUG = "sleepDebug"
    private const val KEEP_DAYS = 40L
    private const val KEEP_SESSIONS = 300
    private const val KEEP_SEEN = 300

    private val _state = MutableStateFlow(WatchState())
    @Volatile private var loaded = false

    val state: StateFlow<WatchState>
        get() { ensureLoaded(); return _state }

    fun current(): WatchState { ensureLoaded(); return _state.value }

    fun hrFor(sessionId: Long): SessionWatch? = current().sessions[sessionId]?.takeIf { it.hasHr }

    fun stepsFor(day: Long): DaySteps? = current().steps[day]

    /** Minutes asleep the night before [day] (the morning of [day]); null = no watch data. */
    fun sleepFor(day: Long): Int? = current().sleep[day]

    private fun sp(): SharedPreferences? =
        runCatching { AppGraph.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val s = sp() ?: return
            _state.value = WatchState(
                linkedAt = s.getLong(K_LINKED, 0L),
                lastSync = s.getLong(K_SYNC, 0L),
                steps = decodeSteps(s.getString(K_STEPS, "") ?: ""),
                sessions = withZones(decodeSessions(s.getString(K_SESSIONS, "") ?: ""), decodeZones(s.getString(K_ZONES, "") ?: "")),
                seen = decodeSeen(s.getString(K_SEEN, "") ?: ""),
                lastImportHe = s.getString(K_IMPORT, "") ?: "",
                lastImportDay = s.getLong(K_IMPORT_DAY, 0L),
                sleep = decodeSleep(s.getString(K_SLEEP, "") ?: ""),
                sleepSpans = decodeSpans(s.getString(K_SLEEP_SPANS, "") ?: ""),
                sleepDiagHe = s.getString(K_SLEEP_DIAG, "") ?: "",
                sleepDiagDebug = s.getString(K_SLEEP_DEBUG, "") ?: ""
            )
            loaded = true
        }
    }

    private fun update(block: (WatchState) -> WatchState) {
        synchronized(this) {
            ensureLoaded()
            val n = block(_state.value)
            _state.value = n
            sp()?.edit()
                ?.putLong(K_LINKED, n.linkedAt)
                ?.putLong(K_SYNC, n.lastSync)
                ?.putString(K_STEPS, encodeSteps(n.steps))
                ?.putString(K_SESSIONS, encodeSessions(n.sessions))
                ?.putString(K_ZONES, encodeZones(n.sessions))
                ?.putString(K_SEEN, n.seen.joinToString(";"))
                ?.putString(K_IMPORT, n.lastImportHe)
                ?.putLong(K_IMPORT_DAY, n.lastImportDay)
                ?.putString(K_SLEEP, encodeSleep(n.sleep))
                ?.putString(K_SLEEP_SPANS, encodeSpans(n.sleepSpans))
                ?.putString(K_SLEEP_DIAG, n.sleepDiagHe)
                ?.putString(K_SLEEP_DEBUG, n.sleepDiagDebug)
                ?.apply()
        }
    }

    // ---------------------------------------------------------------- writes

    fun setStatus(status: HcStatus, hcConnected: Boolean, shStatus: ShStatus) = update {
        it.copy(status = status, hcConnected = hcConnected, shStatus = shStatus, connected = hcConnected || shStatus.connected)
    }

    /** After "חבר ישירות" (settings card): the Samsung status only. */
    fun setSamsungStatus(shStatus: ShStatus) = update {
        it.copy(shStatus = shStatus, connected = it.hcConnected || shStatus.connected)
    }

    /** Called when the permissions are granted: imports start from now. */
    fun markLinked(now: Long) = update { it.copy(linkedAt = now) }

    fun markSynced(now: Long) = update { it.copy(lastSync = now) }

    fun putSteps(day: Long, steps: DaySteps) = update { s ->
        val m = s.steps.toMutableMap()
        m[day] = steps
        val min = Days.today() - KEEP_DAYS
        s.copy(steps = m.filterKeys { it >= min })
    }

    /** Adds / replaces nights (wake-up day -> minutes asleep); keeps the last [KEEP_DAYS] days. */
    fun putSleep(nights: Map<Long, Int>, spans: Map<Long, Pair<Long, Long>> = emptyMap()) = update { s ->
        val min = Days.today() - KEEP_DAYS
        s.copy(
            sleep = (s.sleep + nights).filterKeys { it >= min },
            sleepSpans = (s.sleepSpans + spans).filterKeys { it >= min }
        )
    }

    /** Start / end of the night that ended on the morning of [day]; null = unknown. */
    fun sleepSpanFor(day: Long): Pair<Long, Long>? = current().sleepSpans[day]

    /** The sleep diagnostic of the last sleep read (settings card) and its debug details. */
    fun setSleepDiag(textHe: String, debug: String) = update { it.copy(sleepDiagHe = textHe, sleepDiagDebug = debug) }

    fun putSession(sessionId: Long, data: SessionWatch) = update { s ->
        val m = s.sessions.toMutableMap()
        m[sessionId] = data
        s.copy(sessions = trimSessions(m))
    }

    /** Forgets the watch data of one session (the next sync may look again). */
    fun removeSession(sessionId: Long) = update { s ->
        if (!s.sessions.containsKey(sessionId)) s else s.copy(sessions = s.sessions - sessionId)
    }

    fun markSeen(id: String) = update { s ->
        if (id in s.seen) s else s.copy(seen = (s.seen + id).takeLast(KEEP_SEEN))
    }

    fun setLastImport(textHe: String, day: Long) = update { it.copy(lastImportHe = textHe, lastImportDay = day) }

    fun setImportDiag(textHe: String) = update { it.copy(importDiagHe = textHe) }

    /**
     * Debug trail of the heart rate zone reads (WatchSync.zonesFor): the last 12 lines like
     * "s42 WALK 65m log=380 -> 3/12/20/6/0". Rides in the cloud row ("watchZonesDiag") so problems can be read
     * without the phone. Not shown in the app.
     */
    fun addZonesDiag(line: String) {
        runCatching {
            val p = sp() ?: return
            val old = p.getString(K_ZONES_DIAG, "") ?: ""
            val lines = (old.split('\n').filter { it.isNotBlank() } + (java.time.LocalDateTime.now().toString().take(16) + " " + line)).takeLast(12)
            p.edit().putString(K_ZONES_DIAG, lines.joinToString("\n")).apply()
        }
    }

    fun zonesDiag(): String = runCatching { sp()?.getString(K_ZONES_DIAG, "") ?: "" }.getOrDefault("")

    // ---------------------------------------------------------------- cloud (profile row)

    fun putCloud(o: JSONObject) {
        val s = current()
        o.put("watchSteps", encodeSteps(s.steps))
        o.put("watchSessions", encodeSessions(s.sessions))
        o.put("watchZones", encodeZones(s.sessions))
        o.put("watchSeen", s.seen.joinToString(";"))
        o.put("watchSleep", encodeSleep(s.sleep))
        o.put("watchSleepSpans", encodeSpans(s.sleepSpans))
        o.put("watchZonesDiag", zonesDiag())
    }

    /** Fresh-install restore: adds what is missing locally, never replaces local values. */
    fun restoreFromCloud(d: JSONObject) {
        val steps = decodeSteps(d.optString("watchSteps", ""))
        val sessions = withZones(decodeSessions(d.optString("watchSessions", "")), decodeZones(d.optString("watchZones", "")))
        val seen = decodeSeen(d.optString("watchSeen", ""))
        val sleep = decodeSleep(d.optString("watchSleep", ""))
        val spans = decodeSpans(d.optString("watchSleepSpans", ""))
        if (steps.isEmpty() && sessions.isEmpty() && seen.isEmpty() && sleep.isEmpty()) return
        update { s ->
            s.copy(
                steps = steps + s.steps,
                sleep = sleep + s.sleep,
                sleepSpans = spans + s.sleepSpans,
                sessions = trimSessions(mergeSessions(cloud = sessions, local = s.sessions)),
                seen = (seen + s.seen).distinct().takeLast(KEEP_SEEN)
            )
        }
    }

    // ---------------------------------------------------------------- encoding

    private fun trimSessions(m: Map<Long, SessionWatch>): Map<Long, SessionWatch> =
        if (m.size <= KEEP_SESSIONS) m else m.entries.sortedBy { it.key }.takeLast(KEEP_SESSIONS).associate { it.key to it.value }

    /** Local values win; a local session without zones takes the cloud's zones (older local write). */
    private fun mergeSessions(cloud: Map<Long, SessionWatch>, local: Map<Long, SessionWatch>): Map<Long, SessionWatch> {
        val out = HashMap<Long, SessionWatch>(cloud)
        for ((id, loc) in local) {
            val c = cloud[id]
            out[id] = if (loc.zonesMin.isEmpty() && c != null && c.zonesMin.isNotEmpty()) loc.copy(zonesMin = c.zonesMin) else loc
        }
        return out
    }

    /** Attaches the separately stored zones to the decoded sessions (sessions without an entry keep an empty list). */
    private fun withZones(sessions: Map<Long, SessionWatch>, zones: Map<Long, List<Int>>): Map<Long, SessionWatch> {
        if (zones.isEmpty()) return sessions
        val out = HashMap<Long, SessionWatch>(sessions)
        for ((id, z) in zones) {
            val cur = out[id] ?: continue
            out[id] = cur.copy(zonesMin = z)
        }
        return out
    }

    /** "sessionId:z1,z2,z3,z4,z5;..." (only sessions that have zones). */
    private fun encodeZones(m: Map<Long, SessionWatch>): String =
        m.entries.filter { it.value.zonesMin.size == 5 }.sortedBy { it.key }
            .joinToString(";") { (k, v) -> "$k:" + v.zonesMin.joinToString(",") }

    private fun decodeZones(csv: String): Map<Long, List<Int>> {
        val out = HashMap<Long, List<Int>>()
        for (piece in csv.split(';')) {
            val p = piece.split(':')
            if (p.size != 2) continue
            val id = p[0].toLongOrNull() ?: continue
            val z = p[1].split(',').map { it.trim().toIntOrNull() ?: 0 }
            if (z.size != 5) continue
            out[id] = z.map { it.coerceAtLeast(0) }
        }
        return out
    }

    /** "day:total:inWorkouts;..." */
    private fun encodeSteps(m: Map<Long, DaySteps>): String =
        m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.total}:${it.value.inWorkouts}" }

    private fun decodeSteps(csv: String): Map<Long, DaySteps> {
        val out = HashMap<Long, DaySteps>()
        for (piece in csv.split(';')) {
            val p = piece.split(':')
            if (p.size != 3) continue
            val day = p[0].toLongOrNull() ?: continue
            val total = p[1].toIntOrNull() ?: continue
            val inW = p[2].toIntOrNull() ?: 0
            out[day] = DaySteps(total.coerceAtLeast(0), inW.coerceAtLeast(0))
        }
        return out
    }

    /** "sessionId:avgHr:maxHr:kcal:distanceM;..." */
    private fun encodeSessions(m: Map<Long, SessionWatch>): String =
        m.entries.sortedBy { it.key }.joinToString(";") { (k, v) -> "$k:${v.avgHr}:${v.maxHr}:${v.kcal}:${v.distanceM}" }

    private fun decodeSessions(csv: String): Map<Long, SessionWatch> {
        val out = HashMap<Long, SessionWatch>()
        for (piece in csv.split(';')) {
            val p = piece.split(':')
            if (p.size != 5) continue
            val id = p[0].toLongOrNull() ?: continue
            out[id] = SessionWatch(
                avgHr = p[1].toIntOrNull() ?: 0,
                maxHr = p[2].toIntOrNull() ?: 0,
                kcal = p[3].toIntOrNull() ?: 0,
                distanceM = p[4].toIntOrNull() ?: 0
            )
        }
        return out
    }

    /** "day:minutes;..." */
    private fun encodeSleep(m: Map<Long, Int>): String =
        m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" }

    private fun decodeSleep(csv: String): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (piece in csv.split(';')) {
            val p = piece.split(':')
            if (p.size != 2) continue
            val day = p[0].toLongOrNull() ?: continue
            val minutes = p[1].toIntOrNull() ?: continue
            if (minutes > 0) out[day] = minutes
        }
        return out
    }

    /** "day:startMillis:endMillis;..." */
    private fun encodeSpans(m: Map<Long, Pair<Long, Long>>): String =
        m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.first}:${it.value.second}" }

    private fun decodeSpans(csv: String): Map<Long, Pair<Long, Long>> {
        val out = HashMap<Long, Pair<Long, Long>>()
        for (piece in csv.split(';')) {
            val p = piece.split(':')
            if (p.size != 3) continue
            val day = p[0].toLongOrNull() ?: continue
            val a = p[1].toLongOrNull() ?: continue
            val b = p[2].toLongOrNull() ?: continue
            if (b > a) out[day] = Pair(a, b)
        }
        return out
    }

    private fun decodeSeen(csv: String): List<String> = csv.split(';').map { it.trim() }.filter { it.isNotEmpty() }
}
