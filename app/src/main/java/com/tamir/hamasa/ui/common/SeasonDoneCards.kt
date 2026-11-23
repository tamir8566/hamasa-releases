package com.tamir.hamasa.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.repo.ChallengeCenter
import com.tamir.hamasa.data.repo.EventBoss
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.SeasonVoice
import com.tamir.hamasa.domain.game.SeasonalEvents
import com.tamir.hamasa.domain.game.WeeklyChallenges
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.theme.HColors

/**
 * Done screen: what this session did to the seasonal event boss, and the weekly challenge when it was completed by
 * this session. Both come from SeasonHooks (run inside finishSession) and are kept in memory per session.
 */
@Composable
fun SeasonDoneCards(sessionId: Long) {
    val hit = remember(sessionId) { EventBoss.hitFor(sessionId) }
    val challenge = remember(sessionId) { ChallengeCenter.completedIn(sessionId) }
    if (hit != null && hit.damage > 0) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
            border = BorderStroke(2.dp, if (hit.defeatedNow) HColors.Gold else HColors.Ember)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${hit.eventNameHe}: ${hit.bossNameHe}", color = HColors.Ember, style = MaterialTheme.typography.titleLarge)
                Text("${hit.damage} נזק.", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                if (hit.defeatedNow) {
                    Text(com.tamir.hamasa.domain.game.Addr.x(hit.defeatedHe), color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("+${SeasonalEvents.DEFEAT_XP} ניסיון, +${SeasonalEvents.DEFEAT_GOLD} זהב", color = HColors.Gold, style = MaterialTheme.typography.bodyLarge)
                    BadgeCatalog.byId(hit.badgeId)?.let { def ->
                        Text("תג של החג: ${com.tamir.hamasa.domain.game.CharacterNames.g(def.nameHe)}", color = HColors.Legendary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("נשארו לו ${hit.hpAfter} מתוך ${hit.maxHp}.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (challenge != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
            border = BorderStroke(2.dp, HColors.Gold)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("אתגר השבוע הושלם", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                Text(com.tamir.hamasa.domain.game.Addr.x(challenge), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(SeasonVoice.challengeDone(Days.weekStartOf(Days.today())), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                Text("+${WeeklyChallenges.REWARD_XP} ניסיון, +${WeeklyChallenges.REWARD_GOLD} זהב", color = HColors.Gold, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
