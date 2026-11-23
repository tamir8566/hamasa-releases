package com.tamir.hamasa.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.repo.StoryChapter
import com.tamir.hamasa.data.repo.StoryLog

/**
 * "אימון מסופר" in "ספר הקרבות": the full chapter this session told. Shows nothing for sessions without a chapter.
 */
@Composable
fun StoryChapterCard(sessionId: Long) {
    val chapter by produceState<StoryChapter?>(initialValue = null, key1 = sessionId) {
        value = runCatching { StoryLog.chapterFor(sessionId) }.getOrNull()
    }
    val c = chapter ?: return
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = com.tamir.hamasa.ui.theme.HColors.Ink),
        border = BorderStroke(2.dp, com.tamir.hamasa.ui.theme.HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.tamir.hamasa.domain.game.CharacterNames.g(c.title), color = com.tamir.hamasa.ui.theme.HColors.Gold, style = MaterialTheme.typography.titleMedium)
            c.lines.forEach { line ->
                Text(com.tamir.hamasa.domain.game.CharacterNames.g(line), color = com.tamir.hamasa.ui.theme.HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
