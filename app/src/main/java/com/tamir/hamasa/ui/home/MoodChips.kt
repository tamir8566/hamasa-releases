package com.tamir.hamasa.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch

private val moodIcons: List<ImageVector> = listOf(
    Icons.Filled.SentimentVeryDissatisfied,
    Icons.Filled.SentimentDissatisfied,
    Icons.Filled.SentimentNeutral,
    Icons.Filled.SentimentSatisfied,
    Icons.Filled.SentimentVerySatisfied
)

/** Today's mood 1..5 from the profile, or 0 when not chosen today. */
fun todayMood(p: Profile?): Int = if (p != null && p.moodDay == Days.today()) p.mood else 0

/**
 * "מצב הרוח של היום": five mood chips drawn with Material icons (the app has no emojis).
 * Choosing one stores it in Prefs (mood + moodDay); Shambiuluk's line follows the mood.
 */
@Composable
fun MoodChips(profile: Profile?) {
    val scope = rememberCoroutineScope()
    val selected = todayMood(profile)
    Column(Modifier.fillMaxWidth()) {
        Text("מצב הרוח של היום", color = HColors.ParchmentDim, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (mood in 1..5) {
                val on = mood == selected
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) HColors.Gold else HColors.SlateLight)
                        .border(2.dp, if (on) HColors.Ink else HColors.GoldDeep, RoundedCornerShape(16.dp))
                        .clickable {
                            val today = Days.today()
                            scope.launch { AppGraph.prefs.update { it.copy(mood = mood, moodDay = today) } }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(moodIcons[mood - 1], contentDescription = Voice.moodNames[mood],
                        tint = if (on) HColors.Ink else HColors.Parchment, modifier = Modifier.size(30.dp))
                    Text(Voice.moodNames[mood], color = if (on) HColors.Ink else HColors.Parchment,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (selected > 0) {
            val line = androidx.compose.runtime.remember(selected) { Voice.forMood(selected).orEmpty() }
            Spacer(Modifier.height(6.dp))
            Text(line, color = HColors.Parchment, fontSize = 15.sp)
        }
    }
}
