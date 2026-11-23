package com.tamir.hamasa.domain.energy

/** One heart rate sample from the watch: when (epoch millis) and the beats per minute. */
data class HrPoint(val timeMillis: Long, val bpm: Int)

/**
 * Heart rate zones of a walk / run, pure logic (no Android).
 *
 * Zones by percent of the maximal heart rate (HRmax = 220 - age, [FALLBACK_HR_MAX] when the age is unknown):
 *  1: 50-60%, 2: 60-70%, 3: 70-80%, 4: 80-90%, 5: 90% and above. Below 50% is rest ("מנוחה") and is not shown.
 *
 * Time: every sample is credited with the gap to the next sample (capped at [MAX_GAP_MS], so a hole in the
 * recording is not counted as training); the last sample gets the median gap. The result is whole minutes per
 * zone, rounded.
 */
object HrZones {
    const val ZONES = 5
    const val FALLBACK_HR_MAX = 190
    const val MAX_GAP_MS = 60_000L
    private const val MINUTE_MS = 60_000L

    /** Minutes per zone (index 0 = zone 1 .. index 4 = zone 5), the measured minutes in the zones and at rest. */
    data class Result(val minutes: List<Int>, val totalMinutes: Int, val restMinutes: Int) {
        val isEmpty: Boolean get() = minutes.size != ZONES || minutes.all { it <= 0 }
    }

    val EMPTY = Result(List(ZONES) { 0 }, 0, 0)

    /** 220 - age; the fallback when the age is not known / not sensible. */
    fun hrMax(age: Int): Int = if (age in 14..100) 220 - age else FALLBACK_HR_MAX

    /** Zone 1..5 of [bpm] for [hrMax]; 0 = below 50% (rest). */
    fun zoneOf(bpm: Int, hrMax: Int): Int {
        if (bpm <= 0 || hrMax <= 0) return 0
        val pct = bpm * 100L / hrMax
        return when {
            pct < 50L -> 0
            pct < 60L -> 1
            pct < 70L -> 2
            pct < 80L -> 3
            pct < 90L -> 4
            else -> 5
        }
    }

    /** The lower bound (bpm) of [zone] 1..5 for [hrMax], for labels. */
    fun lowerBpm(zone: Int, hrMax: Int): Int = (hrMax * (40 + zone * 10)) / 100

    fun compute(samples: List<HrPoint>, hrMax: Int): Result {
        val sorted = samples.filter { it.bpm in 25..250 }.sortedBy { it.timeMillis }
        if (sorted.isEmpty()) return EMPTY
        val n = sorted.size
        val gaps = LongArray(n)
        for (i in 0 until n - 1) {
            gaps[i] = (sorted[i + 1].timeMillis - sorted[i].timeMillis).coerceIn(0L, MAX_GAP_MS)
        }
        gaps[n - 1] = if (n == 1) MAX_GAP_MS else median(gaps.copyOf(n - 1))
        // index 0 = rest, 1..5 = zones
        val ms = LongArray(ZONES + 1)
        for (i in 0 until n) {
            val z = zoneOf(sorted[i].bpm, hrMax)
            ms[z] += gaps[i]
        }
        val minutes = ArrayList<Int>(ZONES)
        var total = 0
        for (z in 1..ZONES) {
            val m = toMinutes(ms[z])
            minutes.add(m)
            total += m
        }
        return Result(minutes, total, toMinutes(ms[0]))
    }

    /** Same as [compute] with the age: HRmax = 220 - age. */
    fun computeForAge(samples: List<HrPoint>, age: Int): Result = compute(samples, hrMax(age))

    private fun toMinutes(ms: Long): Int = ((ms + MINUTE_MS / 2) / MINUTE_MS).toInt()

    private fun median(values: LongArray): Long {
        if (values.isEmpty()) return MAX_GAP_MS
        val s = values.copyOf()
        s.sort()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2L
    }

    // ---------------------------------------------------------------- Hebrew

    /** True when [minutes] is a stored zone list worth showing (5 values, at least one minute). */
    fun has(minutes: List<Int>): Boolean = minutes.size == ZONES && minutes.any { it > 0 }

    /** "אזור 3 · 20 דק'" */
    fun labelHe(zone: Int, minutes: Int): String = "אזור $zone · $minutes דק'"

    /** "אזורים: 1·3 2·12 3·20 4·6 5·0" (one line for lists). */
    fun summaryHe(minutes: List<Int>): String {
        if (minutes.size != ZONES) return ""
        val sb = StringBuilder("אזורים:")
        for (i in 0 until ZONES) sb.append(' ').append(i + 1).append('·').append(minutes[i])
        return sb.toString()
    }

    /** Total minutes in the zones of a stored list. */
    fun total(minutes: List<Int>): Int = minutes.sumOf { it.coerceAtLeast(0) }
}
