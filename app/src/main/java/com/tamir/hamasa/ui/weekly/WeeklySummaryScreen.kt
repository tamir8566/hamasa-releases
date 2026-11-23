package com.tamir.hamasa.ui.weekly

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.GearCatalog
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.Rarity
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.ui.common.MuscleHeatLegend
import com.tamir.hamasa.ui.common.MuscleHeatmap
import com.tamir.hamasa.ui.common.RadarChart
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WeeklyCardShape = RoundedCornerShape(20.dp)
private val WeeklyDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/** "סיכום שבועי": the week in numbers, the boss, the muscles, Shambiuluk's verdict and the end-of-week box. */
@Composable
fun WeeklySummaryScreen() {
    val vm: WeeklySummaryViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (st.loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HColors.Gold)
            }
            return@Column
        }

        val from = Days.of(st.weekStart).format(WeeklyDateFmt)
        val to = Days.of(st.weekStart + 6).format(WeeklyDateFmt)
        Text("הסיכום השבועי", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium)
        val current = st.weeksBack == 0
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = { vm.shiftWeek(1) }, modifier = Modifier.testTag("week_prev")) {
                Text("השבוע הקודם", color = HColors.Gold, fontWeight = FontWeight.Bold)
            }
            Text(
                "שבוע $from עד $to", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).testTag("week_range"), textAlign = TextAlign.Center
            )
            androidx.compose.material3.TextButton(onClick = { vm.shiftWeek(-1) }, enabled = !current, modifier = Modifier.testTag("week_next")) {
                Text("השבוע הבא", color = if (current) HColors.ParchmentDim else HColors.Gold, fontWeight = FontWeight.Bold)
            }
        }

        AveragesCard(st.avg)

        // the story: one loud slide per number, tap to advance. Only at the end of the week (Friday, Saturday);
        // on the other days the plain details below are the whole screen. Never for an earlier week.
        if (current && WeeklyWrapped.showOn(Days.today())) {
            com.tamir.hamasa.ui.common.WrappedStory(
                slides = WeeklyWrapped.slides(st, from, to),
                modifier = Modifier.fillMaxWidth().height(520.dp),
                testTag = "weekly_wrapped"
            )
            Text("הפרטים המלאים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
        }

        // Verdict
        Card(shape = WeeklyCardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.GoldDeep)) {
            Text(st.verdictHe, Modifier.padding(16.dp), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
        }

        // Numbers
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberTile("אימונים", st.sessions.toString(), HColors.Parchment, Modifier.weight(1f))
            NumberTile("נפח (ק\"ג)", volumeText(st.volumeKg), HColors.Gold, Modifier.weight(1f))
            NumberTile("שיאים", st.prs.toString(), HColors.Gold, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberTile("ניסיון", st.xp.toString(), HColors.Sky, Modifier.weight(1f))
            NumberTile("זהב", st.gold.toString(), HColors.Gold, Modifier.weight(1f))
            // the streak is a number of today, not of an earlier week
            if (current) NumberTile("רצף", st.streak.toString(), HColors.Ember, Modifier.weight(1f))
        }

        CaloriesWeekCard(st)

        // Boss (of the current week only)
        if (current) Card(shape = WeeklyCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, if (st.bossDefeated) HColors.Gold else HColors.Danger)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("בוס השבוע: ${st.bossNameHe}", color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (st.bossDefeated) "הובס" else "${st.bossHp} / ${st.bossMaxHp}",
                        color = if (st.bossDefeated) HColors.Gold else HColors.ParchmentDim,
                        fontWeight = FontWeight.Bold
                    )
                }
                val frac = (st.bossHp.toFloat() / st.bossMaxHp).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(HColors.Slate)) {
                    Box(Modifier.fillMaxWidth(frac).height(12.dp).clip(RoundedCornerShape(6.dp)).background(HColors.Danger))
                }
                Text(com.tamir.hamasa.domain.game.Addr.x("\"${st.bossLineHe}\""), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Muscles of the week
        Card(shape = WeeklyCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("השרירים של השבוע", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                if (st.heat.isEmpty) {
                    Text("אף שריר לא עבד השבוע. הם נחו. יותר מדי.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
                } else {
                    RadarChart(
                        current = st.heat.regionScores,
                        previous = if (st.previousHeat.isEmpty) null else st.previousHeat.regionScores,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!st.previousHeat.isEmpty) {
                        Text("הקו המקווקו: השבוע הקודם.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    MuscleHeatmap(heat = st.heat.heat, modifier = Modifier.fillMaxWidth())
                    MuscleHeatLegend()
                }
            }
        }

        // End-of-week box
        if (current) BoxCard(
            sealedCount = st.sealedCount,
            canOpen = st.canOpenBox,
            opening = st.opening,
            opened = st.opened,
            onOpen = { vm.openBox() }
        )
        // the monthly summaries live one level up
        androidx.compose.material3.TextButton(
            onClick = { com.tamir.hamasa.ui.progress.ProgressNav.open(com.tamir.hamasa.ui.progress.ProgressNav.monthly(-1)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("סיכומי החודשים", color = HColors.Gold, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NumberTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun volumeText(kg: Double): String =
    if (kg >= 10_000.0) String.format(Locale.US, "%.1f טון", kg / 1000.0) else kg.toInt().toString()

/** "קופסת סוף השבוע": chests of Sunday..Thursday, opened together. */
@Composable
private fun BoxCard(sealedCount: Int, canOpen: Boolean, opening: Boolean, opened: List<ChestDrop>, onOpen: () -> Unit) {
    Card(shape = WeeklyCardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(3.dp, HColors.Gold)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("קופסת סוף השבוע", color = HColors.Gold, style = MaterialTheme.typography.headlineSmall)
            when {
                opened.isNotEmpty() -> {
                    val best = opened.maxByOrNull { it.rarity.ordinal }?.rarity ?: Rarity.COMMON
                    val line = remember(opened) { Voice.chest(best) }
                    Text(line, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    opened.forEachIndexed { i, drop -> RevealedChest(index = i, drop = drop) }
                }
                sealedCount == 0 -> Text(
                    "הקופסה ריקה. תיבות מאימוני ראשון עד חמישי נכנסות לכאן סגורות.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
                )
                else -> {
                    Text(
                        if (sealedCount == 1) "תיבה אחת סגורה מחכה." else "$sealedCount תיבות סגורות מחכות.",
                        color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                    )
                    if (canOpen) {
                        Button(
                            onClick = onOpen,
                            enabled = !opening,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                        ) {
                            if (opening) CircularProgressIndicator(Modifier.size(20.dp), color = HColors.Ink, strokeWidth = 2.dp)
                            else Text(com.tamir.hamasa.domain.game.Addr.t("פתח את הקופסה", "פתחי את הקופסה"), fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    } else {
                        Text(
                            "הקופסה נפתחת ביום שישי בבוקר. עד אז לא מציצים. גם אני לא.",
                            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/** One revealed chest: pops in after the previous one (staggered scale + fade). */
@Composable
private fun RevealedChest(index: Int, drop: ChestDrop) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(350L * index)
        shown = true
    }
    val scale by animateFloatAsState(if (shown) 1f else 0.4f, tween(450), label = "chest_scale_$index")
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(450), label = "chest_alpha_$index")
    val color = when (drop.rarity) {
        Rarity.COMMON -> HColors.ParchmentDim
        Rarity.RARE -> HColors.Rare
        Rarity.LEGENDARY -> HColors.Legendary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = fade }
            .clip(RoundedCornerShape(14.dp))
            .background(HColors.SlateLight)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val rarityHe = when (drop.rarity) { Rarity.COMMON -> "רגילה"; Rarity.RARE -> "נדירה"; Rarity.LEGENDARY -> "אגדית" }
        Text("תיבה $rarityHe", color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (drop.xp > 0) Text("+${drop.xp} ניסיון", color = HColors.Sky, fontWeight = FontWeight.Bold)
        if (drop.gold > 0) Text("+${drop.gold} זהב", color = HColors.Gold, fontWeight = FontWeight.Bold)
        drop.modeId?.let { Text("מצב אימון חדש: " + (ModeCatalog.byId(it)?.nameHe ?: it), color = HColors.Parchment) }
        drop.itemId?.let { Text("ציוד חדש: " + (GearCatalog.byId(it)?.nameHe ?: it), color = HColors.Parchment) }
        if (drop.shield) Text("מגן רצף", color = HColors.Moss)
        if (drop.joker) Text("ג'וקר", color = HColors.Ember)
    }
}

/**
 * "הממוצע שלך": what he burns and eats on an average day, over the last 30 finished days. On top of the weekly
 * summary (owner request 2026-11-23).
 */
@Composable
internal fun AveragesCard(avg: com.tamir.hamasa.data.repo.EnergyTotals) {
    Card(shape = WeeklyCardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("week_averages"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("הממוצע היומי שלך", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
            if (avg.days <= 0) {
                Text("עוד אין ימים שנרשמו. הממוצע ייבנה מהארוחות שתרשום.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            } else {
                val bal = avg.avgEaten - avg.avgBurn
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AverageTile("שריפה", avg.avgBurn.toString(), HColors.Ember, Modifier.weight(1f))
                    AverageTile("אוכל", avg.avgEaten.toString(), HColors.Parchment, Modifier.weight(1f))
                    AverageTile(if (bal < 0) "גירעון" else if (bal > 0) "עודף" else "מאזן", kotlin.math.abs(bal).toString(), if (bal < 0) HColors.Moss else HColors.Gold, Modifier.weight(1f))
                }
                Text(
                    "קלוריות ליום, לפי ${avg.days} הימים האחרונים שנגמרו." +
                        if (avg.estimatedDays > 0) " ${avg.estimatedDays} מהם בלי רישום, ונספרו כיום אוכל ממוצע." else "",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AverageTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * "הקלוריות של השבוע": every day with its distance from the goal, green when he made it and red when not, and
 * the week's total at the bottom. A day counts only once it is over (today is shown, not judged).
 */
@Composable
internal fun CaloriesWeekCard(st: WeeklySummaryState) {
    val wc = com.tamir.hamasa.domain.food.WeekCalories
    val goal = st.calGoal
    val judged = wc.judgedCount(st.calDays)
    val okDays = wc.okCount(st.calDays)
    val total = wc.totalDiff(st.calDays, goal)
    val hasBurn = st.calDays.any { wc.balance(it, st.calBurn) != null }
    Card(shape = WeeklyCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("הקלוריות של השבוע", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            Text(
                "היעד היומי: ${goal} קלוריות. מטרה: ${st.calDirection.nameHe}." +
                    if (hasBurn) " המספר הגדול: כמה אכלת מול מה ששרפת באותו יום." else "",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            for (d in st.calDays) {
                val diff = d.diff(goal)
                // the real balance of the day (eaten minus burned); without a known burn, the distance from the goal
                val bal = wc.balance(d, st.calBurn)
                val shown = bal ?: diff
                val color = when {
                    d.ok == null -> HColors.ParchmentDim
                    bal != null -> if (wc.balanceOk(st.calDirection, bal)) HColors.Moss else HColors.Danger
                    d.ok == true -> HColors.Moss
                    else -> HColors.Danger
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "יום " + Days.hebrewDayName(d.day) + if (d.isToday) " (היום)" else "",
                            color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
                        )
                        if (d.eaten <= 0) {
                            Text("לא נרשם", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                        } else if (d.estimated && bal == null) {
                            Text("לא נרשם", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                signed(shown),
                                color = color, fontWeight = FontWeight.Black, fontSize = 20.sp,
                                modifier = Modifier.width(84.dp), textAlign = TextAlign.End
                            )
                        }
                    }
                    if (d.estimated) {
                        val burn = st.calBurn[d.day] ?: 0
                        Text(
                            "לא נרשם אוכל. נספר כיום ממוצע שלך: ${d.eaten}" + (if (bal != null) " · שרפת $burn" else ""),
                            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
                        )
                    } else if (d.eaten > 0) {
                        val burn = st.calBurn[d.day] ?: 0
                        Text(
                            if (bal != null) "אכלת ${d.eaten} · שרפת $burn" + (if (d.isToday) " (עד סוף היום)" else "") + " · מול היעד ${signed(diff)}"
                            else "אכלת ${d.eaten} · מול היעד",
                            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            val estimatedDays = wc.estimatedCount(st.calDays)
            if (judged == 0 && estimatedDays == 0) {
                Text(
                    "עוד אין יום שנגמר ונרשם. הסיכום נבנה כל יום מחדש.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
            } else {
                if (hasBurn) {
                    val totalBal = wc.totalBalance(st.calDays, st.calBurn)
                    val balColor = if (wc.balanceOk(st.calDirection, totalBal)) HColors.Moss else HColors.Danger
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("בסך הכול מול השריפה", color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(signed(totalBal), color = balColor, fontWeight = FontWeight.Black, fontSize = 24.sp, modifier = Modifier.testTag("week_balance"))
                    }
                    val kg = String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(totalBal) / wc.KCAL_PER_KG_FAT.toFloat())
                    Text(
                        when {
                            totalBal < 0 -> "גירעון של ${-totalBal} קלוריות בימים שנגמרו. שווה ערך לבערך $kg ק\"ג שומן."
                            totalBal > 0 -> "עודף של $totalBal קלוריות בימים שנגמרו. שווה ערך לבערך $kg ק\"ג."
                            else -> "אכלת בדיוק מה ששרפת."
                        },
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                    )
                    if (estimatedDays > 0) Text(
                        (if (estimatedDays == 1) "יום אחד בלי רישום נספר" else "$estimatedDays ימים בלי רישום נספרו") + " לפי יום אוכל ממוצע שלך.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
                    )
                }
                if (judged > 0) {
                val okAll = when (st.calDirection) {
                    com.tamir.hamasa.domain.energy.GoalDirection.CUT -> total <= 0
                    com.tamir.hamasa.domain.energy.GoalDirection.GAIN -> total >= 0
                    com.tamir.hamasa.domain.energy.GoalDirection.MAINTAIN ->
                        kotlin.math.abs(total) <= com.tamir.hamasa.domain.food.Nutrition.HUNT_RANGE * judged
                }
                val totalColor = if (okAll) HColors.Moss else HColors.Danger
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("בסך הכול מול היעד", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(signed(total), color = totalColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Text(
                    "$okDays מתוך $judged ימים בטווח היעד. " + when {
                        total > 0 -> "חרגת בסך הכול ב-$total קלוריות מעל היעד."
                        total < 0 -> "בסך הכול אכלת ${-total} קלוריות מתחת ליעד."
                        else -> "בדיוק על היעד."
                    },
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
                }
            }
        }
    }
}

/** The sign stays on the left of the number in a right-to-left screen (a left-to-right mark in front). */
private fun signed(v: Int): String = "\u200E" + (if (v > 0) "+$v" else if (v < 0) "-${-v}" else "0")
