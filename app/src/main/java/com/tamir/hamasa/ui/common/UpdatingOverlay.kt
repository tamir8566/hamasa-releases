package com.tamir.hamasa.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tamir.hamasa.data.update.UpdateCenter
import com.tamir.hamasa.ui.theme.HColors

/**
 * Full screen "מתעדכן" while an update installs with the app open (only right after the app starts; otherwise
 * updates wait until the app is left). The new version reopens the app by itself (UpdateReplacedReceiver).
 */
@Composable
fun UpdatingOverlay() {
    val on by UpdateCenter.foregroundInstall.collectAsState()
    if (!on) return
    val build by UpdateCenter.updatingBuild.collectAsState()
    val downloading by UpdateCenter.downloading.collectAsState()
    val progress by UpdateCenter.progress.collectAsState()
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(HColors.Ink).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("המסע הגדול", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(24.dp))
            if (downloading && progress > 0f) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = HColors.Gold, trackColor = HColors.Slate)
            } else {
                CircularProgressIndicator(color = HColors.Gold, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (build > 0) "מתעדכן לגרסה $build" else "מתעדכן",
                color = HColors.Parchment, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "רגע אחד. האפליקציה תיסגר ותיפתח מחדש לבד.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
            )
        }
    }
}
