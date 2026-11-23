package com.tamir.hamasa.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Last night's sleep from the watch (docs/watch.md): Samsung Health directly first ([SamsungHealth.readSleep], when
 * connected with the sleep permission), Health Connect (optional permission READ_SLEEP) as the fallback.
 *
 * - Nights (minutes asleep, keyed by the wake-up day) are read by [SamsungHealth.readSleep] /
 *   [HealthConnect.readSleep] and kept in [WatchStore] (last ~40 days). Samsung's value wins for the same night.
 * - Today's slider value lives in Prefs (sleepDay / sleepHoursSet / sleepManual), shared by the coach screen and
 *   the morning roll. The watch fills it in unless he moved the slider himself today: manual always wins.
 * Every failure is swallowed: no permission simply means the slider stays manual.
 */
object WatchSleep {
    private const val TAG = "WatchSleep"
    private const val KEEP_DAYS = 40L

    /** Slider value when nothing is known for today. */
    const val DEFAULT_HOURS = 7f

    val PERMISSION: String by lazy { HealthPermission.getReadPermission(SleepSessionRecord::class) }

    /** Sleep can be read: Samsung Health directly (sleep permission granted there) or Health Connect's READ_SLEEP. */
    suspend fun hasPermission(context: Context): Boolean =
        runCatching {
            SamsungHealth.canReadNow(context, SamsungHealth.Kind.SLEEP) || PERMISSION in HealthConnect.grantedPermissions(context)
        }.getOrDefault(false)

    /** Last sleep read (any path), for the short re-check between full syncs. In memory only. */
    @Volatile private var lastReadAt = 0L
    private const val RECHECK_MS = 60L * 1000L

    /**
     * Called by WatchSync.sync when the watch is connected (every run, forced "סנכרן עכשיו" included): the
     * whole window when almost empty (Health Connect clamps it to what it allows), otherwise the last 3 nights.
     * Updates the settings card's diagnostic line. A failed read in the background (no background permission)
     * keeps the previous line.
     */
    suspend fun sync(context: Context, background: Boolean = false) {
        val today = Days.today()
        val have = WatchStore.current().sleep
        val from = if (have.size < 5) today - KEEP_DAYS + 1 else today - 2
        readAndStore(context, from, today, background)
    }

    /**
     * The app is in front but the full sync is throttled: still worth a sleep read while tonight's sleep is
     * missing (Samsung Health writes the night to Health Connect some time after he wakes up). At most once a minute.
     */
    fun dueWhileThrottled(now: Long = System.currentTimeMillis()): Boolean =
        WatchStore.sleepFor(Days.today()) == null && now - lastReadAt !in 0 until RECHECK_MS

    /**
     * Samsung Health first. Health Connect is read too when Samsung is not connected, failed, or has no night for
     * today yet; its nights only fill what Samsung did not return. The diagnostic line names the source it shows.
     */
    private suspend fun readAndStore(context: Context, from: Long, today: Long, background: Boolean) {
        val now = System.currentTimeMillis()
        lastReadAt = now
        val sh: SleepRead? =
            if (SamsungHealth.canReadNow(context, SamsungHealth.Kind.SLEEP)) SamsungHealth.readSleep(context, from, today) else null
        val shOk: SleepRead? = sh?.takeIf { it.ok }
        val hc: SleepRead? =
            if (shOk != null && shOk.nights.containsKey(today)) null else HealthConnect.readSleep(context, from, today)
        val nights = HashMap<Long, Int>()
        val spans = HashMap<Long, Pair<Long, Long>>()
        if (hc != null) { nights.putAll(hc.nights); spans.putAll(hc.spans) }
        if (shOk != null) { nights.putAll(shOk.nights); spans.putAll(shOk.spans) }
        if (nights.isNotEmpty()) WatchStore.putSleep(nights, spans)
        // nights the watch did not record: the phone's own screen-off night (usage access), no tap needed
        val phone = runCatching {
            PhoneSleep.fillMissing(context.applicationContext, from, today, WatchStore.current().sleep)
        }.getOrDefault(emptyMap())
        if (phone.isNotEmpty()) {
            WatchStore.putSleep(phone.mapValues { it.value.minutes }, phone.mapValues { Pair(it.value.startMillis, it.value.endMillis) })
        }
        val phoneToday = phone[today]
        // the line shows Samsung unless Health Connect read fine and has the records Samsung lacks
        val showSamsung = sh != null && (hc == null || !hc.ok || (sh.ok && (sh.totalCount > 0 || hc.totalCount == 0)))
        val r: SleepRead = if (showSamsung && sh != null) sh else hc ?: SleepRead(ok = false, permission = true)
        val debug = "sh[" + (if (sh != null) debugText(sh, from, today) else "off ${SamsungHealth.lastStatus}") + "] " +
            "hc[" + (if (hc != null) debugText(hc, from, today) else "skipped") + "]"
        val line = if (phoneToday != null) {
            "שינה מהטלפון: ${clockHe(phoneToday.minutes)}, ${hhmm(phoneToday.startMillis)}–${hhmm(phoneToday.endMillis)} (נבדק ${hhmm(now)})"
        } else diagHe(r, now, fromSamsung = showSamsung)
        Log.i(TAG, "sleep read: $line | $debug")
        if (!background || r.ok || !r.permission || phoneToday != null) WatchStore.setSleepDiag(line, debug)
        applyToday()
    }

    /** No watch link: only the phone's screen-off nights of the last 3 days (nothing without usage access). */
    suspend fun phoneOnly(context: Context) {
        val app = context.applicationContext
        if (!PhoneSleep.hasAccess(app)) return
        val today = Days.today()
        val phone = PhoneSleep.fillMissing(app, today - 2, today, WatchStore.current().sleep)
        if (phone.isEmpty()) return
        WatchStore.putSleep(phone.mapValues { it.value.minutes }, phone.mapValues { Pair(it.value.startMillis, it.value.endMillis) })
        val n = phone[today]
        if (n != null) {
            WatchStore.setSleepDiag(
                "שינה מהטלפון: ${clockHe(n.minutes)}, ${hhmm(n.startMillis)}–${hhmm(n.endMillis)} (נבדק ${hhmm(System.currentTimeMillis())})", "phone"
            )
        }
        applyToday()
    }

    /** The settings card line (Hebrew only, no package names). [fromSamsung]: read directly from Samsung Health. */
    fun diagHe(r: SleepRead, checkedAt: Long, fromSamsung: Boolean = false): String {
        val body = if (fromSamsung) when {
            !r.permission -> "שינה מסמסונג הלת': אין הרשאה"
            !r.ok -> "שינה מסמסונג הלת': שגיאה בקריאה"
            r.recentCount == 0 -> "שינה מסמסונג הלת': אין רשומות ב-3 הימים האחרונים"
            else -> "שינה מסמסונג הלת': ${clockHe(r.lastAsleepMinutes)}, ${hhmm(r.lastStartMillis)}–${hhmm(r.lastEndMillis)}"
        } else when {
            !r.permission -> "שינה: אין הרשאה"
            !r.ok -> "שינה: שגיאה בקריאה"
            r.recentCount == 0 -> "שינה בהלת' קונקט: אין רשומות ב-3 הימים האחרונים"
            else -> {
                val count = if (r.recentCount == 1) "רשומה אחת" else "${r.recentCount} רשומות"
                "שינה בהלת' קונקט: $count ב-3 ימים, האחרונה ${hhmm(r.lastStartMillis)}–${hhmm(r.lastEndMillis)}, " +
                    "${clockHe(r.lastAsleepMinutes)} שעות שינה"
            }
        }
        return "$body (נבדק ${hhmm(checkedAt)})"
    }

    private fun debugText(r: SleepRead, from: Long, today: Long): String =
        "ok=${r.ok} perm=${r.permission} days=$from..$today total=${r.totalCount} recent=${r.recentCount} " +
            "nights=${r.nights.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }} " +
            "origins=${r.origins} err=${r.error}"

    private fun hhmm(millis: Long): String = runCatching {
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")

    /**
     * Morning roll: reads only last night, waiting at most [timeoutMs]. A slower read keeps running in the
     * background and still lands in Prefs (the screens follow the profile flow).
     */
    suspend fun quickSync(context: Context, timeoutMs: Long = 3000L) {
        val app = context.applicationContext
        try {
            // neither Health Connect nor Samsung Health (or screenshot tests): nothing to wait for
            if (HealthConnect.status(app) != HcStatus.AVAILABLE && !SamsungHealth.isInstalled(app)) return
            val job = AppGraph.scope.async {
                runCatching {
                    val today = Days.today()
                    readAndStore(app, today, today, background = false)
                }.onFailure { Log.w(TAG, "quick", it) }
            }
            withTimeoutOrNull(timeoutMs) { job.await() }
        } catch (t: Throwable) {
            Log.w(TAG, "quick sync", t)
        }
    }

    /** Puts the watch's value for today on the slider (Prefs), unless he already set it by hand today. */
    suspend fun applyToday() {
        val today = Days.today()
        val minutes = WatchStore.sleepFor(today) ?: return
        val hours = sliderHours(minutes)
        AppGraph.prefs.update { p ->
            if (p.sleepDay == today && (p.sleepManual || p.sleepHoursSet == hours)) p
            else p.copy(sleepDay = today, sleepHoursSet = hours, sleepManual = false)
        }
    }

    /** He moved the slider: his value holds for the rest of today, the watch no longer overwrites it. */
    suspend fun setManual(hours: Float) {
        val today = Days.today()
        AppGraph.prefs.update { it.copy(sleepDay = today, sleepHoursSet = hours.coerceIn(3f, 10f), sleepManual = true) }
    }

    /** Today's slider value from Prefs; null = nothing set today (the screen uses [DEFAULT_HOURS]). */
    fun todayHours(p: Profile, today: Long = Days.today()): Float? =
        if (p.sleepDay == today && p.sleepHoursSet > 0f) p.sleepHoursSet else null

    /** true = today's slider value came from the watch (not moved by hand). */
    fun fromWatch(p: Profile, today: Long = Days.today()): Boolean =
        p.sleepDay == today && !p.sleepManual && p.sleepHoursSet > 0f

    /** Minutes asleep to the slider's resolution: half hours, 3..10. */
    fun sliderHours(minutes: Int): Float = ((minutes / 30f).roundToInt() / 2f).coerceIn(3f, 10f)

    /** "6 שעות 40 דקות" */
    fun durationHe(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val hText = when (h) {
            0 -> ""
            1 -> "שעה"
            2 -> "שעתיים"
            else -> "$h שעות"
        }
        val mText = when (m) {
            0 -> ""
            1 -> "דקה"
            else -> "$m דקות"
        }
        return listOf(hText, mText).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "0 דקות" }
    }

    /** "7:12" */
    fun clockHe(minutes: Int): String = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

    /**
     * Line under the slider: what the watch measured last night, and who decides. "" = no watch data for today.
     */
    fun sliderLine(p: Profile?, watchMinutes: Int?, today: Long = Days.today()): String {
        val minutes = watchMinutes ?: return ""
        if (p == null) return ""
        val times = WatchStore.sleepSpanFor(today)?.let { " (ישנת מ-${hhmm(it.first)} עד ${hhmm(it.second)})" } ?: ""
        return if (fromWatch(p, today)) "מהשעון: ${durationHe(minutes)}$times"
        else "השעון מדד ${durationHe(minutes)}$times. המחוון שלך קובע היום."
    }
}
