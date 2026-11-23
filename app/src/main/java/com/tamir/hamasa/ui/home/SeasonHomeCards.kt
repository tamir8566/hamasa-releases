package com.tamir.hamasa.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.health.RestingHr
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.data.repo.ChallengeCenter
import com.tamir.hamasa.data.repo.DeloadCenter
import com.tamir.hamasa.data.repo.EventBoss
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.SeasonVoice
import com.tamir.hamasa.domain.game.SeasonalEvents
import com.tamir.hamasa.ui.common.rememberNamedPainter
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch

private val SeasonCardShape = RoundedCornerShape(20.dp)

/** "6.11" */
private fun dayLabel(day: Long): String {
    val d = Days.of(day)
    return "${d.dayOfMonth}.${d.monthValue}"
}

/**
 * Coach-season cards on Home: the seasonal event banner, the resting heart-rate warning, the deload proposal
 * (or the running deload week) and the weekly challenge. Each one shows nothing when it has nothing to say.
 */
@Composable
fun SeasonHomeCards(modifier: Modifier = Modifier) {
    val profile by AppGraph.prefs.profile.collectAsStateWithLifecycle(initialValue = null)
    val p = profile ?: return
    val today = remember { Days.today() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EventBanner(p, today)
        RestingHrWarning(p, today)
        DeloadHomeCard(p, today)
        ChallengeCard(p, today)
    }
}

// ---------------------------------------------------------------- seasonal event

@Composable
fun EventBanner(p: Profile, today: Long, compact: Boolean = false) {
    val cur = remember(p.eventBossState, today) { EventBoss.current(p.eventBossState, today) }
    if (cur == null) {
        if (compact) return
        val next = remember(today) { SeasonalEvents.upcoming(today, 3) } ?: return
        val days = (next.second - today).toInt()
        Text(
            if (days == 1) "מחר מתחיל ${next.first.event.nameHe}. ${next.first.event.bossNameHe} כבר בדרך."
            else "בעוד $days ימים: ${next.first.event.nameHe}. ${next.first.event.bossNameHe} כבר בדרך.",
            color = HColors.ParchmentDim, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    val (w, st) = cur
    val boss = remember(w.event.id) { EventBoss.portraitOf(w.event) }
    val portrait = if (boss != null) rememberNamedPainter("boss_${boss.sheet}_${boss.index}") else null
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 0.dp),
        shape = SeasonCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (st.defeated) HColors.Gold else HColors.Legendary)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(if (compact) 56.dp else 72.dp)
                    .height(if (compact) 80.dp else 104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(HColors.Ink)
                    .border(2.dp, HColors.Legendary, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (portrait != null) {
                    Image(painter = portrait, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("אירוע ${w.event.nameHe}", color = HColors.Legendary, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(w.event.bossNameHe, color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
                val frac = if (st.maxHp > 0) (st.hp.toFloat() / st.maxHp).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(HColors.Slate)
                    ) {
                        Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(CircleShape).background(HColors.Danger))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (st.defeated) "הובס" else "${st.hp} מתוך ${st.maxHp}",
                        color = if (st.defeated) HColors.Gold else HColors.ParchmentDim, fontSize = 12.sp
                    )
                }
                Text(
                    com.tamir.hamasa.domain.game.Addr.x(if (st.defeated) "\"${w.event.defeatedHe}\"" else "\"${w.event.quoteHe}\""),
                    color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                )
                if (!compact) {
                    Text(com.tamir.hamasa.domain.game.Addr.x(w.event.bannerHe), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
                val left = w.daysLeft(today)
                Text(
                    (if (st.defeated) "התג של החג נאסף. " else com.tamir.hamasa.domain.game.Addr.x(com.tamir.hamasa.domain.game.BossWeakness.lineHe(com.tamir.hamasa.domain.game.BossWeakness.forEvent(w.event.id))) + ". " + com.tamir.hamasa.domain.game.CharacterNames.g(com.tamir.hamasa.domain.game.BossHints.forEvent(w.event.id)) + " התג רק בזמן האירוע. ") +
                        (if (left <= 1) "היום האחרון." else "נשארו $left ימים."),
                    color = HColors.ParchmentDim, fontSize = 12.sp
                )
            }
        }
    }
}

/** The map's version of the event banner (nothing when no event runs). */
@Composable
fun EventBossMapCard() {
    val profile by AppGraph.prefs.profile.collectAsStateWithLifecycle(initialValue = null)
    val p = profile ?: return
    val today = remember { Days.today() }
    EventBanner(p, today, compact = true)
}

// ---------------------------------------------------------------- resting heart rate

@Composable
private fun RestingHrWarning(p: Profile, today: Long) {
    val st = remember(p.restingHrCsv, today) { RestingHr.status(p.restingHrCsv, today) }
    if (st == null || !st.elevated) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SeasonCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Danger)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.tamir.hamasa.domain.game.CharacterNames.g(SeasonVoice.restingHrWarning(today)), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                "דופק מנוחה ${st.lastBpm}, הרגיל שלך ${st.baseline}. הג'ים ברו רושם את זה כסימן עייפות.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ---------------------------------------------------------------- deload

@Composable
private fun DeloadHomeCard(p: Profile, today: Long) {
    DeloadPanel(p, today, showShambiuluk = true)
}

/** Shared by Home and the coach screen: the proposal (accept / not now), or the running / upcoming deload week. */
@Composable
fun DeloadPanel(p: Profile, today: Long, showShambiuluk: Boolean) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val view by produceState<DeloadCenter.View?>(initialValue = null, p.deloadWeekStart, p.deloadSnoozeUntil, p.restingHrCsv, tick) {
        value = runCatching { DeloadCenter.view(today) }.getOrNull()
    }
    val v = view ?: return
    val proposal = v.proposal
    when {
        v.active || v.upcoming -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SeasonCardShape,
                colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
                border = BorderStroke(2.dp, HColors.Sky)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (v.active) "שבוע הורדת עומס, עד ${dayLabel(v.weekStart + 6)}"
                        else "שבוע הורדת עומס מתחיל ביום ראשון ${dayLabel(v.weekStart)}",
                        color = HColors.Sky, fontWeight = FontWeight.Black, fontSize = 16.sp
                    )
                    Text(
                        "אותם תרגילים, בערך 40% פחות סטים וכ-10% פחות משקל. בלי עליות משקל ובלי מרדף אחרי שיאים. הניסיון והזהב לא משתנים.",
                        color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                    )
                    if (showShambiuluk && v.active) {
                        Text(com.tamir.hamasa.domain.game.CharacterNames.g(SeasonVoice.deloadActive(today)), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!showShambiuluk) {
                        OutlinedButton(
                            onClick = { scope.launch { runCatching { DeloadCenter.cancel(today) }; tick++ } },
                            border = BorderStroke(1.dp, HColors.ParchmentDim)
                        ) { Text(com.tamir.hamasa.domain.game.Addr.t("בטל את שבוע ההורדה", "בטלי את שבוע ההורדה"), color = HColors.ParchmentDim) }
                    }
                }
            }
        }
        proposal != null -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SeasonCardShape,
                colors = CardDefaults.cardColors(containerColor = HColors.Ink),
                border = BorderStroke(2.dp, HColors.Sky)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("הג'ים ברו מציע: שבוע הורדת עומס", color = HColors.Sky, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    proposal.reasonsHe.forEach { r -> Text("• $r", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        "שבוע אחד, ראשון עד שבת: אותם תרגילים, בערך 40% פחות סטים, כ-10% פחות משקל. הגוף מתאושש ובא חזק יותר.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                    )
                    if (showShambiuluk) {
                        Text(com.tamir.hamasa.domain.game.CharacterNames.g(SeasonVoice.deloadOffer(today)), color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { runCatching { DeloadCenter.accept(today) }; tick++ } },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HColors.Sky, contentColor = HColors.Ink),
                            modifier = Modifier.weight(1f)
                        ) { Text(com.tamir.hamasa.domain.game.Addr.t("מקבל", "מקבלת"), fontWeight = FontWeight.Black) }
                        OutlinedButton(
                            onClick = { scope.launch { runCatching { DeloadCenter.snooze(today) }; tick++ } },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, HColors.ParchmentDim),
                            modifier = Modifier.weight(1f)
                        ) { Text("לא עכשיו", color = HColors.Parchment) }
                    }
                }
            }
        }
        else -> {}
    }
}

// ---------------------------------------------------------------- weekly challenge

@Composable
private fun ChallengeCard(p: Profile, today: Long) {
    // xp changes after every finished session and every reward, which is when the count can move
    val state by produceState<ChallengeCenter.State?>(initialValue = null, p.xp, p.challengeDoneWeek, p.challengeId, today) {
        value = ChallengeCenter.refresh(today)
    }
    val s = state ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SeasonCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (s.done) HColors.Gold else HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("אתגר השבוע", color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    if (s.done) "הושלם" else if (s.daysLeft <= 1) "היום האחרון" else "נשארו ${s.daysLeft} ימים",
                    color = if (s.done) HColors.Gold else HColors.ParchmentDim, fontSize = 13.sp
                )
            }
            Text(com.tamir.hamasa.domain.game.Addr.x(s.challenge.titleHe), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(com.tamir.hamasa.domain.game.Addr.x(s.challenge.howHe), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { (s.progress.toFloat() / s.challenge.target.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(8.dp),
                    color = HColors.Gold,
                    trackColor = HColors.Ink,
                    strokeCap = StrokeCap.Round
                )
                Spacer(Modifier.size(8.dp))
                Text("${s.progress} / ${s.challenge.target}", color = HColors.Parchment, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(com.tamir.hamasa.domain.game.CharacterNames.g(s.lineHe), color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
            if (!s.done) {
                Text("פרס: ${com.tamir.hamasa.domain.game.WeeklyChallenges.REWARD_GOLD} זהב ו-${com.tamir.hamasa.domain.game.WeeklyChallenges.REWARD_XP} ניסיון", color = HColors.ParchmentDim, fontSize = 12.sp)
            }
        }
    }
}
