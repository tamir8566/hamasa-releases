package com.tamir.hamasa.data.health

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseLog
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.data.entries.HeartRate
import com.samsung.android.sdk.health.data.data.entries.SleepSession
import com.samsung.android.sdk.health.data.error.AuthorizationException
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.domain.energy.HrPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** The direct link to Samsung Health on this phone (Samsung Health Data SDK, app/libs). */
enum class ShStatus {
    /** Not checked yet, no Samsung Health SDK here (screenshot tests), or the SDK could not start. */
    UNAVAILABLE,
    /** Samsung Health is not installed. */
    NEEDS_INSTALL,
    /** Samsung Health is too old for the SDK. */
    OLD_VERSION,
    /** Samsung Health is disabled in the phone's settings. */
    DISABLED,
    /** Samsung Health was never opened / its setup is not finished. */
    NOT_INITIALIZED,
    /** AuthorizationException: the app is not a Samsung partner, so Samsung Health's developer mode must be on. */
    DEV_MODE_OFF,
    /** Reachable, but no read permission granted yet. */
    NO_PERMISSION,
    /** Some of the four read permissions granted. */
    PARTIAL,
    /** All four read permissions granted (sleep, heart rate, workouts, steps). */
    CONNECTED,
    /** Any other failure (see [SamsungHealth.lastDiag]). */
    ERROR;

    val connected: Boolean get() = this == CONNECTED || this == PARTIAL
}

/**
 * One workout from Samsung Health, already mapped to the app: [id] "sh:<uid>" (session notes and the handled list),
 * [type] null = not imported. [walkTitle]: a real walk / hike (title "הליכה", other endurance is "אימון סיבולת").
 * kcal / distance / heart rate as Samsung measured them (0 = unknown).
 */
data class ShWorkout(
    val id: String,
    val type: SessionType?,
    val walkTitle: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val kcal: Int,
    val distanceM: Int,
    val avgHr: Int,
    val maxHr: Int
) {
    val minutes: Int get() = ((endMillis - startMillis) / 60000L).toInt()
}

/**
 * Read-only access to the Galaxy Watch data DIRECTLY in Samsung Health (Samsung Health Data SDK, package
 * com.samsung.android.sdk.health.data, AAR in app/libs, docs/watch.md). On Tamir's phone Health Connect only
 * gets steps from Samsung Health; this route also gives sleep, heart rate and workouts.
 *
 * The app is not a Samsung partner app, so Samsung Health's developer mode ("קריאת נתונים") must be on, otherwise
 * every call throws AuthorizationException ([ShStatus.DEV_MODE_OFF]).
 *
 * Every call is safe: failures give null / empty plus [lastDiag], never an exception. In Robolectric
 * (Build.FINGERPRINT == "robolectric", the screenshot tests) Samsung Health is reported as unavailable and the SDK
 * is never touched. Reads run on Dispatchers.IO.
 */
object SamsungHealth {
    private const val TAG = "SamsungHealth"
    const val PACKAGE = "com.sec.android.app.shealth"

    /** What we read. App-level names, so the rest of the app never touches SDK types. */
    enum class Kind { SLEEP, HEART_RATE, EXERCISE, STEPS }

    /** A refresh younger than this is reused by the reads (WatchSync.sync refreshes at its start). */
    private const val STATUS_MAX_AGE_MS = 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val BUCKET_MS = 5L * 60L * 1000L

    @Volatile var lastStatus: ShStatus = ShStatus.UNAVAILABLE
        private set

    /** Debug only (logcat / debug fields), never shown in the UI: the last error class, code and message. */
    @Volatile var lastDiag: String = ""
        private set

    @Volatile private var granted: Set<Kind> = emptySet()
    @Volatile private var checkedAt: Long = 0L
    @Volatile private var store: HealthDataStore? = null

    private fun isTestRuntime(): Boolean = Build.FINGERPRINT == "robolectric"

    /** Samsung Health is installed (cheap, no SDK call; the AAR's manifest declares the package query). */
    fun isInstalled(context: Context): Boolean {
        if (isTestRuntime()) return false
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun storeOrNull(context: Context): HealthDataStore? {
        if (isTestRuntime()) return null
        val s = store
        if (s != null) return s
        return try {
            HealthDataService.getStore(context.applicationContext).also { store = it }
        } catch (t: Throwable) {
            Log.w(TAG, "store", t)
            lastDiag = errorText(t)
            null
        }
    }

    private fun permissionOf(kind: Kind): Permission = when (kind) {
        Kind.SLEEP -> Permission.of(DataTypes.SLEEP, AccessType.READ)
        Kind.HEART_RATE -> Permission.of(DataTypes.HEART_RATE, AccessType.READ)
        Kind.EXERCISE -> Permission.of(DataTypes.EXERCISE, AccessType.READ)
        Kind.STEPS -> Permission.of(DataTypes.STEPS, AccessType.READ)
    }

    private fun allPermissions(): Set<Permission> = Kind.values().map { permissionOf(it) }.toSet()

    /** Cached: the status is connected and [kind] was granted at the last refresh. */
    fun canRead(kind: Kind): Boolean = lastStatus.connected && kind in granted

    /** [canRead] after a refresh when the cached status is older than a minute. */
    suspend fun canReadNow(context: Context, kind: Kind): Boolean {
        if (isTestRuntime()) return false
        refresh(context, STATUS_MAX_AGE_MS)
        return canRead(kind)
    }

    /**
     * Re-checks the connection: Samsung Health reachable, developer mode, granted permissions. [maxAgeMs] > 0 reuses
     * a result younger than that. Never throws.
     */
    suspend fun refresh(context: Context, maxAgeMs: Long = 0L): ShStatus {
        if (isTestRuntime()) return ShStatus.UNAVAILABLE
        val now = System.currentTimeMillis()
        if (maxAgeMs > 0L && checkedAt > 0L && now - checkedAt in 0 until maxAgeMs) return lastStatus
        val s = storeOrNull(context)
        if (s == null) {
            return saveStatus(if (isInstalled(context)) ShStatus.UNAVAILABLE else ShStatus.NEEDS_INSTALL, emptySet())
        }
        return try {
            val g: Set<Permission> = withContext(Dispatchers.IO) { s.getGrantedPermissions(allPermissions()) }
            rememberGranted(g)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "refresh", t)
            lastDiag = errorText(t)
            saveStatus(classify(t), emptySet())
        }
    }

    private fun rememberGranted(g: Set<Permission>): ShStatus {
        val kinds = Kind.values().filter { permissionOf(it) in g }.toSet()
        val st = when {
            kinds.size == Kind.values().size -> ShStatus.CONNECTED
            kinds.isEmpty() -> ShStatus.NO_PERMISSION
            else -> ShStatus.PARTIAL
        }
        return saveStatus(st, kinds)
    }

    private fun saveStatus(st: ShStatus, kinds: Set<Kind>): ShStatus {
        granted = kinds
        lastStatus = st
        checkedAt = System.currentTimeMillis()
        return st
    }

    private fun classify(t: Throwable): ShStatus = when (t) {
        is ResolvablePlatformException -> when (t.errorCode) {
            ErrorCode.ERR_PLATFORM_NOT_INSTALLED -> ShStatus.NEEDS_INSTALL
            ErrorCode.ERR_OLD_VERSION_PLATFORM -> ShStatus.OLD_VERSION
            ErrorCode.ERR_PLATFORM_DISABLED -> ShStatus.DISABLED
            ErrorCode.ERR_PLATFORM_NOT_INITIALIZED -> ShStatus.NOT_INITIALIZED
            else -> ShStatus.ERROR
        }
        is AuthorizationException -> ShStatus.DEV_MODE_OFF
        is HealthDataException -> if (t.errorCode == ErrorCode.ERR_NO_USER_PERMISSION) ShStatus.NO_PERMISSION else ShStatus.ERROR
        else -> ShStatus.ERROR
    }

    private fun errorText(t: Throwable): String {
        val code = if (t is HealthDataException) " code=${t.errorCode} ${t.errorMessage ?: ""}" else ""
        return "${t.javaClass.simpleName}:$code ${t.message ?: ""}".take(300)
    }

    /**
     * "חבר ישירות": asks Samsung Health for the four read permissions (Samsung's own screen, needs the Activity).
     * When Samsung Health is missing / old / disabled / not set up and the SDK offers a fix, opens it
     * (ResolvablePlatformException.resolve). Call from the main thread (a Compose coroutine). Never throws.
     */
    suspend fun connect(activity: Activity): ShStatus {
        if (isTestRuntime()) return ShStatus.UNAVAILABLE
        val s = storeOrNull(activity)
            ?: return saveStatus(if (isInstalled(activity)) ShStatus.ERROR else ShStatus.NEEDS_INSTALL, emptySet())
        return try {
            val all = allPermissions()
            val already: Set<Permission> = withContext(Dispatchers.IO) { s.getGrantedPermissions(all) }
            if (already.containsAll(all)) {
                rememberGranted(already)
            } else {
                val asked: Set<Permission> = withContext(Dispatchers.Main) { s.requestPermissions(all, activity) }
                rememberGranted(already + asked)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "connect", t)
            lastDiag = errorText(t)
            if (t is ResolvablePlatformException && t.hasResolution) {
                try {
                    withContext(Dispatchers.Main) { t.resolve(activity) }
                } catch (t2: Throwable) {
                    if (t2 is CancellationException) throw t2
                    Log.w(TAG, "resolve", t2)
                }
            }
            saveStatus(classify(t), emptySet())
        }
    }

    /** The store when [kind] may be read now, else null. */
    private suspend fun ready(context: Context, kind: Kind): HealthDataStore? {
        if (!canReadNow(context, kind)) return null
        return storeOrNull(context)
    }

    // ---------------------------------------------------------------- time helpers

    private fun ldt(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    private fun dayStartMillis(day: Long): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** All data points of a read, following the page token (at most 20 pages). */
    private suspend fun readAllPoints(
        s: HealthDataStore,
        request: (String?) -> ReadDataRequest<HealthDataPoint>
    ): List<HealthDataPoint> {
        val out = ArrayList<HealthDataPoint>()
        var token: String? = null
        var pages = 0
        do {
            val res = s.readData(request(token))
            out.addAll(res.dataList)
            token = res.pageToken
            pages++
        } while (!token.isNullOrEmpty() && pages < 20)
        return out
    }

    // ---------------------------------------------------------------- steps

    /** Steps in [startMillis, endMillis) as Samsung Health counts them (phone + watch merged). Null on failure. */
    suspend fun stepsBetween(context: Context, startMillis: Long, endMillis: Long): Long? {
        if (endMillis <= startMillis) return 0L
        val s = ready(context, Kind.STEPS) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val req = DataType.StepsType.TOTAL.requestBuilder
                    .setLocalTimeFilter(LocalTimeFilter.of(ldt(startMillis), ldt(endMillis)))
                    .build()
                val res = s.aggregateData(req)
                var sum = 0L
                for (a in res.dataList) {
                    val v: Long? = a.value
                    if (v != null && v > 0L) sum += v
                }
                sum
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "steps", t)
            lastDiag = errorText(t)
            null
        }
    }

    /** Steps of one local calendar day (today: until now). Null on failure / not connected. */
    suspend fun stepsForDay(context: Context, day: Long): Long? {
        val start = dayStartMillis(day)
        val end = minOf(dayStartMillis(day + 1), System.currentTimeMillis())
        return stepsBetween(context, start, end)
    }

    // ---------------------------------------------------------------- heart rate

    private class HrSample(val t: Long, val bpm: Float, val max: Float)

    /** Heart rate samples in [startMillis, endMillis] (series data when present, else the point's value). */
    private suspend fun hrSamples(s: HealthDataStore, startMillis: Long, endMillis: Long): List<HrSample> {
        // a point can hold a series that started before the range: read one hour earlier, filter by sample time
        val filter = LocalTimeFilter.of(ldt(startMillis - HOUR_MS), ldt(endMillis))
        val points = readAllPoints(s) { token ->
            val b = DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.ASC)
            if (token != null) b.setPageToken(token)
            b.build()
        }
        val out = ArrayList<HrSample>()
        for (p in points) {
            val series: List<HeartRate>? = p.getValue(DataType.HeartRateType.SERIES_DATA)
            if (series != null && series.isNotEmpty()) {
                for (h in series) {
                    val st: Instant? = h.startTime
                    if (st == null) continue
                    val t = st.toEpochMilli()
                    val bpm = h.heartRate
                    if (t < startMillis || t > endMillis || bpm < 25f || bpm > 250f) continue
                    out.add(HrSample(t, bpm, maxOf(bpm, h.max)))
                }
            } else {
                val v: Float? = p.getValue(DataType.HeartRateType.HEART_RATE)
                val mx: Float? = p.getValue(DataType.HeartRateType.MAX_HEART_RATE)
                val st: Instant? = p.startTime
                if (v == null || st == null) continue
                val t = st.toEpochMilli()
                if (t < startMillis || t > endMillis || v < 25f || v > 250f) continue
                out.add(HrSample(t, v, if (mx != null && mx > v && mx <= 250f) mx else v))
            }
        }
        return out
    }

    /** Average and maximum heart rate in [startMillis, endMillis]; null when there are no samples / not connected. */
    suspend fun heartRate(context: Context, startMillis: Long, endMillis: Long): HrSummary? {
        if (endMillis <= startMillis) return null
        val s = ready(context, Kind.HEART_RATE) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val samples = hrSamples(s, startMillis, endMillis)
                if (samples.isEmpty()) {
                    null
                } else {
                    val avg = samples.sumOf { it.bpm.toDouble() } / samples.size
                    val max = samples.maxOf { it.max }
                    HrSummary(avg.roundToInt(), maxOf(max.roundToInt(), avg.roundToInt()))
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "hr", t)
            lastDiag = errorText(t)
            null
        }
    }

    /**
     * The raw heart rate samples in [startMillis, endMillis] (time + bpm, ascending) for the zone calculation
     * (HrZones). Null when not connected / no permission / the read failed; empty when there were no samples.
     */
    suspend fun hrPoints(context: Context, startMillis: Long, endMillis: Long): List<HrPoint>? {
        if (endMillis <= startMillis) return null
        val s = ready(context, Kind.HEART_RATE) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                hrSamples(s, startMillis, endMillis).map { HrPoint(it.t, it.bpm.roundToInt()) }.sortedBy { it.timeMillis }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "hr points", t)
            lastDiag = errorText(t)
            null
        }
    }

    /**
     * Resting heart rate PROXY per day in [fromDay, toDay] (Samsung has no resting heart rate type): the lowest
     * 5-minute average of the heart rate during the night that ended on that morning (the night rules of
     * [readSleep]). Needs 30+ minutes of samples that night; the result must be 30..120 bpm. Days without a
     * night are left out (no fallback to the daily minimum: one kind of number keeps the baseline honest).
     */
    suspend fun restingHrByDay(context: Context, fromDay: Long, toDay: Long): Map<Long, Int> {
        if (toDay < fromDay) return emptyMap()
        if (!canReadNow(context, Kind.SLEEP) || !canRead(Kind.HEART_RATE)) return emptyMap()
        val s = storeOrNull(context) ?: return emptyMap()
        return try {
            withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                val spans = readSleepSpans(s, fromDay)
                val byNight = HashMap<Long, LongArray>()
                for (sp in spans) {
                    val night = HealthConnect.nightOf(Instant.ofEpochMilli(sp.start), Instant.ofEpochMilli(sp.end), zone) ?: continue
                    if (night < fromDay || night > toDay) continue
                    val cur = byNight[night]
                    if (cur == null) byNight[night] = longArrayOf(sp.start, sp.end)
                    else {
                        if (sp.start < cur[0]) cur[0] = sp.start
                        if (sp.end > cur[1]) cur[1] = sp.end
                    }
                }
                val out = HashMap<Long, Int>()
                for ((night, range) in byNight) {
                    val samples = hrSamples(s, range[0], range[1])
                    val buckets = HashMap<Long, MutableList<Float>>()
                    for (x in samples) buckets.getOrPut((x.t - range[0]) / BUCKET_MS) { ArrayList() }.add(x.bpm)
                    if (buckets.size < 6) continue
                    val lowest = buckets.values.minOf { list -> list.sum() / list.size }
                    val bpm = lowest.roundToInt()
                    if (bpm in 30..120) out[night] = bpm
                }
                out
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "resting hr", t)
            lastDiag = errorText(t)
            emptyMap()
        }
    }

    // ---------------------------------------------------------------- sleep

    private class StageSpan(val start: Long, val end: Long, val awake: Boolean)
    private class SleepSpan(val start: Long, val end: Long, val stages: List<StageSpan>)

    /** Sleep sessions that started from 2 days before [fromDay] until now (each data point can hold several). */
    private suspend fun readSleepSpans(s: HealthDataStore, fromDay: Long): List<SleepSpan> {
        val from = dayStartMillis(fromDay - 2)
        val now = System.currentTimeMillis()
        val filter = LocalTimeFilter.of(ldt(from), ldt(now))
        val points = readAllPoints(s) { token ->
            val b = DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setOrdering(Ordering.ASC)
            if (token != null) b.setPageToken(token)
            b.build()
        }
        val out = ArrayList<SleepSpan>()
        for (p in points) {
            val sessions: List<SleepSession>? = p.getValue(DataType.SleepType.SESSIONS)
            if (sessions != null && sessions.isNotEmpty()) {
                for (ss in sessions) {
                    val a: Instant? = ss.startTime
                    val b: Instant? = ss.endTime
                    if (a == null || b == null || !b.isAfter(a)) continue
                    val stages = ArrayList<StageSpan>()
                    val raw: List<SleepSession.SleepStage>? = ss.stages
                    if (raw != null) {
                        for (st in raw) {
                            val sa: Instant? = st.startTime
                            val sb: Instant? = st.endTime
                            if (sa == null || sb == null) continue
                            stages.add(StageSpan(sa.toEpochMilli(), sb.toEpochMilli(), st.stage == DataType.SleepType.StageType.AWAKE))
                        }
                    }
                    out.add(SleepSpan(a.toEpochMilli(), b.toEpochMilli(), stages))
                }
            } else {
                val a: Instant? = p.startTime
                val b: Instant? = p.endTime
                if (a == null || b == null || !b.isAfter(a)) continue
                out.add(SleepSpan(a.toEpochMilli(), b.toEpochMilli(), emptyList()))
            }
        }
        return out
    }

    /**
     * Sleep for the nights [fromDay]..[toDay] keyed by the wake-up day, with the same night rules as
     * [HealthConnect.readSleep]: a night of day D = sessions ending between D-1 18:00 and D 14:00 (or later on D
     * when they started before 10:00), awake stages excluded, overlapping sessions merged, nights under 60 minutes
     * left out. [SleepRead.permission] false = not connected / no sleep permission. Never throws.
     */
    suspend fun readSleep(context: Context, fromDay: Long, toDay: Long): SleepRead {
        if (toDay < fromDay) return SleepRead(ok = true, permission = true, origins = "samsung")
        if (!canReadNow(context, Kind.SLEEP)) return SleepRead(ok = false, permission = false, origins = "samsung", error = lastDiag)
        val s = storeOrNull(context) ?: return SleepRead(ok = false, permission = true, origins = "samsung", error = "no store")
        return try {
            withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                val spans = readSleepSpans(s, fromDay)
                val byNight = HashMap<Long, MutableList<SleepSpan>>()
                for (sp in spans) {
                    val night = HealthConnect.nightOf(Instant.ofEpochMilli(sp.start), Instant.ofEpochMilli(sp.end), zone) ?: continue
                    byNight.getOrPut(night) { ArrayList() }.add(sp)
                }
                val nights = HashMap<Long, Int>()
                val nightSpans = HashMap<Long, Pair<Long, Long>>()
                for ((night, list) in byNight) {
                    if (night < fromDay || night > toDay) continue
                    val minutes = asleepMinutes(list)
                    if (minutes >= 60) {
                        nights[night] = minutes
                        nightSpans[night] = Pair(list.minOf { it.start }, list.maxOf { it.end })
                    }
                }
                val today = LocalDate.now(zone).toEpochDay()
                val diagFrom = dayStartMillis(today - 2)
                val recent = spans.filter { it.end > diagFrom }
                val last = recent.maxByOrNull { it.end }
                var lastMinutes = 0
                if (last != null) {
                    val lastNight = HealthConnect.nightOf(Instant.ofEpochMilli(last.start), Instant.ofEpochMilli(last.end), zone)
                    val group = if (lastNight != null) byNight[lastNight] ?: listOf(last) else listOf(last)
                    lastMinutes = asleepMinutes(group)
                }
                SleepRead(
                    ok = true,
                    permission = true,
                    nights = nights,
                    spans = nightSpans,
                    recentCount = recent.size,
                    totalCount = spans.size,
                    lastStartMillis = last?.start ?: 0L,
                    lastEndMillis = last?.end ?: 0L,
                    lastAsleepMinutes = lastMinutes,
                    origins = "samsung",
                    error = if (last == null) "" else "stages=${last.stages.size}"
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "sleep", t)
            lastDiag = errorText(t)
            SleepRead(ok = false, permission = true, origins = "samsung", error = errorText(t))
        }
    }

    /** Asleep minutes of one night's sessions; the same merge rules as HealthConnect.asleepMinutes. */
    private fun asleepMinutes(sessions: List<SleepSpan>): Int {
        val sorted = sessions.sortedBy { it.start }
        val groups = ArrayList<MutableList<SleepSpan>>()
        var groupEnd = Long.MIN_VALUE
        for (r in sorted) {
            if (groups.isEmpty() || r.start >= groupEnd) {
                groups.add(arrayListOf(r))
                groupEnd = r.end
            } else {
                groups[groups.size - 1].add(r)
                if (r.end > groupEnd) groupEnd = r.end
            }
        }
        val hourMs = HOUR_MS
        var totalMs = 0L
        for (g in groups) {
            val whole = ArrayList<LongArray>()
            for (r in g) whole.add(longArrayOf(r.start, r.end))
            val wholeMs = HealthConnect.unionMs(whole)
            val withStages = g.filter { it.stages.isNotEmpty() }
            if (withStages.isEmpty()) {
                totalMs += wholeMs
                continue
            }
            val asleep = ArrayList<LongArray>()
            val awake = ArrayList<LongArray>()
            for (r in withStages) {
                for (st in r.stages) {
                    val a = maxOf(st.start, r.start)
                    val b = minOf(st.end, r.end)
                    if (b <= a) continue
                    if (st.awake) awake.add(longArrayOf(a, b)) else asleep.add(longArrayOf(a, b))
                }
            }
            val asleepMs = HealthConnect.unionMs(asleep)
            val awakeMs = HealthConnect.unionMs(awake)
            val covered = HealthConnect.unionMs(asleep + awake)
            val partial = covered * 2 < wholeMs || (asleepMs < hourMs && wholeMs >= 2 * hourMs)
            totalMs += if (partial) maxOf(asleepMs, wholeMs - awakeMs) else asleepMs
        }
        return (totalMs / 60000L).toInt()
    }

    // ---------------------------------------------------------------- workouts

    /**
     * Exercise sessions overlapping [sinceMillis, untilMillis], oldest first. Null when not connected or the read
     * failed (the caller then falls back to Health Connect); empty = really none.
     */
    suspend fun exerciseSessions(context: Context, sinceMillis: Long, untilMillis: Long): List<ShWorkout>? {
        if (untilMillis <= sinceMillis) return emptyList()
        val s = ready(context, Kind.EXERCISE) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val filter = LocalTimeFilter.of(ldt(sinceMillis - HOUR_MS), ldt(untilMillis))
                val points = readAllPoints(s) { token ->
                    val b = DataTypes.EXERCISE.readDataRequestBuilder
                        .setLocalTimeFilter(filter)
                        .setOrdering(Ordering.ASC)
                    if (token != null) b.setPageToken(token)
                    b.build()
                }
                val out = ArrayList<ShWorkout>()
                for (p in points) {
                    val uid: String? = p.uid
                    if (uid.isNullOrBlank()) continue
                    val pointType: DataType.ExerciseType.PredefinedExerciseType? = p.getValue(DataType.ExerciseType.EXERCISE_TYPE)
                    val sessions: List<ExerciseSession>? = p.getValue(DataType.ExerciseType.SESSIONS)
                    if (sessions == null || sessions.isEmpty()) {
                        val a: Instant? = p.startTime
                        val b: Instant? = p.endTime
                        if (a == null || b == null) continue
                        out.add(workout("sh:$uid", pointType, a.toEpochMilli(), b.toEpochMilli(), null, null, null, null))
                    } else {
                        sessions.forEachIndexed { i, ss ->
                            val a: Instant? = ss.startTime
                            val b: Instant? = ss.endTime
                            if (a != null && b != null) {
                                val sessionType: DataType.ExerciseType.PredefinedExerciseType? = ss.exerciseType
                                val kcal: Float? = ss.calories
                                val dist: Float? = ss.distance
                                val mean: Float? = ss.meanHeartRate
                                val max: Float? = ss.maxHeartRate
                                val id = if (i == 0) "sh:$uid" else "sh:$uid:$i"
                                out.add(workout(id, sessionType ?: pointType, a.toEpochMilli(), b.toEpochMilli(), kcal, dist, mean, max))
                            }
                        }
                    }
                }
                out.filter { it.endMillis > sinceMillis && it.startMillis < untilMillis }.sortedBy { it.startMillis }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "sessions", t)
            lastDiag = errorText(t)
            null
        }
    }

    /**
     * Heart rate points from the workouts' own logs (ExerciseSession.log: one entry every few seconds with the
     * heart rate the watch measured during the workout) in [startMillis, endMillis]. This is where a watch workout
     * keeps its heart rate; the separate heart rate data type may hold only sparse all-day samples.
     * Null when not connected / the read failed; empty when the workouts have no logged heart rate.
     */
    suspend fun exerciseHrPoints(context: Context, startMillis: Long, endMillis: Long): List<HrPoint>? {
        if (endMillis <= startMillis) return null
        val s = ready(context, Kind.EXERCISE) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val filter = LocalTimeFilter.of(ldt(startMillis - HOUR_MS), ldt(endMillis + HOUR_MS))
                val points = readAllPoints(s) { token ->
                    val b = DataTypes.EXERCISE.readDataRequestBuilder
                        .setLocalTimeFilter(filter)
                        .setOrdering(Ordering.ASC)
                    if (token != null) b.setPageToken(token)
                    b.build()
                }
                val out = ArrayList<HrPoint>()
                for (p in points) {
                    val sessions: List<ExerciseSession>? = p.getValue(DataType.ExerciseType.SESSIONS)
                    if (sessions == null) continue
                    for (ss in sessions) {
                        val logs: List<ExerciseLog>? = ss.log
                        if (logs == null) continue
                        for (lg in logs) {
                            val ts: Instant? = lg.timestamp
                            val hr: Float? = lg.heartRate
                            if (ts == null || hr == null) continue
                            val t = ts.toEpochMilli()
                            if (t < startMillis || t > endMillis || hr < 25f || hr > 250f) continue
                            out.add(HrPoint(t, hr.roundToInt()))
                        }
                    }
                }
                out.sortedBy { it.timeMillis }.distinctBy { it.timeMillis }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "exercise hr points", t)
            lastDiag = errorText(t)
            null
        }
    }

    private fun workout(
        id: String,
        t: DataType.ExerciseType.PredefinedExerciseType?,
        start: Long,
        end: Long,
        kcal: Float?,
        dist: Float?,
        mean: Float?,
        max: Float?
    ): ShWorkout {
        val avg = if (mean != null && mean >= 25f && mean <= 250f) mean.roundToInt() else 0
        val top = if (max != null && max >= 25f && max <= 250f) max.roundToInt() else 0
        return ShWorkout(
            id = id,
            type = typeFor(t),
            walkTitle = t == DataType.ExerciseType.PredefinedExerciseType.WALKING ||
                t == DataType.ExerciseType.PredefinedExerciseType.HIKING,
            startMillis = start,
            endMillis = end,
            kcal = if (kcal != null && kcal > 0f) kcal.roundToInt() else 0,
            distanceM = if (dist != null && dist > 0f) dist.roundToInt() else 0,
            avgHr = avg,
            maxHr = if (avg > 0) maxOf(top, avg) else 0
        )
    }

    /** Samsung exercise type -> session type (same rules as WatchSync.typeFor for Health Connect); null = not imported. */
    private fun typeFor(t: DataType.ExerciseType.PredefinedExerciseType?): SessionType? = when (t) {
        DataType.ExerciseType.PredefinedExerciseType.RUNNING,
        DataType.ExerciseType.PredefinedExerciseType.TRACK_RUNNING,
        DataType.ExerciseType.PredefinedExerciseType.TREADMILL -> SessionType.RUN

        DataType.ExerciseType.PredefinedExerciseType.WALKING,
        DataType.ExerciseType.PredefinedExerciseType.HIKING,
        DataType.ExerciseType.PredefinedExerciseType.BACKPACKING,
        DataType.ExerciseType.PredefinedExerciseType.SNOWSHOEING -> SessionType.WALK

        DataType.ExerciseType.PredefinedExerciseType.YOGA,
        DataType.ExerciseType.PredefinedExerciseType.PILATES,
        DataType.ExerciseType.PredefinedExerciseType.STRETCHING -> SessionType.YOGA

        // Strength has no sets from the watch: a short session, never a fake gym session (see WatchSync.typeFor).
        DataType.ExerciseType.PredefinedExerciseType.WEIGHT_MACHINE,
        DataType.ExerciseType.PredefinedExerciseType.CIRCUIT_TRAINING,
        DataType.ExerciseType.PredefinedExerciseType.PUSH_UPS,
        DataType.ExerciseType.PredefinedExerciseType.PULL_UPS,
        DataType.ExerciseType.PredefinedExerciseType.SIT_UPS,
        DataType.ExerciseType.PredefinedExerciseType.BURPEES,
        DataType.ExerciseType.PredefinedExerciseType.MOUNTAIN_CLIMBERS,
        DataType.ExerciseType.PredefinedExerciseType.JUMPING_JACKS,
        DataType.ExerciseType.PredefinedExerciseType.BENCH_PRESS,
        DataType.ExerciseType.PredefinedExerciseType.SQUATS,
        DataType.ExerciseType.PredefinedExerciseType.LUNGES,
        DataType.ExerciseType.PredefinedExerciseType.LEG_PRESSES,
        DataType.ExerciseType.PredefinedExerciseType.LEG_EXTENSIONS,
        DataType.ExerciseType.PredefinedExerciseType.LEG_CURLS,
        DataType.ExerciseType.PredefinedExerciseType.BACK_EXTENSIONS,
        DataType.ExerciseType.PredefinedExerciseType.LAT_PULLDOWNS,
        DataType.ExerciseType.PredefinedExerciseType.DEADLIFTS,
        DataType.ExerciseType.PredefinedExerciseType.SHOULDER_PRESSES,
        DataType.ExerciseType.PredefinedExerciseType.FRONT_RAISES,
        DataType.ExerciseType.PredefinedExerciseType.LATERAL_RAISES,
        DataType.ExerciseType.PredefinedExerciseType.CRUNCH,
        DataType.ExerciseType.PredefinedExerciseType.LEG_RAISES,
        DataType.ExerciseType.PredefinedExerciseType.PLANK,
        DataType.ExerciseType.PredefinedExerciseType.ARM_CURLS,
        DataType.ExerciseType.PredefinedExerciseType.ARM_EXTENSIONS -> SessionType.MINI

        // Motor / gliding / sailing sports, golf and the parts of a multisport session are not workouts for the game.
        DataType.ExerciseType.PredefinedExerciseType.SAILING,
        DataType.ExerciseType.PredefinedExerciseType.YACHTING,
        DataType.ExerciseType.PredefinedExerciseType.HANG_GLIDING,
        DataType.ExerciseType.PredefinedExerciseType.GOLF,
        DataType.ExerciseType.PredefinedExerciseType.BREAK,
        DataType.ExerciseType.PredefinedExerciseType.COOL_DOWN,
        DataType.ExerciseType.PredefinedExerciseType.WARM_UP,
        DataType.ExerciseType.PredefinedExerciseType.TRANSITION -> null

        // Cycling, elliptical, stairs, rowing, swimming, ball games, "other": endurance (walk-like).
        else -> SessionType.WALK
    }
}
