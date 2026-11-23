package com.tamir.hamasa.ui.tree

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.ui.common.LevelLockTeaser
import kotlinx.coroutines.flow.map
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.domain.game.StatEffects
import com.tamir.hamasa.domain.tree.TreeModel
import com.tamir.hamasa.ui.common.Assets
import com.tamir.hamasa.ui.common.cropCellScaled
import com.tamir.hamasa.ui.common.drawableId
import com.tamir.hamasa.ui.common.rememberNamedPainter
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The painted oak (res/drawable-nodpi/tree.png), 1536 x 2752. */
private const val TREE_ASSET = "tree"
private const val TREE_ASPECT = 1536f / 2752f

/** Background colour of the painting, so the letterbox bars around a Fit image blend in. */
private val TreeBg = Color(0xFF2F3741)
private val MedallionOff = Color(0xFF6B7078)

/** One short word per medallion so it always fits inside the circle (the dialog shows the full name). */
private fun medallionLabel(b: Branch): String = when (b) {
    Branch.SHOULDERS_ARMS -> "ידיים"
    else -> TreeModel.nameHe(b)
}

/** Medallion centres as fractions of the drawn image (x to the right, y down). */
private val MEDALLIONS: List<Pair<Branch, Pair<Float, Float>>> = listOf(
    Branch.CHEST to (0.33f to 0.13f),
    Branch.BACK to (0.713f to 0.12f),
    Branch.SHOULDERS_ARMS to (0.102f to 0.272f),
    Branch.CORE to (0.900f to 0.284f),
    Branch.LEGS to (0.122f to 0.502f),
    Branch.ENDURANCE to (0.888f to 0.496f)
)

/** Growth stage derived from the total of the six branch levels. Never goes down because nothing is taken away. */
private fun stageIndex(total: Int): Int = when {
    total < 8 -> 0
    total < 20 -> 1
    total < 36 -> 2
    else -> 3
}

private fun stageNameHe(stage: Int): String = when (stage) {
    0 -> "שתיל"
    1 -> "עץ צעיר"
    2 -> "עץ בוגר"
    else -> "עץ אגדי"
}

/** Shambiuluk's one line about the tree, chosen by stage. Deadpan, never shaming. */
private fun shambiulukLine(stage: Int): String = when (stage) {
    0 -> "עץ. כביכול. נו. הברזל יטפל בזה."
    1 -> "הענפים זזים. משהו גדל כאן. ראיתי."
    2 -> com.tamir.hamasa.domain.game.CharacterNames.g("עץ של ממש. לא מופתע. בסדר, קצת.")
    else -> com.tamir.hamasa.domain.game.Addr.t("עץ אגדדדי. האיש והאגדה גידל אגדה.", "עץ אגדדדי. האישה והאגדה גידלה אגדה.")
}

/** The skill tree: the painted oak with six branch medallions that light up as the player's PRs grow. */
@Composable
fun TreeScreen() {
    val vm: TreeViewModel = viewModel()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val stats = remember(levels) { TreeModel.stats(levels) }
    val total = remember(levels) { Branch.values().sumOf { levels[it] ?: 0 } }
    val progress = (total / 30f).coerceIn(0f, 1f)
    // -1 while loading: never flash the lock at a player who already passed the gate
    val playerXp by remember { AppGraph.game.profile.map { it.xp } }.collectAsStateWithLifecycle(initialValue = -1)
    val treeLocked = playerXp >= 0 && !LevelGates.treeOpen(LevelTable.levelFor(playerXp))
    // until the tree opens (level 4) it is a seedling, whatever the branches already measure
    val stage = if (treeLocked) 0 else stageIndex(total)

    LaunchedEffect(Unit) { vm.refresh() }

    // one shared pulse for every lit medallion
    val pulseTransition = rememberInfiniteTransition(label = "medallionPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Column(Modifier.fillMaxSize().background(HColors.Slate)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "עץ הכוח",
                style = MaterialTheme.typography.headlineMedium,
                color = HColors.Parchment,
                modifier = Modifier.weight(1f)
            )
            StageChip(stage = stage, total = total)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // each stat tile says in one short line what the stat does in the game (StatEffects)
            StatChip("כוח עליון", stats.upper, Modifier.weight(1f), com.tamir.hamasa.domain.game.Addr.x(StatEffects.upperLineHe(stats)))
            StatChip("כוח תחתון", stats.lower, Modifier.weight(1f), com.tamir.hamasa.domain.game.Addr.x(StatEffects.lowerLineHe(stats)))
            StatChip("יציבות", stats.stability, Modifier.weight(1f), com.tamir.hamasa.domain.game.Addr.x(StatEffects.stabilityLineHe(stats)))
            StatChip("סיבולת", stats.endurance, Modifier.weight(1f), com.tamir.hamasa.domain.game.Addr.x(StatEffects.enduranceLineHe(stats)))
        }

        // level gate: the tree opens for viewing at level 4; below it the art is dimmed under a teaser card
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            PaintedTree(
                levels = levels,
                progress = progress,
                pulse = pulse,
                onTapBranch = { if (!treeLocked) vm.select(it) },
                modifier = Modifier.fillMaxSize().alpha(if (treeLocked) 0.3f else 1f)
            )
            if (treeLocked) LevelLockTeaser(LevelGates.treeTeaser(playerXp))
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)
        ) {
            Text(
                shambiulukLine(stage),
                style = MaterialTheme.typography.bodyLarge,
                color = HColors.Parchment,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        // stage 2 H: "כישורים מיוחדים" (opens with the tree)
        if (!treeLocked) {
            SkillsCard(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            // "מקצוע לשמביולוק" (locked until level 6, the dialog says so)
            ProfessionCard(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
    }

    val d = detail
    if (d != null) {
        BranchDialog(d, onClose = { vm.clearSelection() })
    }
}

// ---------------------------------------------------------------- tree

/**
 * The painting, letterboxed with ContentScale.Fit, with one medallion overlay per branch positioned by
 * fractions of the drawn image rect. Laid out left-to-right because it is a picture, not text.
 */
@Composable
private fun PaintedTree(
    levels: Map<Branch, Int>,
    progress: Float,
    pulse: Float,
    onTapBranch: (Branch) -> Unit,
    modifier: Modifier = Modifier
) {
    val painter = rememberNamedPainter(TREE_ASSET)
    // the tree is always fully visible; it only gets greener and brighter as the branches level up
    val saturation = 0.45f + 0.55f * progress
    val brightness = 0.78f + 0.22f * progress
    val filter = remember(saturation, brightness) {
        val m = ColorMatrix().apply { setToSaturation(saturation) }
        val scale = ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) }
        m *= scale
        ColorFilter.colorMatrix(m)
    }
    // tree.png is transparent (feathered edges), so it sits straight on the app background.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier) {
            val boxW: Dp = maxWidth
            val boxH: Dp = if (maxHeight.value.isFinite() && maxHeight > 0.dp) maxHeight else boxW / TREE_ASPECT
            val heightLimited = (boxW.value / boxH.value) > TREE_ASPECT
            val drawnW: Dp = if (heightLimited) boxH * TREE_ASPECT else boxW
            val drawnH: Dp = if (heightLimited) boxH else boxW / TREE_ASPECT
            val left: Dp = (boxW - drawnW) / 2f
            val top: Dp = (boxH - drawnH) / 2f

            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = filter,
                    modifier = Modifier.fillMaxSize()
                )
            }

            val diameter: Dp = drawnW * 0.15f
            for ((branch, frac) in MEDALLIONS) {
                val cx = left + drawnW * frac.first
                val cy = top + drawnH * frac.second
                Medallion(
                    branch = branch,
                    level = levels[branch] ?: 0,
                    diameter = diameter,
                    pulse = pulse,
                    onTap = { onTapBranch(branch) },
                    modifier = Modifier.absoluteOffset(x = cx - diameter / 2f, y = cy - diameter / 2f)
                )
            }
        }
    }
}

/** Applies the same saturation + brightness transform the image gets, so the letterbox matches the painting. */
private fun filteredColor(c: Color, saturation: Float, brightness: Float): Color {
    val lum = 0.213f * c.red + 0.715f * c.green + 0.072f * c.blue
    val r = (lum + (c.red - lum) * saturation) * brightness
    val g = (lum + (c.green - lum) * saturation) * brightness
    val b = (lum + (c.blue - lum) * saturation) * brightness
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 1f)
}

@Composable
private fun Medallion(
    branch: Branch,
    level: Int,
    diameter: Dp,
    pulse: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The painted medallion IS the button: nothing is drawn over it. A trained branch gets a soft ring of light
    // around the painting (outside the medallion's face, so the art stays fully visible); tap opens the details.
    val lit = level >= 1
    Box(
        modifier = modifier
            .size(diameter)
            .drawBehind {
                if (lit) {
                    val inner = size.minDimension * 0.42f
                    val outer = size.minDimension * 0.62f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                (inner / outer) to Color.Transparent,
                                ((inner / outer) + 0.08f).coerceAtMost(1f) to HColors.Gold.copy(alpha = 0.45f * pulse),
                                1f to Color.Transparent
                            ),
                            center = center,
                            radius = outer
                        ),
                        radius = outer,
                        center = center
                    )
                }
            }
            .clip(CircleShape)
            .clickable(onClickLabel = TreeModel.nameHe(branch), onClick = onTap)
    )
}

// ---------------------------------------------------------------- top chips

@Composable
private fun StatChip(label: String, value: Int, modifier: Modifier = Modifier, effect: String? = null) {
    // Tiles stay clean (number + name); what the stat does in the game opens on tap.
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.clickable(enabled = effect != null) { open = true },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = HColors.Gold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = HColors.ParchmentDim)
            if (effect != null) {
                Text("מה זה נותן", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = HColors.GoldDeep)
            }
        }
    }
    if (open && effect != null) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("$label: $value", color = HColors.Gold, fontWeight = FontWeight.Bold) },
            text = { Text(effect, style = MaterialTheme.typography.bodyLarge, color = HColors.Parchment) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("סגור", color = HColors.Gold) } },
            containerColor = HColors.SlateLight
        )
    }
}

/** Growth-stage chip: the stage's own transparent picture (tree_stage_1..4) next to the stage name. */
@Composable
private fun StageChip(stage: Int, total: Int) {
    val painter = rememberNamedPainter("tree_stage_${stage.coerceIn(0, 3) + 1}")
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)
    ) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 72.dp, height = 56.dp), contentAlignment = Alignment.Center) {
                if (painter != null) {
                    Image(painter = painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stageNameHe(stage), style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
                Text("סך רמות: $total", style = MaterialTheme.typography.labelSmall, color = HColors.ParchmentDim)
            }
        }
    }
}

/** Loads one of the four growth stages (2 x 2 grid: sapling, young, mature, legendary) off the main thread. */
@Composable
private fun rememberGrowthCell(stage: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, stage) {
        value = withContext(Dispatchers.IO) {
            try {
                val id = context.drawableId(Assets.treeGrowth)
                if (id == 0) null else {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val sheet = BitmapFactory.decodeResource(context.resources, id, opts)
                    if (sheet == null) null else {
                        val cell = cropCellScaled(sheet, 2, 2, stage.coerceIn(0, 3), 200)
                        if (cell !== sheet) sheet.recycle()
                        cell.asImageBitmap()
                    }
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
}

// ---------------------------------------------------------------- detail dialog

@Composable
private fun BranchDialog(d: BranchDetail, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text("סגור", color = HColors.Gold) }
        },
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(TreeModel.nameHe(d.branch), style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, modifier = Modifier.weight(1f))
                Text("רמה ${d.level}", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("התרגיל החזק: ${d.mainExerciseHe}", style = MaterialTheme.typography.bodyLarge, color = HColors.Parchment)
                Text("השיא שלך: ${d.bestHe}", style = MaterialTheme.typography.bodyLarge, color = HColors.Parchment)
                if (d.exercises.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("התרגילים של הענף", style = MaterialTheme.typography.labelLarge, color = HColors.Gold)
                    Spacer(Modifier.height(4.dp))
                    d.exercises.forEachIndexed { i, ex ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                ex.nameHe,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (i == 0 && ex.ratio > 0f) HColors.Gold else HColors.Parchment,
                                fontWeight = FontWeight.Bold
                            )
                            Text(d.exerciseLinesHe.getOrNull(i) ?: "", style = MaterialTheme.typography.labelMedium, color = HColors.ParchmentDim)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(d.meaningHe, style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
                Spacer(Modifier.height(6.dp))
                Text(d.nextHintHe, style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
                Spacer(Modifier.height(4.dp))
                Text(TreeModel.growsWhenHe(d.branch), style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
                if (d.gear.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("ציוד שהענף פותח", style = MaterialTheme.typography.labelLarge, color = HColors.Gold)
                    Spacer(Modifier.height(4.dp))
                    val shown = d.gear.take(8)
                    for (g in shown) {
                        val prefix = if (g.unlocked) "פתוח" else "נעול"
                        Text(
                            "${g.item.nameHe} (${g.requirementHe}) - $prefix",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (g.unlocked) HColors.Gold else HColors.ParchmentDim
                        )
                    }
                    val more = d.gear.size - shown.size
                    if (more > 0) {
                        Text("ועוד $more פריטים", style = MaterialTheme.typography.labelSmall, color = HColors.ParchmentDim)
                    }
                }
            }
        }
    )
}
