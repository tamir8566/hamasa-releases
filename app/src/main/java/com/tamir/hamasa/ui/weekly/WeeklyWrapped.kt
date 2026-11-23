package com.tamir.hamasa.ui.weekly

import com.tamir.hamasa.domain.game.WrappedText
import com.tamir.hamasa.ui.common.WrappedPalette
import com.tamir.hamasa.ui.common.WrappedSlide

/** The weekly summary as a "wrapped" story: one loud slide per number. */
object WeeklyWrapped {

    /** The story is an end-of-week thing: it shows only on Friday and Saturday (owner request 2026-11-12). */
    fun showOn(epochDay: Long): Boolean {
        val d = java.time.LocalDate.ofEpochDay(epochDay).dayOfWeek
        return d == java.time.DayOfWeek.FRIDAY || d == java.time.DayOfWeek.SATURDAY
    }

    fun slides(st: WeeklySummaryState, from: String, to: String): List<WrappedSlide> {
        val top = st.heat.setsByRegion.entries.maxByOrNull { it.value }
        val texts = ArrayList<WrappedText.Slide>()
        texts.add(WrappedText.weekCover(from, to))
        texts.add(WrappedText.sessions(st.sessions, week = true))
        texts.add(WrappedText.volume(st.volumeKg))
        texts.add(WrappedText.records(st.prs))
        texts.add(WrappedText.topMuscle(top?.key?.nameHe, top?.value ?: 0))
        if (st.bossNameHe.isNotEmpty()) texts.add(WrappedText.boss(st.bossNameHe, st.bossDefeated, st.bossHp, st.bossMaxHp))
        val judged = st.calDays.count { it.ok != null }
        val ok = st.calDays.count { it.ok == true }
        texts.add(WrappedText.calories(ok, judged, week = true))
        texts.add(WrappedText.streak(st.streak, best = false))
        texts.add(WrappedText.loot(st.xp, st.gold))
        texts.add(WrappedText.weekEnd(st.verdictHe))
        return texts.mapIndexed { i, t ->
            val (bg, fg) = WrappedPalette.at(i)
            WrappedSlide(bg, fg, t.kicker, t.big, t.line, t.sub, t.bigSmall)
        }
    }
}
