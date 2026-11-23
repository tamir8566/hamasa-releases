package com.tamir.hamasa.ui.badges

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.BadgeEntity
import com.tamir.hamasa.data.repo.MonthlyBadge
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.BadgeDef
import com.tamir.hamasa.domain.game.GameFacts
import com.tamir.hamasa.domain.game.Rarity
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.ui.common.Assets
import com.tamir.hamasa.ui.common.cropMeasuredScaled
import com.tamir.hamasa.ui.common.drawableId
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Loads the 4 badge sheets once (off the main thread), crops every badge cell with
 * [BadgeCatalog.sheetGrid] and keeps the small icons in memory for the grid.
 */
class BadgesViewModel(private val app: Application) : ViewModel() {
    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons

    /** Facts for the half-way hints (null while loading). */
    private val _facts = MutableStateFlow<GameFacts?>(null)
    val facts: StateFlow<GameFacts?> = _facts

    /** "תגי החודש" (this month's seasonal badges) and the seasonal badges whose month passed un-earned. */
    private val _monthly = MutableStateFlow<List<MonthlyBadge>>(emptyList())
    val monthly: StateFlow<List<MonthlyBadge>> = _monthly
    private val _expired = MutableStateFlow<Set<String>>(emptySet())
    val expired: StateFlow<Set<String>> = _expired

    init {
        viewModelScope.launch(Dispatchers.IO) { loadAll() }
        viewModelScope.launch {
            _monthly.value = runCatching { AppGraph.game.monthlyBadges() }.getOrDefault(emptyList())
            _expired.value = runCatching { AppGraph.game.expiredSeasonalIds() }.getOrDefault(emptySet())
        }
        viewModelScope.launch {
            _facts.value = runCatching {
                val last = AppGraph.db.sessions().doneSessionsOnce().firstOrNull()?.id
                AppGraph.game.buildFacts(last)
            }.getOrNull()
        }
    }

    private fun loadAll() {
        val out = mutableMapOf<String, ImageBitmap>()
        for (sheet in 1..4) {
            val name = Assets.badgeSheets.getOrNull(sheet - 1) ?: continue
            val resId = app.drawableId(name)
            if (resId == 0) continue
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp: Bitmap = try { BitmapFactory.decodeResource(app.resources, resId, opts) ?: continue } catch (e: Throwable) { continue }
            val (cols, rows) = BadgeCatalog.sheetGrid[sheet] ?: (1 to 1)
            for (def in BadgeCatalog.bySheet(sheet)) {
                try {
                    out[def.id] = cropMeasuredScaled(bmp, name, cols, rows, def.iconIndex, 256).asImageBitmap()
                } catch (e: Throwable) {
                    // a bad index must never crash the screen; the cell simply shows no icon
                }
            }
            bmp.recycle()
            _icons.value = out.toMap()
        }
        _icons.value = out.toMap()
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BadgesViewModel(app) as T
    }
}

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun rarityColor(r: Rarity): Color = when (r) {
    Rarity.COMMON -> HColors.ParchmentDim
    Rarity.RARE -> HColors.Rare
    Rarity.LEGENDARY -> HColors.Legendary
}

@Composable
fun BadgesScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: BadgesViewModel = viewModel(factory = BadgesViewModel.Factory(app))
    val icons by vm.icons.collectAsStateWithLifecycle()
    val unlockedList by AppGraph.game.badges.collectAsStateWithLifecycle(initialValue = emptyList<BadgeEntity>())
    val unlocked: Map<String, Long> = remember(unlockedList) { unlockedList.associate { it.id to it.unlockedAtMillis } }
    var selected by remember { mutableStateOf<BadgeDef?>(null) }
    val facts by vm.facts.collectAsStateWithLifecycle()
    // "לחלק מהתגים נפתח רמז מעורפל אחרי שעמדת בחצי מהתנאי" (from level 9 already at 30%)
    val hintOpen: (BadgeDef) -> Boolean = { def ->
        val f = facts
        if (f == null) false else (BadgeCatalog.progress(def.id, f) ?: 0f) >= BadgeCatalog.hintThreshold(f.level)
    }

    val monthly by vm.monthly.collectAsStateWithLifecycle()
    val expired by vm.expired.collectAsStateWithLifecycle()
    // Seasonal badges live in the "תגי החודש" row while their month runs; in the grid only once earned.
    // An un-earned one whose month ended is gone forever. Earned badges are never taken away.
    val monthlyIds = monthly.map { it.def.id }.toSet()
    val gridBadges = BadgeCatalog.all.filter { def ->
        def.id in unlocked || (def.seasonMonth == null && def.id !in expired)
    }
    val total = gridBadges.size + monthly.count { it.def.id !in unlocked }
    val count = gridBadges.count { it.id in unlocked }

    Column(Modifier.fillMaxSize().background(HColors.Slate)) {
        Text(
            text = "תגים",
            style = MaterialTheme.typography.headlineMedium,
            color = HColors.Gold,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = "$count מתוך $total",
            style = MaterialTheme.typography.titleMedium,
            color = HColors.Parchment,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            textAlign = TextAlign.Center
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (monthly.isNotEmpty()) {
                item(key = "monthly_row", span = { GridItemSpan(maxLineSpan) }) {
                    MonthlyRow(
                        monthly = monthly, icons = icons, unlocked = unlocked,
                        onClick = { def -> selected = def }
                    )
                }
            }
            items(gridBadges.filter { it.id !in monthlyIds || it.id in unlocked }, key = { it.id }) { def ->
                BadgeCell(
                    def = def,
                    icon = icons[def.id],
                    unlocked = def.id in unlocked,
                    hintOpen = def.id !in unlocked && hintOpen(def),
                    onClick = { selected = def }
                )
            }
        }
    }

    selected?.let { def ->
        BadgeDialog(def = def, icon = icons[def.id], unlockedAt = unlocked[def.id], hintOpen = def.id !in unlocked && hintOpen(def), onDismiss = { selected = null })
    }
}

/** "תגי החודש": this month's seasonal badges with the days left. What is not caught does not come back. */
@Composable
private fun MonthlyRow(monthly: List<MonthlyBadge>, icons: Map<String, ImageBitmap>, unlocked: Map<String, Long>, onClick: (BadgeDef) -> Unit) {
    val daysLeft = monthly.firstOrNull()?.daysLeft ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(HColors.SlateLight)
            .border(2.dp, HColors.GoldDeep, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Text("תגי החודש", style = MaterialTheme.typography.titleMedium, color = HColors.Gold, fontWeight = FontWeight.Bold)
        Text(
            if (daysLeft <= 1) "היום האחרון. מחר הם נעלמים." else "עוד $daysLeft ימים. מה שלא נתפס, לא חוזר.",
            style = MaterialTheme.typography.labelMedium, color = HColors.ParchmentDim
        )
        Spacer(Modifier.height(8.dp))
        Row {
            for (m in monthly) {
                Box(Modifier.width(88.dp)) {
                    BadgeCell(
                        def = m.def,
                        icon = icons[m.def.id],
                        unlocked = m.def.id in unlocked,
                        hintOpen = true,
                        onClick = { onClick(m.def) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeCell(def: BadgeDef, icon: ImageBitmap?, unlocked: Boolean, hintOpen: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (unlocked) HColors.SlateLight else HColors.Ink)
                .border(2.dp, if (unlocked) rarityColor(def.rarity) else Color(0xFF3A414A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (unlocked && icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = com.tamir.hamasa.domain.game.CharacterNames.g(def.nameHe),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            } else {
                Text(
                    text = "?",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = if (unlocked) HColors.Parchment else HColors.ParchmentDim
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val label = when {
            unlocked -> com.tamir.hamasa.domain.game.CharacterNames.g(def.nameHe)
            !def.hidden -> com.tamir.hamasa.domain.game.CharacterNames.g(def.hintHe)
            hintOpen -> com.tamir.hamasa.domain.game.CharacterNames.g(BadgeCatalog.vagueHintHe(def.id))
            else -> Voice.secretHint(def.id)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (unlocked) HColors.Parchment else HColors.ParchmentDim,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BadgeDialog(def: BadgeDef, icon: ImageBitmap?, unlockedAt: Long?, hintOpen: Boolean, onDismiss: () -> Unit) {
    val unlocked = unlockedAt != null
    val title = if (unlocked) com.tamir.hamasa.domain.game.CharacterNames.g(def.nameHe) else if (!def.hidden) com.tamir.hamasa.domain.game.CharacterNames.g(def.nameHe) else "תג סודי"
    val hint = when {
        unlocked -> com.tamir.hamasa.domain.game.CharacterNames.g(def.hintHe)
        !def.hidden -> com.tamir.hamasa.domain.game.CharacterNames.g(def.hintHe)
        hintOpen -> com.tamir.hamasa.domain.game.CharacterNames.g(BadgeCatalog.vagueHintHe(def.id))
        else -> Voice.secretHint(def.id)
    }
    val dateText = unlockedAt?.let {
        try { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt) } catch (e: Throwable) { null }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("נו טוב") } },
        title = {
            Text(title, color = HColors.Gold, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(if (unlocked) HColors.SlateLight else HColors.Ink)
                        .border(3.dp, if (unlocked) rarityColor(def.rarity) else Color(0xFF3A414A), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (unlocked && icon != null) {
                        Image(bitmap = icon, contentDescription = com.tamir.hamasa.domain.game.CharacterNames.g(def.nameHe), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                    } else {
                        Text("?", fontSize = 40.sp, fontWeight = FontWeight.Black, color = HColors.ParchmentDim)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "דרגה: " + BadgeCatalog.rarityNameHe(def.rarity),
                    color = rarityColor(def.rarity),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center
                )
                def.seasonMonth?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("תג עונתי, חודש $it", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                Text(hint, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                if (!unlocked && hintOpen && !def.hidden) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "רמז נוסף: " + com.tamir.hamasa.domain.game.CharacterNames.g(BadgeCatalog.vagueHintHe(def.id)),
                        color = HColors.Gold,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (dateText != null) "נפתח בתאריך $dateText" else "עדיין נעול",
                    color = HColors.ParchmentDim,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        },
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Gold,
        textContentColor = HColors.Parchment
    )
}
