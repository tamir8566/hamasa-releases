package com.tamir.hamasa.ui.settings

import android.content.Intent
import android.provider.Settings
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tamir.hamasa.data.health.PhoneSleep
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.ui.theme.HColors

/**
 * "שינה אוטומטית מהטלפון" (data/health/PhoneSleep): the night is taken from the phone's screen-off stretch, with no
 * confirm tap in Samsung Health. Needs the one-time "usage access" switch; the button opens that settings screen and
 * the row re-checks when he comes back.
 */
@Composable
fun PhoneSleepRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(PhoneSleep.hasAccess(context)) }
    LifecycleResumeEffect(Unit) {
        val now = PhoneSleep.hasAccess(context)
        if (now && !on) WatchSync.syncSoon(context, force = true)
        on = now
        onPauseOrDispose { }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("שינה אוטומטית מהטלפון", color = HColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (on) {
            Text(
                "פועל. בלילות שהשעון לא רשם, השינה נלקחת מהזמן שמסך הטלפון היה כבוי בלילה. בלי ללחוץ על כלום.",
                color = HColors.Moss, fontSize = 13.sp
            )
        } else {
            Text(
                "בלילות שהשעון לא רשם, השינה תילקח מהזמן שמסך הטלפון היה כבוי בלילה, בלי לאשר כלום בסמסונג הלת'. " +
                    "צריך פעם אחת להפעיל \"גישה לנתוני שימוש\" לאפליקציה.",
                color = HColors.ParchmentDim, fontSize = 13.sp
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("הפעלת שינה אוטומטית", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
        }
    }
}
