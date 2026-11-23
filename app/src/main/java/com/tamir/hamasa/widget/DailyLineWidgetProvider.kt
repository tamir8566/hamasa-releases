package com.tamir.hamasa.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tamir.hamasa.MainActivity
import com.tamir.hamasa.R
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Voice
import kotlinx.coroutines.runBlocking

/**
 * Small home-screen widget ("המשפט של שמביולוק"): one Shambiuluk line that changes once a day.
 * Today's cached Gemini morning line when there is one (AiVoice.morningLine), otherwise an offline
 * Voice line picked by the day. Classic RemoteViews. Tap opens the app.
 */
class DailyLineWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val line = runBlocking { lineForToday() }
                val views = buildViews(app, line)
                for (id in appWidgetIds) runCatching { appWidgetManager.updateAppWidget(id, views) }
            } catch (e: Throwable) {
                // a widget must never crash the app process
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** Recomputed on every update, so a new day shows a new line within the update period. */
        private suspend fun lineForToday(): String {
            val today = Days.today()
            val p = AppGraph.prefs.current()
            // background entry point: sync the hero / address choice before building the line
            com.tamir.hamasa.domain.game.CharacterNames.set(p.characterVariant)
            com.tamir.hamasa.domain.game.Addr.set(p.userGender)
            if (p.aiMorningDay == today && p.aiMorningLine.isNotBlank()) return p.aiMorningLine
            return Voice.dailyLine(today)
        }

        private fun buildViews(context: Context, line: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_daily_line)
            views.setTextViewText(R.id.widget_line_text, line)
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                context, Widgets.RC_LINE_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_line_root, pi)
            return views
        }
    }
}
