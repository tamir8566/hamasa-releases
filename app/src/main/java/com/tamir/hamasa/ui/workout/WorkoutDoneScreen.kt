package com.tamir.hamasa.ui.workout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import com.tamir.hamasa.domain.coach.ExerciseCompare
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.AiVoice
import com.tamir.hamasa.data.ai.AiClient
import com.tamir.hamasa.data.ai.VoiceContext
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.repo.BattleLog
import com.tamir.hamasa.data.repo.SessionRewards
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.GearCatalog
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.ModeVoice
import com.tamir.hamasa.domain.game.MuscleHeat
import com.tamir.hamasa.domain.game.Rarity
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.ui.common.DurationEditDialog
import com.tamir.hamasa.ui.common.DurationEditPencil
import com.tamir.hamasa.ui.common.MuscleHeatLegend
import com.tamir.hamasa.ui.common.MuscleHeatmap
import com.tamir.hamasa.ui.common.Sfx
import com.tamir.hamasa.ui.common.Sound
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WorkoutDoneViewModel(private val sessionId: Long) : ViewModel() {
    /** Edits the workout length (1..300 minutes). XP / gold stay; [session] re-emits the new duration. */
    fun editDuration(minutes: Int) {
        viewModelScope.launch { runCatching { AppGraph.workouts.editDuration(sessionId, minutes) } }
    }

    val session: StateFlow<SessionEntity?> =
        AppGraph.workouts.session(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Fallback streak when the rewards holder is empty (e.g. process restarted on this screen). */
    val streak: StateFlow<Int> =
        AppGraph.game.profile.map { it.streak }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Muscle heat of this session (completed sets only); null while loading. */
    val heat: StateFlow<MuscleHeat.HeatResult?> =
        flow { emit(AppGraph.workouts.heatForSession(sessionId)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Snapshot taken once so a recomposition never loses it. */
    val rewards: SessionRewards? = RewardsHolder.last

    /** "הג'ים ברו מסכם": every exercise of this session against its last time (gym sessions; empty otherwise). */
    val compare: StateFlow<List<com.tamir.hamasa.domain.coach.ExerciseCompare.Row>> =
        flow { emit(runCatching { compareRows() }.getOrDefault(emptyList())) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun compareRows(): List<com.tamir.hamasa.domain.coach.ExerciseCompare.Row> {
        val s = AppGraph.db.sessions().byId(sessionId) ?: return emptyList()
        if (s.type != SessionType.GYM) return emptyList()
        val sets = AppGraph.db.sessions().setsForOnce(sessionId)
        val out = ArrayList<com.tamir.hamasa.domain.coach.ExerciseCompare.Row>()
        for ((id, rows) in sets.groupBy { it.exerciseId }) {
            if (rows.none { it.completed && it.reps > 0 }) continue
            val e = AppGraph.db.exercises().byId(id) ?: com.tamir.hamasa.domain.coach.ExerciseCatalog.byId(id)
            val name = e?.nameHe ?: id
            val weighted = e?.unit == "kg"
            // the last DONE session with this exercise that is not this one
            val prev = AppGraph.workouts.lastDoneSessionsSetsFor(id, 3).firstOrNull { it.isNotEmpty() && it.first().sessionId != sessionId }
            com.tamir.hamasa.domain.coach.ExerciseCompare.row(id, name, weighted, rows, prev)?.let { out.add(it) }
        }
        return out
    }

    /** The boss fell in this session: Shambiuluk's line for it, chosen once. */
    val bossDefeatedLine: String? = rewards?.boss?.takeIf { it.defeatedNow }?.let { Voice.bossDefeated(it.nameHe) }

    /** Level up: Shambiuluk's line (chosen once) and what opened on the way. */
    val levelUpLine: String? = rewards?.newLevel?.let { Voice.levelUp(it) }
    val levelUnlocks: List<String> = run {
        val r = rewards
        val lvl = r?.newLevel
        if (r == null || lvl == null) emptyList() else LevelGates.unlocksBetween(if (r.levelFrom > 0) r.levelFrom else lvl - 1, lvl)
    }

    /** Mode "race_clock": today against the previous session of the same type. */
    val raceLine: String? = run {
        val r = rewards
        if (r != null && r.raceCompared) com.tamir.hamasa.domain.game.CharacterNames.g(ModeVoice.raceResult(r.raceDeltaSec)) else null
    }

    /** A mode found by luck at the end of the session. */
    val luckyModeLine: String? = rewards?.luckyModeId?.let { ModeVoice.luckyMode(ModeCatalog.byId(it)?.nameHe ?: it) }

    /**
     * Shambiuluk's closing line, chosen once and written to "ספר הקרבות" (one line per session).
     * A line already stored for this session wins, so coming back shows the same sentence.
     */
    private val fallbackLine: String = bossDefeatedLine ?: Voice.done()

    /**
     * Stage 2 G, "sometimes Gemini": about one session in three (and never over the boss line) the closing line is
     * asked from Gemini. It is used only if it arrives within 4 s; otherwise the regular Voice line. While waiting
     * the line is "" (the screen shows a short placeholder). Whatever is shown is what goes to "ספר הקרבות".
     */
    private val tryAi: Boolean = AiClient.enabled && bossDefeatedLine == null && sessionId % 3L == 0L

    private val _doneLine = MutableStateFlow(if (tryAi) "" else fallbackLine)
    val doneLine: StateFlow<String> = _doneLine

    /** badgeId -> gear item name, for legendary badges that gave Shambiuluk an item. */
    private val _badgeGear = MutableStateFlow<Map<String, String>>(emptyMap())
    val badgeGear: StateFlow<Map<String, String>> = _badgeGear

    init {
        viewModelScope.launch {
            var line = fallbackLine
            if (tryAi) {
                // a line already in the battle log (coming back to this screen) wins without asking again
                val stored = runCatching { BattleLog.lineFor(sessionId) }.getOrNull()
                if (stored == null) {
                    val ctx = runCatching { doneVoiceContext(sessionId) }.getOrNull()
                    val ai = if (ctx == null) null else AiVoice.line(ctx, timeoutMs = 4_000L)
                    if (ai != null) line = ai
                }
            }
            _doneLine.value = line
            runCatching { _doneLine.value = BattleLog.record(sessionId, line) }
            val out = HashMap<String, String>()
            for (id in rewards?.newBadges.orEmpty()) {
                val item = runCatching { AppGraph.game.gearFromBadge(id) }.getOrNull() ?: continue
                out[id] = GearCatalog.byId(item)?.nameHe ?: continue
            }
            if (out.isNotEmpty()) _badgeGear.value = out
        }
    }

    /** Line for a shield that covered a missed day without burning (equipped-gear buff), chosen once. */
    val shieldSavedLine: String = Voice.shieldSaved()

    /** Facts of this session for the Gemini closing line. */
    private suspend fun doneVoiceContext(sessionId: Long): VoiceContext.WorkoutDone {
        val s = AppGraph.db.sessions().byId(sessionId)
        val r = rewards
        val typeHe = when (s?.type) {
            SessionType.GYM -> "מכון"
            SessionType.YOGA -> "יוגה"
            SessionType.WALK -> "הליכה"
            SessionType.MINI -> "אימון זעיר"
            SessionType.RUN -> "ריצה"
            null -> "אימון"
        }
        return VoiceContext.WorkoutDone(
            typeHe = typeHe,
            minutes = ((s?.durationSec ?: 0) / 60).coerceAtLeast(1),
            prs = r?.prs.orEmpty().take(3),
            bossHit = r?.bossHit ?: false,
            bossDefeatedHe = r?.boss?.takeIf { it.defeatedNow }?.nameHe,
            streak = r?.streak ?: AppGraph.prefs.current().streak,
            modeHe = s?.modeId?.let { ModeCatalog.byId(it)?.nameHe }
        )
    }
}

/** "הג'ים ברו מסכם": the headline, then one factual line per exercise, colored by its verdict. */
@Composable
private fun CoachCompareCard(rows: List<com.tamir.hamasa.domain.coach.ExerciseCompare.Row>) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Sky)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("coach_compare"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("הג'ים ברו מסכם", color = HColors.Sky, style = MaterialTheme.typography.titleLarge)
            Text(ExerciseCompare.headlineHe(rows), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            rows.forEach { r ->
                val color = when (r.verdict) {
                    ExerciseCompare.Verdict.UP -> HColors.Moss
                    ExerciseCompare.Verdict.DOWN -> HColors.Danger
                    else -> HColors.ParchmentDim
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        when (r.verdict) { ExerciseCompare.Verdict.UP -> "▲"; ExerciseCompare.Verdict.DOWN -> "▼"; else -> "●" },
                        color = color, style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(ExerciseCompare.lineHe(r), color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** "ציוד: +12 נקודות, +3 זהב" (only the parts that are above zero). */
private fun gearBonusLine(gearXp: Int, gearGold: Int): String {
    val parts = ArrayList<String>()
    if (gearXp > 0) parts.add("+$gearXp נקודות")
    if (gearGold > 0) parts.add("+$gearGold זהב")
    return "ציוד: " + parts.joinToString(", ")
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun WorkoutDoneScreen(sessionId: Long, onClose: () -> Unit, onOpenDetail: () -> Unit = {}) {
    val vm: WorkoutDoneViewModel = viewModel(key = "workout_done_$sessionId") { WorkoutDoneViewModel(sessionId) }
    val session by vm.session.collectAsStateWithLifecycle()
    val profileStreak by vm.streak.collectAsStateWithLifecycle()
    val heat by vm.heat.collectAsStateWithLifecycle()
    val doneLine by vm.doneLine.collectAsStateWithLifecycle()
    val badgeGear by vm.badgeGear.collectAsStateWithLifecycle()
    val rewards = vm.rewards

    val xpTarget = session?.xpEarned ?: rewards?.xp ?: 0
    val goldTarget = session?.goldEarned ?: rewards?.gold ?: 0
    val durationSec = session?.durationSec ?: 0
    // duration edit: finished app sessions only (watch imports "hc:" / "sh:" stay read-only)
    val canEditDuration = session?.let { it.status == SessionStatus.DONE && !WatchSync.isWatchSession(it) } ?: false
    var editingDuration by rememberSaveable { mutableStateOf(false) }
    val streak = rewards?.streak ?: profileStreak

    // Count-up: start at 0, then animate to the real values once they are known.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(xpTarget, goldTarget) { if (xpTarget > 0 || goldTarget > 0) started = true }
    // Fixed closing sound once, then a coin when the gold counter starts (stage 1 D).
    val sfxContext = androidx.compose.ui.platform.LocalContext.current
    var finishPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!finishPlayed) { finishPlayed = true; Sfx.play(sfxContext, Sound.FINISH) }
    }
    var coinPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(started) {
        if (started && !coinPlayed && goldTarget > 0) {
            coinPlayed = true
            kotlinx.coroutines.delay(1100)
            Sfx.play(sfxContext, Sound.COIN)
        }
    }
    val xpShown by animateIntAsState(
        targetValue = if (started) xpTarget else 0,
        animationSpec = tween(durationMillis = 1600),
        label = "xp"
    )
    val goldShown by animateIntAsState(
        targetValue = if (started) goldTarget else 0,
        animationSpec = tween(durationMillis = 1600, delayMillis = 300),
        label = "gold"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "אימון הושלם",
            color = HColors.Gold,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            doneLine.ifEmpty { "..." },
            color = HColors.Parchment,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        rewards?.newLevel?.let { lvl -> LevelUpBanner(lvl, vm.levelUpLine.orEmpty(), vm.levelUnlocks) }

        // mode "race_clock" and a mode found by luck
        vm.raceLine?.let { line -> ModeLineCard(title = "מרוץ נגד השעון", text = line) }
        vm.luckyModeLine?.let { line -> ModeLineCard(title = "מצב חדש", text = line) }
        val silenceXp = rewards?.silenceBonusXp ?: 0
        if (silenceXp > 0) ModeLineCard(title = "השקט", text = "בלי אוזניות. +$silenceXp נקודות על הכבוד. הכבוד נרשם.")

        // XP + gold + duration
        val gearXp = rewards?.gearXp ?: 0
        val gearGold = rewards?.gearGold ?: 0
        Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.GoldDeep)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(value = xpShown.toString(), label = "ניסיון", color = HColors.Sky)
                    Stat(value = goldShown.toString(), label = "זהב", color = HColors.Gold)
                    Stat(
                        value = formatClock(durationSec),
                        label = "משך",
                        color = HColors.Parchment,
                        onEdit = if (canEditDuration) ({ editingDuration = true }) else null
                    )
                }
                if (gearXp > 0 || gearGold > 0) {
                    Text(
                        gearBonusLine(gearXp, gearGold),
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
                // "מקצוע לשמביולוק": the profession's share of the xp
                val professionXp = rewards?.professionXp ?: 0
                if (professionXp > 0) {
                    Text(
                        "מקצוע: +$professionXp נקודות",
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
                if (rewards?.hardBattle == true) {
                    Text(
                        "הקרב הקשה: הניסיון הוכפל. אמצע השבוע נכבש.",
                        color = HColors.Ember,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
                // sleep: medium night (80%, records without bonus) or short night and the gym anyway
                val sleepNote = rewards?.sleepNoteHe.orEmpty()
                if (sleepNote.isNotEmpty()) {
                    Text(
                        com.tamir.hamasa.domain.game.Addr.x(sleepNote),
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
                val stations = rewards?.mapStations ?: 1
                if (stations > 1) {
                    Text(
                        "סיבולת: התקדמת $stations תחנות במפה.",
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }
        }

        // the goal of the day, judged by the sets (no longer asked at the finish)
        GoalOfDayCard(sessionId)

        // energy calculator: net burn of this session (computed on the fly)
        com.tamir.hamasa.ui.energy.SessionBurnCard(sessionId = sessionId, refreshKey = durationSec)

        if (editingDuration && canEditDuration) {
            DurationEditDialog(
                initialMinutes = (durationSec / 60).coerceAtLeast(1),
                onDismiss = { editingDuration = false },
                onSave = { minutes ->
                    editingDuration = false
                    vm.editDuration(minutes)
                }
            )
        }

        // Shield kept by gear
        if (rewards?.shieldSaved == true) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Moss)) {
                Text(
                    vm.shieldSavedLine,
                    Modifier.padding(16.dp),
                    color = HColors.Parchment,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // "הימור על עצמך" won / "קופת המפלצת" released
        val betWon = rewards?.betWonGold ?: 0
        val potReleased = rewards?.potReleasedGold ?: 0
        if (betWon > 0 || potReleased > 0) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Gold)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (betWon > 0) Text("ההימור על עצמך: +$betWon זהב. הימרת נכון. נדיר אצל מהמרים.", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                    if (potReleased > 0) Text("שבוע מלא. קופת המפלצת נפתחה: +$potReleased זהב. המפלצת לא מרוצה.", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // Streak
        Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("רצף", color = HColors.ParchmentDim, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(streakDaysHe(streak), color = HColors.Ember, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
        }

        // the coach's comparison with last time, exercise by exercise
        val cmp by vm.compare.collectAsStateWithLifecycle()
        if (cmp.isNotEmpty()) CoachCompareCard(cmp)

        // Muscles worked today
        val h = heat
        if (h != null && !h.isEmpty) MusclesWorkedCard(h)

        // PRs
        val prs = rewards?.prs ?: emptyList()
        if (prs.isNotEmpty()) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Gold)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("שיאים חדשים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                    prs.forEach { pr ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = HColors.Gold)
                            Spacer(Modifier.width(8.dp))
                            Text(pr, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }

        com.tamir.hamasa.ui.records.NewRecordPageCard(prs)

        // Boss fight in rounds
        val boss = rewards?.boss
        if (boss != null && boss.damage > 0) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, if (boss.defeatedNow) HColors.Gold else HColors.Danger)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(boss.nameHe, color = HColors.Danger, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${boss.hits} מכות מתוך ${boss.rounds} סיבובים. ${boss.damage} נזק.",
                        color = HColors.Parchment,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    // "כל בוס נפגע משילוב אחר": how this session matched the weakness
                    if (boss.weaknessHe.isNotEmpty()) {
                        Text(
                            com.tamir.hamasa.domain.game.Addr.x("חולשה: ${boss.weaknessHe}. " + com.tamir.hamasa.domain.game.BossWeakness.doneLineHe(boss.grade)),
                            color = HColors.Gold, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // "קרבות בוס בשלבים": the boss entered a new phase in this session
                    if (!boss.defeatedNow && boss.phaseAfter != boss.phaseBefore) {
                        com.tamir.hamasa.domain.game.BossPhases.enterLineHe(boss.phaseAfter)?.let { line ->
                            Text(boss.phaseAfter.nameHe + ". " + line, color = HColors.Gold, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (boss.defeatedNow) {
                        Text(vm.bossDefeatedLine ?: "הבוס הובס.", color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("+200 ניסיון, +50 זהב", color = HColors.Gold, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text("נשארו לו ${boss.hpAfter} מתוך ${boss.maxHp}.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else if (boss != null && boss.grade == com.tamir.hamasa.domain.game.WeakGrade.NONE && !boss.defeatedNow) {
            // the session had no ingredient of the boss's weakness: say what it wants (no damage was dealt)
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Danger)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(boss.nameHe, color = HColors.Danger, style = MaterialTheme.typography.titleMedium)
                    Text(com.tamir.hamasa.domain.game.BossWeakness.doneLineHe(boss.grade), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                    Text(com.tamir.hamasa.domain.game.Addr.x("חולשה: ${boss.weaknessHe}."), color = HColors.Gold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (rewards?.bossHit == true) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Danger)) {
                Text(
                    "פגעת בבוס השבוע. הוא הרגיש את זה.",
                    Modifier.padding(16.dp),
                    color = HColors.Parchment,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // coach season: seasonal event boss hit, weekly challenge completed, the coach's summary
        com.tamir.hamasa.ui.common.SeasonDoneCards(sessionId = sessionId)
        com.tamir.hamasa.ui.common.CoachSaysCard(sessionId = sessionId)

        // stage 2 H: shadow summary, skill "ריכוז", the story chapter
        V2DoneCards(sessionId = sessionId)

        // "האימונים שלי": save this workout, or update the template it came from after a swap
        SaveWorkoutCard(sessionId = sessionId)

        // Badges
        val badges = rewards?.newBadges ?: emptyList()
        if (badges.isNotEmpty()) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Legendary)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("תגים חדשים", color = HColors.Legendary, style = MaterialTheme.typography.titleLarge)
                    badges.forEach { id ->
                        val def = BadgeCatalog.byId(id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(12.dp)
                                    .height(12.dp)
                                    .background(rarityColor(def?.rarity ?: Rarity.COMMON), RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(com.tamir.hamasa.domain.game.Addr.x(def?.nameHe ?: id), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        }
                        badgeGear[id]?.let { item ->
                            Text(com.tamir.hamasa.domain.game.CharacterNames.pick("תג אגדי. שמביולוק קיבל ציוד: $item", "תג אגדי. שמביולוקה קיבלה ציוד: $item"), color = HColors.Legendary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // Chest (Sunday..Thursday chests wait sealed in the weekly box)
        rewards?.chest?.let { chest -> ChestCard(chest) }
        if (rewards?.chestSealed == true) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("תיבה סגורה", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "נכנסה לקופסת סוף השבוע. לא מציצים. נפתח הכול ביחד ביום שישי, בסיכום השבועי.",
                        color = HColors.Parchment,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        TextButton(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
            Text("לפרטים המלאים", color = HColors.Gold, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CardShape,
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
        ) { Text("סגור", fontWeight = FontWeight.Black, fontSize = 20.sp) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, onEdit: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black)
        if (onEdit == null) {
            Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
                DurationEditPencil(onClick = onEdit)
            }
        }
    }
}

@Composable
private fun MusclesWorkedCard(heat: MuscleHeat.HeatResult) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("שרירים שעבדו היום", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            MuscleHeatmap(heat = heat.heat, modifier = Modifier.fillMaxWidth())
            MuscleHeatLegend()
            val lines = heat.regionLines()
            if (lines.isNotEmpty()) {
                Text(
                    lines.joinToString(", "),
                    color = HColors.Parchment,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LevelUpBanner(level: Int, line: String = "", unlocks: List<String> = emptyList()) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.Gold), border = BorderStroke(3.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("הידדדדד", color = HColors.Ink, style = MaterialTheme.typography.headlineMedium)
            Text("עלית לרמה $level", color = HColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(line.ifEmpty { com.tamir.hamasa.domain.game.Addr.t("האיש והאגדה. שוב.", "האישה והאגדה. שוב.") }, color = HColors.Ink, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (unlocks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("נפתח:", color = HColors.Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                unlocks.forEach { u ->
                    Text(com.tamir.hamasa.domain.game.Addr.x(u), color = HColors.Ink, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A short line of today's mode on the done screen (race against the clock, a mode found by luck). */
@Composable
private fun ModeLineCard(title: String, text: String) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Sky)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = HColors.Sky, style = MaterialTheme.typography.titleMedium)
            Text(text, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun rarityColor(r: Rarity): Color = when (r) {
    Rarity.COMMON -> HColors.ParchmentDim
    Rarity.RARE -> HColors.Rare
    Rarity.LEGENDARY -> HColors.Legendary
}

private fun rarityName(r: Rarity): String = when (r) {
    Rarity.COMMON -> "רגיל"
    Rarity.RARE -> "נדיר"
    Rarity.LEGENDARY -> "אגדי"
}

@Composable
private fun ChestCard(chest: ChestDrop) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val color = rarityColor(chest.rarity)
    // Flip around the Y axis; the contents appear once past 90 degrees.
    val rotation by animateFloatAsState(
        targetValue = if (revealed) 180f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "chest_flip"
    )
    val scale by animateFloatAsState(
        targetValue = if (revealed) 1.04f else 1f,
        animationSpec = tween(durationMillis = 700),
        label = "chest_scale"
    )
    val showBack = rotation > 90f

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = if (showBack) HColors.Ink else HColors.SlateLight),
        border = BorderStroke(3.dp, color),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = rotation
                scaleX = scale
                scaleY = scale
                cameraDistance = 12f * density
            }
            .clickable { if (!revealed) revealed = true }
    ) {
        if (!showBack) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("תיבת אוצר", color = color, style = MaterialTheme.typography.headlineMedium)
                Text(com.tamir.hamasa.domain.game.Addr.t("לחץ כדי לפתוח", "לחצי כדי לפתוח"), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            // The back face is mirrored by the rotation, so flip it once more to read normally.
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { rotationY = 180f }
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("תיבה ${rarityName(chest.rarity)}", color = color, style = MaterialTheme.typography.headlineMedium)
                if (chest.xp > 0) Text("+${chest.xp} ניסיון", color = HColors.Sky, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (chest.gold > 0) Text("+${chest.gold} זהב", color = HColors.Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                chest.modeId?.let { id ->
                    Text("מצב אימון חדש: " + (ModeCatalog.byId(id)?.nameHe ?: id), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
                }
                chest.itemId?.let { id ->
                    Text("ציוד חדש: " + (GearCatalog.byId(id)?.nameHe ?: id), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
                }
                if (chest.shield) Text("מגן רצף", color = HColors.Moss, style = MaterialTheme.typography.titleMedium)
                if (chest.joker) Text("ג'וקר", color = HColors.Ember, style = MaterialTheme.typography.titleMedium)
                if (chest.xp == 0 && chest.gold == 0 && chest.modeId == null && chest.itemId == null && !chest.shield && !chest.joker) {
                    Text(com.tamir.hamasa.domain.game.CharacterNames.g("ריקה. מציין: גם זה קורה."), color = HColors.ParchmentDim)
                }
            }
        }
    }
}

/** "יום אחד", "יומיים", "5 ימים". */
private fun streakDaysHe(days: Int): String = when (days) {
    1 -> "יום אחד"
    2 -> "יומיים"
    else -> "$days ימים"
}
