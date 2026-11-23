package com.tamir.hamasa.ui.progress

import com.tamir.hamasa.data.repo.MonthSummary
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.domain.game.WrappedText
import com.tamir.hamasa.ui.common.WrappedPalette
import com.tamir.hamasa.ui.common.WrappedSlide

/** The monthly summary as a "wrapped" story. */
object MonthlyWrapped {

    fun slides(s: MonthSummary, monthHe: String, direction: GoalDirection, closingHe: String): List<WrappedSlide> {
        val kg = s.kgChange
        val good = kg != null && when (direction) {
            GoalDirection.CUT -> kg <= -0.05f
            GoalDirection.GAIN -> kg >= 0.05f
            GoalDirection.MAINTAIN -> kotlin.math.abs(kg) < 0.5f
        }
        val texts = listOf(
            WrappedText.monthCover(monthHe, s.month.year),
            WrappedText.weight(kg, s.startKg, s.endKg, good),
            WrappedText.sessions(s.sessions, week = false),
            WrappedText.records(s.records),
            WrappedText.bosses(s.bossesDefeated),
            WrappedText.badges(s.badges),
            WrappedText.streak(s.bestStreak, best = true),
            WrappedText.kcalAvg(s.kcalAvg, s.kcalGoal, s.kcalDays),
            WrappedText.monthEnd(closingHe)
        )
        return texts.mapIndexed { i, t ->
            val (bg, fg) = WrappedPalette.at(i + 2)
            WrappedSlide(bg, fg, t.kicker, t.big, t.line, t.sub, t.bigSmall)
        }
    }
}
