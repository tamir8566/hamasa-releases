package com.tamir.hamasa.ui.common

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.tamir.hamasa.R
import com.tamir.hamasa.data.AppGraph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The three fixed sounds (res/raw). */
enum class Sound { COIN, START, FINISH }

/**
 * Tiny SoundPool wrapper: loads the three sounds once, plays them on demand.
 * Respects the "צלילים" setting (Prefs.soundOn). Never throws.
 */
object Sfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<Sound, Int>()
    private val loaded = HashSet<Int>()
    private val pendingPlay = HashSet<Int>()
    @Volatile private var enabled: Boolean = true

    /** Loads the sounds (first call only). Safe to call from any screen. */
    @Synchronized
    fun init(context: Context) {
        if (pool != null) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            p.setOnLoadCompleteListener { sp, sampleId, status ->
                if (status != 0) return@setOnLoadCompleteListener
                var playNow = false
                synchronized(this) {
                    loaded.add(sampleId)
                    if (pendingPlay.remove(sampleId)) playNow = true
                }
                if (playNow && enabled) sp.play(sampleId, 1f, 1f, 1, 0, 1f)
            }
            val app = context.applicationContext
            ids[Sound.COIN] = p.load(app, R.raw.sound_coin, 1)
            ids[Sound.START] = p.load(app, R.raw.sound_start, 1)
            ids[Sound.FINISH] = p.load(app, R.raw.sound_finish, 1)
            pool = p
            AppGraph.scope.launch {
                AppGraph.prefs.profile.map { it.soundOn }.distinctUntilChanged().collect { enabled = it }
            }
        } catch (e: Throwable) {
            pool = null
        }
    }

    fun play(context: Context, sound: Sound) {
        init(context)
        if (!enabled) return
        val p = pool ?: return
        val id = ids[sound] ?: return
        try {
            val ready = synchronized(this) {
                if (id in loaded) true else { pendingPlay.add(id); false }
            }
            if (ready) p.play(id, 1f, 1f, 1, 0, 1f)
        } catch (e: Throwable) {
            // sound is decoration only
        }
    }
}
