package com.tamir.hamasa.data.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sleep from the phone itself, with no tap anywhere: the night's long "screen off" stretch, the same idea Samsung
 * Health uses for its "did you sleep 23:40-06:50?" suggestion. Read from Android's usage events (screen on / off),
 * which needs the one-time "usage access" switch (Settings > usage access). Used only for nights the watch did not
 * record; a watch night always wins (WatchSleep reads it later and overwrites).
 *
 * Rules for the night that ends on the morning of day D:
 *  - events from D-1 18:00 to D 14:00 (or now);
 *  - screen-off stretches joined when the screen was on for less than [GLANCE_MS] in between (checking the time);
 *  - the longest joined stretch that starts between D-1 20:00 and D 06:00 and is at least [MIN_SLEEP_MS] of screen
 *    off; the glances are not counted as sleep;
 *  - it must be over: the screen came on after it (he is up).
 * Never throws.
 */
object PhoneSleep {
    private const val TAG = "PhoneSleep"
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val GLANCE_MS = 10L * 60L * 1000L
    private const val MIN_SLEEP_MS = 3L * HOUR_MS
    private const val MAX_SLEEP_MS = 14L * HOUR_MS

    data class Night(val startMillis: Long, val endMillis: Long, val minutes: Int)

    /** The "usage access" switch is on for this app. */
    fun hasAccess(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    /** Screen-off stretch as (start, end). */
    data class Off(val start: Long, val end: Long)

    /** The night that ended on the morning of [day]; null without access, without events, or when not over yet. */
    fun nightFor(context: Context, day: Long, now: Long = System.currentTimeMillis()): Night? {
        if (!hasAccess(context)) return null
        return try {
            val zone = ZoneId.systemDefault()
            val date = LocalDate.ofEpochDay(day)
            val from = date.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
            val until = minOf(now, date.atTime(14, 0).atZone(zone).toInstant().toEpochMilli())
            if (until <= from) return null
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val events = usm.queryEvents(from, until) ?: return null
            val offs = ArrayList<Off>()
            var offSince = -1L
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (offSince < 0L) offSince = e.timeStamp
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        if (offSince >= 0L && e.timeStamp > offSince) offs.add(Off(offSince, e.timeStamp))
                        offSince = -1L
                    }
                }
            }
            // a stretch still open at the end = still asleep (or the phone is just lying there): not over yet
            pick(offs, from = date.minusDays(1).atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
                latestStart = date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli())
        } catch (t: Throwable) {
            Log.w(TAG, "night", t)
            null
        }
    }

    /**
     * Pure part of [nightFor] (tests): joins stretches split by short glances and returns the longest block that
     * starts in [from, latestStart] with at least [MIN_SLEEP_MS] of screen off.
     */
    fun pick(offs: List<Off>, from: Long, latestStart: Long): Night? {
        if (offs.isEmpty()) return null
        val sorted = offs.sortedBy { it.start }
        var best: Night? = null
        var i = 0
        while (i < sorted.size) {
            val start = sorted[i].start
            var end = sorted[i].end
            var offMs = end - start
            var j = i + 1
            while (j < sorted.size && sorted[j].start - end < GLANCE_MS) {
                offMs += sorted[j].end - sorted[j].start
                end = sorted[j].end
                j++
            }
            if (start in from..latestStart && offMs >= MIN_SLEEP_MS && offMs <= MAX_SLEEP_MS) {
                val n = Night(start, end, (offMs / 60_000L).toInt())
                if (best == null || n.minutes > best.minutes) best = n
            }
            i = j
        }
        return best
    }

    /**
     * Fills the nights of [fromDay]..[today] that have no sleep yet (watch nights untouched). Returns the nights
     * it added, day -> night.
     */
    fun fillMissing(context: Context, fromDay: Long, today: Long, have: Map<Long, Int>): Map<Long, Night> {
        if (!hasAccess(context)) return emptyMap()
        val out = HashMap<Long, Night>()
        for (day in fromDay..today) {
            if (have.containsKey(day)) continue
            val n = nightFor(context, day) ?: continue
            out[day] = n
        }
        return out
    }
}
