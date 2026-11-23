package com.tamir.hamasa.ui.map

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.Boss
import com.tamir.hamasa.domain.game.BossCatalog
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.ui.common.Assets
import com.tamir.hamasa.ui.common.LevelLockTeaser
import kotlinx.coroutines.flow.map
import androidx.compose.ui.draw.alpha
import com.tamir.hamasa.ui.common.drawableId
import com.tamir.hamasa.ui.common.rememberNamedPainter
import com.tamir.hamasa.ui.theme.HColors

private const val STATIONS_PER_SEGMENT = 10
private const val SHEET_ASPECT = 1536f / 2752f

/** Hebrew region names, one per ten stations; the last one repeats forever. */
private val REGIONS = listOf("הכפר", "הגבעות", "היער", "הנהר", "הביצה", "המדבר", "הקניון", "הלבה", "היער הקפוא", "ההרים", "איי השמיים")

/** Station centres (x, y) as fractions of the map_lower image, from the village upwards. */
private val LOWER_STATIONS = listOf(
    0.204f to 0.920f, 0.430f to 0.869f, 0.591f to 0.818f, 0.495f to 0.750f, 0.280f to 0.659f,
    0.754f to 0.500f, 0.497f to 0.430f, 0.509f to 0.333f, 0.599f to 0.220f, 0.591f to 0.108f
)

/** Station centres (x, y) as fractions of the map_upper image, from the canyon up to the gate. */
private val UPPER_STATIONS = listOf(
    0.599f to 0.929f, 0.596f to 0.818f, 0.489f to 0.750f, 0.281f to 0.659f, 0.511f to 0.560f,
    0.754f to 0.500f, 0.498f to 0.430f, 0.509f to 0.334f, 0.327f to 0.263f, 0.491f to 0.075f
)

/** Region index (0 = הכפר .. 10 = איי השמיים) for a station count; one region per ten stations, the last repeats forever. */
fun regionIndex(station: Int): Int = (station / STATIONS_PER_SEGMENT).coerceIn(0, REGIONS.size - 1)

fun regionName(station: Int): String = REGIONS[regionIndex(station)]

/** "בסוף כל עולם מחכה בוס": the boss guarding the end of map segment [segment] (one per ten stations, roster order). */
fun regionBoss(segment: Int): Boss {
    val n = BossCatalog.all.size
    return BossCatalog.all[((segment % n) + n) % n]
}

/** Endless vertical journey: the village is at the bottom, each finished session is one station. */
@Composable
fun MapScreen() {
    val vm: MapViewModel = viewModel()
    val distance by vm.distance.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val boss = vm.boss

    val currentSegment = distance / STATIONS_PER_SEGMENT
    val segmentCount = currentSegment + 2
    val listState = rememberLazyListState()
    LaunchedEffect(currentSegment) { listState.scrollToItem(currentSegment) }
    // region boss tapped on the map: (boss, region passed)
    var shownBoss by remember { mutableStateOf<Pair<Boss, Boolean>?>(null) }
    // -1 while loading: never flash the lock at a player who already passed the gate
    val playerXp by remember { AppGraph.game.profile.map { it.xp } }.collectAsStateWithLifecycle(initialValue = -1)
    val characterVariant by remember { AppGraph.game.profile.map { it.characterVariant } }.collectAsStateWithLifecycle(initialValue = "M")
    val mapLocked = playerXp >= 0 && !LevelGates.mapOpen(LevelTable.levelFor(playerXp))

    Column(Modifier.fillMaxSize().background(HColors.Slate)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("תחנה $distance מתוך המסע", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, modifier = Modifier.weight(1f))
            Text(regionName(distance), style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
        }
        // level 10: a new chapter of the map
        if (playerXp >= 0 && LevelTable.levelFor(playerXp) >= LevelGates.TITLE) {
            Text(
                LevelGates.mapChapterHe(LevelTable.levelFor(playerXp)),
                style = MaterialTheme.typography.labelLarge,
                color = HColors.Gold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
            )
        }
        // level gate: the map opens at level 6; below it the map is dimmed under a teaser card
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Column(Modifier.fillMaxSize().alpha(if (mapLocked) 0.3f else 1f)) {
        BossCard(
            boss = boss,
            hp = progress?.hp ?: boss.maxHp,
            maxHp = progress?.maxHp ?: boss.maxHp,
            defeated = progress?.defeated ?: false
        )
        // coach season: the holiday event boss joins the weekly boss (nothing when no event runs)
        com.tamir.hamasa.ui.home.EventBossMapCard()
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(count = segmentCount, key = { it }) { segment ->
                MapSegment(segment = segment, distance = distance, variant = characterVariant, onBossTap = { b, passed -> if (!mapLocked) shownBoss = b to passed })
            }
        }
        }
        if (mapLocked) LevelLockTeaser(LevelGates.mapTeaser(playerXp))
        }
    }

    val shown = shownBoss
    if (shown != null) {
        RegionBossDialog(boss = shown.first, passed = shown.second, onClose = { shownBoss = null })
    }
}

/** Card of a region boss, opened from its marker at the end of the region. */
@Composable
private fun RegionBossDialog(boss: Boss, passed: Boolean, onClose: () -> Unit) {
    val portrait = rememberNamedPainter("boss_${boss.sheet}_${boss.index}")
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("סגור", color = HColors.Gold) } },
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        shape = RoundedCornerShape(22.dp),
        title = { Text(boss.nameHe, style = MaterialTheme.typography.titleLarge, color = HColors.Parchment) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(HColors.Ink)
                        .border(3.dp, if (passed) HColors.Gold else HColors.Danger, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (portrait != null) {
                        Image(painter = portrait, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(10.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    com.tamir.hamasa.domain.game.Addr.x(if (passed) "\"${boss.defeatedHe}\"" else "\"${boss.quoteHe}\""),
                    style = MaterialTheme.typography.bodyLarge, color = HColors.Parchment
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (passed) "שומר סוף העולם הזה. עברת אותו." else "שומר את סוף העולם הזה. מחכה בתחנה האחרונה.",
                    style = MaterialTheme.typography.bodyMedium, color = if (passed) HColors.Gold else HColors.ParchmentDim
                )
                Text(com.tamir.hamasa.domain.game.Addr.x("חולשה: ${boss.howToHitHe}"), style = MaterialTheme.typography.labelSmall, color = HColors.ParchmentDim)
            }
        }
    )
}

@Composable
private fun BossCard(boss: Boss, hp: Int, maxHp: Int, defeated: Boolean) {
    // Each boss has its own transparent cut-out (boss_<sheet>_<index>), so nothing of the neighbours shows.
    val portrait = rememberNamedPainter("boss_${boss.sheet}_${boss.index}")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(96.dp)
                    .height(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HColors.Ink)
                    .border(3.dp, HColors.GoldDeep, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (portrait != null) {
                    Image(painter = portrait, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("בוס השבוע: ${boss.nameHe}", style = MaterialTheme.typography.titleMedium, color = HColors.Parchment, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // HP bar (boss fights are in rounds now, so HP is too large for one pip per point)
                    val frac = if (maxHp > 0) (hp.toFloat() / maxHp).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(HColors.Slate)
                            .border(2.dp, HColors.Ink, CircleShape)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(frac)
                                .height(12.dp)
                                .clip(CircleShape)
                                .background(HColors.Danger)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (defeated) "הובס השבוע" else "$hp מתוך $maxHp",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (defeated) HColors.Gold else HColors.ParchmentDim
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    com.tamir.hamasa.domain.game.Addr.x(if (defeated) "\"${boss.defeatedHe}\"" else "\"${boss.quoteHe}\""),
                    style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment
                )
                // "כל בוס נפגע משילוב אחר": the weakness (both = double, one = half, none = nothing) and Shambiuluk's hint
                Text(
                    com.tamir.hamasa.domain.game.Addr.x(com.tamir.hamasa.domain.game.BossWeakness.lineHe(com.tamir.hamasa.domain.game.BossWeakness.forBoss(boss.id))),
                    style = MaterialTheme.typography.bodyMedium, color = HColors.Gold, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text("שניהם: נזק כפול. אחד: חצי. אף אחד: כלום.", style = MaterialTheme.typography.labelSmall, color = HColors.ParchmentDim)
                if (!defeated) {
                    Text(com.tamir.hamasa.domain.game.CharacterNames.g(com.tamir.hamasa.domain.game.BossHints.forBoss(boss.id)), style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
                }
            }
        }
    }
}

/** One parchment sheet (map_lower for even segments, map_upper for odd) with its ten stations overlaid. */
@Composable
private fun MapSegment(segment: Int, distance: Int, variant: String, onBossTap: (Boss, Boolean) -> Unit) {
    val context = LocalContext.current
    val lower = segment % 2 == 0
    val imageName = if (lower) Assets.mapLower else Assets.mapUpper
    val stations = if (lower) LOWER_STATIONS else UPPER_STATIONS
    val imageId = remember(imageName) { context.drawableId(imageName) }
    val anchorId = remember(variant) { com.tamir.hamasa.ui.common.CharacterArt.anchorResId(context, variant, 0) }

    // The map is a picture: lay it out left-to-right regardless of the app's RTL direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(SHEET_ASPECT)) {
            val w = maxWidth
            val h = maxHeight
            if (imageId != 0) {
                Image(painter = painterResource(imageId), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(HColors.Parchment))
            }
            for (j in stations.indices) {
                val index = segment * STATIONS_PER_SEGMENT + j
                val (fx, fy) = stations[j]
                val cx = w * fx
                val cy = h * fy
                val isCurrent = index == distance
                val isPassed = index < distance
                val isBorder = j == 0 && segment > 0
                if (isCurrent && anchorId != 0) {
                    val s = 56.dp
                    Image(
                        painter = painterResource(anchorId), contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(AbsoluteAlignment.TopLeft)
                            .absoluteOffset(x = cx - s / 2, y = cy - s * 0.8f)
                            .size(s)
                    )
                } else {
                    val s = if (isCurrent) 30.dp else 24.dp
                    val fill = when {
                        isCurrent -> HColors.Ember
                        isPassed -> HColors.Gold
                        else -> HColors.ParchmentDim.copy(alpha = 0.45f)
                    }
                    val ring = when {
                        isBorder && !isPassed -> HColors.Danger
                        isPassed || isCurrent -> HColors.Ink
                        else -> HColors.Ink.copy(alpha = 0.5f)
                    }
                    Box(
                        Modifier
                            .align(AbsoluteAlignment.TopLeft)
                            .absoluteOffset(x = cx - s / 2, y = cy - s / 2)
                            .size(s)
                            .clip(CircleShape)
                            .background(fill)
                            .border(3.dp, ring, CircleShape)
                    )
                }
            }
            // the region boss waits beside the last station of the region
            val boss = regionBoss(segment)
            val lastIndex = segment * STATIONS_PER_SEGMENT + stations.size - 1
            val passed = distance > lastIndex
            val (lastFx, lastFy) = stations.last()
            val bs = 48.dp
            val bossX = if (lastFx < 0.5f) w * lastFx + 22.dp else w * lastFx - 22.dp - bs
            BossMarker(
                boss = boss, passed = passed,
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset(x = bossX, y = h * lastFy - bs / 2)
                    .size(bs)
                    .clickable { onBossTap(boss, passed) }
            )
        }
    }
}

/** Small circular portrait of a region boss (its own image boss_<sheet>_<index>). Gold ring once passed. */
@Composable
private fun BossMarker(boss: Boss, passed: Boolean, modifier: Modifier) {
    val portrait = rememberNamedPainter("boss_${boss.sheet}_${boss.index}")
    Box(
        modifier
            .clip(CircleShape)
            .background(HColors.Ink)
            .border(3.dp, if (passed) HColors.Gold else HColors.Danger, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (portrait != null) {
            Image(painter = portrait, contentDescription = boss.nameHe, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
    }
}
