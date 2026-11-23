package com.tamir.hamasa.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tamir.hamasa.HamasaApp
import com.tamir.hamasa.MainActivity
import com.tamir.hamasa.R
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Voice
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    fun ensureScheduled(context: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/**
 * Hourly check (the periodic worker drifts, so every rule is a WINDOW + "already sent today", never `hour ==`).
 *
 * Workout reminder: one hour before the planned workout hour, sent once per day while now is in
 * [workout - 60 min, workout - 1 min] and he has not trained yet today.
 * Quiet-hours rule: the workout reminder IGNORES quiet hours. He picked that hour himself, so a 06:00 or
 * 07:00 workout (with quiet hours 23–07) still gets its 05:00 / 06:00 reminder. Quiet hours only silence nudges.
 *
 * Close-the-day nudge: from 21:00 until midnight, once per day, only outside quiet hours and only if the
 * day was not closed yet.
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // stage 3, first: a walk the watch recorded counts as training today before the reminder decides
        // (at most every 30 min in the background; never throws)
        runCatching { com.tamir.hamasa.data.health.WatchSync.sync(applicationContext, force = false, background = true) }
        // home-screen widgets: a new day means a new line and a fresh calorie count
        com.tamir.hamasa.widget.Widgets.refreshNow(applicationContext)
        val p = AppGraph.prefs.current()
        // background entry point: the Prefs collect in AppGraph is async, so sync the hero / address choice here
        com.tamir.hamasa.domain.game.CharacterNames.set(p.characterVariant)
        com.tamir.hamasa.domain.game.Addr.set(p.userGender)
        val now = LocalTime.now()
        val hour = now.hour
        val minuteOfDay = hour * 60 + now.minute
        val quiet = if (p.quietFrom > p.quietTo) (hour >= p.quietFrom || hour < p.quietTo) else (hour in p.quietFrom until p.quietTo)
        val today = Days.today()
        val trainedToday = p.lastActiveDay == today

        val workoutMinute = p.plannedTomorrowHour.coerceIn(0, 23) * 60
        val remindFrom = (workoutMinute - 60).coerceAtLeast(0)
        val inWorkoutWindow = minuteOfDay in remindFrom until workoutMinute
        if (!trainedToday && inWorkoutWindow && p.reminderSentDay != today) {
            notify(1, "האימון מתחיל בעוד שעה", Voice.reminderOneHour())
            AppGraph.prefs.update { it.copy(reminderSentDay = today) }
        }

        val inCloseWindow = hour >= CLOSE_DAY_NUDGE_HOUR
        if (!quiet && inCloseWindow && p.closeNudgeDay != today && p.lastCloseDay != today) {
            notify(2, "סגירת היום", "מה האימון של מחר ובאיזו שעה? שתי לחיצות.")
            AppGraph.prefs.update { it.copy(closeNudgeDay = today) }
        }
        runCatching { photoAndBetChecks(quiet) }
        // stage 2 F: from 21:00 the day's food rewards settle even if the day is never closed (once per day)
        if (inCloseWindow) runCatching { com.tamir.hamasa.data.repo.MealGame.settleDay(today) }
        // light update check (shares the once-per-hour limit with UpdateWorker; notifies or installs silently)
        runCatching { com.tamir.hamasa.data.update.UpdateCenter.lightBackgroundCheck(applicationContext) }
        return Result.success()
    }

    /**
     * Stage 1 D. (a) Monthly progress-photo reminder: from the 1st of the month at 10:00 (window to the 3rd,
     * the worker drifts), once per month, outside quiet hours, only if no photo was taken this month yet.
     * (b) An expired "הימור על עצמך" bet moves to the monster pot.
     */
    private suspend fun photoAndBetChecks(quiet: Boolean) {
        com.tamir.hamasa.data.repo.Bets.settleMissed()
        val date = java.time.LocalDate.now()
        val monthKey = date.year * 12 + date.monthValue
        if (quiet || date.dayOfMonth > 3 || LocalTime.now().hour < 10) return
        if (AppGraph.prefs.current().photoNudgeMonth == monthKey) return
        val first = date.withDayOfMonth(1).toEpochDay()
        if (AppGraph.db.progress().photosBetween(first, date.toEpochDay()) > 0) return
        notify(3, "תמונת התקדמות", "חודש חדש. אותה תנוחה, אותו מקום. תמונה אחת, ונשווה לקודמת.")
        AppGraph.prefs.update { it.copy(photoNudgeMonth = monthKey) }
    }

    private companion object {
        const val CLOSE_DAY_NUDGE_HOUR = 21
    }

    private fun notify(id: Int, title: String, text: String) {
        val ctx = applicationContext
        // his rule: only "updated" and "rest over" reach the phone and the watch
        if (com.tamir.hamasa.data.NotifPolicy.essentialOnly(ctx)) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, HamasaApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_fg).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
