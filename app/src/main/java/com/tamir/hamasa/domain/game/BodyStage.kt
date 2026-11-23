package com.tamir.hamasa.domain.game

import com.tamir.hamasa.data.prefs.Profile
import kotlin.math.abs

/**
 * THE IRON RULE: the body of Shambiuluk depends ONLY on body weight. Never on level/xp/workouts.
 * Anchor index 0 = heaviest image (stage 1), index ANCHORS-1 = athletic (stage 10).
 * [t] is the crossfade from [anchorA] to [anchorB].
 */
data class BodyBlend(val anchorA: Int, val anchorB: Int, val t: Float, val beyondGoal: Boolean)

/** Which way the calibrated mapping runs (from the anchor weight toward the goal weight). */
enum class BodyDirection { LOSS, MAINTAIN, GAIN }

object BodyStage {
    /** Number of body-stage images (sh_anchor_1..sh_anchor_10 / shf_anchor_1..10). */
    const val ANCHORS = 10

    /** Slope used when the anchors give none (maintain goal, or the user picked the final stage): kg per stage. */
    const val DEFAULT_KG_PER_STAGE = 2.5f

    /** Goal within this many kg of the anchor weight = maintain. */
    const val MAINTAIN_BAND_KG = 0.5f

    /**
     * Calibrated mapping (per user, still a pure function of the weight):
     *  - the user's chosen stage [anchorStage] (1..10, may be fractional) is pinned at [anchorKg];
     *  - stage 10 is pinned at [goalKg];
     *  - LOSS (goal lighter): position = a0 + (anchorKg - kg) * rate, rate = (9 - a0) / (anchorKg - goalKg).
     *    Lighter than the goal stays at stage 10 (beyondGoal). Heavier than the anchor continues on the same line
     *    toward stage 1 (clamped).
     *  - GAIN (goal heavier, muscle): position = a0 + (kg - anchorKg) * rate, rate = (9 - a0) / (goalKg - anchorKg).
     *    Heavier than the goal stays at stage 10 (beyondGoal). Lighter than the anchor goes toward stage 1 on the
     *    same line (clamped).
     *  - MAINTAIN (goal within 0.5 kg): lighter = toward stage 10, heavier = toward stage 1, at
     *    [DEFAULT_KG_PER_STAGE] kg per stage. Same default slope when the user picked stage 10 already.
     * a0 = anchorStage - 1 (0-based). Positions are clamped to 0..9; the integer part is anchorA, the fraction is t.
     */
    fun calibrated(currentKg: Float, anchorStage: Float, anchorKg: Float, goalKg: Float, anchorCount: Int = ANCHORS): BodyBlend {
        val last = (anchorCount - 1).coerceAtLeast(0).toFloat()
        val a0 = (anchorStage - 1f).coerceIn(0f, last)
        val dir = directionOf(anchorKg, goalKg)
        val span = abs(anchorKg - goalKg)
        val rate = if (dir != BodyDirection.MAINTAIN && last - a0 > 0.001f && span > 0.001f) (last - a0) / span else 1f / DEFAULT_KG_PER_STAGE
        val towardKg = if (dir == BodyDirection.GAIN) currentKg - anchorKg else anchorKg - currentKg
        val pos = (a0 + towardKg * rate).coerceIn(0f, last)
        val beyond = when (dir) {
            BodyDirection.LOSS -> currentKg < goalKg
            BodyDirection.GAIN -> currentKg > goalKg
            BodyDirection.MAINTAIN -> false
        }
        return toBlend(pos, last.toInt(), beyond)
    }

    fun directionOf(anchorKg: Float, goalKg: Float): BodyDirection = when {
        goalKg < anchorKg - MAINTAIN_BAND_KG -> BodyDirection.LOSS
        goalKg > anchorKg + MAINTAIN_BAND_KG -> BodyDirection.GAIN
        else -> BodyDirection.MAINTAIN
    }

    /**
     * The original fixed mapping (before calibration): the range start..goal maps linearly onto the anchors.
     * Used only when a profile has no anchors yet (old install before [legacyAnchor] ran).
     */
    fun legacyBlend(currentKg: Float, startKg: Float, goalKg: Float, anchorCount: Int = ANCHORS): BodyBlend {
        val last = (anchorCount - 1).coerceAtLeast(0)
        if (startKg <= goalKg) return BodyBlend(last, last, 0f, currentKg <= goalKg)
        val beyond = currentKg < goalKg
        val p = ((startKg - currentKg) / (startKg - goalKg)).coerceIn(0f, 1f)   // 0 at start, 1 at goal
        return toBlend(p * last, last, beyond)
    }

    /**
     * Anchors (stage 1..10, kg) for an install that had none, chosen so the displayed body stays exactly the same:
     *  - start > goal and the latest weight strictly between goal + 0.5 and start: the anchor is today's legacy
     *    position at today's weight. The calibrated line through it and (goal, 10) is the legacy line itself.
     *  - start > goal + 0.5 otherwise: anchor (start, stage 1), which is again the legacy line.
     *  - start <= goal (legacy always showed the final stage): anchor (latest, stage 10).
     */
    fun legacyAnchor(latestKg: Float, startKg: Float, goalKg: Float): Pair<Float, Float> {
        val last = (ANCHORS - 1).toFloat()
        if (startKg > goalKg + MAINTAIN_BAND_KG) {
            if (latestKg > goalKg + MAINTAIN_BAND_KG && latestKg < startKg) {
                val pos = (startKg - latestKg) / (startKg - goalKg) * last
                return Pair(1f + pos, latestKg)
            }
            return Pair(1f, startKg)
        }
        if (startKg > goalKg) return Pair(1f + legacyPosition(latestKg, startKg, goalKg), latestKg)
        return Pair(ANCHORS.toFloat(), latestKg)
    }

    private fun legacyPosition(currentKg: Float, startKg: Float, goalKg: Float): Float {
        val last = (ANCHORS - 1).toFloat()
        if (startKg <= goalKg) return last
        return ((startKg - currentKg) / (startKg - goalKg)).coerceIn(0f, 1f) * last
    }

    /** True when the profile carries calibration anchors. */
    fun hasAnchors(p: Profile): Boolean = p.stageAnchorStage >= 1f && p.stageAnchorWeight > 0f

    /** The body for [currentKg] for this profile: calibrated when anchors exist, else the legacy mapping. */
    fun forProfile(currentKg: Float, p: Profile): BodyBlend =
        if (hasAnchors(p)) calibrated(currentKg, p.stageAnchorStage, p.stageAnchorWeight, p.goalWeightKg)
        else legacyBlend(currentKg, p.startWeightKg, p.goalWeightKg)

    private fun toBlend(scaled: Float, last: Int, beyond: Boolean): BodyBlend {
        val a = scaled.toInt().coerceIn(0, last)
        val b = (a + 1).coerceAtMost(last)
        val t = if (a == b) 0f else (scaled - a).coerceIn(0f, 1f)
        return BodyBlend(a, b, t, beyond)
    }
}
