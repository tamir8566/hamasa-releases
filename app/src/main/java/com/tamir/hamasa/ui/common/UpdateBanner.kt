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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.update.UpdateCenter
import com.tamir.hamasa.ui.theme.HColors

/**
 * One-line gold strip shown at the top of the Home screen when a newer build is available.
 * Renders nothing otherwise, so it is safe to place unconditionally.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val available by UpdateCenter.available.collectAsStateWithLifecycle()
    val downloading by UpdateCenter.downloading.collectAsStateWithLifecycle()
    val progress by UpdateCenter.progress.collectAsStateWithLifecycle()
    val info = available ?: return
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HColors.Gold)
            .clickable(enabled = !downloading) { UpdateCenter.downloadAndInstall(context) }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            if (downloading) "מוריד גרסה ${info.build}..." else "גרסה ${info.build} מוכנה — " + com.tamir.hamasa.domain.game.Addr.t("לחץ להתקנה", "לחצי להתקנה"),
            color = HColors.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black
        )
        if (downloading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = HColors.Ink,
                trackColor = HColors.GoldDeep
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}
