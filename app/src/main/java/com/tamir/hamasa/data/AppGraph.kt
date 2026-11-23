package com.tamir.hamasa.data

import android.content.Context
import com.tamir.hamasa.data.db.AppDatabase
import com.tamir.hamasa.data.prefs.Prefs
import com.tamir.hamasa.data.repo.GameRepository
import com.tamir.hamasa.data.repo.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Tiny service locator. No DI framework to keep the build simple. */
object AppGraph {
    lateinit var appContext: Context; private set
    lateinit var db: AppDatabase; private set
    lateinit var prefs: Prefs; private set
    lateinit var workouts: WorkoutRepository; private set
    lateinit var game: GameRepository; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        db = AppDatabase.build(context)
        prefs = Prefs(context)
        game = GameRepository(db, prefs)
        workouts = WorkoutRepository(db, prefs, game)
        // hero variant + how the app addresses the player, kept in sync for every text that is built
        scope.launch {
            prefs.profile.collect { p ->
                com.tamir.hamasa.domain.game.CharacterNames.set(p.characterVariant)
                com.tamir.hamasa.domain.game.Addr.set(p.userGender)
            }
        }
        scope.launch {
            workouts.seedIfNeeded()
            com.tamir.hamasa.data.repo.EconomyFix.runOnce()
            com.tamir.hamasa.data.repo.BodyCalibration.migrateIfNeeded()
        }
    }
}
