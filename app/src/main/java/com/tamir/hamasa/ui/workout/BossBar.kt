package com.tamir.hamasa.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.ui.theme.HColors
import kotlin.math.roundToInt

private val StripShape = RoundedCornerShape(16.dp)

/**
 * Pinned strip above the live workout: "הקרב הקשה" (when this session is the mid-week battle) and the
 * slim boss bar (whenever the weekly boss is open and standing, with its weakness). Each counted set shakes the bar
 * and flashes it.
 */
@Composable
fun BattleStrip(bar: BossBarState?, hardBattle: Boolean) {
    if (bar == null && !hardBattle) return
    Column(
        Modifier.fillMaxWidth().background(HColors.Ink).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (hardBattle) HardBattleLine()
        if (bar != null) BossBarCard(bar)
    }
}

@Composable
private fun HardBattleLine() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(StripShape)
            .background(HColors.Ember.copy(alpha = 0.25f))
            .border(2.dp, HColors.Ember, StripShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("הקרב הקשה", color = HColors.Ember, fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text("ניסיון כפול על האימון הזה", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BossBarCard(bar: BossBarState) {
    val shake = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    var seenHits by remember { mutableIntStateOf(-1) }
    LaunchedEffect(bar.hits) {
        val first = seenHits < 0
        val hit = !first && bar.hits > seenHits
        seenHits = bar.hits
        if (hit) {
            flash.snapTo(1f)
            for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(x, tween(45))
            flash.animateTo(0f, tween(450))
        }
    }
    val fraction by animateFloatAsState(
        targetValue = if (bar.maxHp > 0) bar.hp.toFloat() / bar.maxHp else 0f,
        animationSpec = tween(600),
        label = "boss_hp"
    )
    val bg = lerp(HColors.SlateLight, HColors.Danger, flash.value * 0.45f)

    Column(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(shake.value.roundToInt(), 0) }
            .clip(StripShape)
            .background(bg)
            .border(2.dp, HColors.Danger, StripShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bar.nameHe, color = HColors.Parchment, fontWeight = FontWeight.Black, fontSize = 16.sp)
                if (bar.returningTimes > 0) {
                    Text("אויב חוזר · חזק יותר", color = HColors.Ember, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Text(bar.phase.nameHe, color = HColors.Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Text("${bar.hp} / ${bar.maxHp}", color = HColors.Parchment, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(HColors.Slate)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(HColors.Danger)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "סיבובים: ${bar.roundsLeft} מתוך ${bar.rounds}",
                color = if (bar.roundsLeft > 0) HColors.Gold else HColors.ParchmentDim,
                style = MaterialTheme.typography.labelLarge
            )
            Text("הגנה ${bar.defense}", color = HColors.Sky, style = MaterialTheme.typography.labelLarge)
        }
        // "קרבות בוס בשלבים": what decides the damage in this phase
        Text(
            bar.phaseRule.ifEmpty { "נזק: עליון ${bar.upperHit}, רגליים ${bar.lowerHit}" },
            color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge
        )
        // "כל בוס נפגע משילוב אחר": the weakness, what this session has of it so far, and Shambiuluk's hint
        if (bar.weaknessHe.isNotEmpty()) {
            Text(bar.weaknessHe, color = HColors.Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (bar.weaknessStatusHe.isNotEmpty()) {
                Text(bar.weaknessStatusHe, color = HColors.Parchment, style = MaterialTheme.typography.labelLarge)
            }
            if (bar.hits == 0 && bar.hintHe.isNotEmpty()) {
                Text(bar.hintHe, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val line = when {
            bar.defeated -> com.tamir.hamasa.domain.game.Addr.t("הבוס על הקרשים. סיים את האימון כדי לגמור אותו.", "הבוס על הקרשים. סיימי את האימון כדי לגמור אותו.")
            bar.roundsLeft == 0 -> "הסיבובים נגמרו. הסטים הבאים לא פוגעים. הסיבולת מוסיפה סיבובים."
            bar.phaseLine != null -> bar.phaseLine
            bar.hits == 0 -> bar.taunt
            else -> null
        }
        if (line != null) Text(line, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
    }
}
