package com.tamir.hamasa.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.tamir.hamasa.MainActivity
import com.tamir.hamasa.R
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.domain.game.BodyStage
import com.tamir.hamasa.domain.game.LevelTable
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

/**
 * Home-screen widget: this week's 7 days on top (trained days in gold, today ringed), Shambiuluk's current body
 * (by body weight only — the iron rule), level + streak, XP bar, calories left today with the protein line,
 * and a big gold "צלם ארוחה" button that opens the meal camera.
 * Classic RemoteViews (no Glance). Tapping anywhere else opens the app.
 * Shorter heights drop, in order: the XP text, the protein line, then (compact button) the XP bar, and at the
 * very bottom the level row folds into the calories line. The week row always stays (see [BigWidgetSize]).
 */
class HamasaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        render(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun render(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val data = runBlocking { loadData(app) }
                for (id in appWidgetIds) {
                    runCatching {
                        val size = sizeOf(appWidgetManager.getAppWidgetOptions(id))
                        appWidgetManager.updateAppWidget(id, buildViews(app, data, size))
                    }
                }
            } catch (e: Throwable) {
                // a widget must never crash the app process
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val MAX_BODY_PX = 300

        // Height budget (dp): padding 12 + week row 24 + gaps 8 + button 48 (compact 36) = 92 (80),
        // content: level row 20, XP bar 12, XP text 16, calories 24, protein 16.
        private const val TALL_DP = 180
        private const val MEDIUM_DP = 164
        private const val SMALL_DP = 146
        private const val COMPACT_DP = 124

        // Week circle states (index into the drawables / text colors below).
        private const val DAY_IDLE_PAST = 0
        private const val DAY_IDLE_FUTURE = 1
        private const val DAY_TODAY = 2
        private const val DAY_DONE = 3
        private const val DAY_DONE_TODAY = 4

        private const val COLOR_INK = 0xFF14171A.toInt()
        private const val COLOR_GOLD = 0xFFE8B04B.toInt()
        private const val COLOR_PARCHMENT_DIM = 0xFFB9A98B.toInt()
        private const val COLOR_FADED = 0xFF6E685E.toInt()

        private val DAY_LETTERS = arrayOf("א", "ב", "ג", "ד", "ה", "ו", "ש")
        private val DAY_IDS = intArrayOf(
            R.id.widget_day_0, R.id.widget_day_1, R.id.widget_day_2, R.id.widget_day_3,
            R.id.widget_day_4, R.id.widget_day_5, R.id.widget_day_6
        )

        /** Portrait height is OPTION_APPWIDGET_MAX_HEIGHT (the app and most home screens are portrait). 0 = unknown. */
        private fun sizeOf(options: Bundle?): BigWidgetSize {
            if (options == null) return BigWidgetSize.MEDIUM
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val h = if (maxH > 0) maxH else minH
            return when {
                h <= 0 -> BigWidgetSize.MEDIUM
                h >= TALL_DP -> BigWidgetSize.TALL
                h >= MEDIUM_DP -> BigWidgetSize.MEDIUM
                h >= SMALL_DP -> BigWidgetSize.SMALL
                h >= COMPACT_DP -> BigWidgetSize.COMPACT
                else -> BigWidgetSize.TINY
            }
        }

        private suspend fun loadData(context: Context): BigWidgetData {
            val p = AppGraph.prefs.current()
            com.tamir.hamasa.domain.game.CharacterNames.set(p.characterVariant)
            com.tamir.hamasa.domain.game.Addr.set(p.userGender)
            val kg = AppGraph.db.weights().latestOnce()?.kg ?: p.startWeightKg
            val level = LevelTable.levelFor(p.xp)
            val from = LevelTable.xpForLevel(level)
            val to = LevelTable.xpForLevel(level + 1)
            val percent = (LevelTable.progress(p.xp) * 100f).toInt().coerceIn(0, 100)

            // THE IRON RULE: body image by weight only.
            val blend = BodyStage.forProfile(kg, p)
            val idx = if (blend.t >= 0.5f) blend.anchorB else blend.anchorA
            val body = loadBody(context, com.tamir.hamasa.ui.common.CharacterArt.anchorResId(context, p.characterVariant, idx))

            // Same goals as the food screen: kcalGoal (kept in sync by EnergyData.syncAutoGoal when automatic),
            // macros from Nutrition.targets (a stored goal > 0 wins over the automatic value).
            val meals = AppGraph.db.meals().forDayOnce(Days.today())
            val total = meals.sumOf { it.kcal }
            val protein = meals.sumOf { it.proteinG.toDouble() }.toFloat()
            val targets = Nutrition.targets(
                p.kcalGoal.coerceAtLeast(1), kg, p.proteinGoal, p.carbsGoal, p.fatGoal, p.heightCm, p.bodyFatPct,
                com.tamir.hamasa.data.repo.EnergyData.directionOf(p, kg)
            )
            val diff = targets.kcal - total
            val kcalText = if (diff >= 0) "נשארו $diff קלוריות" else "עברת ב-${-diff} קלוריות"
            val macroText = "חלבון ${protein.roundToInt()}/${targets.proteinG}"

            // This week (Sunday → Saturday), same source as the home week strip: finished sessions.
            val today = Days.today()
            val weekStart = Days.weekStartOf(today)
            val trained = HashSet<Long>()
            for (s in AppGraph.db.sessions().doneSessionsOnce()) {
                if (s.dateEpochDay in weekStart..(weekStart + 6)) trained.add(s.dateEpochDay)
            }
            val week = IntArray(7) { c ->
                val day = weekStart + c
                val done = trained.contains(day)
                when {
                    done && day == today -> DAY_DONE_TODAY
                    done -> DAY_DONE
                    day == today -> DAY_TODAY
                    day < today -> DAY_IDLE_PAST
                    else -> DAY_IDLE_FUTURE
                }
            }

            return BigWidgetData(
                levelText = "רמה $level",
                streakText = "רצף ${p.streak}",
                xpPercent = percent,
                xpText = "ניסיון ${p.xp - from} מתוך ${to - from}",
                body = body,
                kcalText = kcalText,
                macroText = macroText,
                week = week
            )
        }

        private fun buildViews(context: Context, d: BigWidgetData, size: BigWidgetSize): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_hamasa)
            views.setTextViewText(R.id.widget_level, d.levelText)
            views.setTextViewText(R.id.widget_streak, d.streakText)
            views.setProgressBar(R.id.widget_xp_bar, 100, d.xpPercent, false)
            views.setTextViewText(R.id.widget_xp_text, d.xpText)
            views.setTextViewText(R.id.widget_macro, d.macroText)
            views.setContentDescription(R.id.widget_body, com.tamir.hamasa.domain.game.CharacterNames.heNow())

            val showXpText = size == BigWidgetSize.TALL
            val showMacro = size == BigWidgetSize.TALL || size == BigWidgetSize.MEDIUM
            val bigButton = showMacro || size == BigWidgetSize.SMALL
            val showLevelRow = size != BigWidgetSize.TINY
            views.setViewVisibility(R.id.widget_xp_text, if (showXpText) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_macro, if (showMacro) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_xp_bar, if (bigButton) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_snap_meal, if (bigButton) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_snap_meal_small, if (bigButton) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_level_row, if (showLevelRow) View.VISIBLE else View.GONE)
            // TINY: no room for the level row, so the level rides on the calories line.
            views.setTextViewText(R.id.widget_kcal, if (showLevelRow) d.kcalText else "${d.levelText}. ${d.kcalText}")

            for (c in 0 until 7) {
                val state = d.week.getOrElse(c) { DAY_IDLE_FUTURE }
                val bg = when (state) {
                    DAY_DONE -> R.drawable.widget_day_done
                    DAY_DONE_TODAY -> R.drawable.widget_day_done_today
                    DAY_TODAY -> R.drawable.widget_day_today
                    else -> R.drawable.widget_day_idle
                }
                val color = when (state) {
                    DAY_DONE, DAY_DONE_TODAY -> COLOR_INK
                    DAY_TODAY -> COLOR_GOLD
                    DAY_IDLE_PAST -> COLOR_FADED
                    else -> COLOR_PARCHMENT_DIM
                }
                views.setTextViewText(DAY_IDS[c], DAY_LETTERS[c])
                views.setInt(DAY_IDS[c], "setBackgroundResource", bg)
                views.setTextColor(DAY_IDS[c], color)
            }
            d.body?.let { views.setImageViewBitmap(R.id.widget_body, it) }

            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val openPi = PendingIntent.getActivity(
                context, Widgets.RC_BIG_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPi)

            val snap = Intent(context, MainActivity::class.java)
                .setAction(Widgets.ACTION_SNAP_MEAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val snapPi = PendingIntent.getActivity(
                context, Widgets.RC_BIG_SNAP_MEAL, snap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_snap_meal, snapPi)
            views.setOnClickPendingIntent(R.id.widget_snap_meal_small, snapPi)
            return views
        }

        /** Decodes a body anchor downscaled to at most [MAX_BODY_PX] high (RemoteViews bitmap size limits). */
        private fun loadBody(context: Context, resId: Int): Bitmap? {
            if (resId == 0) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(context.resources, resId, bounds)
                var sample = 1
                while (bounds.outHeight / (sample * 2) >= MAX_BODY_PX) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeResource(context.resources, resId, opts) ?: return null
                if (bmp.height <= MAX_BODY_PX) return bmp
                val w = (bmp.width * MAX_BODY_PX.toFloat() / bmp.height).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, w, MAX_BODY_PX, true)
                if (scaled != bmp) bmp.recycle()
                scaled
            } catch (e: Throwable) {
                null
            }
        }

        fun componentName(context: Context): ComponentName = ComponentName(context, HamasaWidgetProvider::class.java)
    }
}

/**
 * What fits (the week row and calories always show): TALL everything, MEDIUM hides the XP text,
 * SMALL also the protein line, COMPACT also the XP bar and uses the compact button,
 * TINY also folds the level row into the calories line.
 */
private enum class BigWidgetSize { TINY, COMPACT, SMALL, MEDIUM, TALL }

private class BigWidgetData(
    val levelText: String,
    val streakText: String,
    val xpPercent: Int,
    val xpText: String,
    val body: Bitmap?,
    val kcalText: String,
    val macroText: String,
    /** 7 day states, Sunday first (see the DAY_* constants in [HamasaWidgetProvider]). */
    val week: IntArray
)
