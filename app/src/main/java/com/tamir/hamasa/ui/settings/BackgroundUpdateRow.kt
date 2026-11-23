package com.tamir.hamasa.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.ui.theme.HColors

private fun ignoringBattery(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

/**
 * Background updates need the phone to let the app wake up while closed (Samsung puts apps to sleep).
 * One button that asks the system to stop restricting the app's battery; hidden once allowed.
 */
@SuppressLint("BatteryLife")
@Composable
fun BackgroundUpdateRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ignoringBattery(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = ignoringBattery(context)
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(6.dp))
        if (allowed) {
            Text("עדכון כשהאפליקציה סגורה: פעיל", color = HColors.ParchmentDim, fontSize = 13.sp)
            return@Column
        }
        Text(
            "כדי שהעדכון יגיע גם כשהאפליקציה סגורה, הטלפון צריך לא להרדים אותה.",
            color = HColors.ParchmentDim, fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                val ok = runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                if (!ok) runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(Addr.t("אפשר עדכון ברקע", "אפשרי עדכון ברקע"), color = HColors.Gold, fontWeight = FontWeight.Bold)
        }
    }
}
