package com.tamir.hamasa.ui.settings

import android.content.Intent
import android.net.Uri
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
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.ui.workout.RestBubbleService

/**
 * "עיגול מנוחה מעל אפליקציות": while he is in another app during a rest, the timer floats as a small circle.
 * Needs the one-time "הצגה מעל אפליקציות אחרות" switch; the button opens it and the row re-checks on return.
 */
@Composable
fun RestBubbleRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(RestBubbleService.canDraw(context)) }
    LifecycleResumeEffect(Unit) {
        on = RestBubbleService.canDraw(context)
        onPauseOrDispose { }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("טיימר מנוחה מעל אפליקציות", color = HColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (on) {
            Text(
                "פועל. כשאתה יוצא מהאפליקציה באמצע מנוחה, הטיימר צף על המסך כעיגול קטן. לחיצה עליו מחזירה לאימון, וגוררים אותו לאן שנוח.",
                color = HColors.Moss, fontSize = 13.sp
            )
        } else {
            Text(
                "כשאתה יוצא מהאפליקציה באמצע מנוחה, הטיימר יצוף על המסך כעיגול קטן. צריך פעם אחת להפעיל \"הצגה מעל אפליקציות אחרות\".",
                color = HColors.ParchmentDim, fontSize = 13.sp
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("הפעלת העיגול הצף", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
        }
    }
}
