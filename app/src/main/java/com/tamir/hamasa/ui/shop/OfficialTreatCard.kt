package com.tamir.hamasa.ui.shop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.repo.OfficialTreat
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch

/**
 * "פינוק כפרס רשמי" (spec v2: "ארוחת פינוק שנקנתה בזהב נרשמת כפרס, לא ככישלון"): a treat meal bought with gold.
 * It goes to "יומן פרסים" like any reward and into today's food log marked "פינוק רשמי".
 */
@Composable
fun OfficialTreatCard(gold: Int) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(3.dp, HColors.Gold),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("פינוק רשמי", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            Text(
                "ארוחת פינוק שנקנית בזהב. נרשמת ביומן האוכל כפרס, לא ככישלון. מחיר מומלץ: ${OfficialTreat.DEFAULT_PRICE} זהב.",
                color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = { open = true },
                enabled = !busy,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HColors.Gold, contentColor = HColors.Ink,
                    disabledContainerColor = Color(0xFF3A414A), disabledContentColor = HColors.ParchmentDim
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("פינוק רשמי", fontSize = 17.sp, fontWeight = FontWeight.Black) }
        }
    }

    if (open) {
        var name by remember { mutableStateOf("") }
        var kcal by remember { mutableStateOf("") }
        var price by remember { mutableStateOf(OfficialTreat.DEFAULT_PRICE.toString()) }
        val priceInt = price.toIntOrNull() ?: 0
        val kcalInt = kcal.toIntOrNull()
        val valid = name.isNotBlank() && priceInt > 0 && kcalInt != null && !busy
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Gold,
            textContentColor = HColors.Parchment,
            title = { Text("פינוק רשמי", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("מה הפינוק") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = kcal, onValueChange = { v -> kcal = v.filter { it.isDigit() }.take(5) },
                        label = { Text("קלוריות בערך") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = price, onValueChange = { v -> price = v.filter { it.isDigit() }.take(5) },
                        label = { Text("מחיר בזהב") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                    )
                    Text("בקופה: $gold זהב.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid && gold >= priceInt,
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = runCatching { OfficialTreat.buy(name, kcalInt ?: 0, priceInt) }.getOrDefault(false)
                            busy = false
                            open = false
                            message = if (ok) OfficialTreat.boughtLineHe() else ShopRules.noGold()
                        }
                    }
                ) { Text(com.tamir.hamasa.domain.game.Addr.t("קנה", "קני") + " ב-$priceInt זהב", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { if (!busy) open = false }) { Text("ביטול", color = HColors.Parchment) } }
        )
    }

    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Gold,
            textContentColor = HColors.Parchment,
            title = { Text(com.tamir.hamasa.ui.common.rememberCharacterNameHe(), fontWeight = FontWeight.Bold) },
            text = { Text(m, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("נרשם", color = HColors.Gold, fontWeight = FontWeight.Bold) } }
        )
    }
}
