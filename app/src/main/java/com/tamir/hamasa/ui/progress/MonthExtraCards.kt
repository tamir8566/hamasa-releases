package com.tamir.hamasa.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.repo.EnergyTotals
import com.tamir.hamasa.domain.coach.MonthProgress
import com.tamir.hamasa.domain.food.WeekCalories
import com.tamir.hamasa.ui.theme.HColors
import java.util.Locale
import kotlin.math.abs

private val ExtraCardShape = RoundedCornerShape(20.dp)

private fun thousands(v: Int): String = String.format(Locale.US, "%,d", v)

private fun oneDecimal(v: Float): String {
    val r = Math.round(v * 10f) / 10f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else String.format(Locale.US, "%.1f", r)
}

/** The sign stays on the left of the number in a right-to-left screen (a left-to-right mark in front). */
private fun signedPct(v: Float): String {
    val t = oneDecimal(abs(v))
    return "‎" + (if (t == "0") "0%" else if (v > 0f) "+$t%" else "-$t%")
}

/**
 * "שרפת מול אכלת": the month's totals and what is left after the sum, in calories and in kilograms of fat
 * (owner request 2026-11-23). Counts the finished days; a day with nothing logged counts as his usual day.
 */
@Composable
internal fun MonthEnergyCard(t: EnergyTotals) {
    Card(shape = ExtraCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("month_energy"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("שרפת מול אכלת", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            if (t.days <= 0) {
                Text("אין ימים עם רישום אוכל בחודש הזה, אז אין מה לחשב.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("שרפת בסך הכול", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(thousands(t.burned), color = HColors.Ember, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("אכלת בסך הכול", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(thousands(t.eaten), color = HColors.Parchment, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            val bal = t.balance
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (bal < 0) "קלוריות שירדו החודש" else if (bal > 0) "קלוריות שנוספו החודש" else "מאזן החודש",
                    color = HColors.Gold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                )
                Text(
                    thousands(abs(bal)), color = if (bal < 0) HColors.Moss else HColors.Gold,
                    fontWeight = FontWeight.Black, fontSize = 26.sp, modifier = Modifier.testTag("month_balance")
                )
            }
            val kg = String.format(Locale.US, "%.2f", abs(bal) / WeekCalories.KCAL_PER_KG_FAT.toFloat())
            Text(
                when {
                    bal < 0 -> "גירעון של ${thousands(-bal)} קלוריות. שווה ערך לבערך $kg ק\"ג שומן."
                    bal > 0 -> "עודף של ${thousands(bal)} קלוריות. שווה ערך לבערך $kg ק\"ג."
                    else -> "אכלת בדיוק מה ששרפת."
                },
                color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "ליום בממוצע: שריפה ${thousands(t.avgBurn)}, אוכל ${thousands(t.avgEaten)}.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "לפי ${t.days} ימים שנגמרו." +
                    if (t.estimatedDays > 0) " ${t.estimatedDays} מהם בלי רישום אוכל, ונספרו כיום אוכל ממוצע שלך." else "",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** "ההתקדמות בתרגילים": every exercise he did on two days or more this month, with a small graph and the change in percent. */
@Composable
internal fun MonthProgressCard(rows: List<MonthProgress.Row>) {
    Card(shape = ExtraCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("month_progress"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ההתקדמות בתרגילים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            if (rows.isEmpty()) {
                Text(
                    "אין החודש תרגיל שחזר בשני אימונים. צריך לפחות שניים כדי להשוות.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }
            val up = rows.count { it.changePct > 0.05f }
            val down = rows.count { it.changePct < -0.05f }
            Text(
                "${rows.size} תרגילים שחזרו החודש: $up עלו, $down ירדו, ${rows.size - up - down} בלי שינוי.",
                color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
            )
            for (r in rows) ProgressRow(r)
            Text(
                "כל נקודה היא אימון. האחוז משווה את האימון האחרון בחודש לראשון, לפי הסט הטוב ביותר: המשקל והחזרות יחד.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun setText(p: MonthProgress.Point, weighted: Boolean): String =
    if (weighted) "${oneDecimal(p.kg)} ק\"ג על ${p.reps} חזרות" else "${p.reps} חזרות"

@Composable
private fun ProgressRow(r: MonthProgress.Row) {
    val pct = r.changePct
    val color = if (pct > 0.05f) HColors.Moss else if (pct < -0.05f) HColors.Danger else HColors.ParchmentDim
    Column(Modifier.fillMaxWidth().testTag("month_progress_row"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(r.nameHe, color = HColors.Parchment, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(signedPct(pct), color = color, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        ProgressSpark(r.points.map { it.value }, color, Modifier.fillMaxWidth().height(54.dp))
        Text("בהתחלה: " + setText(r.first, r.weighted), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
        Text("בסוף: " + setText(r.last, r.weighted) + " · ${r.points.size} אימונים", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
    }
}

/** A small line through the workouts of the month, the first on the left like the weight chart. */
@Composable
private fun ProgressSpark(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.minOrNull() ?: 0f
        val hi = values.maxOrNull() ?: 0f
        val span = if (hi - lo < 0.001f) 1f else hi - lo
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun at(i: Int): Offset {
            val x = pad + w * i / (values.size - 1)
            val y = if (hi - lo < 0.001f) size.height / 2f else pad + h * (1f - (values[i] - lo) / span)
            return Offset(x, y)
        }
        drawLine(HColors.Ink, Offset(pad, size.height - pad / 2), Offset(size.width - pad, size.height - pad / 2), strokeWidth = 1.dp.toPx())
        for (i in 1 until values.size) {
            drawLine(color, at(i - 1), at(i), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        for (i in values.indices) {
            drawCircle(HColors.Slate, radius = 5.dp.toPx(), center = at(i))
            drawCircle(color, radius = 3.5.dp.toPx(), center = at(i))
        }
    }
}
