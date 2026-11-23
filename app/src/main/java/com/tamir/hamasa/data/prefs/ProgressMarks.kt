package com.tamir.hamasa.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.tamir.hamasa.data.AppGraph

/**
 * Small local-only marks for the progress features (monthly summary seen, photo card dismissed, Shambiuluk tap
 * history). Own SharedPreferences file, on purpose outside [Prefs]: never synced to the cloud, never exported.
 * Losing them only means a card shows once more.
 */
object ProgressMarks {
    private const val FILE = "progress_marks"
    private const val K_SUMMARY_SEEN = "summarySeenMonth"
    private const val K_PHOTO_DISMISSED = "photoCardDismissedMonth"
    private const val K_TAP_RECENT = "tapRecent"
    private const val K_AI_DAY = "tapAiDay"
    private const val K_AI_USED = "tapAiUsed"
    private const val K_AI_ASKED = "tapAiAsked"

    private fun sp(): SharedPreferences? =
        runCatching { AppGraph.appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }.getOrNull()

    /** yyyy*12+month key, the same scheme Prefs uses for monthly flags. */
    fun monthKey(year: Int, month: Int): Int = year * 12 + month

    var summarySeenMonth: Int
        get() = sp()?.getInt(K_SUMMARY_SEEN, 0) ?: 0
        set(v) { sp()?.edit()?.putInt(K_SUMMARY_SEEN, v)?.apply() }

    var photoCardDismissedMonth: Int
        get() = sp()?.getInt(K_PHOTO_DISMISSED, 0) ?: 0
        set(v) { sp()?.edit()?.putInt(K_PHOTO_DISMISSED, v)?.apply() }

    /** Ids of the last tap lines, newest first. */
    fun recentTapIds(): List<String> =
        (sp()?.getString(K_TAP_RECENT, "") ?: "").split(',').filter { it.isNotBlank() }

    fun pushTapId(id: String, keep: Int = 10) {
        val list = (listOf(id) + recentTapIds().filter { it != id }).take(keep)
        sp()?.edit()?.putString(K_TAP_RECENT, list.joinToString(","))?.apply()
    }

    /** Gemini tap lines shown today / requests made today. Both reset when the day changes. */
    fun aiUsedToday(today: Long): Int = if ((sp()?.getLong(K_AI_DAY, 0L) ?: 0L) == today) sp()?.getInt(K_AI_USED, 0) ?: 0 else 0
    fun aiAskedToday(today: Long): Int = if ((sp()?.getLong(K_AI_DAY, 0L) ?: 0L) == today) sp()?.getInt(K_AI_ASKED, 0) ?: 0 else 0

    fun markAiUsed(today: Long) = bump(today, used = true)
    fun markAiAsked(today: Long) = bump(today, used = false)

    private fun bump(today: Long, used: Boolean) {
        val s = sp() ?: return
        val sameDay = s.getLong(K_AI_DAY, 0L) == today
        val u = if (sameDay) s.getInt(K_AI_USED, 0) else 0
        val a = if (sameDay) s.getInt(K_AI_ASKED, 0) else 0
        s.edit()
            .putLong(K_AI_DAY, today)
            .putInt(K_AI_USED, if (used) u + 1 else u)
            .putInt(K_AI_ASKED, if (used) a else a + 1)
            .apply()
    }
}
