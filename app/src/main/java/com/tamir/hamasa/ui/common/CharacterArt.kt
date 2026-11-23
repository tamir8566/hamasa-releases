package com.tamir.hamasa.ui.common

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.BodyBlend
import com.tamir.hamasa.domain.game.BodyStage
import com.tamir.hamasa.domain.game.CharacterNames
import kotlinx.coroutines.flow.map

/**
 * Character body images by variant. Male: sh_anchor_1..10. Female: shf_anchor_1..10.
 * A missing female image falls back to the male one with the same number, so nothing breaks before the art exists.
 * Indexes are 0-based (0 = stage 1, heaviest; 9 = stage 10, athletic).
 */
object CharacterArt {
    fun maleName(index: Int): String = "sh_anchor_" + (index.coerceIn(0, BodyStage.ANCHORS - 1) + 1)

    fun femaleName(index: Int): String = "shf_anchor_" + (index.coerceIn(0, BodyStage.ANCHORS - 1) + 1)

    /** Drawable id for the anchor [index] of [variant]; 0 only when even the male image is missing. */
    fun anchorResId(context: Context, variant: String?, index: Int): Int {
        if (CharacterNames.isFemale(variant)) {
            val f = context.drawableId(femaleName(index))
            if (f != 0) return f
        }
        return context.drawableId(maleName(index))
    }
}

@Composable
private fun rememberAnchorPainter(variant: String?, index: Int): Painter? {
    val context = LocalContext.current
    val v = CharacterNames.normalize(variant)
    val id = remember(v, index) { CharacterArt.anchorResId(context, v, index) }
    return if (id == 0) null else painterResource(id)
}

/**
 * The player's character drawn from two anchors crossfaded by [blend] (THE IRON RULE: blend comes from body weight
 * only, via BodyStage.forProfile). Same layout as ShambiulukFigure, plus the variant.
 */
@Composable
fun CharacterFigure(
    blend: BodyBlend,
    variant: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    // One image, the nearer stage. A crossfade of two stages showed both bodies at once ("a second figure behind
    // him"): the poses differ between stages, so the fading one left ghost arms and a double outline.
    val index = if (blend.t >= 0.5f && blend.anchorB != blend.anchorA) blend.anchorB else blend.anchorA
    val a = rememberAnchorPainter(variant, index)
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        if (a != null) {
            Image(painter = a, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** One fixed stage image (0-based [index]) of [variant], e.g. for the onboarding pickers. */
@Composable
fun CharacterStageImage(variant: String?, index: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val p = rememberAnchorPainter(variant, index)
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        if (p != null) Image(painter = p, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
    }
}

/** The character's Hebrew name for the current profile (CharacterNames.he), for titles that show the name. */
@Composable
fun rememberCharacterNameHe(): String {
    val variant by remember { AppGraph.prefs.profile.map { it.characterVariant } }.collectAsState(initial = CharacterNames.MALE)
    return CharacterNames.he(variant)
}
