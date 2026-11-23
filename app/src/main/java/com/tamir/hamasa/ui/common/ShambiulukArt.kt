package com.tamir.hamasa.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.game.BodyBlend

/** Painter for a drawable-nodpi asset by name; null when the resource is missing (agent E's assets). */
@Composable
fun rememberNamedPainter(name: String): Painter? {
    val context = LocalContext.current
    val id = remember(name) { context.drawableId(name) }
    return if (id == 0) null else painterResource(id)
}

/**
 * Shambiuluk drawn from two anchors crossfaded by [blend] (THE IRON RULE: blend comes from body weight only).
 * Both anchors share one canvas size, so drawing B over A with alpha = t lines up.
 */
@Composable
fun ShambiulukFigure(blend: BodyBlend, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val names = Assets.characterAnchors
    // one image, the nearer stage (no crossfade: two bodies at once looked like a second figure behind him)
    val index = if (blend.t >= 0.5f && blend.anchorB != blend.anchorA) blend.anchorB else blend.anchorA
    val a = rememberNamedPainter(names[index.coerceIn(0, names.size - 1)])
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        if (a != null) {
            Image(painter = a, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** Highest map region that has its own scene (איי השמיים); anything beyond repeats it. */
const val MAX_SCENE_REGION = 10

/**
 * Home-scene background layers (far, mid, near drawable names) for a map region.
 * Regions 0..1 (הכפר, הגבעות) share the starting misty-forest ledge; regions 2..10 have their own
 * bg_r{n}_* layers (היער, הנהר, הביצה, המדבר, הקניון, הלבה, היער הקפוא, ההרים, איי השמיים).
 * All three layers of a region share one 1080x1920 canvas (mid/near carry transparency), so they line up exactly.
 */
fun sceneFor(region: Int): Triple<String, String, String> {
    val r = region.coerceIn(0, MAX_SCENE_REGION)
    if (r < 2) return Triple(Assets.homeBgFar, Assets.homeBgMid, Assets.homeBgNear)
    return Triple("bg_r${r}_far", "bg_r${r}_mid", "bg_r${r}_near")
}

fun focusLabelHe(f: Focus): String = when (f) {
    Focus.LOSE_WEIGHT -> "ירידה במשקל"
    Focus.BUILD_MUSCLE -> "בניית שריר"
    Focus.STRENGTH -> "כוח"
    Focus.FLEXIBILITY -> "גמישות"
    Focus.ENDURANCE -> "סיבולת"
    Focus.BRANCH_CHEST -> "דגש חזה"
    Focus.BRANCH_BACK -> "דגש גב"
    Focus.BRANCH_LEGS -> "דגש רגליים"
    Focus.BRANCH_SHOULDERS_ARMS -> "דגש כתפיים וידיים"
    Focus.BRANCH_CORE -> "דגש ליבה"
}

fun sessionTypeLabelHe(t: SessionType): String = when (t) {
    SessionType.GYM -> "מכון"
    SessionType.YOGA -> "יוגה"
    SessionType.WALK -> "הליכה"
    SessionType.MINI -> "אימון זעיר"
    SessionType.RUN -> "ריצה"
}

/** Simple hour picker (no experimental APIs): a button that opens a dropdown of hours. */
@Composable
fun HourDropdown(label: String, hour: Int, range: IntRange = 0..23, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text(String.format("%s %02d:00", label, hour))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (h in range) {
                DropdownMenuItem(text = { Text(String.format("%02d:00", h)) }, onClick = { open = false; onPick(h) })
            }
        }
    }
}
