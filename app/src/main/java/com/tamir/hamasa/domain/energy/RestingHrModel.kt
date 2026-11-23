package com.tamir.hamasa.domain.energy

/**
 * Daily resting heart rate from the watch (Health Connect RestingHeartRateRecord), pure logic.
 *
 *  - baseline = median of the days [last - 30, last - 3] (about 28 days before the checked run), at least
 *    [MIN_BASELINE_DAYS] values;
 *  - elevated = the last 3 days all >= baseline + 5 bpm, or the last 2 days all >= baseline + 7 bpm;
 *    "last" is the newest day with a value and it must be today or yesterday (old data never warns).
 * Elevated resting HR is a fatigue signal for the coach (deload proposal) and a warning from Shambiuluk on Home.
 */
object RestingHrModel {
    const val KEEP_DAYS = 40L
    const val MIN_BASELINE_DAYS = 10
    const val RISE_3_DAYS = 5
    const val RISE_2_DAYS = 7

    data class Status(
        val lastDay: Long,
        val lastBpm: Int,
        /** 0 when there is not enough history yet. */
        val baseline: Int,
        val elevated: Boolean,
        /** How many bpm the last day is above the baseline (0 when unknown / below). */
        val aboveBy: Int
    )

    /** "day:bpm;day:bpm" -> map. Malformed pieces are ignored. */
    fun decode(csv: String): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (piece in csv.split(';')) {
            val kv = piece.split(':')
            if (kv.size != 2) continue
            val d = kv[0].trim().toLongOrNull() ?: continue
            val v = kv[1].trim().toIntOrNull() ?: continue
            if (v in 25..150) out[d] = v
        }
        return out
    }

    fun encode(m: Map<Long, Int>): String = m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" }

    /** Adds / replaces [fresh] days and keeps only the last [KEEP_DAYS] days before [today]. */
    fun merge(old: Map<Long, Int>, fresh: Map<Long, Int>, today: Long): Map<Long, Int> {
        val m = HashMap<Long, Int>(old)
        for ((d, v) in fresh) if (v in 25..150) m[d] = v
        return m.filterKeys { it > today - KEEP_DAYS && it <= today }
    }

    fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid] + 1) / 2
    }

    fun status(days: Map<Long, Int>, today: Long): Status? {
        if (days.isEmpty()) return null
        val last = days.keys.maxOrNull() ?: return null
        val lastBpm = days[last] ?: return null
        val window = days.filterKeys { it >= last - 30 && it <= last - 3 }.values.toList()
        val baseline = if (window.size >= MIN_BASELINE_DAYS) median(window) else 0
        val fresh = last >= today - 1
        var elevated = false
        if (baseline > 0 && fresh) {
            val d0 = days[last]
            val d1 = days[last - 1]
            val d2 = days[last - 2]
            val three = d0 != null && d1 != null && d2 != null &&
                d0 >= baseline + RISE_3_DAYS && d1 >= baseline + RISE_3_DAYS && d2 >= baseline + RISE_3_DAYS
            val two = d0 != null && d1 != null && d0 >= baseline + RISE_2_DAYS && d1 >= baseline + RISE_2_DAYS
            elevated = three || two
        }
        val above = if (baseline > 0) (lastBpm - baseline).coerceAtLeast(0) else 0
        return Status(last, lastBpm, baseline, elevated, above)
    }

    /** The last [n] days (oldest first) as (day, bpm or null), for the small trend chart. */
    fun trend(days: Map<Long, Int>, today: Long, n: Int = 14): List<Pair<Long, Int?>> =
        ((today - n + 1)..today).map { d -> Pair(d, days[d]) }
}
