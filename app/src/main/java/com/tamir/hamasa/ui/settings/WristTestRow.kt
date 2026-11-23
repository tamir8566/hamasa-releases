package com.tamir.hamasa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.ui.workout.WristCard

/**
 * "בדיקת רטט בשעון": one button that sends the same alerting notification the end of a rest sends, so he can
 * check on the spot whether the watch buzzes at all.
 */
@Composable
fun WristTestRow() {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("בדיקת רטט בשעון", color = HColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "שולח לשעון את אותה התראה שמגיעה בסוף מנוחה: רטט ארוך של שתי שניות.",
            color = HColors.ParchmentDim, fontSize = 13.sp
        )
        OutlinedButton(
            onClick = {
                val ok = WristCard.testBuzz(context)
                result = if (ok) "נשלח. השעון אמור לרטוט עכשיו." else "לא נשלח. ההתראות של האפליקציה כבויות בטלפון."
            },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("רטט בשעון עכשיו", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
        result?.let { Text(it, color = HColors.Moss, fontSize = 13.sp) }
    }
}
