package com.tamir.hamasa.ui.workout

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tamir.hamasa.R

/**
 * The rest timer outside the app:
 *  - [show]: an ongoing, silent notification with a live countdown (Android's own chronometer, so it keeps
 *    ticking on the lock screen with no updates from the app); tapping it brings the workout back.
 *  - [ping]: a short beep through the MEDIA stream when the rest is over, so it is heard in the earphones over
 *    the music (the music is ducked for a moment through transient audio focus, then comes back).
 *  - [show] also sets an alarm at the end of the rest ([RestAlarmReceiver] plays [ping]), so the beep comes even
 *    when the system froze or killed the backgrounded app and the ViewModel's countdown never reaches its end.
 *    Exact when allowed (USE_EXACT_ALARM, Android 13+; SCHEDULE_EXACT_ALARM on 12), else inexact while-idle.
 *    When the ViewModel is alive it pings too: [ping] ignores a second call within [PING_DEDUP_MS], so one beep.
 * Never throws.
 */
object RestAlerts {
    /**
     * IMPORTANCE_DEFAULT (still silent: no sound, no vibration on the channel, setSilent on the notification): many
     * phones hide LOW ("silent") notifications on the lock screen, which is exactly where this countdown matters.
     * A channel's importance cannot change after creation, hence the new id; the old LOW channel is deleted.
     */
    private const val CHANNEL = "rest_countdown"
    private const val OLD_CHANNEL = "rest_timer"
    private const val ID = 4401
    private const val ALARM_REQ = 4402
    private const val PING_DEDUP_MS = 3_000L

    @Volatile private var lastPingAt = 0L
    /** End time of the rest that [show] set up last, and of the rest that was already pinged (one ping per rest). */
    @Volatile private var scheduledEnd = 0L
    @Volatile private var pingedEnd = 0L
    private val pingLock = Any()

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(OLD_CHANNEL) != null) runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "טיימר מנוחה", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "הזמן שנשאר למנוחה, גם במסך הנעול"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    /** Shows / replaces the countdown that ends at [endAtMillis]. [nextHe] e.g. "הבא: סקוואט, סט 3". */
    fun show(context: Context, endAtMillis: Long, nextHe: String) {
        scheduledEnd = endAtMillis
        runCatching { scheduleAlarm(context.applicationContext, endAtMillis) }
        RestBubbleService.start(context.applicationContext, endAtMillis)
        // his rule: no countdown notification, only the end-of-rest alert (the alarm and the bubble still run)
        if (com.tamir.hamasa.data.NotifPolicy.essentialOnly(context)) return
        runCatching {
            val ctx = context.applicationContext
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            ensureChannel(ctx)
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = if (open != null) PendingIntent.getActivity(ctx, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
            val left = (endAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("מנוחה")
                .setContentText(nextHe)
                .setWhen(endAtMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // gone by itself shortly after the end, even if the app was killed meanwhile
                .setTimeoutAfter(left + 5_000L)
            if (pi != null) b.setContentIntent(pi)
            NotificationManagerCompat.from(ctx).notify(ID, b.build())
        }
    }

    /** Removes the countdown and the end-of-rest alarm. */
    fun cancel(context: Context) {
        runCatching { cancelAlarm(context.applicationContext) }
        RestBubbleService.stop(context.applicationContext)
        cancelNotification(context)
    }

    /** Only the countdown notification (the alarm already fired). */
    fun cancelNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(ID) }
    }

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, RestAlarmReceiver::class.java).setAction(RestAlarmReceiver.ACTION)
        return PendingIntent.getBroadcast(ctx, ALARM_REQ, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun scheduleAlarm(ctx: Context, endAtMillis: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactOk) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, pi)
                return
            } catch (e: SecurityException) {
                // permission revoked between the check and the call: inexact below
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, pi)
    }

    private fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmIntent(ctx))
    }

    /**
     * Two short beeps on the media stream (earphones), with the music ducked for a moment.
     * One ping per rest: the alarm ([RestAlarmReceiver]) and the ViewModel's countdown both call this at the end of
     * the same rest; whichever comes second is ignored, even when it comes much later (a frozen app catching up
     * when it is opened again). A second call within [PING_DEDUP_MS] is ignored too (a rest with no [show]).
     * With the app not on screen (locked phone, another app) it also vibrates: the workout screen's own vibration
     * runs only while the screen is visible.
     */
    fun ping(context: Context) {
        val now = System.currentTimeMillis()
        synchronized(pingLock) {
            if (!shouldPing(now)) return
        }
        // the wrist card comes back vibrating (the watch mirrors it); its channel vibrates the phone too
        val wristAlerted = runCatching { WristCard.restOver(context.applicationContext) }.getOrDefault(false)
        runCatching {
            if (!wristAlerted && !com.tamir.hamasa.data.update.UpdateCenter.isForeground) vibrate(context.applicationContext)
        }
        runCatching {
            val ctx = context.applicationContext
            val am = ctx.getSystemService(AudioManager::class.java)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            runCatching { am?.requestAudioFocus(focus) }
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { tone.release() }
                runCatching { am?.abandonAudioFocusRequest(focus) }
            }, 900L)
        }
    }

    /** The de-duplication decision (call under [pingLock]); records the ping when it says yes. */
    internal fun shouldPing(now: Long): Boolean {
        val end = scheduledEnd
        if (end > 0L && pingedEnd == end) return false
        val since = now - lastPingAt
        if (since >= 0L && since < PING_DEDUP_MS) return false
        lastPingAt = now
        pingedEnd = end
        return true
    }

    /** For tests: forget the last rest and ping. */
    internal fun resetForTest() {
        synchronized(pingLock) {
            lastPingAt = 0L
            scheduledEnd = 0L
            pingedEnd = 0L
        }
    }

    private fun vibrate(ctx: Context) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 300L, 150L, 300L), -1))
    }
}
