package com.tamir.hamasa.data.repo

import android.util.Log
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.BodyStage

/**
 * One-time body-stage calibration for installs that were onboarded before calibration existed (Tamir's phone).
 * Sets stageAnchorStage / stageAnchorWeight from the legacy formula and the latest weight, so the body shown
 * today stays exactly the same (BodyStage.legacyAnchor explains why the line is identical). New users get their
 * anchors from onboarding instead. Until this runs, BodyStage.forProfile uses the legacy mapping, which shows
 * the same body anyway.
 */
object BodyCalibration {
    private const val TAG = "BodyCalibration"

    suspend fun migrateIfNeeded() {
        try {
            val p = AppGraph.prefs.current()
            if (!p.onboarded || BodyStage.hasAnchors(p)) return
            val latest = AppGraph.db.weights().latestOnce()?.kg ?: p.startWeightKg
            AppGraph.prefs.update { cur ->
                if (!cur.onboarded || BodyStage.hasAnchors(cur)) cur
                else {
                    val (stage, kg) = BodyStage.legacyAnchor(latest, cur.startWeightKg, cur.goalWeightKg)
                    cur.copy(stageAnchorStage = stage, stageAnchorWeight = kg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "calibration migration failed", e)
        }
    }
}
