package com.tamir.hamasa.ui.energy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.health.SessionWatch
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.domain.energy.BurnMethod
import com.tamir.hamasa.domain.energy.HrZones
import com.tamir.hamasa.domain.energy.WorkoutBurn
import com.tamir.hamasa.ui.theme.HColors
import java.util.Locale

/**
 * "שרפת בערך 310 קלוריות": the session's net burn, computed on the fly (nothing stored).
 * [refreshKey] (e.g. the session duration) recomputes once the session is closed. Shows nothing while unknown.
 * Stage 3: the watch heart rate (and distance) of the session when there is one; it may arrive a few
 * minutes after the session closed, so the card listens to WatchStore and recomputes.
 */
@Composable
fun SessionBurnCard(sessionId: Long, refreshKey: Int = 0, showSteps: Boolean = true) {
    val watchState by WatchStore.state.collectAsState()
    val watch: SessionWatch? = watchState.sessions[sessionId]?.takeIf { it.hasHr }
    // walks / runs synced before zones existed: compute them now from the watch samples (once per open)
    val context = LocalContext.current
    val needZones = watch != null && !watch.hasZones
    LaunchedEffect(sessionId, needZones) {
        if (needZones) runCatching { WatchSync.backfillZones(context, sessionId) }
    }
    val burn by produceState<WorkoutBurn?>(initialValue = null, key1 = sessionId, key2 = refreshKey, key3 = watch?.avgHr) {
        value = runCatching { EnergyData.sessionBurn(sessionId) }.getOrNull()
    }
    val steps by produceState<Int?>(initialValue = null, key1 = sessionId, key2 = refreshKey) {
        value = runCatching { WatchSync.stepsForSession(context.applicationContext, sessionId) }.getOrNull()
    }
    val b = burn ?: return
    if (b.kcal <= 0) return
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ember)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "שרפת בערך ${String.format(Locale.US, "%,d", b.kcal)} קלוריות",
                color = HColors.Ember, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black
            )
            Text(
                when (b.method) {
                    BurnMethod.HEART_RATE -> "לפי הדופק, המשך והמשקל. מעבר לשריפה הטבעית."
                    BurnMethod.DISTANCE -> "לפי הכלל שלך: המשקל כפול ${String.format(Locale.US, "%.1f", b.km)} ק\"מ, ובהליכה כפול 60 אחוז."
                    BurnMethod.DISTANCE_EST -> "לפי הכלל שלך, עם מרחק משוער של ${String.format(Locale.US, "%.1f", b.km)} ק\"מ לפי הזמן. אם תכתוב את המרחק, החישוב יהיה מדויק."
                    else -> "לפי סוג האימון, הקצב, המשך והמשקל. מעבר לשריפה הטבעית. הערכה, לא מאזניים."
                },
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            val st = steps
            if (showSteps && st != null && st > 0) {
                Text(
                    "צעדים באימון: ${String.format(Locale.US, "%,d", st)}",
                    color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold
                )
            }
            if (watch != null) {
                val parts = ArrayList<String>()
                parts.add("דופק ממוצע ${watch.avgHr}")
                if (watch.maxHr > 0) parts.add("שיא ${watch.maxHr}")
                if (watch.distanceM >= 100) parts.add("${String.format(Locale.US, "%.1f", watch.distanceM / 1000f)} ק\"מ")
                Text(
                    "מהשעון: " + parts.joinToString(", ") + ".",
                    color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold
                )
                if (watch.hasZones) HrZonesRow(watch.zonesMin)
            }
        }
    }
}

/** Zone colors, cool to hot: 1 Sky, 2 Moss, 3 Gold, 4 Ember, 5 Danger. */
private val ZoneColors = listOf(HColors.Sky, HColors.Moss, HColors.Gold, HColors.Ember, HColors.Danger)

/**
 * Minutes per heart rate zone (walks / runs): one row per zone with the label "אזור N · M דק'" and a colored bar
 * whose length is the zone's share of the longest zone. Zones with no time get a thin empty track.
 */
@Composable
fun HrZonesRow(zonesMin: List<Int>) {
    if (zonesMin.size != HrZones.ZONES) return
    val total = HrZones.total(zonesMin)
    val longest = (zonesMin.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "אזורי דופק ($total דק' נמדדו)",
            color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black
        )
        for (i in 0 until HrZones.ZONES) {
            val m = zonesMin[i].coerceAtLeast(0)
            val color = ZoneColors[i]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    HrZones.labelHe(i + 1, m),
                    color = if (m > 0) color else HColors.ParchmentDim,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(132.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .background(HColors.Ink, RoundedCornerShape(8.dp))
                ) {
                    if (m > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth(m.toFloat() / longest.toFloat())
                                .fillMaxHeight()
                                .background(color, RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
        }
    }
}
