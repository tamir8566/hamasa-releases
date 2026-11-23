package com.tamir.hamasa.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.CoachDebrief
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.delay

/**
 * "הג'ים ברו אומר": the coach's serious summary of a finished gym session (data/ai/CoachDebrief) with the computed
 * targets for next time. Shows nothing for sessions that are not gym sessions with completed sets.
 */
@Composable
fun CoachSaysCard(sessionId: Long, modifier: Modifier = Modifier) {
    // shown only for gym sessions: false until the session row says so
    var isGym by remember(sessionId) { mutableStateOf(false) }
    var loading by remember(sessionId) { mutableStateOf(true) }
    val debrief by produceState<CoachDebrief.Debrief?>(initialValue = null, key1 = sessionId) {
        val s = runCatching { AppGraph.db.sessions().byId(sessionId) }.getOrNull()
        if (s == null || s.type != SessionType.GYM) {
            loading = false
            return@produceState
        }
        isGym = true
        var d: CoachDebrief.Debrief? = null
        var tries = 0
        // the session may still be closing when the screen opens: try again a little later
        while (d == null && tries < 3) {
            if (tries > 0) delay(1500)
            d = runCatching { CoachDebrief.forSession(sessionId) }.getOrNull()
            tries++
        }
        value = d
        loading = false
    }
    val d = debrief
    if (!isGym || (d == null && !loading)) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.Sky)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("הג'ים ברו אומר", color = HColors.Sky, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (d == null) {
                Text("הג'ים ברו עובר על המספרים.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(com.tamir.hamasa.domain.game.Addr.x(d.text), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                if (d.nextLines.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("בפעם הבאה", color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    d.nextLines.forEach { line ->
                        Text("• ${com.tamir.hamasa.domain.game.Addr.x(line)}", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
