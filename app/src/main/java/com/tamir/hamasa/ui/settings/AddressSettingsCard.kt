package com.tamir.hamasa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import kotlinx.coroutines.launch
import com.tamir.hamasa.ui.theme.HColors

/**
 * "פנייה" card of the settings screen: how the app addresses the player (Prefs.userGender, "M" = אתה, "F" = את)
 * and which hero walks the journey (Prefs.characterVariant, "M" = שמביולוק, "F" = שמביולוקה).
 * A change is written to Prefs and applied at once to [Addr] / [CharacterNames], and the home-screen widgets refresh.
 */
@Composable
fun AddressSettingsCard(userGender: String, characterVariant: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var gender by remember { mutableStateOf(if (userGender == Addr.FEMALE) Addr.FEMALE else Addr.MALE) }
    var variant by remember { mutableStateOf(CharacterNames.normalize(characterVariant)) }
    LaunchedEffect(userGender) { gender = if (userGender == Addr.FEMALE) Addr.FEMALE else Addr.MALE }
    LaunchedEffect(characterVariant) { variant = CharacterNames.normalize(characterVariant) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("פנייה", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("איך האפליקציה פונה אליך: בלשון זכר או בלשון נקבה.", color = HColors.ParchmentDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressOption("אתה", gender == Addr.MALE, Modifier.weight(1f)) {
                    gender = Addr.MALE
                    Addr.set(Addr.MALE)
                    scope.launch {
                        AppGraph.prefs.update { it.copy(userGender = Addr.MALE, aiMorningLine = "") }
                        runCatching { com.tamir.hamasa.widget.Widgets.refreshNow(context) }
                    }
                }
                AddressOption("את", gender == Addr.FEMALE, Modifier.weight(1f)) {
                    gender = Addr.FEMALE
                    Addr.set(Addr.FEMALE)
                    scope.launch {
                        AppGraph.prefs.update { it.copy(userGender = Addr.FEMALE, aiMorningLine = "") }
                        runCatching { com.tamir.hamasa.widget.Widgets.refreshNow(context) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("הדמות", color = HColors.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("עם מי יוצאים למסע. הגוף, הרמות והציוד נשארים כמו שהם.", color = HColors.ParchmentDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressOption("שמביולוק", variant == CharacterNames.MALE, Modifier.weight(1f)) {
                    variant = CharacterNames.MALE
                    CharacterNames.set(CharacterNames.MALE)
                    scope.launch {
                        AppGraph.prefs.update { it.copy(characterVariant = CharacterNames.MALE, aiMorningLine = "") }
                        runCatching { com.tamir.hamasa.widget.Widgets.refreshNow(context) }
                    }
                }
                AddressOption("שמביולוקה", variant == CharacterNames.FEMALE, Modifier.weight(1f)) {
                    variant = CharacterNames.FEMALE
                    CharacterNames.set(CharacterNames.FEMALE)
                    scope.launch {
                        AppGraph.prefs.update { it.copy(characterVariant = CharacterNames.FEMALE, aiMorningLine = "") }
                        runCatching { com.tamir.hamasa.widget.Widgets.refreshNow(context) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("המשפטים מתעדכנים במסך הבא שנפתח.", color = HColors.ParchmentDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AddressOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
            modifier = modifier.height(44.dp)
        ) { Text(label, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(24.dp), modifier = modifier.height(44.dp)) {
            Text(label, color = HColors.Parchment, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
        }
    }
}
