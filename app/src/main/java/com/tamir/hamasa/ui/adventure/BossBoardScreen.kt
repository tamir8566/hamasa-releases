package com.tamir.hamasa.ui.adventure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.domain.game.Boss
import com.tamir.hamasa.domain.game.BossCatalog
import com.tamir.hamasa.domain.game.BossReturn
import com.tamir.hamasa.ui.common.rememberNamedPainter
import com.tamir.hamasa.ui.theme.HColors

/** "לוח הבוסים": this week's boss up close, and the whole roster with how many times each one fell. */
@Composable
fun BossBoardScreen() {
    val vm: AdventureViewModel = viewModel()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val bossPair by vm.boss.collectAsStateWithLifecycle()
    val defeats: Map<String, Int> = profile?.let { p ->
        runCatching { BossReturn.parse(p.bossDefeatsCsv).mapValues { it.value.count } }.getOrDefault(emptyMap())
    } ?: emptyMap()

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "כולם מחייכים בתמונה. אף אחד מהם לא נחמד.",
            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
        )
        val bp = bossPair
        if (bp != null) {
            val boss = bp.first
            val row = bp.second
            val portrait = rememberNamedPainter("boss_${boss.sheet}_${boss.index}")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = HColors.Ink),
                border = BorderStroke(3.dp, if (row.defeated) HColors.Moss else HColors.Danger)
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(110.dp).height(160.dp).clip(RoundedCornerShape(16.dp)).background(HColors.SlateLight),
                        contentAlignment = Alignment.Center
                    ) {
                        if (portrait != null) {
                            Image(painter = portrait, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("הבוס של השבוע", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
                        Text(boss.nameHe, color = HColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text(
                            "\"${com.tamir.hamasa.domain.game.Addr.x(if (row.defeated) boss.defeatedHe else boss.quoteHe)}\"",
                            color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                        )
                        if (row.maxHp > 0) {
                            val frac = (row.hp.toFloat() / row.maxHp.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = if (row.defeated) HColors.Moss else HColors.Danger,
                                trackColor = HColors.SlateLight
                            )
                            Text(
                                if (row.defeated) "הובס השבוע." else "${row.hp.coerceAtLeast(0)} מתוך ${row.maxHp} חיים",
                                color = HColors.Parchment, style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Text("איך פוגעים: ${com.tamir.hamasa.domain.game.Addr.x(boss.howToHitHe)}", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Text("כל הבוסים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
        for (rowBosses in BossCatalog.all.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in rowBosses) {
                    RosterCell(b, defeats[b.id] ?: 0, isThisWeek = bp?.first?.id == b.id, modifier = Modifier.weight(1f))
                }
                // keep the cells the same width on a short last row
                for (i in rowBosses.size until 3) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RosterCell(boss: Boss, timesBeaten: Int, isThisWeek: Boolean, modifier: Modifier = Modifier) {
    val portrait = rememberNamedPainter("boss_${boss.sheet}_${boss.index}")
    Card(
        modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (isThisWeek) HColors.Gold else HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
                if (portrait != null) {
                    Image(painter = portrait, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                boss.nameHe, color = HColors.Parchment, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2
            )
            Text(
                when (timesBeaten) {
                    0 -> "עוד לא הובס"
                    1 -> "הובס פעם אחת"
                    else -> "הובס $timesBeaten פעמים"
                },
                color = if (timesBeaten > 0) HColors.Moss else HColors.ParchmentDim, fontSize = 12.sp, textAlign = TextAlign.Center
            )
        }
    }
}
