package com.tamir.hamasa.ui.weekly

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.WeeklyBox
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WeeklySummaryBannerViewModel : ViewModel() {
    /** Chests waiting sealed in the end-of-week box. */
    val sealedCount: StateFlow<Int> = AppGraph.prefs.profile
        .map { WeeklyBox.decode(it.sealedChests).size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        // Stability: the monthly streak shields are granted on the first app open of a month as well
        // (not only at the first workout), so they are there before a day can be missed.
        viewModelScope.launch { runCatching { AppGraph.game.grantMonthlyShields() } }
    }
}

/**
 * Small Home banner from Friday morning until Saturday night: "הסיכום השבועי מחכה".
 * [onOpen] is Home's existing history callback; the weekly summary is opened from the top of the history screen.
 */
@Composable
fun WeeklySummaryBanner(onOpen: () -> Unit) {
    val vm: WeeklySummaryBannerViewModel = viewModel()
    val sealed by vm.sealedCount.collectAsStateWithLifecycle()
    if (!WeeklyBox.isSummaryTime()) return
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.Gold)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("הסיכום השבועי מחכה", color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text(
                if (sealed > 0) com.tamir.hamasa.domain.game.Addr.t("וגם קופסה עם $sealed תיבות סגורות. לחץ כאן.", "וגם קופסה עם $sealed תיבות סגורות. לחצי כאן.") else com.tamir.hamasa.domain.game.Addr.t("השבוע במספרים. ופסק דין. לחץ כאן.", "השבוע במספרים. ופסק דין. לחצי כאן."),
                color = HColors.Parchment,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}
