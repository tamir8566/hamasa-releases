package com.tamir.hamasa.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.BossFights
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.BossHints
import com.tamir.hamasa.domain.game.BossReturn
import com.tamir.hamasa.domain.game.BossWeakness
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.ui.theme.HColors

private data class ReturningEnemy(
    val nameHe: String,
    val lineHe: String,
    val times: Int,
    val hp: Int,
    val maxHp: Int,
    val defeated: Boolean,
    /** "כל בוס נפגע משילוב אחר": "חולשה: ..." and Shambiuluk's hint. */
    val weaknessHe: String = "",
    val hintHe: String = ""
)

private suspend fun loadReturningEnemy(): ReturningEnemy? {
    if (!LevelGates.bossOpen(LevelTable.levelFor(AppGraph.prefs.current().xp))) return null
    val (boss, progress) = BossFights.forDay(Days.today())
    val times = BossFights.returningTimes(boss, progress.weekEpoch)
    if (times <= 0) return null
    return ReturningEnemy(boss.nameHe, BossReturn.lineHe(boss.id), times, progress.hp, progress.maxHp, progress.defeated,
        BossWeakness.lineHe(BossWeakness.forBoss(boss.id)), BossHints.forBoss(boss.id))
}

/**
 * "אויב חוזר" (spec v2: "בוס שנוצח חוזר חזק יותר עם משפט משלו"): when this week's boss was beaten before,
 * a small card on Home with its comeback line. Nothing when it is a first meeting.
 */
@Composable
fun ReturningEnemyCard(modifier: Modifier = Modifier) {
    val enemy by produceState<ReturningEnemy?>(initialValue = null) {
        value = runCatching { loadReturningEnemy() }.getOrNull()
    }
    val e = enemy ?: return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (e.defeated) HColors.Gold else HColors.Ember)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("אויב חוזר: ${e.nameHe}", color = HColors.Ember, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(if (e.defeated) "הובס שוב" else "${e.hp} / ${e.maxHp}", color = HColors.Parchment, fontWeight = FontWeight.Bold)
            }
            Text(com.tamir.hamasa.domain.game.Addr.x("\"${e.lineHe}\""), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (e.defeated) "גם הפעם. הוא ילמד. או שלא."
                else "ניצחונות קודמים: ${e.times}. חזר עם +${BossReturn.extraPercent(e.times)}% חיים.",
                color = HColors.ParchmentDim,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!e.defeated && e.weaknessHe.isNotEmpty()) {
                Text(com.tamir.hamasa.domain.game.Addr.x(e.weaknessHe), color = HColors.Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(com.tamir.hamasa.domain.game.CharacterNames.g(e.hintHe), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
