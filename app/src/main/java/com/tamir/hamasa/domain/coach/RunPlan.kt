package com.tamir.hamasa.domain.coach

/** One step of an interval run. */
enum class RunStepKind { WARMUP, RUN, WALK, COOLDOWN }

data class RunStep(val kind: RunStepKind, val sec: Int) {
    val isRun: Boolean get() = kind == RunStepKind.RUN

    /** "ריצה 60 שניות", "הליכה 2 דק'", "חימום 5 דק' הליכה". */
    val labelHe: String
        get() = when (kind) {
            RunStepKind.WARMUP -> "חימום ${RunPlan.durHe(sec)} הליכה"
            RunStepKind.RUN -> "ריצה ${RunPlan.durHe(sec)}"
            RunStepKind.WALK -> "הליכה ${RunPlan.durHe(sec)}"
            RunStepKind.COOLDOWN -> "שחרור ${RunPlan.durHe(sec)} הליכה"
        }
}

/**
 * The running coach: from zero to 5 km in 9 weeks, 3 sessions a week, never two run days in a row.
 *
 * Built on the NHS Couch to 5K structure (5-minute walk before and after every session, run/walk intervals that
 * grow every week, 30 minutes continuous in week 9) and made gentler for a heavy beginner (about 105 kg):
 * longer walk breaks in weeks 1–4, no 20-minute continuous jump in week 5 (it arrives in week 7), and smaller
 * steps between sessions. A session rated hard or with a too-high average heart rate repeats the week
 * (data/repo/RunCoach). Sources and the table are in docs/coach_logic.md ("מאמן הריצה").
 */
object RunPlan {
    const val WEEKS = 9
    const val DAYS_PER_WEEK = 3
    const val WARMUP_SEC = 300
    const val COOLDOWN_SEC = 300

    /** "60 שניות", "90 שניות", "2 דק'", "2.5 דק'". */
    fun durHe(sec: Int): String {
        if (sec <= 90) return "$sec שניות"
        if (sec % 60 == 0) return "${sec / 60} דק'"
        val tenths = (sec * 10 + 30) / 60
        return "${tenths / 10}.${tenths % 10} דק'"
    }

    /** [n] runs of [run] seconds with [walk] seconds of walking between them (none after the last run). */
    private fun reps(run: Int, walk: Int, n: Int): List<RunStep> {
        val out = ArrayList<RunStep>()
        for (i in 0 until n) {
            out.add(RunStep(RunStepKind.RUN, run))
            if (i < n - 1) out.add(RunStep(RunStepKind.WALK, walk))
        }
        return out
    }

    private fun run(sec: Int) = RunStep(RunStepKind.RUN, sec)
    private fun walk(sec: Int) = RunStep(RunStepKind.WALK, sec)

    /** The main part of week [week] (1..9), session [day] (1..3). Out-of-range values are clamped. */
    private fun main(week: Int, day: Int): List<RunStep> {
        val w = week.coerceIn(1, WEEKS)
        val d = day.coerceIn(1, DAYS_PER_WEEK)
        return when (w) {
            1 -> reps(60, 120, 8)
            2 -> reps(90, 120, 6)
            3 -> reps(120, 120, 6)
            4 -> reps(180, 120, 5)
            5 -> when (d) {
                1 -> reps(300, 180, 3)
                2 -> reps(360, 150, 3)
                else -> reps(480, 180, 2)
            }
            6 -> when (d) {
                1 -> reps(480, 120, 3)
                2 -> reps(600, 180, 2)
                else -> reps(720, 180, 2)
            }
            7 -> when (d) {
                1 -> reps(900, 180, 2)
                2 -> listOf(run(1080), walk(180), run(420))
                else -> listOf(run(1200))
            }
            8 -> when (d) {
                1 -> listOf(run(1320))
                else -> listOf(run(1500))
            }
            else -> when (d) {
                1 -> listOf(run(1680))
                else -> listOf(run(1800))
            }
        }
    }

    /** Warm-up walk, the intervals, cool-down walk. */
    fun steps(week: Int, day: Int): List<RunStep> =
        listOf(RunStep(RunStepKind.WARMUP, WARMUP_SEC)) + main(week, day) + RunStep(RunStepKind.COOLDOWN, COOLDOWN_SEC)

    fun totalSec(week: Int, day: Int): Int = steps(week, day).sumOf { it.sec }

    fun runSec(week: Int, day: Int): Int = steps(week, day).filter { it.isRun }.sumOf { it.sec }

    /** "8 פעמים: ריצה 60 שניות, הליכה 2 דק'" or the list of steps when they are not uniform. */
    fun summaryHe(week: Int, day: Int): String {
        val m = main(week, day)
        val runs = m.filter { it.isRun }
        val walks = m.filter { !it.isRun }
        if (runs.size == 1) return "ריצה רצופה ${durHe(runs[0].sec)}"
        val uniform = runs.all { it.sec == runs[0].sec } && walks.all { it.sec == walks.firstOrNull()?.sec }
        if (uniform && walks.isNotEmpty()) return "${runs.size} פעמים: ריצה ${durHe(runs[0].sec)}, הליכה ${durHe(walks[0].sec)} ביניהן"
        return m.joinToString(", ") { it.labelHe }
    }

    /** "שבוע 3 · אימון 2". */
    fun labelHe(week: Int, day: Int): String = "שבוע $week · אימון $day"

    /** Sessions done in the plan before ([week], [day]), 0..27. */
    fun doneBefore(week: Int, day: Int): Int =
        ((week.coerceIn(1, WEEKS + 1) - 1) * DAYS_PER_WEEK + (day.coerceIn(1, DAYS_PER_WEEK) - 1)).coerceIn(0, WEEKS * DAYS_PER_WEEK)

    const val TOTAL_SESSIONS = WEEKS * DAYS_PER_WEEK
}
