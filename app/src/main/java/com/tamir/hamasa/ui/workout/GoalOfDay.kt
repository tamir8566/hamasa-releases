package com.tamir.hamasa.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.ui.theme.HColors

/**
 * "עמדת במטרת היום": no longer a question at the finish. The goal is met when every set of the session was done
 * (a session without sets has no goal to judge). Feeds the same bonus the old checkbox gave.
 */
object GoalOfDay {
    fun met(sets: List<SetLogEntity>): Boolean = sets.isNotEmpty() && sets.all { it.completed }

    fun lineHe(sets: List<SetLogEntity>): String? {
        if (sets.isEmpty()) return null
        val done = sets.count { it.completed }
        return if (done == sets.size) "עמדת במטרת היום. כל הסטים בוצעו."
        else "לא עמדת במטרת היום. בוצעו $done מתוך ${sets.size} סטים."
    }
}

@Composable
fun GoalOfDayCard(sessionId: Long) {
    val sets by produceState<List<SetLogEntity>>(initialValue = emptyList(), key1 = sessionId) {
        value = runCatching { AppGraph.db.sessions().setsForOnce(sessionId) }.getOrDefault(emptyList())
    }
    val line = GoalOfDay.lineHe(sets) ?: return
    val met = GoalOfDay.met(sets)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (met) HColors.Moss else HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(line, color = if (met) HColors.Moss else HColors.Parchment, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
