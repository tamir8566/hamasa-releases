package com.tamir.hamasa.ui.energy

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.health.RestingHr
import com.tamir.hamasa.data.health.WatchPermissionContract
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.energy.RestingHrModel
import com.tamir.hamasa.ui.theme.HColors

/**
 * "דופק מנוחה": the watch's daily resting heart rate, last 14 days as small bars, with the baseline (median of the
 * previous weeks). Nothing when the watch is not connected; a button when the optional permission is missing.
 */
@Composable
fun RestingHrCard() {
    val context = LocalContext.current
    val watch by WatchStore.state.collectAsState()
    if (!watch.connected) return
    val profile by AppGraph.prefs.profile.collectAsStateWithLifecycle(initialValue = null)
    val p = profile ?: return
    var tick by remember { mutableIntStateOf(0) }
    val granted by produceState<Boolean?>(initialValue = null, tick) {
        value = RestingHr.hasPermission(context)
    }
    val contract = remember { WatchPermissionContract() }
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        WatchSync.onPermissionsResult(context, result)
        tick++
    }
    // read on every composition (cheap): a card kept on screen past midnight follows the new day
    val today = Days.today()
    val days = remember(p.restingHrCsv) { RestingHrModel.decode(p.restingHrCsv) }
    val status = remember(days, today) { RestingHrModel.status(days, today) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (status?.elevated == true) HColors.Danger else HColors.Ink),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("דופק מנוחה", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            if (granted == false) {
                Text(
                    "השעון מחובר, אבל קריאת דופק המנוחה עוד לא אושרה. היא עוזרת לג'ים ברו לזהות עייפות מצטברת.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = {
                    runCatching { launcher.launch(setOf(RestingHr.PERMISSION)) }
                }) { Text(com.tamir.hamasa.domain.game.Addr.t("אשר דופק מנוחה", "אשרי דופק מנוחה"), color = HColors.Parchment, fontWeight = FontWeight.Bold) }
            }
            if (status == null) {
                if (granted == true) Text("עוד אין נתונים מהשעון.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            } else {
                // an old reading says how old it is (older than yesterday)
                val ageDays = (today - status.lastDay).toInt()
                val age = when {
                    ageDays < 2 -> ""
                    ageDays == 2 -> " (לפני יומיים)"
                    else -> " (לפני $ageDays ימים)"
                }
                Text(
                    if (status.baseline > 0) "אחרון: ${status.lastBpm} פעימות$age. הרגיל שלך: ${status.baseline}."
                    else "אחרון: ${status.lastBpm} פעימות$age. הבסיס נקבע אחרי כעשרה ימים של נתונים.",
                    color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge
                )
                if (status.elevated) {
                    Text("גבוה מהרגיל כמה ימים ברצף. הג'ים ברו רושם את זה כסימן עייפות.", color = HColors.Danger, style = MaterialTheme.typography.bodyMedium)
                }
                HrBars(RestingHrModel.trend(days, today, 14), status.baseline)
            }
        }
    }
}

@Composable
private fun HrBars(trend: List<Pair<Long, Int?>>, baseline: Int) {
    val values = trend.mapNotNull { it.second }
    if (values.isEmpty()) return
    val lo = (minOf(values.minOrNull() ?: 0, if (baseline > 0) baseline else Int.MAX_VALUE) - 3).coerceAtLeast(0)
    val hi = maxOf(values.maxOrNull() ?: 0, baseline) + 2
    val span = (hi - lo).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for ((_, v) in trend) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                if (v != null) {
                    val frac = ((v - lo).toFloat() / span).coerceIn(0.08f, 1f)
                    val high = baseline > 0 && v >= baseline + RestingHrModel.RISE_3_DAYS
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(frac)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (high) HColors.Danger else HColors.Sky)
                    )
                }
            }
        }
    }
    Text("ארבעה עשר הימים האחרונים. אדום: חמש פעימות ומעלה מעל הרגיל.", color = HColors.ParchmentDim, fontSize = 12.sp)
}
