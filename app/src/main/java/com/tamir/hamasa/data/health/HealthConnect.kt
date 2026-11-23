package com.tamir.hamasa.data.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.tamir.hamasa.domain.energy.HrPoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/** Health Connect on this phone. */
enum class HcStatus { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

/** Average / maximum heart rate over a time range (beats per minute). */
data class HrSummary(val avg: Int, val max: Int)

/**
 * One sleep read from Health Connect ([HealthConnect.readSleep]).
 * [ok] false = the read failed ([permission] false = the sleep permission is not granted).
 * [nights]: minutes asleep keyed by the wake-up day. [recentCount]: sessions that ended in the last 3 days;
 * [lastStartMillis]..[lastEndMillis] the latest of them, [lastAsleepMinutes] its night. [origins] (package names)
 * and [error] are for the debug field only, never shown in the UI.
 */
data class SleepRead(
    val ok: Boolean,
    val permission: Boolean,
    val nights: Map<Long, Int> = emptyMap(),
    /** Night (wake-up day) -> first start, last end of its sessions, in millis. */
    val spans: Map<Long, Pair<Long, Long>> = emptyMap(),
    val recentCount: Int = 0,
    val totalCount: Int = 0,
    val lastStartMillis: Long = 0L,
    val lastEndMillis: Long = 0L,
    val lastAsleepMinutes: Int = 0,
    val origins: String = "",
    val error: String = ""
)

/** One exercise session the watch (through Samsung Health) wrote to Health Connect. */
data class WatchWorkout(
    val id: String,
    val exerciseType: Int,
    val startMillis: Long,
    val endMillis: Long,
    val originPackage: String
) {
    val minutes: Int get() = ((endMillis - startMillis) / 60000L).toInt()
}

/**
 * Thin read-only wrapper around the Health Connect client (androidx.health.connect:connect-client).
 * Sleep is read (optional permission, [sleepByNight]); moving the slider by hand still wins. Every call is safe: failures give null / empty,
 * never an exception. In Robolectric (screenshot tests) Health Connect is reported as unavailable.
 */
object HealthConnect {
    private const val TAG = "HealthConnect"
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    /** Needed for "מחובר": steps, heart rate and exercise sessions. */
    val REQUIRED: Set<String> by lazy {
        setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        )
    }

    /** Everything we ask for. Distance and calories are nice to have (shown on imported workouts). */
    val ALL: Set<String> by lazy {
        REQUIRED + setOf(
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            // optional: daily resting heart rate (fatigue signal for the coach); "מחובר" never depends on it
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            // optional: sleep sessions (last night's sleep for the coach / morning roll, data/health/WatchSleep)
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        )
    }

    /** Lets the hourly worker read while the app is closed (Android 14+, when Health Connect supports it). */
    const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    private fun isTestRuntime(): Boolean = Build.FINGERPRINT == "robolectric"

    fun status(context: Context): HcStatus {
        if (isTestRuntime()) return HcStatus.UNAVAILABLE
        return try {
            when (HealthConnectClient.getSdkStatus(context.applicationContext, PROVIDER_PACKAGE)) {
                HealthConnectClient.SDK_AVAILABLE -> HcStatus.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.NEEDS_INSTALL
                else -> HcStatus.UNAVAILABLE
            }
        } catch (t: Throwable) {
            Log.w(TAG, "status", t)
            HcStatus.UNAVAILABLE
        }
    }

    private fun client(context: Context): HealthConnectClient? {
        if (status(context) != HcStatus.AVAILABLE) return null
        return try {
            HealthConnectClient.getOrCreate(context.applicationContext, PROVIDER_PACKAGE)
        } catch (t: Throwable) {
            Log.w(TAG, "client", t)
            null
        }
    }

    suspend fun grantedPermissions(context: Context): Set<String> {
        val c = client(context) ?: return emptySet()
        return try { c.permissionController.getGrantedPermissions() } catch (t: Throwable) { Log.w(TAG, "granted", t); emptySet() }
    }

    suspend fun isConnected(context: Context): Boolean = grantedPermissions(context).containsAll(REQUIRED)

    /** [ALL] plus the background-read permission when this Health Connect version supports it. */
    fun permissionsToRequest(context: Context): Set<String> {
        val c = client(context) ?: return ALL
        val bg = try {
            c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (t: Throwable) { false }
        return if (bg) ALL + BACKGROUND_PERMISSION else ALL
    }

    /** Play Store page of Health Connect (only needed before Android 14, where it is a separate app). */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"))
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------------------------------------------------------------- reads

    private fun dayStart(day: Long): Instant = LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant()

    /** Steps in [start, end), de-duplicated across phone and watch by Health Connect. Null on failure. */
    suspend fun stepsBetween(context: Context, start: Instant, end: Instant): Long? {
        if (!end.isAfter(start)) return 0L
        val c = client(context) ?: return null
        return try {
            val res = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)))
            res[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (t: Throwable) { Log.w(TAG, "steps", t); null }
    }

    /** Steps of one local calendar day (today: until now). */
    suspend fun stepsForDay(context: Context, day: Long): Long? {
        val start = dayStart(day)
        val end = minOf(dayStart(day + 1), Instant.now())
        return stepsBetween(context, start, end)
    }

    /** Average and maximum heart rate in [startMillis, endMillis]; null when there are no samples. */
    suspend fun heartRate(context: Context, startMillis: Long, endMillis: Long): HrSummary? {
        if (endMillis <= startMillis) return null
        val c = client(context) ?: return null
        return try {
            val res = c.aggregate(
                AggregateRequest(
                    setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
                    TimeRangeFilter.between(Instant.ofEpochMilli(startMillis), Instant.ofEpochMilli(endMillis))
                )
            )
            val avg = res[HeartRateRecord.BPM_AVG] ?: return null
            val max = res[HeartRateRecord.BPM_MAX] ?: avg
            if (avg <= 0L) null else HrSummary(avg.toInt(), max.toInt())
        } catch (t: Throwable) { Log.w(TAG, "hr", t); null }
    }

    /**
     * The raw heart rate samples in [startMillis, endMillis] (time + bpm, ascending) for the zone calculation
     * (HrZones): every HeartRateRecord that touches the range, its samples filtered to the range.
     * Null when Health Connect is not available / the read failed; empty when there were no samples.
     */
    suspend fun hrPoints(context: Context, startMillis: Long, endMillis: Long): List<HrPoint>? {
        if (endMillis <= startMillis) return null
        val c = client(context) ?: return null
        return try {
            val records = readAll(
                c, HeartRateRecord::class,
                TimeRangeFilter.between(Instant.ofEpochMilli(startMillis), Instant.ofEpochMilli(endMillis))
            )
            val out = ArrayList<HrPoint>()
            for (r in records) {
                for (smp in r.samples) {
                    val t = smp.time.toEpochMilli()
                    if (t < startMillis || t > endMillis) continue
                    val bpm = smp.beatsPerMinute.toInt()
                    if (bpm < 25 || bpm > 250) continue
                    out.add(HrPoint(t, bpm))
                }
            }
            out.sortBy { it.timeMillis }
            out
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "hr points", t)
            null
        }
    }

    /** Calories of a time range: active calories when present, else total; null when none / no permission. */
    suspend fun kcal(context: Context, startMillis: Long, endMillis: Long): Int? {
        if (endMillis <= startMillis) return null
        val c = client(context) ?: return null
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMillis), Instant.ofEpochMilli(endMillis))
        val active = try {
            c.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                ?.inKilocalories
        } catch (t: Throwable) { null }
        if (active != null && active > 0.0) return active.roundToInt()
        val total = try {
            c.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), range))[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                ?.inKilocalories
        } catch (t: Throwable) { null }
        return if (total != null && total > 0.0) total.roundToInt() else null
    }

    /** Distance in meters of a time range; null when none / no permission. */
    suspend fun distanceMeters(context: Context, startMillis: Long, endMillis: Long): Int? {
        if (endMillis <= startMillis) return null
        val c = client(context) ?: return null
        return try {
            val m = c.aggregate(
                AggregateRequest(
                    setOf(DistanceRecord.DISTANCE_TOTAL),
                    TimeRangeFilter.between(Instant.ofEpochMilli(startMillis), Instant.ofEpochMilli(endMillis))
                )
            )[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            if (m != null && m > 0.0) m.roundToInt() else null
        } catch (t: Throwable) { null }
    }

    /**
     * Resting heart rate per local day in [fromDay, toDay] (average of that day's records, bpm). Empty when the
     * optional permission is not granted, there are no records, or anything fails.
     */
    suspend fun restingHrByDay(context: Context, fromDay: Long, toDay: Long): Map<Long, Int> {
        if (toDay < fromDay) return emptyMap()
        val c = client(context) ?: return emptyMap()
        return try {
            val perm = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
            if (perm !in c.permissionController.getGrantedPermissions()) return emptyMap()
            val now = Instant.now()
            val end = minOf(dayStart(toDay + 1), now)
            val start = maxOf(dayStart(fromDay), historyFloor(now))
            if (!end.isAfter(start)) return emptyMap()
            val records = try {
                readAll(c, RestingHeartRateRecord::class, TimeRangeFilter.between(start, end))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // the wide first read can still hit the history limit (e.g. linkedAt restored from a backup)
                val narrow = maxOf(start, now.minus(Duration.ofDays(NARROW_DAYS)))
                if (narrow == start) throw t
                Log.w(TAG, "resting hr wide read failed, retrying narrow", t)
                readAll(c, RestingHeartRateRecord::class, TimeRangeFilter.between(narrow, end))
            }
            val zone = ZoneId.systemDefault()
            val byDay = HashMap<Long, MutableList<Long>>()
            for (r in records) {
                val day = r.time.atZone(zone).toLocalDate().toEpochDay()
                byDay.getOrPut(day) { ArrayList() }.add(r.beatsPerMinute)
            }
            val out = HashMap<Long, Int>()
            for ((day, list) in byDay) {
                if (list.isNotEmpty()) out[day] = (list.sum().toDouble() / list.size).roundToInt()
            }
            out
        } catch (t: Throwable) { Log.w(TAG, "resting hr", t); emptyMap() }
    }

    /**
     * Earliest instant Health Connect lets us read other apps' data from: 30 days before the first permission
     * grant (asking for older data is an ERROR, not an empty result). [WatchState.linkedAt] is never before the
     * first grant, so linkedAt - 29 days is always readable. Not linked yet: 29 days back from now.
     */
    private fun historyFloor(now: Instant): Instant {
        val linkedAt = runCatching { WatchStore.current().linkedAt }.getOrDefault(0L)
        val base = if (linkedAt > 0L && linkedAt <= now.toEpochMilli()) Instant.ofEpochMilli(linkedAt) else now
        return base.minus(Duration.ofDays(29))
    }

    /** Retry window after a failed wide read (well inside the history limit). */
    private const val NARROW_DAYS = 4L

    /** All records of [type] in [filter], following the page token (Health Connect pages at 1000 records). */
    private suspend fun <T : Record> readAll(c: HealthConnectClient, type: KClass<T>, filter: TimeRangeFilter): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        var pages = 0
        do {
            val res = c.readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageToken = token))
            out.addAll(res.records)
            token = res.pageToken
            pages++
        } while (!token.isNullOrEmpty() && pages < 20)
        return out
    }

    /**
     * Minutes asleep per night in [fromDay, toDay], keyed by the local day he woke up. See [readSleep].
     * Empty when the optional permission is missing or anything fails.
     */
    suspend fun sleepByNight(context: Context, fromDay: Long, toDay: Long): Map<Long, Int> =
        readSleep(context, fromDay, toDay).nights

    /**
     * Reads the sleep sessions for the nights [fromDay]..[toDay] (keyed by the local day he woke up) and what the
     * settings card's diagnostic line needs. A night of day D = every sleep session that ENDS between D-1 18:00
     * and D 14:00, or later on D when it started before 10:00 of D (a long sleep-in; afternoon naps do not count).
     * Overlapping sessions (Samsung can write duplicates) are merged, see [asleepMinutes]. Nights under 60 minutes
     * are left out. Never throws.
     */
    suspend fun readSleep(context: Context, fromDay: Long, toDay: Long): SleepRead {
        if (toDay < fromDay) return SleepRead(ok = true, permission = true)
        val c = client(context) ?: return SleepRead(ok = false, permission = true, error = "no client")
        val perm = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val granted = try {
            perm in c.permissionController.getGrantedPermissions()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "sleep permission", t)
            return SleepRead(ok = false, permission = true, error = errorText(t))
        }
        if (!granted) return SleepRead(ok = false, permission = false)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        // wide on purpose: the window of each night is applied below by the session's end time
        val wanted = LocalDate.ofEpochDay(fromDay - 2).atStartOfDay(zone).toInstant()
        val from = maxOf(wanted, historyFloor(now))
        var error = ""
        val records: List<SleepSessionRecord> = try {
            readAll(c, SleepSessionRecord::class, TimeRangeFilter.between(from, now))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "sleep wide read failed", t)
            error = errorText(t)
            // e.g. the history limit when linkedAt was restored from a backup: the last few days only
            val narrow = maxOf(from, now.minus(Duration.ofDays(NARROW_DAYS)))
            if (narrow == from) return SleepRead(ok = false, permission = true, error = error)
            try {
                readAll(c, SleepSessionRecord::class, TimeRangeFilter.between(narrow, now))
            } catch (t2: Throwable) {
                if (t2 is CancellationException) throw t2
                Log.w(TAG, "sleep narrow read failed", t2)
                return SleepRead(ok = false, permission = true, error = error + " | " + errorText(t2))
            }
        }
        return try {
            val valid = records.filter { it.endTime.isAfter(it.startTime) }
            val byNight = HashMap<Long, MutableList<SleepSessionRecord>>()
            for (r in valid) {
                val night = nightOf(r.startTime, r.endTime, zone) ?: continue
                byNight.getOrPut(night) { ArrayList() }.add(r)
            }
            val nights = HashMap<Long, Int>()
            val spans = HashMap<Long, Pair<Long, Long>>()
            for ((night, list) in byNight) {
                if (night < fromDay || night > toDay) continue
                val minutes = asleepMinutes(list)
                if (minutes >= 60) {
                    nights[night] = minutes
                    spans[night] = Pair(list.minOf { it.startTime.toEpochMilli() }, list.maxOf { it.endTime.toEpochMilli() })
                }
            }
            // diagnostics: the last 3 days (today, yesterday, the day before)
            val today = LocalDate.now(zone).toEpochDay()
            val diagFrom = LocalDate.ofEpochDay(today - 2).atStartOfDay(zone).toInstant()
            val recent = valid.filter { it.endTime.isAfter(diagFrom) }
            val last = recent.maxByOrNull { it.endTime.toEpochMilli() }
            var lastMinutes = 0
            if (last != null) {
                val lastNight = nightOf(last.startTime, last.endTime, zone)
                val group = if (lastNight != null) byNight[lastNight] ?: listOf(last) else listOf(last)
                lastMinutes = asleepMinutes(group)
            }
            val origins = valid.map { it.metadata.dataOrigin.packageName }.distinct().joinToString(",")
            val stageInfo = if (last == null) "" else
                "stages=${last.stages.size}:" + last.stages.map { it.stage }.distinct().sorted().joinToString("/")
            SleepRead(
                ok = true,
                permission = true,
                nights = nights,
                spans = spans,
                recentCount = recent.size,
                totalCount = valid.size,
                lastStartMillis = last?.startTime?.toEpochMilli() ?: 0L,
                lastEndMillis = last?.endTime?.toEpochMilli() ?: 0L,
                lastAsleepMinutes = lastMinutes,
                origins = origins,
                error = listOf(error, stageInfo).filter { it.isNotEmpty() }.joinToString(" | ")
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "sleep", t)
            SleepRead(ok = false, permission = true, error = errorText(t))
        }
    }

    private fun errorText(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(300)

    /**
     * The night (wake-up day) of a session that ran [start]..[end]; null for an afternoon nap.
     * Ends before 14:00 -> that day. Ends 18:00 or later -> the next day. Ends 14:00..18:00 -> that day only
     * when it started before 10:00 of that day (or earlier), i.e. a long sleep-in, not a nap.
     */
    internal fun nightOf(start: Instant, end: Instant, zone: ZoneId): Long? {
        val local = end.atZone(zone)
        val day = local.toLocalDate().toEpochDay()
        val hour = local.hour
        return when {
            hour < 14 -> day
            hour >= 18 -> day + 1
            start.isBefore(LocalDate.ofEpochDay(day).atStartOfDay(zone).plusHours(10).toInstant()) -> day
            else -> null
        }
    }

    private fun isAwakeStage(stage: Int): Boolean =
        stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
            stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED ||
            stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED

    /**
     * Total asleep minutes of one night's sessions. Overlapping sessions form a group. In a group with stages,
     * every stage that is not awake / awake in bed / out of bed counts (light, deep, REM, "sleeping", unknown),
     * clipped to its session. When the stages cover less than half of the group's time (Samsung sometimes writes
     * stages for part of the night only) or give under an hour of a night of 2+ hours, the group counts as its
     * whole length minus the awake stages. A group without stages counts its whole length.
     */
    private fun asleepMinutes(sessions: List<SleepSessionRecord>): Int {
        val sorted = sessions.sortedBy { it.startTime.toEpochMilli() }
        // groups of overlapping sessions
        val groups = ArrayList<MutableList<SleepSessionRecord>>()
        var groupEnd = Long.MIN_VALUE
        for (r in sorted) {
            val a = r.startTime.toEpochMilli()
            val b = r.endTime.toEpochMilli()
            if (groups.isEmpty() || a >= groupEnd) {
                groups.add(arrayListOf(r))
                groupEnd = b
            } else {
                groups[groups.size - 1].add(r)
                if (b > groupEnd) groupEnd = b
            }
        }
        val hourMs = 60L * 60000L
        var totalMs = 0L
        for (g in groups) {
            val whole = ArrayList<LongArray>()
            for (r in g) whole.add(longArrayOf(r.startTime.toEpochMilli(), r.endTime.toEpochMilli()))
            val wholeMs = unionMs(whole)
            val withStages = g.filter { it.stages.isNotEmpty() }
            if (withStages.isEmpty()) {
                totalMs += wholeMs
                continue
            }
            val asleep = ArrayList<LongArray>()
            val awake = ArrayList<LongArray>()
            for (r in withStages) {
                val s0 = r.startTime.toEpochMilli()
                val s1 = r.endTime.toEpochMilli()
                for (st in r.stages) {
                    val a = maxOf(st.startTime.toEpochMilli(), s0)
                    val b = minOf(st.endTime.toEpochMilli(), s1)
                    if (b <= a) continue
                    if (isAwakeStage(st.stage)) awake.add(longArrayOf(a, b)) else asleep.add(longArrayOf(a, b))
                }
            }
            val asleepMs = unionMs(asleep)
            val awakeMs = unionMs(awake)
            val covered = unionMs(asleep + awake)
            val partial = covered * 2 < wholeMs || (asleepMs < hourMs && wholeMs >= 2 * hourMs)
            totalMs += if (partial) maxOf(asleepMs, wholeMs - awakeMs) else asleepMs
        }
        return (totalMs / 60000L).toInt()
    }

    /** Length of the union of [intervals] (each [start, end] in millis). */
    internal fun unionMs(intervals: List<LongArray>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals.sortedBy { it[0] }
        var sum = 0L
        var curA = sorted[0][0]
        var curB = sorted[0][1]
        for (i in 1 until sorted.size) {
            val a = sorted[i][0]
            val b = sorted[i][1]
            if (a <= curB) {
                if (b > curB) curB = b
            } else {
                sum += curB - curA
                curA = a
                curB = b
            }
        }
        sum += curB - curA
        return sum
    }

    /** Exercise sessions that overlap [sinceMillis, untilMillis], oldest first. Empty on failure. */
    suspend fun exerciseSessions(context: Context, sinceMillis: Long, untilMillis: Long): List<WatchWorkout> {
        if (untilMillis <= sinceMillis) return emptyList()
        val c = client(context) ?: return emptyList()
        return try {
            val res = c.readRecords(
                ReadRecordsRequest(
                    ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(sinceMillis), Instant.ofEpochMilli(untilMillis))
                )
            )
            res.records.map { r ->
                WatchWorkout(
                    id = r.metadata.id,
                    exerciseType = r.exerciseType,
                    startMillis = r.startTime.toEpochMilli(),
                    endMillis = r.endTime.toEpochMilli(),
                    originPackage = r.metadata.dataOrigin.packageName
                )
            }.sortedBy { it.startMillis }
        } catch (t: Throwable) { Log.w(TAG, "sessions", t); emptyList() }
    }
}

/**
 * The Health Connect permission screen as an ActivityResultContract that never throws: the real contract
 * (PermissionController.createRequestPermissionResultContract) is created only when it is launched, so
 * composing the settings card in tests does not touch Health Connect at all.
 */
class WatchPermissionContract : ActivityResultContract<Set<String>, Set<String>>() {
    private val inner: ActivityResultContract<Set<String>, Set<String>>? by lazy {
        try { PermissionController.createRequestPermissionResultContract(HealthConnect.PROVIDER_PACKAGE) } catch (t: Throwable) { null }
    }

    override fun createIntent(context: Context, input: Set<String>): Intent =
        inner?.createIntent(context, input) ?: HealthConnect.installIntent()

    override fun parseResult(resultCode: Int, intent: Intent?): Set<String> =
        try { inner?.parseResult(resultCode, intent) ?: emptySet() } catch (t: Throwable) { emptySet() }
}
