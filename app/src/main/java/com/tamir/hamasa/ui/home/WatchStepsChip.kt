package com.tamir.hamasa.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.theme.HColors
import java.util.Locale

/**
 * Stage 3: "8,412 צעדים היום" from the watch, only when it is connected and today has a count.
 * Under it, today's watch import line ("מהשעון: הליכה של 42 דקות...") when there was one.
 */
@Composable
fun WatchStepsChip(modifier: Modifier = Modifier) {
    val st by WatchStore.state.collectAsState()
    if (!st.connected) return
    val today = Days.today()
    val steps = st.steps[today]?.total ?: return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HColors.Ink,
            border = BorderStroke(2.dp, HColors.GoldDeep)
        ) {
            Text(
                "${String.format(Locale.US, "%,d", steps)} צעדים היום",
                color = HColors.Parchment, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        if (st.lastImportDay == today && st.lastImportHe.isNotBlank()) {
            Text(
                com.tamir.hamasa.domain.game.Addr.x(st.lastImportHe), color = HColors.ParchmentDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
