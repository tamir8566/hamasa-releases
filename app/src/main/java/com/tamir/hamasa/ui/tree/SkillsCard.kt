package com.tamir.hamasa.ui.tree

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.SkillBook
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.SkillId
import com.tamir.hamasa.domain.game.Skills
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch

/** Snapshot of "כישורים מיוחדים" for the tree screen. */
private data class SkillsUi(
    val unlocked: Boolean,
    val chosen: SkillId?,
    val switchInDays: Int,
    val readyInDays: Int,
    val hintHe: String?
)

private suspend fun loadSkills(): SkillsUi {
    val p = AppGraph.prefs.current()
    val today = Days.today()
    val chosen = Skills.byName(p.skillId)
    return SkillsUi(
        unlocked = SkillBook.unlocked(),
        chosen = chosen,
        switchInDays = if (chosen == null) 0 else Skills.daysUntilSwitch(p.skillChosenDay, today),
        readyInDays = if (chosen == null) 0 else Skills.daysUntilReady(chosen, p.skillUsedDay, today),
        hintHe = if (chosen == SkillId.CURIOSITY) SkillBook.currentHintHe() else null
    )
}

/**
 * "כישורים מיוחדים" (spec v2: "בוחרים אחד משלושה: ריכוז, סבלנות, סקרנות"): a slim card on the tree screen
 * that opens the choice. Shown only once the tree is open (the caller checks the level gate).
 */
@Composable
fun SkillsCard(modifier: Modifier = Modifier) {
    var refresh by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val ui by produceState<SkillsUi?>(initialValue = null, key1 = refresh) {
        value = runCatching { loadSkills() }.getOrNull()
    }
    val u = ui ?: return
    Card(
        modifier = modifier.fillMaxWidth().clickable { open = true },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, if (u.unlocked) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.5f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("כישורים מיוחדים", color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    !u.unlocked -> "נעול"
                    u.chosen == null -> com.tamir.hamasa.domain.game.Addr.t("בחר כישור", "בחרי כישור")
                    else -> u.chosen.nameHe
                },
                color = if (u.unlocked) HColors.Parchment else HColors.ParchmentDim,
                fontWeight = FontWeight.Bold
            )
        }
    }
    if (open) {
        SkillsDialog(u, onClose = { open = false }, onChanged = { refresh++ })
    }
}

@Composable
private fun SkillsDialog(u: SkillsUi, onClose: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Gold,
        textContentColor = HColors.Parchment,
        title = { Text("כישורים מיוחדים", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!u.unlocked) {
                    Text(
                        "נפתח כשענפי העץ יחד מגיעים לרמה ${Skills.UNLOCK_TOTAL_LEVELS}. שיאים ואימונים מגדלים ענפים. אני מחכה. בסבלנות. לא בכישור.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    Text("בוחרים אחד משלושה. מחליפים פעם ב-${Skills.SWITCH_DAYS} ימים.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
                SkillId.values().forEach { id ->
                    val isChosen = u.chosen == id
                    val canPick = u.unlocked && !isChosen && (u.chosen == null || u.switchInDays == 0)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = HColors.Slate),
                        border = BorderStroke(if (isChosen) 3.dp else 1.dp, if (isChosen) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(id.nameHe, color = if (isChosen) HColors.Gold else HColors.Parchment, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(com.tamir.hamasa.domain.game.Addr.x(id.effectHe), style = MaterialTheme.typography.bodyMedium)
                            if (isChosen && id.active) {
                                val ready = u.readyInDays == 0
                                SkillButton(text = if (ready) com.tamir.hamasa.domain.game.Addr.t("השתמש", "השתמשי") else "עוד ${u.readyInDays} ימים", enabled = ready) {
                                    scope.launch {
                                        message = com.tamir.hamasa.domain.game.CharacterNames.g(runCatching { SkillBook.use() }.getOrDefault("לא עכשיו."))
                                        onChanged()
                                    }
                                }
                            } else if (isChosen) {
                                Text("פעיל. עובד לבד.", color = HColors.Moss, fontWeight = FontWeight.Bold)
                            } else if (u.unlocked) {
                                SkillButton(text = if (canPick) com.tamir.hamasa.domain.game.Addr.t("בחר", "בחרי") else "החלפה בעוד ${u.switchInDays} ימים", enabled = canPick) {
                                    scope.launch {
                                        message = com.tamir.hamasa.domain.game.CharacterNames.g(runCatching { SkillBook.choose(id) }.getOrNull() ?: "עוד לא.")
                                        onChanged()
                                    }
                                }
                            }
                        }
                    }
                }
                u.hintHe?.let { h ->
                    Text(com.tamir.hamasa.domain.game.CharacterNames.g("הרמז האחרון: $h"), color = HColors.Sky, style = MaterialTheme.typography.bodyMedium)
                }
                message?.let { m ->
                    Text(m, color = HColors.Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("סגור", color = HColors.Gold, fontWeight = FontWeight.Bold) } }
    )
}

@Composable
private fun SkillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = HColors.Gold, contentColor = HColors.Ink,
            disabledContainerColor = Color(0xFF3A414A), disabledContentColor = HColors.ParchmentDim
        ),
        modifier = Modifier.fillMaxWidth()
    ) { Text(text, fontWeight = FontWeight.Black) }
}
