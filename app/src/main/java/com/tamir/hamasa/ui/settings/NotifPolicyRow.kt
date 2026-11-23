package com.tamir.hamasa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.NotifPolicy
import com.tamir.hamasa.ui.theme.HColors

/** "רק ההתראות ההכרחיות": only "updated" and "rest over" are sent; everything else stays quiet. */
@Composable
fun NotifPolicyRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(NotifPolicy.essentialOnly(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("רק ההתראות ההכרחיות", color = HColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Switch(
                checked = on,
                onCheckedChange = { v -> on = v; NotifPolicy.setEssentialOnly(context, v) },
                colors = SwitchDefaults.colors(checkedThumbColor = HColors.Ink, checkedTrackColor = HColors.Gold)
            )
        }
        Text(
            if (on) "רק שתי התראות: האפליקציה עודכנה, והמנוחה נגמרה. בלי תזכורות, בלי כרטיס הסט הבא, בלי ספירה לאחור, בלי תשובות השף."
            else "כל ההתראות: תזכורות, הסט הבא בשעון, ספירה לאחור של המנוחה, תשובות השף.",
            color = HColors.ParchmentDim, fontSize = 13.sp
        )
    }
}
