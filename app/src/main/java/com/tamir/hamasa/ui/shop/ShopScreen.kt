package com.tamir.hamasa.ui.shop

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.db.InventoryEntity
import com.tamir.hamasa.data.db.RewardEntity
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.RewardCatalog
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.GearCatalog
import com.tamir.hamasa.domain.game.GearItem
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.Rarity
import com.tamir.hamasa.domain.game.StatEffects
import com.tamir.hamasa.domain.tree.TreeModel
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.ui.common.Sfx
import com.tamir.hamasa.ui.common.Sound
import com.tamir.hamasa.data.repo.Bets
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect

private val cardShape = RoundedCornerShape(18.dp)

private fun rarityColor(r: Rarity): Color = when (r) {
    Rarity.COMMON -> HColors.ParchmentDim
    Rarity.RARE -> HColors.Rare
    Rarity.LEGENDARY -> HColors.Legendary
}

/** The gold shop: real-life rewards, streak shields, gear and chest rolls. */
@Composable
fun ShopScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ShopViewModel = viewModel(factory = ShopViewModel.Factory(app))

    val profile by vm.profile.collectAsStateWithLifecycle()
    val rewards by vm.rewards.collectAsStateWithLifecycle()
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val icons by vm.icons.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val rewardLog by vm.rewardLog.collectAsStateWithLifecycle()
    val minWeightKg by vm.minWeightKg.collectAsStateWithLifecycle()

    // "צליל הזהב": a coin on every gold change while the shop is open (purchases, chest drops).
    var lastGold by remember { mutableIntStateOf(-1) }
    LaunchedEffect(profile.gold, profile.onboarded) {
        if (!profile.onboarded) return@LaunchedEffect   // the placeholder Profile() before DataStore loads
        if (lastGold >= 0 && profile.gold != lastGold) Sfx.play(context, Sound.COIN)
        lastGold = profile.gold
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<RewardEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RewardEntity?>(null) }

    val tabs = listOf("פרסים", "מגן רצף", "ציוד", "תיבה")

    Column(Modifier.fillMaxSize().background(HColors.Slate)) {
        Text(
            text = "החנות",
            style = MaterialTheme.typography.headlineMedium,
            color = HColors.Gold,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp),
            textAlign = TextAlign.Center
        )
        val shieldMax = StatEffects.maxShields(stats)
        GoldBar(gold = profile.gold, shields = profile.shields, max = shieldMax)
        if (profile.monsterPot > 0 || profile.betStake > 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (profile.monsterPot > 0) {
                    Text(
                        "קופת המפלצת: ${profile.monsterPot} זהב. שבוע עם ${Bets.FULL_WEEK_DAYS} ימי אימון פותח אותה.",
                        color = HColors.Ember, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold
                    )
                }
                if (profile.betStake > 0) {
                    Text("הימור פתוח: ${profile.betStake} זהב על אימון מחר.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        TabRow(
            selectedTabIndex = tab,
            containerColor = HColors.Slate,
            contentColor = HColors.Gold
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Text(
                            label,
                            color = if (tab == i) HColors.Gold else HColors.ParchmentDim,
                            fontWeight = if (tab == i) FontWeight.Black else FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tab) {
                0 -> {
                    item {
                        Text(
                            com.tamir.hamasa.domain.game.Addr.t("פרסים מהחיים האמיתיים. אתה קובע מה ומה המחיר. הזהב משלם.", "פרסים מהחיים האמיתיים. את קובעת מה ומה המחיר. הזהב משלם."),
                            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // stage 2 H: "פינוק כפרס רשמי"
                    item(key = "official_treat") { OfficialTreatCard(gold = profile.gold) }
                    items(rewards, key = { it.id }) { r ->
                        // suggested rewards (RewardCatalog): tier label, and the milestone gate of a legendary one
                        val entry = RewardCatalog.byName(r.nameHe)
                        val open = entry == null || RewardCatalog.isOpen(entry, minWeightKg, LevelTable.levelFor(profile.xp))
                        RewardCard(
                            r = r,
                            isGoal = profile.rewardGoalId == r.id,
                            gold = profile.gold,
                            onToggleGoal = { vm.toggleGoal(r) },
                            tierHe = entry?.tier?.nameHe,
                            lockedHe = if (entry != null && !open) com.tamir.hamasa.domain.game.Addr.x(RewardCatalog.gateHe(entry)) else null,
                            canBuy = open && !busy && profile.gold >= r.priceGold,
                            onBuy = { vm.buyReward(r) },
                            onEdit = { editing = r },
                            onDelete = { deleting = r }
                        )
                    }
                    item {
                        GoldButton(text = "פרס חדש", enabled = true, onClick = { creating = true })
                    }
                    item {
                        Text("יומן פרסים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (rewardLog.isEmpty()) {
                        item { Text(com.tamir.hamasa.domain.game.Addr.t("עדיין לא נקנה פרס. הזהב מחכה. אתה גם.", "עדיין לא נקנה פרס. הזהב מחכה. את גם."), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(rewardLog.take(50), key = { "log_" + it.id }) { l ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(rewardNameFromReason(l.reason), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text("${-l.gold} זהב · ${formatLogDate(l.atMillis)}", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                1 -> {
                    item { ShieldCard(shields = profile.shields, gold = profile.gold, busy = busy, onBuy = { vm.buyShield() }, max = shieldMax) }
                }
                2 -> {
                    item {
                        Text(
                            "ציוד ל${com.tamir.hamasa.domain.game.CharacterNames.he(profile.characterVariant)}. נפתח כשהעץ שלך חזק מספיק: חזה/גב ${stats.upper}, רגליים ${stats.lower}, יציבות ${stats.stability}, סיבולת ${stats.endurance}.",
                            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    item {
                        val activeBuffs = inventory
                            .filter { it.equipped }
                            .mapNotNull { GearCatalog.byId(it.itemId) }
                            .map { GearCatalog.buffLabelHe(it.buff) }
                        Text(
                            if (activeBuffs.isEmpty()) "בונוסים פעילים: אין. ציוד לבוש מוסיף בונוס קטן." else "בונוסים פעילים: " + activeBuffs.joinToString(" | "),
                            color = HColors.Gold, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold
                        )
                    }
                    items(GearCatalog.all, key = { it.id }) { item ->
                        val owned = inventory.firstOrNull { it.itemId == item.id }
                        GearCard(
                            item = item,
                            icon = icons[item.id],
                            owned = owned,
                            stats = stats,
                            gold = profile.gold,
                            busy = busy,
                            onBuy = { vm.buyGear(item) },
                            onEquip = { vm.equip(item) }
                        )
                    }
                }
                else -> {
                    item { ChestCard(gold = profile.gold, busy = busy, onRoll = { vm.buyChest() }) }
                }
            }
        }
    }

    message?.let { m ->
        MessageDialog(m = m, onDismiss = { vm.dismissMessage() })
    }

    if (creating) {
        RewardDialog(
            title = "פרס חדש",
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, price, note -> vm.addReward(name, price, note); creating = false }
        )
    }
    editing?.let { r ->
        RewardDialog(
            title = "עריכת פרס",
            initial = r,
            onDismiss = { editing = null },
            onSave = { name, price, note -> vm.updateReward(r, name, price, note); editing = null }
        )
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = { TextButton(onClick = { vm.deleteReward(r); deleting = null }) { Text(com.tamir.hamasa.domain.game.Addr.t("מחק", "מחקי"), color = HColors.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ביטול", color = HColors.Parchment) } },
            title = { Text("למחוק את \"${r.nameHe}\"?", color = HColors.Gold, fontWeight = FontWeight.Bold) },
            text = { Text("הפרס יימחק מהרשימה. הזהב שכבר הוצא עליו לא חוזר.", color = HColors.Parchment) },
            containerColor = HColors.SlateLight
        )
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun GoldBar(gold: Int, shields: Int, max: Int = ShopRules.SHIELD_MAX) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(cardShape)
            .background(HColors.Ink)
            .border(BorderStroke(3.dp, HColors.GoldDeep), cardShape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("זהב: $gold", color = HColors.Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("מגנים: $shields מתוך $max", color = HColors.Parchment, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ShopCard(borderColor: Color = HColors.Ink, content: @Composable () -> Unit) {
    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(3.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) { content() }
    }
}

@Composable
private fun GoldButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = HColors.Gold, contentColor = HColors.Ink,
            disabledContainerColor = Color(0xFF3A414A), disabledContentColor = HColors.ParchmentDim
        ),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

// ---------------------------------------------------------------- rewards

@Composable
private fun RewardCard(
    r: RewardEntity,
    canBuy: Boolean,
    onBuy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isGoal: Boolean = false,
    gold: Int = 0,
    onToggleGoal: () -> Unit = {},
    tierHe: String? = null,
    lockedHe: String? = null
) {
    ShopCard(borderColor = if (isGoal) HColors.Gold else HColors.Ink) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(r.nameHe, color = HColors.Parchment, style = MaterialTheme.typography.titleLarge)
                if (tierHe != null) {
                    Text("פרס $tierHe", color = HColors.Gold, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                if (lockedHe != null) {
                    Text(lockedHe, color = HColors.Ember, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                if (r.note.isNotBlank()) {
                    Text(r.note, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
                if (r.redeemedCount > 0) {
                    Text("נרכש ${r.redeemedCount} פעמים", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "עריכה", tint = HColors.ParchmentDim) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "מחיקה", tint = HColors.Danger) }
        }
        if (isGoal) {
            Spacer(Modifier.height(6.dp))
            val have = gold.coerceAtMost(r.priceGold)
            Text("יעד: $have מתוך ${r.priceGold} זהב", color = HColors.Gold, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (r.priceGold <= 0) 1f else (gold.toFloat() / r.priceGold).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = HColors.Gold,
                trackColor = HColors.Ink
            )
        }
        TextButton(onClick = onToggleGoal) {
            Text(if (isGoal) com.tamir.hamasa.domain.game.Addr.t("בטל יעד", "בטלי יעד") else com.tamir.hamasa.domain.game.Addr.t("סמן כיעד", "סמני כיעד"), color = HColors.Sky, fontWeight = FontWeight.Bold)
        }
        GoldButton(text = com.tamir.hamasa.domain.game.Addr.t("קנה", "קני") + " ב-${r.priceGold} זהב", enabled = canBuy, onClick = onBuy)
    }
}

/** "reward:<id>:<name>" -> name (names may contain ':'). */
private fun rewardNameFromReason(reason: String): String {
    val parts = reason.split(":", limit = 3)
    return if (parts.size == 3 && parts[2].isNotBlank()) parts[2] else "פרס"
}

private fun formatLogDate(millis: Long): String =
    runCatching {
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy").format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
    }.getOrDefault("")

@Composable
private fun RewardDialog(title: String, initial: RewardEntity?, onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.nameHe ?: "") }
    var price by remember { mutableStateOf(initial?.priceGold?.toString() ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    val priceInt = price.trim().toIntOrNull() ?: 0
    val valid = name.isNotBlank() && priceInt > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(name, priceInt, note) }, enabled = valid) {
                Text("שמירה", color = if (valid) HColors.Gold else HColors.ParchmentDim, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול", color = HColors.Parchment) } },
        title = { Text(title, color = HColors.Gold, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("שם הפרס") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price, onValueChange = { v -> price = v.filter { it.isDigit() }.take(6) },
                    label = { Text("מחיר בזהב") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("הערה (לא חובה)") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Gold,
        textContentColor = HColors.Parchment
    )
}

// ---------------------------------------------------------------- shield

@Composable
private fun ShieldCard(shields: Int, gold: Int, busy: Boolean, onBuy: () -> Unit, max: Int = ShopRules.SHIELD_MAX) {
    // the bag size grows with lower strength (StatEffects.maxShields)
    val full = shields >= max
    ShopCard(borderColor = HColors.Moss) {
        Text("מגן רצף", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "מגן אחד מכסה יום אחד שפספסת: הרצף ממשיך כאילו התאמנת. נשרף אוטומטית ביום הפספוס הראשון.",
            color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until maxOf(max, shields)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (i < shields) HColors.Moss else HColors.Ink)
                        .border(2.dp, if (i < shields) HColors.Parchment else Color(0xFF3A414A), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (i < shields) Icons.Filled.Shield else Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = if (i < shields) HColors.Gold else Color(0xFF4A525C),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("ברשותך $shields מתוך $max. גודל התיק עולה עם כוח תחתון.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        GoldButton(
            text = if (full) "המלאי מלא" else com.tamir.hamasa.domain.game.Addr.t("קנה", "קני") + " מגן ב-${ShopRules.SHIELD_PRICE} זהב",
            enabled = !busy && !full && gold >= ShopRules.SHIELD_PRICE,
            onClick = onBuy
        )
    }
}

// ---------------------------------------------------------------- gear

@Composable
private fun GearCard(
    item: GearItem,
    icon: ImageBitmap?,
    owned: InventoryEntity?,
    stats: TreeModel.Stats,
    gold: Int,
    busy: Boolean,
    onBuy: () -> Unit,
    onEquip: () -> Unit
) {
    val color = rarityColor(item.rarity)
    val missing = ShopRules.missingHe(item, stats)
    val price = ShopRules.gearPrice(item.rarity)
    val isOwned = owned != null
    val isEquipped = owned?.equipped == true
    ShopCard(borderColor = if (isOwned) color else HColors.Ink) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(HColors.Ink)
                    .border(2.dp, color, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = item.nameHe,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                } else {
                    Text("?", color = HColors.ParchmentDim, fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.nameHe, color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
                Text(
                    GearCatalog.slotNameHe(item.slot) + " | " + BadgeCatalog.rarityNameHe(item.rarity),
                    color = color, style = MaterialTheme.typography.labelLarge
                )
                Text("דרישות: " + ShopRules.requirementsHe(item), color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                Text(
                    "בונוס: " + GearCatalog.buffLabelHe(item.buff),
                    color = if (isEquipped) HColors.Gold else HColors.Parchment,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            isOwned -> {
                // owned but the tree is not strong enough: stays his, cannot be worn yet
                val locked = !isEquipped && missing != null
                if (locked) {
                    Text("נדרש: " + (missing ?: "").removePrefix("דורש "), color = HColors.Ember, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (isEquipped) "ברשותך, לבוש" else "ברשותך",
                        color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onEquip,
                        enabled = !isEquipped && !locked,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HColors.Gold, contentColor = HColors.Ink,
                            disabledContainerColor = Color(0xFF3A414A), disabledContentColor = HColors.ParchmentDim
                        )
                    ) { Text(if (isEquipped) "לבוש" else "לבש", fontWeight = FontWeight.Black) }
                }
            }
            missing != null -> {
                Text("נעול. $missing", color = HColors.Ember, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                GoldButton(text = com.tamir.hamasa.domain.game.Addr.t("קנה", "קני") + " ב-$price זהב", enabled = !busy && gold >= price, onClick = onBuy)
            }
        }
    }
}

// ---------------------------------------------------------------- chest

@Composable
private fun ChestCard(gold: Int, busy: Boolean, onRoll: () -> Unit) {
    ShopCard(borderColor = HColors.GoldDeep) {
        Text("תיבת הגרלה", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "תיבה אחת, גורל אחד. זהב, מגן, מצב אימון חדש או ציוד אגדי. כל תיבה בלי אגדי מעלה את הסיכוי בפעם הבאה.",
            color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(
            text = "הגרל תיבה ב-${ShopRules.CHEST_PRICE} זהב",
            enabled = !busy && gold >= ShopRules.CHEST_PRICE,
            onClick = onRoll
        )
    }
}

@Composable
private fun DropDetails(drop: ChestDrop) {
    val color = rarityColor(drop.rarity)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("תיבה " + BadgeCatalog.rarityNameHe(drop.rarity), color = color, style = MaterialTheme.typography.headlineMedium)
        if (drop.xp > 0) Text("+${drop.xp} ניסיון", color = HColors.Sky, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (drop.gold > 0) Text("+${drop.gold} זהב", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        drop.modeId?.let { id ->
            Text("מצב אימון חדש: " + (ModeCatalog.byId(id)?.nameHe ?: id), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
        }
        drop.itemId?.let { id ->
            Text("ציוד חדש: " + (GearCatalog.byId(id)?.nameHe ?: id), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
        }
        if (drop.shield) Text("מגן רצף", color = HColors.Moss, style = MaterialTheme.typography.titleMedium)
        if (drop.joker) Text("ג'וקר", color = HColors.Ember, style = MaterialTheme.typography.titleMedium)
        if (drop.xp == 0 && drop.gold == 0 && drop.modeId == null && drop.itemId == null && !drop.shield && !drop.joker) {
            Text(com.tamir.hamasa.domain.game.CharacterNames.g("ריקה. מציין: גם זה קורה."), color = HColors.ParchmentDim)
        }
    }
}

// ---------------------------------------------------------------- Shambiuluk dialog

@Composable
private fun MessageDialog(m: ShopMessage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור", color = HColors.Gold, fontWeight = FontWeight.Bold) } },
        title = { Text(com.tamir.hamasa.ui.common.rememberCharacterNameHe(), color = HColors.Gold, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                m.drop?.let { d ->
                    DropDetails(d)
                    Spacer(Modifier.height(12.dp))
                }
                Text(com.tamir.hamasa.domain.game.CharacterNames.g(m.text), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        },
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Gold,
        textContentColor = HColors.Parchment
    )
}
