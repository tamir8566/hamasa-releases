package com.tamir.hamasa.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.health.SessionWatch
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.energy.RestingHrModel
import com.tamir.hamasa.ui.theme.HColors

/**
 * "הלב" on Home: resting heart rate (today, or the newest day) against the usual baseline, a 14-day sparkline,
 * the average / peak heart rate of the last workout the watch measured, and the "elevated" state.
 * Nothing at all when the watch is not connected or there is no heart data; one line when the data is thin.
 * Read-only (data/health/RestingHr keeps the days in Prefs.restingHrCsv, WatchStore keeps the per-session HR).
 */
@Composable
fun HomeHeartCard(modifier: Modifier = Modifier) {
    val watch by WatchStore.state.collectAsState()
    if (!watch.connected) return
    val profile by AppGraph.prefs.profile.collectAsStateWithLifecycle(initialValue = null)
    val p = profile ?: return
    val today = remember { Days.today() }
    val days = remember(p.restingHrCsv) { RestingHrModel.decode(p.restingHrCsv) }
    val status = remember(days, today) { RestingHrModel.status(days, today) }
    // the newest session the watch gave a heart rate for (session ids grow over time)
    val lastWorkout: SessionWatch? = remember(watch.sessions) {
        watch.sessions.entries.filter { it.value.hasHr }.maxByOrNull { it.key }?.value
    }
    if (status == null && lastWorkout == null) return

    val trend = remember(days, today) { RestingHrModel.trend(days, today, 14) }
    val points = trend.count { it.second != null }
    val elevated = status?.elevated == true
    val restLine = if (status != null) {
        val whenHe = when (status.lastDay) {
            today -> "היום"
            today - 1 -> "אתמול"
            else -> "לפני ${today - status.lastDay} ימים"
        }
        if (status.baseline > 0) "דופק מנוחה $whenHe: ${status.lastBpm}. הרגיל: ${status.baseline}."
        else "דופק מנוחה $whenHe: ${status.lastBpm}."
    } else null
    val workoutLine = lastWorkout?.let { w ->
        if (w.maxHr > 0) "אימון אחרון: ממוצע ${w.avgHr}, שיא ${w.maxHr}." else "אימון אחרון: ממוצע ${w.avgHr}."
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = HColors.Ink,
        border = BorderStroke(2.dp, if (elevated) HColors.Danger else HColors.GoldDeep)
    ) {
        // thin data: a single line
        if (points < 3 && !elevated) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("הלב", color = HColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text(
                    "  " + listOfNotNull(restLine, workoutLine).joinToString(" "),
                    color = HColors.Parchment, fontSize = 13.sp, maxLines = 2
                )
            }
            return@Surface
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("הלב", color = HColors.Gold, fontSize = 17.sp, fontWeight = FontWeight.Black)
                if (status != null) {
                    Text(
                        "  ${status.lastBpm}",
                        color = if (elevated) HColors.Danger else HColors.Parchment, fontSize = 22.sp, fontWeight = FontWeight.Black
                    )
                    Text(" פעימות", color = HColors.ParchmentDim, fontSize = 13.sp)
                }
            }
            if (restLine != null) Text(restLine, color = HColors.Parchment, fontSize = 13.sp)
            HeartSparkline(trend, status?.baseline ?: 0, Modifier.fillMaxWidth().height(40.dp))
            if (workoutLine != null) Text(workoutLine, color = HColors.ParchmentDim, fontSize = 13.sp)
            if (elevated) {
                Text(
                    "גבוה מהרגיל כמה ימים ברצף. אולי עייפות. הג'ים ברו כבר יודע.",
                    color = HColors.Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 14 days of resting heart rate as a thin line (oldest on the start side, so in RTL it reads right to left like the
 * other charts), days without data are skipped. The dim horizontal line is the baseline.
 */
@Composable
private fun HeartSparkline(trend: List<Pair<Long, Int?>>, baseline: Int, modifier: Modifier = Modifier) {
    val values = trend.mapNotNull { it.second }
    if (values.isEmpty()) return
    val lo0 = values.minOrNull() ?: 0
    val hi0 = values.maxOrNull() ?: 0
    val lo = (if (baseline > 0) minOf(lo0, baseline) else lo0) - 2
    val hi = (if (baseline > 0) maxOf(hi0, baseline) else hi0) + 2
    val span = (hi - lo).coerceAtLeast(1).toFloat()
    val lineColor = HColors.Sky
    val baseColor = HColors.ParchmentDim.copy(alpha = 0.45f)
    val highColor = HColors.Danger
    Canvas(modifier) {
        val n = trend.size
        if (n < 2) return@Canvas
        val stepX = size.width / (n - 1).toFloat()
        // RTL: index 0 (oldest) on the right
        fun x(i: Int): Float = size.width - i * stepX
        fun y(v: Int): Float = size.height - (v - lo) / span * size.height
        if (baseline > 0) {
            drawLine(baseColor, Offset(0f, y(baseline)), Offset(size.width, y(baseline)), strokeWidth = 2f)
        }
        var prev: Offset? = null
        trend.forEachIndexed { i, (_, v) ->
            if (v != null) {
                val pt = Offset(x(i), y(v))
                val pr = prev
                if (pr != null) drawLine(lineColor, pr, pt, strokeWidth = 5f, cap = StrokeCap.Round)
                prev = pt
            }
        }
        // dots: red when 5+ above the baseline
        trend.forEachIndexed { i, (_, v) ->
            if (v != null) {
                val high = baseline > 0 && v >= baseline + RestingHrModel.RISE_3_DAYS
                drawCircle(if (high) highColor else lineColor, radius = if (high) 6f else 4f, center = Offset(x(i), y(v)))
            }
        }
    }
}
