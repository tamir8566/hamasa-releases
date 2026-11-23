package com.tamir.hamasa.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.update.UpdateCenter
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.delay

/**
 * Top-of-Home update line. While a self-update runs: a small non-blocking "מעדכן לגרסה N…" line.
 * On the first open after a self-update: "עודכן לגרסה N." for a few seconds (tap hides it).
 * Otherwise it falls back to the regular [UpdateBanner] (tap to install), which renders nothing
 * when there is no newer build, so this is safe to place unconditionally.
 */
@Composable
fun AutoUpdateStrip(modifier: Modifier = Modifier) {
    val updating by UpdateCenter.updatingBuild.collectAsStateWithLifecycle()
    val justUpdated by UpdateCenter.justUpdated.collectAsStateWithLifecycle()
    val downloading by UpdateCenter.downloading.collectAsStateWithLifecycle()
    val progress by UpdateCenter.progress.collectAsStateWithLifecycle()

    if (updating > 0) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(HColors.SlateLight)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("מעדכן לגרסה $updating…", color = HColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (downloading) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = HColors.Gold,
                    trackColor = HColors.Ink
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        return
    }

    if (justUpdated > 0) {
        LaunchedEffect(justUpdated) {
            delay(8_000L)
            UpdateCenter.dismissJustUpdated()
        }
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(HColors.SlateLight)
                .clickable { UpdateCenter.dismissJustUpdated() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("עודכן לגרסה $justUpdated.", color = HColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        return
    }

    UpdateBanner(modifier)
}
