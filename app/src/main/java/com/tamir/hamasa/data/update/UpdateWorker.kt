package com.tamir.hamasa.data.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import kotlinx.coroutines.flow.first

/**
 * Every ~2 hours on any network (mobile data too): [UpdateCenter.backgroundUpdate] (silent install, or a
 * "יש גרסה חדשה" notification). Never fails or retries.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            // the notification text follows the player's / hero's grammar: read the stored choice first
            runCatching {
                val p = AppGraph.prefs.profile.first()
                CharacterNames.set(p.characterVariant)
                Addr.set(p.userGender)
            }
            UpdateCenter.backgroundUpdate(applicationContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("UpdateWorker", "background update failed", e)
        }
        return Result.success()
    }
}
