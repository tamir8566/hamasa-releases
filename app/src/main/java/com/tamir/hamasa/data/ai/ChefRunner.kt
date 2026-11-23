package com.tamir.hamasa.data.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tamir.hamasa.R
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.domain.food.MacroTargets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs a chef question outside the screen: the request lives in the app scope, so leaving the food screen or the
 * app does not cancel it. The answer lands in ChefStore (the card shows it whenever it is opened again), and when
 * he is not looking at the chef card at that moment a notification "השף ענה" brings him back to the food screen.
 * Never throws.
 */
object ChefRunner {
    const val ACTION_OPEN_CHEF = "com.tamir.hamasa.ACTION_OPEN_CHEF"
    private const val CHANNEL = "chef_answers"
    private const val NOTIF_ID = 4420

    /** The question being answered right now (null = none). */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    /** True while the chef card is on screen with the app in front (set by the card). */
    @Volatile var cardVisible: Boolean = false

    /** Starts one question; ignored while another one is running. */
    fun send(
        context: Context,
        messageHe: String,
        targets: MacroTargets,
        meals: List<MealEntity>,
        bodyweightKg: Float,
        kind: MealSuggester.MealKind
    ) {
        val msg = messageHe.trim()
        if (msg.isEmpty() || _pending.value != null) return
        _pending.value = msg
        val app = context.applicationContext
        ChefKeepAliveService.start(app)
        AppGraph.scope.launch {
            try {
                var reply = runCatching { Chef.ask(msg, targets, meals, bodyweightKg, kind) }.getOrDefault(Chef.Reply.Failed)
                if (reply == Chef.Reply.Failed && !com.tamir.hamasa.data.update.UpdateCenter.isForeground) {
                    // a connection cut while the app was left: wait until he is back in the app (up to 10 minutes), ask once more.
                    // (In the foreground the failure is real: no second round, he already waited once.)
                    var waited = 0
                    while (!com.tamir.hamasa.data.update.UpdateCenter.isForeground && waited < 600) {
                        kotlinx.coroutines.delay(1000L)
                        waited++
                    }
                    reply = runCatching { Chef.ask(msg, targets, meals, bodyweightKg, kind) }.getOrDefault(Chef.Reply.Failed)
                }
                ChefStore.addLine(ChefLine(false, msg))
                val line = when (reply) {
                    is Chef.Reply.Ok -> {
                        val added = reply.learnedHe.isNotEmpty() && ChefStore.addFacts(reply.learnedHe)
                        val text = if (added) reply.textHe + "\nרשמתי לעצמי: " + reply.learnedHe.joinToString(". ") else reply.textHe
                        ChefLine(true, text, reply.recipes)
                    }
                    Chef.Reply.NoKey -> ChefLine(true, "השף צריך את הבינה המלאכותית, והיא כבויה באפליקציה הזאת.")
                    Chef.Reply.Failed -> ChefLine(true, "אין תשובה כרגע. אפשר לנסות שוב." + failureHe())
                }
                ChefStore.addLine(line)
                val looking = cardVisible && com.tamir.hamasa.data.update.UpdateCenter.isForeground
                if (!looking) notifyAnswer(app, line)
            } finally {
                ChefKeepAliveService.stop(app)
                _pending.value = null
            }
        }
    }

    /** The reason of the failure, in Hebrew, so a stuck chef can be reported precisely ("סיבה: ..."). */
    private fun failureHe(): String {
        val raw = Chef.lastFailure ?: return ""
        val he = when {
            raw.contains("timeout", ignoreCase = true) -> "השרת לא ענה בזמן"
            raw.contains("no usable") -> "התשובה הגיעה בלי מתכון שאפשר להשתמש בו"
            raw.contains("429") -> "יותר מדי בקשות, לחכות דקה"
            raw.contains("401") || raw.contains("403") -> "המפתח של הבינה המלאכותית נדחה"
            raw.contains("404") -> "המודל לא נמצא"
            raw.contains("no key") -> "אין מפתח"
            else -> "תקלה בחיבור"
        }
        return "\nסיבה: $he. (" + raw.take(160) + ")"
    }

    /** Back on the chef card: the answer is in front of him, the notification is not needed any more. */
    fun clearNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(NOTIF_ID) }
    }

    private fun notifyAnswer(ctx: Context, line: ChefLine) {
        if (com.tamir.hamasa.data.NotifPolicy.essentialOnly(ctx)) return
        runCatching {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "תשובות השף", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "כשהשף עונה ומסך האוכל לא פתוח"
                        setShowBadge(true)
                    }
                )
            }
            val open = Intent(ctx, com.tamir.hamasa.MainActivity::class.java)
                .setAction(ACTION_OPEN_CHEF)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val names = line.recipes.joinToString(", ") { it.nameHe }
            val body = if (names.isNotEmpty()) names else line.textHe
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("השף ענה")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(line.textHe + if (names.isNotEmpty()) "\n" + names else ""))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build())
        }
    }
}
