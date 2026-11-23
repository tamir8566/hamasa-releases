package com.tamir.hamasa.ui.workout

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tamir.hamasa.R
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SetLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The workout on the wrist, with no watch app: a phone notification that the watch (Galaxy Wearable / Wear OS)
 * mirrors by itself.
 *  - [show]: the next set ("סקוואט" / "סט 3 מתוך 4 · 8 חזרות · 80 ק"ג") with one button, "סט הושלם". Silent, updated
 *    in place after every set. Not ongoing on purpose: watches do not mirror ongoing notifications.
 *  - [restOver]: at the end of a rest the same card comes back on an alerting channel ("המנוחה נגמרה"), so the
 *    watch vibrates on the wrist. Only when the app is not on screen (the workout screen vibrates by itself).
 *  - "סט הושלם" ([WristActionReceiver]) records the set with its planned reps and weight: through the live workout
 *    screen when it exists (same path as the button there: rest timer, PR, boss), else straight to the db with a
 *    90 s rest. The numbers can be fixed later in the workout screen (edit a done set).
 * Never throws.
 */
object WristCard {
    private const val TAG = "WristCard"
    private const val CHANNEL = "workout_wrist"
    private const val CHANNEL_ALERT = "rest_over_wrist2"   // one long 2-second buzz; the old channel (short double buzz) is deleted
    private const val OLD_CHANNEL_ALERT = "rest_over_wrist"
    private const val ID = 4410
    private const val ID_TEST = 4413
    private const val REQ_OPEN = 4411
    private const val REQ_DONE = 4412
    /** Rest after a set done from the wrist while the workout screen is gone (no plan rest at hand). */
    const val FALLBACK_REST_SEC = 90

    data class Card(val sessionId: Long, val setId: Long, val title: String, val text: String)

    @Volatile var current: Card? = null
        private set

    /** The workout screen's ViewModel while it exists, so the wrist button takes the same path as the screen. */
    @Volatile private var live: WeakReference<WorkoutViewModel>? = null

    fun attach(vm: WorkoutViewModel) { live = WeakReference(vm) }

    fun detach(vm: WorkoutViewModel) {
        if (live?.get() === vm) live = null
    }

    private fun liveFor(sessionId: Long): WorkoutViewModel? = live?.get()?.takeIf { it.sessionId == sessionId }

    // ------------------------------------------------------------------ texts

    /** The first set not done yet, in exercise order (the order of the session's rows, grouped by exercise). */
    fun nextSet(list: List<SetLogEntity>): SetLogEntity? =
        list.groupBy { it.exerciseId }.values.flatMap { g -> g.sortedBy { it.setIndex } }.firstOrNull { !it.completed }

    /** "סט 3 מתוך 4 · 8 חזרות · 80 ק"ג" (timed: "45 שניות"; no weight: no kilos). */
    fun lineFor(set: SetLogEntity, list: List<SetLogEntity>, ex: ExerciseEntity?): String {
        val total = list.count { it.exerciseId == set.exerciseId }
        val parts = ArrayList<String>()
        parts.add("סט " + (set.setIndex + 1) + " מתוך " + total)
        if (ex?.unit == "sec") {
            if (set.targetReps > 0) parts.add(set.targetReps.toString() + " שניות")
        } else {
            if (set.targetReps > 0) parts.add(set.targetReps.toString() + " חזרות")
            if (set.targetWeight > 0f) parts.add(formatKg(set.targetWeight) + " ק\"ג")
        }
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ notification

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "האימון בשעון", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "הסט הבא וכפתור סט הושלם, גם בשעון"
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        if (nm.getNotificationChannel(OLD_CHANNEL_ALERT) != null) runCatching { nm.deleteNotificationChannel(OLD_CHANNEL_ALERT) }
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "סוף המנוחה בשעון", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "רטט ביד כשהמנוחה נגמרת"
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 2000L)
                }
            )
        }
    }

    /**
     * Shows / replaces the card silently. Under his "essential only" rule the silent card is not posted at all (the
     * state is kept, so the end of the rest still alerts with the next set), and a previous alert is taken down.
     */
    fun show(context: Context, card: Card) {
        current = card
        if (com.tamir.hamasa.data.NotifPolicy.essentialOnly(context)) {
            runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(ID) }
            return
        }
        post(context.applicationContext, card, card.title, alert = false)
    }

    /**
     * End of a rest: the same card again, vibrating (the watch too), whether the app is on screen or not.
     * True when an alerting card was posted (the caller then skips its own vibration).
     */
    fun restOver(context: Context): Boolean {
        val c = current ?: return false
        // always alerting, also while the app is on screen: the owner wants the watch to buzz at the end of every rest
        return post(context.applicationContext, c, "המנוחה נגמרה. " + c.title, alert = true)
    }

    /**
     * The settings test button: one notification on the alerting channel ("בדיקת רטט"), the same path the end of a
     * rest takes, so he can see whether the watch buzzes at all. True when it was posted (notification permission on).
     */
    fun testBuzz(context: Context): Boolean {
        val ctx = context.applicationContext
        return runCatching {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("בדיקת רטט")
                .setContentText("אם השעון רטט עכשיו, סוף המנוחה יגיע אליו.")
                .setCategory("workout")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(longArrayOf(0L, 2000L))
                .setOnlyAlertOnce(false)
                .setAutoCancel(true)
                .setTimeoutAfter(20_000L)
            NotificationManagerCompat.from(ctx).notify(ID_TEST, b.build())
            true
        }.onFailure { Log.w(TAG, "testBuzz", it) }.getOrDefault(false)
    }

    fun clear(context: Context) {
        current = null
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(ID) }
    }

    /** True when the notification was posted. */
    private fun post(ctx: Context, card: Card, title: String, alert: Boolean): Boolean {
        return runCatching {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            ensureChannels(ctx)
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val openPi = if (open != null) PendingIntent.getActivity(ctx, REQ_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
            val done = Intent(ctx, WristActionReceiver::class.java)
                .setAction(WristActionReceiver.ACTION_DONE)
                .putExtra(WristActionReceiver.EXTRA_SESSION, card.sessionId)
                .putExtra(WristActionReceiver.EXTRA_SET, card.setId)
            val donePi = PendingIntent.getBroadcast(ctx, REQ_DONE, done, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(ctx, if (alert) CHANNEL_ALERT else CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle(title)
                .setContentText(card.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(card.text))
                .setCategory("workout")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setAutoCancel(false)
                .addAction(0, "סט הושלם", donePi)
            if (alert) {
                b.setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVibrate(longArrayOf(0L, 2000L))
                    .setOnlyAlertOnce(false)
            } else {
                b.setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
            }
            if (openPi != null) b.setContentIntent(openPi)
            NotificationManagerCompat.from(ctx).notify(ID, b.build())
            true
        }.onFailure { Log.w(TAG, "post", it) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ the wrist button

    /**
     * "סט הושלם" from the watch. The set must still be the open set of an ACTIVE session; anything else (already
     * done from the phone, session finished) only refreshes the card.
     */
    suspend fun completeFromWrist(context: Context, sessionId: Long, setId: Long) {
        val app = context.applicationContext
        val db = AppGraph.db
        val session = db.sessions().byId(sessionId)
        if (session == null || session.status != SessionStatus.ACTIVE) {
            clear(app)
            return
        }
        val set = db.sessions().setById(setId)
        if (set == null || set.completed || set.sessionId != sessionId) {
            refresh(app, sessionId)
            return
        }
        val vm = liveFor(sessionId)
        if (vm != null) {
            // the screen's own path: rest from the plan, PR banner, boss hit; its sets flow updates the card
            vm.completeSet(set, set.targetReps, set.targetWeight)
            return
        }
        AppGraph.workouts.completeSetChecked(set, set.targetReps, set.targetWeight)
        val list = db.sessions().setsForOnce(sessionId)
        refresh(app, sessionId, list)
        val next = nextSet(list)
        if (next != null) {
            val name = db.exercises().byId(next.exerciseId)?.nameHe ?: next.exerciseId
            RestAlerts.show(app, System.currentTimeMillis() + FALLBACK_REST_SEC * 1000L, "הבא: " + name + ", סט " + (next.setIndex + 1))
        }
    }

    /** Rebuilds the card from the db (or clears it when nothing is left). */
    suspend fun refresh(context: Context, sessionId: Long, known: List<SetLogEntity>? = null) {
        val app = context.applicationContext
        val list = known ?: AppGraph.db.sessions().setsForOnce(sessionId)
        val next = nextSet(list)
        if (next == null) {
            clear(app)
            return
        }
        val ex = AppGraph.db.exercises().byId(next.exerciseId)
        show(app, Card(sessionId, next.id, ex?.nameHe ?: next.exerciseId, lineFor(next, list, ex)))
    }
}

/** The "סט הושלם" button of [WristCard] (on the phone or mirrored on the watch). Explicit intent only. */
class WristActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DONE) return
        val sessionId = intent.getLongExtra(EXTRA_SESSION, -1L)
        val setId = intent.getLongExtra(EXTRA_SET, -1L)
        if (sessionId < 0L || setId < 0L) return
        val app = context.applicationContext
        val pending = runCatching { goAsync() }.getOrNull()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                WristCard.completeFromWrist(app, sessionId, setId)
            } catch (t: Throwable) {
                Log.w("WristCard", "done", t)
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.tamir.hamasa.WRIST_SET_DONE"
        const val EXTRA_SESSION = "session"
        const val EXTRA_SET = "set"
    }
}
