package com.tamir.hamasa.data.cloud

import android.content.Context
import android.util.Log
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.prefs.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * The personal recovery code ("profile code") that identifies one user's cloud data. No email, no password:
 * every Supabase request carries the header `x-profile-code`, and the row level security policies of
 * docs/multiuser.sql only show / accept rows whose profile_code equals it.
 *
 * The code is generated once per install (12 base32 chars, shown as "XXXX-XXXX-XXXX") and kept in the
 * "cloud" SharedPreferences next to device_id. It is deliberately NOT a field of the DataStore [Profile]:
 * it is the identity, never part of profileJson / DataBackup, and the HTTP layer reads it synchronously.
 *
 * Onboarding ("יש לי קוד שחזור") and the settings card "החשבון שלי" call [restoreFromCode].
 */
object Account {
    private const val TAG = "Account"
    private const val KEY_CODE = "profile_code"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val CODE_CHARS = 12

    /** True while [restoreFromCode] replaces the local data: pushes are skipped so nothing half-wiped is uploaded. */
    @Volatile var restoring: Boolean = false
        private set

    enum class RestoreResult {
        /** The cloud data of the code is now on this device. */
        OK,
        /** Not 12 valid characters. Nothing changed. */
        BAD_CODE,
        /** The code is valid but the cloud has nothing for it. Nothing changed. */
        NOT_FOUND,
        /** docs/multiuser.sql has not run yet, so the cloud cannot tell users apart. Nothing changed. */
        SERVER_NOT_READY,
        /** No network / the cloud did not answer. Nothing changed. */
        NETWORK,
        /** Local data was cleared but the download failed midway; the new code is kept, retry restores the rest. */
        FAILED,
        /** Switching codes needs a full backup of the current data first, and it failed. Nothing changed. */
        BACKUP_FAILED
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(CloudSync.PREFS, Context.MODE_PRIVATE)

    /** This install's code, "XXXX-XXXX-XXXX". Generated (once) on first use. */
    fun profileCode(context: Context = AppGraph.appContext): String {
        val p = sp(context)
        val existing = p.getString(KEY_CODE, null)
        if (!existing.isNullOrBlank()) return existing
        synchronized(this) {
            val again = p.getString(KEY_CODE, null)
            if (!again.isNullOrBlank()) return again
            val fresh = generate()
            // commit (not apply): the code must survive a crash right after it was first shown or sent
            p.edit().putString(KEY_CODE, fresh).commit()
            return fresh
        }
    }

    /** Code for HTTP headers; null if the app context is not ready yet (never throws). */
    fun profileCodeOrNull(): String? = runCatching { profileCode(AppGraph.appContext) }.getOrNull()

    private fun setProfileCode(context: Context, code: String) {
        sp(context).edit().putString(KEY_CODE, code).commit()
    }

    private fun generate(): String {
        val rnd = SecureRandom()
        val sb = StringBuilder()
        for (i in 0 until CODE_CHARS) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        return format(sb.toString())
    }

    private fun format(raw12: String): String = raw12.substring(0, 4) + "-" + raw12.substring(4, 8) + "-" + raw12.substring(8, 12)

    /**
     * A typed code in canonical form ("XXXX-XXXX-XXXX"), or null when it is not 12 valid characters.
     * Lenient: spaces and dashes are ignored, lower case is fine, and 0 / 1 / 8 (never generated) read as O / I / B.
     */
    fun normalize(input: String): String? {
        val sb = StringBuilder()
        for (c0 in input.trim().uppercase()) {
            val c = when (c0) {
                '0' -> 'O'
                '1' -> 'I'
                '8' -> 'B'
                else -> c0
            }
            if (c == '-' || c == ' ' || c == '_') continue
            if (ALPHABET.indexOf(c) < 0) return null
            sb.append(c)
        }
        if (sb.length != CODE_CHARS) return null
        return format(sb.toString())
    }

    /**
     * Switches this device to [code] and replaces the local data with that code's cloud data.
     * Order is chosen so a typo never wipes anything: the code is validated, the server must already separate
     * users (docs/multiuser.sql), and the cloud must hold data for the code BEFORE local data is touched.
     * Progress photos (local files only, never uploaded) are kept. Call from a coroutine; runs on IO.
     */
    suspend fun restoreFromCode(code: String): RestoreResult = withContext(Dispatchers.IO) {
        val ctx = AppGraph.appContext
        val canon = normalize(code) ?: return@withContext RestoreResult.BAD_CODE
        when (CloudSync.serverSeparatesUsers(force = true)) {
            null -> return@withContext RestoreResult.NETWORK
            false -> return@withContext RestoreResult.SERVER_NOT_READY
            true -> Unit
        }
        val has = CloudSync.cloudHasData(canon) ?: return@withContext RestoreResult.NETWORK
        if (!has) return@withContext RestoreResult.NOT_FOUND
        // A restore that stopped midway left a partial copy: the retry must run the full (fresh) restore again.
        val incomplete = CloudSync.restoreIncomplete(ctx)
        if (canon == profileCode(ctx) && !localIsEmpty() && !incomplete) {
            // same code as now: nothing to switch, just fill in what is missing
            runCatching { CloudSync.pullAll(ctx, fillMissing = true) }
            return@withContext RestoreResult.OK
        }
        // Switching away from data that may not be in the cloud yet: back it up fully first, or do not switch.
        // (Skipped when there is nothing of value here: an empty fresh install, or a partial copy of a restore.)
        if (!incomplete && hasLocalData()) {
            val pushed = runCatching { CloudSync.pushAll(ctx) }.getOrDefault(false)
            if (!pushed) return@withContext RestoreResult.BACKUP_FAILED
        }
        restoring = true
        try {
            CloudSync.cancelPendingPush(ctx)
            // set before the wipe: from here until a complete pull, pushes are blocked and a retry runs fresh
            CloudSync.setRestoreIncomplete(ctx, true)
            wipeLocal()
            setProfileCode(ctx, canon)
            CloudSync.forgetDevice(ctx)
            val ok = runCatching { CloudSync.pullAll(ctx, forceFresh = true) }.getOrDefault(false)
            if (ok) RestoreResult.OK else RestoreResult.FAILED
        } catch (e: Exception) {
            Log.w(TAG, "restoreFromCode failed", e)
            RestoreResult.FAILED
        } finally {
            restoring = false
        }
    }

    private suspend fun localIsEmpty(): Boolean =
        runCatching { AppGraph.db.sessions().countAll() == 0 }.getOrDefault(true)

    /** Anything worth backing up before a switch: sessions, weights, or a completed onboarding. */
    private suspend fun hasLocalData(): Boolean {
        if (!localIsEmpty()) return true
        val weights = runCatching { AppGraph.db.weights().allOnce().isNotEmpty() }.getOrDefault(false)
        if (weights) return true
        return runCatching { AppGraph.prefs.current().onboarded }.getOrDefault(false)
    }

    /** Clears every user table (catalog exercises and progress photos stay) and resets the profile. */
    private suspend fun wipeLocal() {
        val db = AppGraph.db
        db.runInTransaction(Runnable {
            val w = db.openHelper.writableDatabase
            for (t in listOf(
                "set_logs", "sessions", "weights", "badges", "unlocked_modes", "inventory", "ledger", "meals",
                "boss_progress", "rewards", "template_items", "workout_templates", "measurements"
            )) {
                runCatching { w.execSQL("DELETE FROM $t") }.onFailure { Log.w(TAG, "wipe $t", it) }
            }
            runCatching { w.execSQL("DELETE FROM exercises WHERE isCustom = 1") }.onFailure { Log.w(TAG, "wipe customs", it) }
        })
        // Back to defaults; keep only device-local switches that must not run again or that are not user data.
        AppGraph.prefs.update { cur ->
            Profile().copy(
                economyFix1Done = cur.economyFix1Done,
                aiProvider = cur.aiProvider,
                soundOn = cur.soundOn
            )
        }
        // the shop seeds default rewards only once; the cloud list (if any) replaces it in pullAll
        AppGraph.appContext.getSharedPreferences("shop", Context.MODE_PRIVATE).edit().putBoolean("seeded", false).apply()
    }
}
