package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SetLogEntity

/**
 * One hook at the end of WorkoutRepository.finishSession for the coach-season features: the seasonal event boss
 * and the weekly challenge. Never throws to the caller; the weekly boss and every other reward are untouched.
 */
object SeasonHooks {
    suspend fun afterFinish(s: SessionEntity, sets: List<SetLogEntity>) {
        runCatching { EventBoss.applySession(s, sets) }
        runCatching { ChallengeCenter.refresh(sessionId = s.id) }
    }
}
