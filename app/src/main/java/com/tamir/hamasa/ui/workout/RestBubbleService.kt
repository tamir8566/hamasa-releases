package com.tamir.hamasa.ui.workout

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.tamir.hamasa.data.update.UpdateCenter
import java.util.Locale

/**
 * The rest timer as a small floating circle over other apps (needs "הצגה מעל אפליקציות אחרות").
 * Started by [RestAlerts.show], stopped by [RestAlerts.cancel] or by itself when the rest ends.
 * Visible only while the app is not on screen; a tap brings the app back; it can be dragged.
 * Never throws: without the permission it just stops.
 */
class RestBubbleService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var endAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val left = endAt - System.currentTimeMillis()
            if (left <= 0L) { stopSelf(); return }
            val sec = ((left + 999L) / 1000L).toInt()
            view?.text = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
            view?.visibility = if (UpdateCenter.isForeground) View.GONE else View.VISIBLE
            handler.postDelayed(this, 250L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        endAt = intent?.getLongExtra(EXTRA_END, 0L) ?: 0L
        if (endAt <= System.currentTimeMillis() || !canDraw(this)) { stopSelf(); return START_NOT_STICKY }
        runCatching { ensureView() }.onFailure { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun ensureView() {
        if (view != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val size = (68 * density).toInt()
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#14171A"))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E8B04B"))
                setStroke((3 * density).toInt(), Color.parseColor("#14171A"))
            }
            alpha = 0.92f
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (140 * density).toInt()
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(tv, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) openApp()
            }
            true
        }
        wm.addView(tv, lp)
        view = tv
        params = lp
    }

    private fun openApp() {
        runCatching {
            val i = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i != null) startActivity(i)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        val v = view
        if (v != null) runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        view = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_END = "end"

        fun canDraw(ctx: Context): Boolean = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

        fun start(ctx: Context, endAtMillis: Long) {
            runCatching {
                if (!canDraw(ctx)) return
                ctx.startService(Intent(ctx, RestBubbleService::class.java).putExtra(EXTRA_END, endAtMillis))
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, RestBubbleService::class.java)) }
        }
    }
}
