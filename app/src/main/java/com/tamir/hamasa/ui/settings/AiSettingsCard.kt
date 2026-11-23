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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.AiClient
import com.tamir.hamasa.data.ai.ClaudeConfig
import com.tamir.hamasa.data.ai.GeminiConfig
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "בינה מלאכותית" card of the settings screen: which provider answers first (data/ai/AiClient) and its status. */
@Composable
fun AiSettingsCard() {
    val profile by AppGraph.prefs.profile.collectAsStateWithLifecycle(initialValue = Profile())
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }

    // AiClient keeps its status in plain fields: look again every few seconds while the card is on screen.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000L)
            tick++
        }
    }

    val choice = profile.aiProvider
    // read the fields again on every tick
    val lastProvider = remember(tick, testing) { AiClient.lastProvider }
    val lastOkAt = remember(tick, testing) { AiClient.lastOkAt }
    val lastViaProxy = remember(tick, testing) { AiClient.lastViaProxy }
    val errorLines = remember(tick, testing) { AiClient.errorLinesHe() }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("בינה מלאכותית", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text(
                com.tamir.hamasa.domain.game.CharacterNames.g("מי עונה קודם: הערכת ארוחות, הערות לג'ים ברו, והשורות של שמביולוק. אם הוא לא עונה, השני מנסה."),
                color = HColors.ParchmentDim, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderOption("אוטומטי", choice == "auto", Modifier.weight(1f)) { setProvider(scope, "auto") }
                ProviderOption("Claude", choice == "claude", Modifier.weight(1f)) { setProvider(scope, "claude") }
                ProviderOption("ג'מיני", choice == "gemini", Modifier.weight(1f)) { setProvider(scope, "gemini") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (ClaudeConfig.enabled || GeminiConfig.enabled)
                    "קודם דרך השרת. אם הוא לא זמין, ישירות: מפתח Claude " + (if (ClaudeConfig.enabled) "יש" else "אין") +
                        ", מפתח ג'מיני " + (if (GeminiConfig.enabled) "יש" else "אין") + "."
                else "הכול עובר דרך השרת. אין מפתחות בטלפון.",
                color = HColors.Parchment, fontSize = 14.sp
            )
            if (!AiClient.enabled) {
                Text("אין אף מפתח. הכל עובד בלי בינה מלאכותית.", color = HColors.Ember, fontSize = 14.sp)
            } else {
                val first = AiClient.orderFor(choice).firstOrNull()
                if (first != null) {
                    Text("עונה קודם: ${AiClient.nameHe(first)}.", color = HColors.Parchment, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            val p = lastProvider
            Text(
                if (p != null && lastOkAt > 0L) "תשובה אחרונה: ${AiClient.nameHe(p)}, ${if (lastViaProxy) "דרך השרת" else "ישירות"}, ${agoHe(lastOkAt)}."
                else "עוד לא הייתה תשובה מאז שהאפליקציה נפתחה.",
                color = HColors.ParchmentDim, fontSize = 13.sp
            )
            val chefMs = remember(tick, testing) { com.tamir.hamasa.data.ai.Chef.lastCallMs }
            if (chefMs > 0L) Text("השף: הקריאה האחרונה לקחה ${(chefMs / 1000f).let { String.format(java.util.Locale.US, "%.1f", it) }} שניות.", color = HColors.ParchmentDim, fontSize = 13.sp)
            if (errorLines.isNotEmpty()) {
                Text("תקלה אחרונה:", color = HColors.Ember, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                for (line in errorLines) Text(line, color = HColors.Ember, fontSize = 13.sp)
            }
            if (AiClient.enabled) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        testing = true
                        testMessage = null
                        scope.launch {
                            val ans = AiClient.generate(
                                listOf(AiClient.Part.Text("ענה במילה אחת בעברית: שלום.")),
                                tier = AiClient.Tier.LITE, temperature = 0.2, maxOutputTokens = 50
                            )
                            val who = AiClient.lastProvider
                            val via = if (AiClient.lastViaProxy) "דרך השרת" else "ישירות"
                            testMessage = if (ans != null && who != null) "${AiClient.nameHe(who)} ענה ($via): ${ans.take(40)}"
                            else "אין תשובה. " + AiClient.errorLinesHe().joinToString(" ")
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (testing) "בודק..." else "בדיקת חיבור", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
            }
            val m = testMessage
            if (m != null) {
                Spacer(Modifier.height(6.dp))
                Text(m, color = HColors.Parchment, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ProviderOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
            modifier = modifier.height(44.dp)
        ) { Text(label, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(24.dp), modifier = modifier.height(44.dp)) {
            Text(label, color = HColors.Parchment, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        }
    }
}

private fun setProvider(scope: kotlinx.coroutines.CoroutineScope, value: String) {
    scope.launch {
        AppGraph.prefs.update { it.copy(aiProvider = value) }
    }
}

private fun agoHe(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L)
    return when {
        min < 1L -> "ממש עכשיו"
        min == 1L -> "לפני דקה"
        min < 60L -> "לפני $min דקות"
        min < 120L -> "לפני שעה"
        else -> "לפני ${min / 60L} שעות"
    }
}
