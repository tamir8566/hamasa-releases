package com.tamir.hamasa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.AiVoice
import com.tamir.hamasa.data.ai.AiClient
import com.tamir.hamasa.data.ai.VoiceContext
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.prefs.ProgressMarks
import com.tamir.hamasa.data.repo.BossFights
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Professions
import com.tamir.hamasa.domain.game.TapContext
import com.tamir.hamasa.domain.game.TapVoice
import com.tamir.hamasa.domain.game.Voice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.random.Random

/**
 * "לחיצה על שמביולוק" on Home: a context line for the speech bubble. The offline line is ready at once.
 * About one tap in four (at most [AI_USES_PER_DAY] a day, only with a Gemini key) a Gemini line that was fetched
 * earlier in the background is shown instead; the tap never waits for the network.
 */
class ShambiulukTapViewModel : ViewModel() {

    private companion object {
        const val AI_USES_PER_DAY = 3
        const val AI_ASKS_PER_DAY = 4
        const val AI_ONE_IN = 4
        /** One prefetched Gemini line, kept across Home screens of the same process. */
        @Volatile var prefetched: String? = null
        @Volatile var fetching = false
    }

    private var busy = false

    init { prefetchAi() }

    /** Builds the line off the main thread and hands it to [onLine]. Ignores taps while one is being built. */
    fun onTap(onLine: (String) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val today = Days.today()
                val ai = prefetched
                if (ai != null && AiClient.enabled && ProgressMarks.aiUsedToday(today) < AI_USES_PER_DAY && Random.nextInt(AI_ONE_IN) == 0) {
                    prefetched = null
                    ProgressMarks.markAiUsed(today)
                    onLine(ai)
                    prefetchAi()
                    return@launch
                }
                val ctx = runCatching { context() }.getOrNull()
                val line = if (ctx == null) {
                    Voice.morning(null)
                } else {
                    val pick = TapVoice.pick(ctx, ProgressMarks.recentTapIds())
                    ProgressMarks.pushTapId(pick.id)
                    pick.text
                }
                onLine(line)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun context(): TapContext {
        val p = AppGraph.prefs.current()
        val today = Days.today()
        val todays = AppGraph.db.sessions().forDay(today).first()
        val bossPair = runCatching { BossFights.forDay(today) }.getOrNull()
        val boss = bossPair?.first
        val progress = bossPair?.second
        val meals = AppGraph.db.meals().forDayOnce(today)
        val watch = runCatching { WatchStore.current() }.getOrNull()
        val steps = if (watch != null && watch.connected) watch.steps[today]?.total else null
        return TapContext(
            hour = LocalTime.now().hour,
            streak = p.streak,
            trainedToday = todays.any { it.status == SessionStatus.DONE },
            activeSession = todays.any { it.status == SessionStatus.ACTIVE },
            bossNameHe = boss?.nameHe ?: "",
            bossHpFrac = if (progress != null && progress.maxHp > 0) progress.hp.toFloat() / progress.maxHp else 1f,
            bossDefeated = progress?.defeated ?: false,
            kcalLeft = if (meals.isEmpty()) null else p.kcalGoal - meals.sumOf { it.kcal },
            steps = steps,
            mood = if (p.moodDay == today) p.mood else 0,
            professionId = Professions.active(p.professionId, p.xp)?.name
        )
    }

    /** Fetches one Gemini tap line in the background (app scope), within the daily request cap. */
    private fun prefetchAi() {
        if (!AiClient.enabled || prefetched != null || fetching) return
        val today = Days.today()
        if (ProgressMarks.aiUsedToday(today) >= AI_USES_PER_DAY || ProgressMarks.aiAskedToday(today) >= AI_ASKS_PER_DAY) return
        fetching = true
        AppGraph.scope.launch {
            try {
                // no network line before the profile exists (onboarding not finished)
                if (!AppGraph.prefs.current().onboarded) return@launch
                val c = context()
                ProgressMarks.markAiAsked(today)
                val prof = Professions.active(AppGraph.prefs.current().professionId, AppGraph.prefs.current().xp)?.displayHe
                val line = AiVoice.line(
                    VoiceContext.Tap(
                        partOfDayHe = TapVoice.partOfDayHe(c.hour),
                        streak = c.streak,
                        trainedToday = c.trainedToday,
                        bossHe = c.bossNameHe,
                        bossDefeated = c.bossDefeated,
                        kcalLeft = c.kcalLeft,
                        steps = c.steps,
                        moodHe = Voice.moodNames.getOrNull(c.mood)?.ifEmpty { null },
                        professionHe = prof
                    ),
                    15_000L
                )
                if (line != null) prefetched = line
            } catch (e: Throwable) {
                // offline / no key / bad answer: the offline lines cover it
            } finally {
                fetching = false
            }
        }
    }
}
