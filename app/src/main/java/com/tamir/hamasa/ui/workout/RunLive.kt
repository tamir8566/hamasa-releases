package com.tamir.hamasa.ui.workout

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.RunCoach
import com.tamir.hamasa.data.repo.RunFeel
import com.tamir.hamasa.data.repo.WorkoutRepository
import com.tamir.hamasa.domain.coach.RunPlan
import com.tamir.hamasa.domain.coach.RunStep
import com.tamir.hamasa.domain.coach.RunStepKind
import com.tamir.hamasa.ui.common.Sfx
import com.tamir.hamasa.ui.common.Sound
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the live run card shows. [stepIndex] = -1 before the start. */
data class RunLiveUi(
    val labelHe: String,
    val steps: List<RunStep>,
    val started: Boolean,
    val paused: Boolean,
    val stepIndex: Int,
    val stepRemainingSec: Int,
    val elapsedSec: Int,
    val totalSec: Int,
    val done: Boolean
) {
    val current: RunStep? get() = steps.getOrNull(stepIndex)
    val next: RunStep? get() = steps.getOrNull(stepIndex + 1)
}

/**
 * The interval timer of a plan run (RUN session with notes "run:<week>:<day>"). The clock is wall time from the
 * moment he pressed "יוצאים לדרך" minus pauses, plus skipped seconds, so it stays right after the screen was off or
 * the app was in the background. Kept in local SharedPreferences "run_live" (one live run at a time).
 */
class RunLiveState(private val sessionId: Long, private val scope: CoroutineScope) {

    private val _ui = MutableStateFlow<RunLiveUi?>(null)
    /** Null = not a plan run (or not loaded yet). */
    val ui: StateFlow<RunLiveUi?> = _ui

    private val _feel = MutableStateFlow<RunFeel?>(null)
    val feel: StateFlow<RunFeel?> = _feel

    private var steps: List<RunStep> = emptyList()
    private var label = ""
    private var setCompleted = false

    private fun sp(): SharedPreferences? =
        runCatching { AppGraph.appContext.getSharedPreferences("run_live", Context.MODE_PRIVATE) }.getOrNull()

    private fun startMs(): Long = sp()?.takeIf { it.getLong("sid", 0L) == sessionId }?.getLong("startMs", 0L) ?: 0L
    private fun pausedAt(): Long = sp()?.takeIf { it.getLong("sid", 0L) == sessionId }?.getLong("pausedAt", 0L) ?: 0L
    private fun pausedTotal(): Long = sp()?.takeIf { it.getLong("sid", 0L) == sessionId }?.getLong("pausedTotal", 0L) ?: 0L
    private fun offsetSec(): Int = sp()?.takeIf { it.getLong("sid", 0L) == sessionId }?.getInt("offset", 0) ?: 0

    init {
        scope.launch {
            val s = AppGraph.db.sessions().byId(sessionId) ?: return@launch
            val wd = RunCoach.parseNotes(s.notes) ?: return@launch
            steps = RunPlan.steps(wd.first, wd.second)
            label = RunPlan.labelHe(wd.first, wd.second)
            setCompleted = AppGraph.db.sessions().setsForOnce(sessionId).any { it.exerciseId == WorkoutRepository.RUN_EXERCISE_ID && it.completed }
            while (true) {
                tick()
                delay(500)
            }
        }
    }

    private fun elapsedSec(): Int {
        val start = startMs()
        if (start <= 0L) return 0
        val now = System.currentTimeMillis()
        val pa = pausedAt()
        val end = if (pa > 0L) pa else now
        val ms = (end - start - pausedTotal()).coerceAtLeast(0L)
        return (ms / 1000L).toInt() + offsetSec()
    }

    private fun tick() {
        val total = steps.sumOf { it.sec }
        val started = startMs() > 0L
        val elapsed = elapsedSec().coerceAtMost(total)
        var idx = -1
        var remaining = 0
        if (started) {
            var acc = 0
            idx = steps.size - 1
            for (i in steps.indices) {
                if (elapsed < acc + steps[i].sec) {
                    idx = i
                    remaining = acc + steps[i].sec - elapsed
                    break
                }
                acc += steps[i].sec
            }
        }
        val done = started && elapsed >= total
        _ui.value = RunLiveUi(label, steps, started, pausedAt() > 0L, idx, remaining, elapsed, total, done)
        if (done && !setCompleted) {
            setCompleted = true
            scope.launch { completeRunSet(total) }
        }
    }

    private suspend fun completeRunSet(seconds: Int) {
        val set = AppGraph.db.sessions().setsForOnce(sessionId).firstOrNull { it.exerciseId == WorkoutRepository.RUN_EXERCISE_ID && !it.completed }
            ?: return
        AppGraph.workouts.completeSet(set, seconds.coerceAtLeast(1), 0f)
    }

    /** "יוצאים לדרך". */
    fun start() {
        if (startMs() > 0L) return
        sp()?.edit()?.putLong("sid", sessionId)?.putLong("startMs", System.currentTimeMillis())
            ?.putLong("pausedAt", 0L)?.putLong("pausedTotal", 0L)?.putInt("offset", 0)?.apply()
        tick()
    }

    fun togglePause() {
        if (startMs() <= 0L) return
        val e = sp()?.edit() ?: return
        val pa = pausedAt()
        if (pa > 0L) e.putLong("pausedTotal", pausedTotal() + (System.currentTimeMillis() - pa)).putLong("pausedAt", 0L)
        else e.putLong("pausedAt", System.currentTimeMillis())
        e.apply()
        tick()
    }

    /** "לשלב הבא": the rest of the current step is skipped. */
    fun skipStep() {
        val u = _ui.value ?: return
        if (!u.started || u.done) return
        sp()?.edit()?.putInt("offset", offsetSec() + u.stepRemainingSec)?.apply()
        tick()
    }

    fun setFeel(f: RunFeel) {
        _feel.value = f
    }

    /**
     * Before the session is finished: a run stopped early still records the seconds he ran (the set target stays
     * the planned total, so the plan sees it was not complete).
     */
    suspend fun beforeFinish() {
        val u = _ui.value ?: return
        if (!setCompleted && u.started && u.elapsedSec > 0) {
            setCompleted = true
            completeRunSet(u.elapsedSec)
        }
    }

    /** After the session is finished: the plan moves (or repeats), and the local timer is cleared. */
    suspend fun afterFinish() {
        val u = _ui.value ?: return
        RunCoach.afterRun(sessionId, _feel.value, u.done)
        sp()?.edit()?.clear()?.apply()
    }
}

/** Short double buzz at every switch between running and walking. */
private fun runVibrate(context: Context, long: Boolean) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    try {
        val pattern = if (long) longArrayOf(0L, 600L, 200L, 600L) else longArrayOf(0L, 300L, 150L, 300L)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    } catch (e: Exception) {
        // a nicety; never crash the run over it
    }
}

private fun stepTitleHe(kind: RunStepKind): String = when (kind) {
    RunStepKind.WARMUP -> "חימום, הליכה"
    RunStepKind.RUN -> "ריצה"
    RunStepKind.WALK -> "הליכה"
    RunStepKind.COOLDOWN -> "שחרור, הליכה"
}

/**
 * Screen-level effects of a plan run (call it outside the scrolling list so it is always composed): the screen
 * stays on, and every step switch vibrates and plays a sound (the sound follows the app's sound setting in Sfx).
 */
@Composable
fun RunCueEffects(state: RunLiveState) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val u = ui ?: return
    val context = LocalContext.current

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // vibration + sound at every step switch (sound follows the app's sound setting inside Sfx)
    var lastIndex by remember { mutableIntStateOf(-2) }
    LaunchedEffect(u.stepIndex, u.done) {
        val prev = lastIndex
        lastIndex = if (u.done) Int.MAX_VALUE else u.stepIndex
        if (prev == -2) return@LaunchedEffect // first composition: no cue for the state we came back to
        if (u.done && prev != Int.MAX_VALUE) {
            runVibrate(context, long = true)
            Sfx.play(context, Sound.FINISH)
        } else if (!u.done && u.stepIndex >= 0 && u.stepIndex != prev) {
            runVibrate(context, long = u.current?.isRun == true)
            Sfx.play(context, Sound.START)
        }
    }
}

/** The live interval card of a plan run: steps, countdown, pause / skip, how it felt at the end. */
@Composable
fun RunLiveCard(state: RunLiveState) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val feel by state.feel.collectAsStateWithLifecycle()
    val u = ui ?: return

    val cur = u.current
    val accent = if (cur?.isRun == true) HColors.Ember else HColors.Sky
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(3.dp, if (u.started && !u.done) accent else HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ריצה · ${u.labelHe}", color = HColors.Gold, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            when {
                !u.started -> {
                    Text(
                        "${formatClock(u.totalSec)} בסך הכול. חימום והליכת שחרור כלולים. בכל מעבר: רטט וצליל.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                    )
                    StepList(u)
                    Button(
                        onClick = { state.start() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                    ) { Text("יוצאים לדרך", fontWeight = FontWeight.Black, fontSize = 20.sp) }
                }
                !u.done && cur != null -> {
                    Text(
                        stepTitleHe(cur.kind),
                        color = accent, fontSize = 34.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    Text(
                        formatClock(u.stepRemainingSec),
                        color = HColors.Parchment, fontSize = 64.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    val nxt = u.next
                    Text(
                        if (nxt != null) "הבא: ${nxt.labelHe}" else "אחרון. כמעט שם.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    LinearProgressIndicator(
                        progress = { if (u.totalSec > 0) u.elapsedSec.toFloat() / u.totalSec.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = HColors.Gold,
                        trackColor = HColors.SlateLight
                    )
                    Text(
                        "שלב ${u.stepIndex + 1} מתוך ${u.steps.size} · נשארו ${formatClock(u.totalSec - u.elapsedSec)}",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                    )
                    if (u.paused) Text("בהשהיה", color = HColors.Gold, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { state.togglePause() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                        ) { Text(if (u.paused) "המשך" else "השהה", fontWeight = FontWeight.Black) }
                        OutlinedButton(
                            onClick = { state.skipStep() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(2.dp, HColors.Sky),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Sky)
                        ) { Text("לשלב הבא", fontWeight = FontWeight.Bold) }
                    }
                }
                else -> {
                    Text("הסתיים. ${formatClock(u.totalSec)} על הרגליים.", color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
                    Text("איך הרגיש?", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        RunFeel.values().forEach { f ->
                            val selected = feel == f
                            Button(
                                onClick = { state.setFeel(f) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) HColors.Gold else HColors.SlateLight,
                                    contentColor = if (selected) HColors.Ink else HColors.Parchment
                                )
                            ) { Text(f.nameHe, fontWeight = FontWeight.Black) }
                        }
                    }
                    Text(
                        when (feel) {
                            RunFeel.HARD -> "רשמתי. בשבוע הזה נשארים, מתחילים אותו שוב."
                            null -> "קשה = חוזרים על השבוע. קל או סביר = ממשיכים. לסיום: \"סיום אימון\" למטה."
                            else -> "יפה. בפעם הבאה האימון הבא בתוכנית. לסיום: \"סיום אימון\" למטה."
                        },
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** The steps before the start: one short line each. */
@Composable
private fun StepList(u: RunLiveUi) {
    Column(
        Modifier.fillMaxWidth().background(HColors.Slate, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        u.steps.forEachIndexed { i, st ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    st.labelHe,
                    color = if (st.isRun) HColors.Ember else HColors.Parchment,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (st.isRun) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
