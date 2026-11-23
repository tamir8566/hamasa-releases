package com.tamir.hamasa.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.repo.GhostSet
import com.tamir.hamasa.data.repo.ShadowRun
import com.tamir.hamasa.data.repo.StoryChapter
import com.tamir.hamasa.data.repo.StoryLog
import com.tamir.hamasa.data.repo.StoryTeller
import com.tamir.hamasa.data.repo.V2Results
import com.tamir.hamasa.domain.game.StoryChapters
import com.tamir.hamasa.ui.map.journeyStations
import com.tamir.hamasa.ui.map.regionIndex
import com.tamir.hamasa.ui.map.regionName
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private val V2CardShape = RoundedCornerShape(20.dp)

/** "אימון מסופר" on the live screen: the chapter and how many of its lines the completed sets revealed. */
data class StoryLive(val title: String, val lines: List<String>, val revealed: Int) {
    val shown: List<String> get() = lines.take(revealed.coerceIn(0, lines.size))
    val hiddenCount: Int get() = (lines.size - revealed).coerceAtLeast(0)
}

/**
 * Live bookkeeping of the v2 ideas for one workout: the story chapter ("אימון מסופר") and the ghost of the
 * previous comparable session ("צל של אתמול"). Purely informational: never changes a target, weight or rep.
 * Used from the view model's main-thread coroutines only.
 */
class WorkoutV2Live(private val sessionId: Long) {
    private val _story = MutableStateFlow<StoryLive?>(null)
    val story: StateFlow<StoryLive?> = _story

    /** exerciseId -> the ghost's sets in order (missing = no ghost for that exercise). */
    private val _ghosts = MutableStateFlow<Map<String, List<GhostSet>>>(emptyMap())
    val ghosts: StateFlow<Map<String, List<GhostSet>>> = _ghosts

    private var session: SessionEntity? = null
    private var chapter: StoryChapter? = null
    private var lastSets: List<SetLogEntity> = emptyList()
    private val ghostTried = HashSet<String>()
    private var loaded = false

    suspend fun load() {
        val s = AppGraph.db.sessions().byId(sessionId) ?: return
        session = s
        val station = runCatching { journeyStations().first() }.getOrDefault(0)
        val name = regionName(station)
        chapter = runCatching { StoryTeller.ensure(sessionId, regionIndex(station), name) }.getOrNull()
        loaded = true
        refresh(AppGraph.db.sessions().setsForOnce(sessionId))
        // optional AI chapter, once, only before the first set (offline chapter stays on any failure)
        if (s.status == SessionStatus.ACTIVE && lastSets.none { it.completed }) {
            runCatching { StoryTeller.tryAi(sessionId, name) }
            chapter = runCatching { StoryLog.chapterFor(sessionId) }.getOrNull() ?: chapter
            refresh(lastSets)
        }
    }

    suspend fun onSets(sets: List<SetLogEntity>) {
        lastSets = sets
        if (!loaded) return
        refresh(sets)
    }

    private suspend fun refresh(sets: List<SetLogEntity>) {
        lastSets = sets
        val c = chapter
        if (c != null) {
            _story.value = StoryLive(c.title, c.lines, StoryChapters.revealed(sets.count { it.completed }, sets.size, c.lines.size))
        }
        val s = session ?: return
        val missing = sets.map { it.exerciseId }.distinct().filter { it !in ghostTried }
        if (missing.isNotEmpty()) {
            ghostTried.addAll(missing)
            val found = runCatching { ShadowRun.ghostsFor(s, missing) }.getOrDefault(emptyMap())
            if (found.isNotEmpty()) _ghosts.value = _ghosts.value + found
        }
    }
}

// ---------------------------------------------------------------- live screen

@Composable
fun StoryCard(story: StoryLive) {
    // the whole revealed part of the chapter by default: a story line read alone, out of context, is not understood
    var all by rememberSaveable { mutableStateOf(true) }
    val shown = story.shown
    Card(shape = V2CardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(com.tamir.hamasa.domain.game.Addr.x(story.title), color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (shown.size > 1) {
                    TextButton(onClick = { all = !all }) {
                        Text(if (all) "רק השורה החדשה" else "כל הפרק", color = HColors.Sky, fontWeight = FontWeight.Bold)
                    }
                }
            }
            val visible = if (all) shown else shown.takeLast(1)
            visible.forEach { line ->
                Text(com.tamir.hamasa.domain.game.CharacterNames.g(line), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            }
            if (story.hiddenCount > 0) {
                Text(if (story.hiddenCount == 1) "עוד שורה אחת. הסט האחרון חושף אותה." else "עוד ${story.hiddenCount} שורות. כל סט מקדם את הסיפור.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "60×8", "45 שנ'" or "12 חזרות". */
private fun ghostText(g: GhostSet, unit: String): String = when {
    unit == "sec" -> "${g.reps} שנ'"
    g.weight > 0f -> "${formatKg(g.weight)}×${g.reps}"
    else -> "${g.reps} חזרות"
}

/** One line under a set: what the ghost did in the matching set, and after the set who won. */
@Composable
fun ShadowLine(ghost: GhostSet?, set: SetLogEntity, unit: String) {
    if (ghost == null) return
    // the goal he chose decides who wins when the weights differ (ShadowRun.outcome)
    val focus by produceState(initialValue = com.tamir.hamasa.data.prefs.Focus.LOSE_WEIGHT) {
        value = runCatching { AppGraph.prefs.current().focus }.getOrDefault(com.tamir.hamasa.data.prefs.Focus.LOSE_WEIGHT)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("הצל: " + ghostText(ghost, unit), color = HColors.ParchmentDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (set.completed) {
            when (ShadowRun.outcome(set.reps, set.weight, ghost, focus)) {
                ShadowRun.NOT_COMPARABLE -> Text("לא משווים בין משקלים שונים", color = HColors.ParchmentDim, fontSize = 13.sp)
                1 -> Text("עברת אותו", color = HColors.Moss, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                0 -> Text("תיקו", color = HColors.ParchmentDim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                else -> Text("הצל לפניך", color = HColors.Ember, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- done screen

/**
 * The v2 cards of the done screen: the shadow summary, skill "ריכוז" and the full chapter of "אימון מסופר".
 */
@Composable
fun V2DoneCards(sessionId: Long) {
    val shadow by produceState<Pair<Int, Int>?>(initialValue = null, key1 = sessionId) {
        value = runCatching { ShadowRun.summary(sessionId) }.getOrNull()
    }
    val chapter by produceState<StoryChapter?>(initialValue = null, key1 = sessionId) {
        value = runCatching { StoryLog.chapterFor(sessionId) }.getOrNull()
    }
    val focusXp = remember(sessionId) { V2Results.last?.takeIf { it.sessionId == sessionId }?.focusXp ?: 0 }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shadow?.let { (wins, compared) ->
            V2Card(title = "צל של אתמול", border = HColors.Sky) {
                Text(com.tamir.hamasa.domain.game.CharacterNames.g(ShadowRun.summaryLineHe(wins, compared)), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (focusXp > 0) {
            V2Card(title = "ריכוז", border = HColors.Gold) {
                Text("השיאים נספרו פעמיים: +$focusXp ניסיון. ריכוז זה כישור. גם זה.", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            }
        }
        chapter?.let { c ->
            V2Card(title = com.tamir.hamasa.domain.game.Addr.x(c.title), border = HColors.GoldDeep) {
                c.lines.forEach { line -> Text(com.tamir.hamasa.domain.game.CharacterNames.g(line), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge) }
                Text("הפרק נשמר בספר הקרבות.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun V2Card(title: String, border: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Card(shape = V2CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
