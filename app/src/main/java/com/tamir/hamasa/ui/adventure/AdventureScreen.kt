package com.tamir.hamasa.ui.adventure

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.BossProgressEntity
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.data.repo.BossFights
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.Boss
import com.tamir.hamasa.ui.common.Assets
import com.tamir.hamasa.ui.common.cropMeasuredScaled
import com.tamir.hamasa.ui.common.drawableId
import com.tamir.hamasa.ui.common.rememberNamedPainter
import com.tamir.hamasa.ui.map.journeyStations
import com.tamir.hamasa.ui.map.regionName
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live numbers for the hub cards. Everything is read-only: the hub never changes game state. */
class AdventureViewModel : ViewModel() {
    val profile: StateFlow<Profile?> = AppGraph.prefs.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Stations passed on the journey (same flow the map uses). */
    val stations: StateFlow<Int> = journeyStations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val badgesUnlocked: StateFlow<Int> = AppGraph.game.badges
        .map { list -> list.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** The newest unlocked badge, for the badges card art; null = none yet. */
    val latestBadgeId: StateFlow<String?> = AppGraph.game.badges
        .map { list -> list.maxByOrNull { it.unlockedAtMillis }?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _branchTotal = MutableStateFlow<Int?>(null)
    val branchTotal: StateFlow<Int?> = _branchTotal

    private val _boss = MutableStateFlow<Pair<Boss, BossProgressEntity>?>(null)
    val boss: StateFlow<Pair<Boss, BossProgressEntity>?> = _boss

    init {
        viewModelScope.launch {
            _branchTotal.value = runCatching { AppGraph.game.branchLevels().values.sum() }.getOrNull()
        }
        viewModelScope.launch {
            _boss.value = runCatching { BossFights.forDay(Days.today()) }.getOrNull()
        }
    }
}

private val HubShape = RoundedCornerShape(24.dp)

/** Decodes a drawable by name, downsampled, off the main thread (the map / tree paintings are large). */
@Composable
internal fun rememberSampledArt(name: String, sample: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, name, sample) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val id = context.drawableId(name)
                if (id == 0) null else {
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                    BitmapFactory.decodeResource(context.resources, id, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
}

/**
 * One icon of a badge sprite sheet, cut with the MEASURED rectangle (SheetRects), so no sliver of the
 * neighbour bleeds in. Decoded at full size (the hub draws it large), loaded off the main thread.
 */
@Composable
private fun rememberMeasuredCell(sheetName: String, cols: Int, rows: Int, index: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, sheetName, cols, rows, index) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val id = context.drawableId(sheetName)
                if (id == 0) null else {
                    val full = BitmapFactory.decodeResource(context.resources, id)
                    if (full == null) null else {
                        val cell = cropMeasuredScaled(full, sheetName, cols, rows, index, 400)
                        if (cell !== full) full.recycle()
                        cell.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }
}

/**
 * "ההרפתקה": the game side of the app in one place. Big illustrated cards open the journey map, the strength
 * tree, the shop, the badges and the boss board (each opens with a back arrow).
 */
@Composable
fun AdventureScreen(
    onOpenMap: () -> Unit = {},
    onOpenTree: () -> Unit = {},
    onOpenShop: () -> Unit = {},
    onOpenBadges: () -> Unit = {},
    onOpenBosses: () -> Unit = {}
) {
    val vm: AdventureViewModel = viewModel()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val stations by vm.stations.collectAsStateWithLifecycle()
    val badges by vm.badgesUnlocked.collectAsStateWithLifecycle()
    val latestBadgeId by vm.latestBadgeId.collectAsStateWithLifecycle()
    val branchTotal by vm.branchTotal.collectAsStateWithLifecycle()
    val bossPair by vm.boss.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ההרפתקה", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "חמש דלתות. מאחורי כל אחת מחכה משהו. לא תמיד משהו נחמד.",
            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
        )

        // ---- the journey map
        val p = profile
        val mapLine = when {
            p == null -> ""
            p.mapStartDone < 0 -> "המסע עוד לא התחיל. הוא מחכה לרמה 6."
            else -> "אזור: ${regionName(stations)} · תחנה $stations"
        }
        val mapArt by rememberSampledArt(Assets.mapLower, 4)
        HubCard(
            title = "המסע",
            info = mapLine,
            hint = "המפה. כל אימון הוא צעד.",
            onClick = onOpenMap
        ) {
            ArtImage(mapArt, ContentScale.Crop)
        }

        // ---- the strength tree
        val treeArt by rememberSampledArt(TREE_ART, 2)
        HubCard(
            title = "עץ הכוח",
            info = if (branchTotal != null) "סך רמות הענפים: $branchTotal מתוך 60" else com.tamir.hamasa.domain.game.CharacterNames.g("סופר ענפים. רגע."),
            hint = "שישה ענפים. כל שיא מגדל אותם.",
            onClick = onOpenTree
        ) {
            // the whole painted oak, cropped to fill: the crown with the glowing branch medallions
            ArtImage(treeArt, ContentScale.Crop, alignment = Alignment.TopCenter)
        }

        // ---- the shop
        // the golden treasure chest (badges_3). If his newest badge IS that chest, the copper one, so the two
        // cards never show the same picture.
        val chestIndex = if (latestBadgeId == CHEST_BADGE_ID) CHEST_ALT_INDEX else CHEST_INDEX
        val chestGrid = BadgeCatalog.sheetGrid[CHEST_SHEET] ?: (1 to 1)
        val chestArt by rememberMeasuredCell(
            Assets.badgeSheets.getOrNull(CHEST_SHEET - 1) ?: "", chestGrid.first, chestGrid.second, chestIndex
        )
        HubCard(
            title = "החנות",
            info = "בכיס: ${p?.gold ?: 0} זהב",
            hint = "ציוד, תיבות ופינוקים. הסוחר מחייך.",
            onClick = onOpenShop
        ) {
            ArtImage(chestArt, ContentScale.Fit, padDp = 10)
        }

        // ---- the badges
        val total = BadgeCatalog.all.size
        // newest unlocked badge; none yet = the closed "?" relic, dimmed
        val badgeDef = latestBadgeId?.let { id -> BadgeCatalog.byId(id) }
        val badgeSheetNo = badgeDef?.iconSheet ?: LOCKED_SHEET
        val badgeIndex = badgeDef?.iconIndex ?: LOCKED_INDEX
        val badgeGrid = BadgeCatalog.sheetGrid[badgeSheetNo] ?: (1 to 1)
        val badgeArt by rememberMeasuredCell(
            Assets.badgeSheets.getOrNull(badgeSheetNo - 1) ?: "", badgeGrid.first, badgeGrid.second, badgeIndex
        )
        HubCard(
            title = "ההישגים",
            info = "נפתחו $badges מתוך $total",
            hint = "כל תג הוא סיפור. חלקם מביכים.",
            onClick = onOpenBadges
        ) {
            ArtImage(badgeArt, ContentScale.Fit, padDp = 10, alpha = if (badgeDef != null) 1f else 0.55f)
        }

        // ---- the boss board
        val bp = bossPair
        val portrait = if (bp != null) rememberNamedPainter("boss_${bp.first.sheet}_${bp.first.index}") else null
        HubCard(
            title = "לוח הבוסים",
            info = when {
                bp == null -> "הבוס של השבוע מתלבש. רגע."
                bp.second.defeated -> "הבוס של השבוע: ${bp.first.nameHe}. הובס."
                else -> "הבוס של השבוע: ${bp.first.nameHe} · ${bp.second.hp.coerceAtLeast(0)} מתוך ${bp.second.maxHp} חיים"
            },
            hint = "חמישה עשר בוסים. אחד בכל שבוע.",
            onClick = onOpenBosses,
            extra = {
                if (bp != null && bp.second.maxHp > 0) {
                    Spacer(Modifier.height(8.dp))
                    val frac = (bp.second.hp.toFloat() / bp.second.maxHp.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (bp.second.defeated) HColors.Moss else HColors.Danger,
                        trackColor = HColors.Ink
                    )
                }
            }
        ) {
            if (portrait != null) {
                Image(painter = portrait, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Tree painting (res/drawable-nodpi/tree.png, transparent background). */
private const val TREE_ART = "tree"

/** Shop art: the treasure-chest icons on badges_3 (index 5 golden, index 4 copper = the "first_chest" badge). */
private const val CHEST_SHEET = 3
private const val CHEST_INDEX = 5
private const val CHEST_ALT_INDEX = 4
private const val CHEST_BADGE_ID = "challenge_10"

/** Badges art when nothing is unlocked yet: the closed "?" relic (badges_4, index 0). */
private const val LOCKED_SHEET = 4
private const val LOCKED_INDEX = 0

private val ArtPanelWidth = 124.dp
private val HubCardHeight = 140.dp

@Composable
private fun ArtImage(
    bitmap: ImageBitmap?,
    scale: ContentScale,
    padDp: Int = 0,
    alpha: Float = 1f,
    alignment: Alignment = Alignment.Center
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = scale,
            alignment = alignment,
            alpha = alpha,
            modifier = Modifier.fillMaxSize().padding(padDp.dp)
        )
    }
}

/**
 * The art side of every hub card, one treatment for all five: dark ink, a soft gold glow in the middle, the art
 * (paintings cropped to fill, figures and icons centred), then a fade into the card colour toward the text so
 * the picture melts into the card instead of ending in a hard edge.
 */
@Composable
private fun ArtPanel(content: @Composable BoxScope.() -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Brushes are not mirrored by RTL: the text side is on the left in RTL, on the right in LTR.
    val fade = if (rtl) {
        Brush.horizontalGradient(
            0f to HColors.SlateLight,
            0.15f to HColors.SlateLight.copy(alpha = 0.55f),
            0.45f to Color.Transparent
        )
    } else {
        Brush.horizontalGradient(
            0.55f to Color.Transparent,
            0.85f to HColors.SlateLight.copy(alpha = 0.55f),
            1f to HColors.SlateLight
        )
    }
    Box(
        Modifier
            .width(ArtPanelWidth)
            .fillMaxHeight()
            .background(HColors.Ink)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        0f to HColors.Gold.copy(alpha = 0.30f),
                        0.5f to HColors.Gold.copy(alpha = 0.08f),
                        1f to Color.Transparent,
                        center = center,
                        radius = size.maxDimension * 0.62f
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
        Box(Modifier.matchParentSize().background(fade))
    }
}

/** A big hub card: art on the start side (right in RTL), title + live line + a small hint on the other. */
@Composable
private fun HubCard(
    title: String,
    info: String,
    hint: String,
    onClick: () -> Unit,
    extra: @Composable () -> Unit = {},
    art: @Composable () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = HubShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(3.dp, HColors.GoldDeep)
    ) {
        Row(Modifier.fillMaxWidth().height(HubCardHeight), verticalAlignment = Alignment.CenterVertically) {
            ArtPanel { art() }
            // same rhythm on every card: title, 6dp, live line, 4dp, hint (+ optional extra), centred vertically
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(title, color = HColors.Gold, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                if (info.isNotBlank()) {
                    Text(
                        info, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(hint, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                extra()
            }
        }
    }
}
