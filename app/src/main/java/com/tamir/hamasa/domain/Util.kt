package com.tamir.hamasa.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object Days {
    fun today(): Long = LocalDate.now().toEpochDay()
    fun of(day: Long): LocalDate = LocalDate.ofEpochDay(day)
    /** First day of the week that contains [day]. Weeks run Sunday → Saturday (Israel). */
    fun weekStartOf(day: Long): Long = of(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toEpochDay()
    /** Last day of the week (Saturday) that contains [day]. */
    fun weekEndOf(day: Long): Long = weekStartOf(day) + 6
    @Deprecated("weeks start on Sunday", ReplaceWith("weekStartOf(day)"))
    fun mondayOf(day: Long): Long = weekStartOf(day)
    fun isWeekend(day: Long): Boolean = of(day).dayOfWeek.let { it == DayOfWeek.FRIDAY || it == DayOfWeek.SATURDAY }
    fun hebrewDayName(day: Long): String = when (of(day).dayOfWeek) {
        DayOfWeek.SUNDAY -> "ראשון"; DayOfWeek.MONDAY -> "שני"; DayOfWeek.TUESDAY -> "שלישי"
        DayOfWeek.WEDNESDAY -> "רביעי"; DayOfWeek.THURSDAY -> "חמישי"; DayOfWeek.FRIDAY -> "שישי"; DayOfWeek.SATURDAY -> "שבת"
    }
}
