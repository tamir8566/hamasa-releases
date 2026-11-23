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
import com.tamir.hamasa.data.repo.ProfessionBook
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.GearCatalog
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.ProfessionId
import com.tamir.hamasa.domain.game.Professions
import com.tamir.hamasa.ui.theme.HColors
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val SeasonDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")

/** Snapshot of "מקצוע לשמביולוק" for the tree screen. */
private data class ProfessionUi(
    val unlocked: Boolean,
    val chosen: ProfessionId?,
    val canSwitch: Boolean,
    val nextSwitchDay: Long,     // 0 = a change is allowed now
    val seasonHe: String,
    val ownGearHe: List<String>  // equipped items of the chosen profession's theme
)

private suspend fun loadProfession(): ProfessionUi {
    val p = AppGraph.prefs.current()
    val today = Days.today()
    val unlocked = Professions.unlocked(LevelTable.levelFor(p.xp))
    val chosen = Professions.byName(p.professionId)
    val ownGear = if (chosen == null) emptyList() else runCatching {
        AppGraph.db.game().inventoryOnce()
            .filter { it.equipped }
            .mapNotNull { GearCatalog.byId(it.itemId) }
            .filter { Professions.gearTheme(it) == chosen }
            .map { it.nameHe }
    }.getOrDefault(emptyList())
    return ProfessionUi(
        unlocked = unlocked,
        chosen = chosen,
        canSwitch = Professions.canSwitch(p.professionChosenDay, today),
        nextSwitchDay = if (chosen == null) 0L else Professions.nextSwitchDay(p.professionChosenDay, today),
        seasonHe = Professions.seasonNameHe(today),
        ownGearHe = ownGear
    )
}

private fun dateLabel(day: Long): String = runCatching { Days.of(day).format(SeasonDate) }.getOrDefault("")

/**
 * "מקצוע לשמביולוק" (spec: "לוחם / נווד / נזיר, משנה ציוד ובונוסים, מחליפים פעם בעונה"): a slim card on the
 * tree screen, under "כישורים מיוחדים", that opens the choice. Locked until Professions.UNLOCK_LEVEL.
 */
@Composable
fun ProfessionCard(modifier: Modifier = Modifier) {
    var refresh by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val ui by produceState<ProfessionUi?>(initialValue = null, key1 = refresh) {
        value = runCatching { loadProfession() }.getOrNull()
    }
    val u = ui ?: return
    Card(
        modifier = modifier.fillMaxWidth().clickable { open = true },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, if (u.unlocked) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.5f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("מקצוע", color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    !u.unlocked -> "נעול"
                    u.chosen == null -> com.tamir.hamasa.domain.game.Addr.t("בחר מקצוע", "בחרי מקצוע")
                    else -> u.chosen.displayHe
                },
                color = if (u.unlocked) HColors.Parchment else HColors.ParchmentDim,
                fontWeight = FontWeight.Bold
            )
        }
    }
    if (open) {
        ProfessionDialog(u, onClose = { open = false }, onChanged = { refresh++ })
    }
}

@Composable
private fun ProfessionDialog(u: ProfessionUi, onClose: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Gold,
        textContentColor = HColors.Parchment,
        title = { Text("מקצוע ל" + com.tamir.hamasa.ui.common.rememberCharacterNameHe(), fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!u.unlocked) {
                    Text(Professions.lockedLineHe(), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        "בוחרים אחד משלושה. מחליפים פעם בעונה, עונה היא שלושה חודשים. ההחלפה לא לוקחת כלום: הציוד, הרמות והתגים נשארים.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                    )
                    Text("עכשיו: ${u.seasonHe}.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    if (u.chosen != null && !u.canSwitch && u.nextSwitchDay > 0L) {
                        Text("ההחלפה הבאה מ-${dateLabel(u.nextSwitchDay)}.", color = HColors.Sky, fontWeight = FontWeight.Bold)
                    }
                }
                ProfessionId.values().forEach { id ->
                    val isChosen = u.chosen == id
                    val canPick = u.unlocked && !isChosen && (u.chosen == null || u.canSwitch)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = HColors.Slate),
                        border = BorderStroke(if (isChosen) 3.dp else 1.dp, if (isChosen) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(id.displayHe, color = if (isChosen) HColors.Gold else HColors.Parchment, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(com.tamir.hamasa.domain.game.CharacterNames.g(id.descriptionHe), style = MaterialTheme.typography.bodyMedium)
                            Professions.effectsHe(id).forEach { line ->
                                Text("• " + com.tamir.hamasa.domain.game.CharacterNames.g(line), color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(com.tamir.hamasa.domain.game.Addr.x(Professions.gearRuleHe(id)), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
                            if (isChosen) {
                                Text("פעיל.", color = HColors.Moss, fontWeight = FontWeight.Bold)
                                if (u.ownGearHe.isNotEmpty()) {
                                    Text("ציוד מתאים שעליך: " + u.ownGearHe.joinToString(", "), color = HColors.Moss, style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (u.unlocked) {
                                val label = when {
                                    canPick -> com.tamir.hamasa.domain.game.Addr.t("בחר", "בחרי")
                                    u.nextSwitchDay > 0L -> "החלפה מ-${dateLabel(u.nextSwitchDay)}"
                                    else -> "עוד לא"
                                }
                                ProfessionButton(text = label, enabled = canPick) {
                                    scope.launch {
                                        message = com.tamir.hamasa.domain.game.CharacterNames.g(runCatching { ProfessionBook.choose(id) }.getOrNull() ?: "עוד לא. העונה עוד לא נגמרה.")
                                        onChanged()
                                    }
                                }
                            }
                        }
                    }
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
private fun ProfessionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
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
