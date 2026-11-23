package com.tamir.hamasa.ui.energy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.data.repo.EnergyDay
import com.tamir.hamasa.data.repo.EnergySnapshot
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.energy.ActivityLevel
import com.tamir.hamasa.domain.energy.BmrMethod
import com.tamir.hamasa.domain.energy.Confidence
import com.tamir.hamasa.domain.energy.EnergyModel
import com.tamir.hamasa.domain.energy.GoalDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** "מחשבון אנרגיה": burn vs intake, the automatic goal and its settings. Nothing new is stored but prefs. */
class EnergyViewModel : ViewModel() {
    private val _snap = MutableStateFlow<EnergySnapshot?>(null)
    val snap: StateFlow<EnergySnapshot?> = _snap

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Last refresh start; the init call and the first resume arrive together, one computation is enough. */
    private var lastRefreshAt = 0L

    init { refresh() }

    /** Recomputes everything (latest weigh-in, workouts, steps); also on every resume of the screen. */
    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt in 0 until 2_000L) return
        lastRefreshAt = now
        viewModelScope.launch {
            _snap.value = runCatching { EnergyData.syncAutoGoal() ?: EnergyData.snapshot() }.getOrNull()
        }
    }

    fun dismissMessage() { _message.value = null }

    private fun edit(msg: String?, block: (Profile) -> Profile) {
        viewModelScope.launch {
            AppGraph.prefs.update(block)
            CloudSync.pushSoon(AppGraph.appContext)
            if (msg != null) _message.value = msg
            _snap.value = runCatching { EnergyData.syncAutoGoal() ?: EnergyData.snapshot() }.getOrNull()
            com.tamir.hamasa.widget.Widgets.refreshAll(AppGraph.appContext)
        }
    }

    fun saveBasics(age: Int, sex: String, heightCm: Int, bodyFatPct: Float, activity: Int) =
        edit("נשמר. החישוב עודכן.") {
            it.copy(
                age = age.coerceIn(14, 100), sex = sex, heightCm = heightCm.coerceIn(120, 230),
                bodyFatPct = if (bodyFatPct in 3f..60f) bodyFatPct else 0f,
                activityLevel = activity.coerceIn(0, ActivityLevel.values().size - 1)
            )
        }

    fun saveAgeSex(age: Int, sex: String) = edit("רשמתי. עכשיו החשבון אמיתי.") { it.copy(age = age.coerceIn(14, 100), sex = sex) }

    fun setRate(x100: Int) = edit(null) { it.copy(goalRateX100 = x100.coerceIn(10, 100)) }

    fun setGainRate(x100: Int) = edit(null) { it.copy(gainRateX100 = x100.coerceIn(10, 50)) }

    /** -1 = automatic, 0 cut, 1 maintain, 2 gain. */
    fun setDirection(i: Int) = edit(
        when (i) {
            0 -> "הכיוון: ירידה. היעד והחלבון עודכנו."
            1 -> "הכיוון: שמירה. אוכלים כמו ששורפים."
            2 -> "הכיוון: עלייה. עודף קטן, לאט ובשקט."
            else -> "הכיוון אוטומטי. לפי משקל היעד והמטרה בג'ים ברו."
        }
    ) { it.copy(goalDirection = i.coerceIn(-1, 2)) }

    fun setKcalManual(kcal: Int) = edit("יעד ידני נשמר. הוא קובע.") { it.copy(kcalGoal = kcal.coerceIn(800, 6000), kcalAuto = false) }

    fun setKcalAuto() = edit("היעד אוטומטי. המחשבון מעדכן אותו לבד.") { it.copy(kcalAuto = true) }

    fun setUseAdaptive(on: Boolean) = edit(
        if (on) "היעד נשען עכשיו על הנתונים בפועל." else "חזרנו לנוסחה."
    ) { it.copy(useAdaptiveTdee = on) }

    /** [value] 0 = back to automatic. */
    fun setProtein(value: Int) = edit(null) { it.copy(proteinGoal = value.coerceIn(0, 500)) }
    fun setCarbs(value: Int) = edit(null) { it.copy(carbsGoal = value.coerceIn(0, 900)) }
    fun setFat(value: Int) = edit(null) { it.copy(fatGoal = value.coerceIn(0, 400)) }
}

private val ECard = RoundedCornerShape(22.dp)

private fun n(v: Int): String = String.format(Locale.US, "%,d", v)

/** Signed number that stays left-to-right inside Hebrew text. */
private fun signed(v: Int): String = "‎" + (if (v < 0) "−" else if (v > 0) "+" else "") + n(abs(v))

private fun kg1(v: Float): String = String.format(Locale.US, "%.1f", v)
private fun kg2(v: Float): String = String.format(Locale.US, "%.2f", v)

/** "31.10" style day of a weigh-in. */
private fun dayDot(day: Long): String {
    val d = Days.of(day)
    return "${d.dayOfMonth}.${d.monthValue}"
}

private fun rateLabel(x100: Int): String = when (x100) {
    25 -> "0.25%"
    50 -> "0.5%"
    75 -> "0.75%"
    100 -> "1%"
    else -> String.format(Locale.US, "%.2f%%", x100 / 100f)
}

@Composable
fun EnergyScreen() {
    val vm: EnergyViewModel = viewModel()
    val snapOrNull by vm.snap.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var askDismissed by rememberSaveable { mutableStateOf(false) }

    // back from the background / another app: recompute with the latest weigh-in, steps and workouts
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val s = snapOrNull
    if (s == null) {
        Box(Modifier.fillMaxSize().background(HColors.Slate).padding(24.dp)) {
            Text(com.tamir.hamasa.domain.game.CharacterNames.g("סופר. רגע."), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val p = s.profile

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            com.tamir.hamasa.domain.game.CharacterNames.g("שמביולוק סופר קלוריות. יד אחת לשריפה, יד אחת לצלחת. שתיהן עייפות."),
            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
        )
        message?.let { line ->
            Card(shape = ECard, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.Gold)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(com.tamir.hamasa.domain.game.Addr.x(line), color = HColors.Parchment, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.dismissMessage() }) { Text("סגור", color = HColors.Gold) }
                }
            }
        }
        if (p.age == 0) {
            Text(
                "הגיל עוד לא ידוע. החישוב משתמש בגיל ${EnergyData.FALLBACK_AGE} " + com.tamir.hamasa.domain.game.Addr.t("עד שתמלא אותו בפרטים האישיים.", "עד שתמלאי אותו בפרטים האישיים."),
                color = HColors.Ember, style = MaterialTheme.typography.bodyMedium
            )
        }
        val wDay = s.weightDay
        if (s.hasWeight && wDay != null) {
            Text(
                "לפי משקל ${kg1(s.weightKg)} ק\"ג (שקילה מ-${dayDot(wDay)})",
                color = HColors.Gold, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
            )
        }
        if (!s.hasWeight) {
            Text(
                "אין עדיין שקילה. החישוב לפי משקל ההתחלה (${kg1(p.startWeightKg)} ק\"ג).",
                color = HColors.Ember, style = MaterialTheme.typography.bodyMedium
            )
        }
        TodayCard(s)
        WeekChartCard(s.week)
        // watch: daily resting heart rate (nothing when the watch is not connected)
        RestingHrCard()
        TargetCard(s, onAuto = { vm.setKcalAuto() }, onAdaptive = { vm.setUseAdaptive(it) })
        GoalSettingsCard(s, vm)
        PersonalCard(p, onSave = { age, sex, h, bf, act -> vm.saveBasics(age, sex, h, bf, act) })
        HowCard(s)
        Spacer(Modifier.height(16.dp))
    }

    if (p.age == 0 && !askDismissed) {
        AgeSexDialog(
            initialSex = p.sex,
            onSave = { age, sex -> askDismissed = true; vm.saveAgeSex(age, sex) },
            onDismiss = { askDismissed = true }
        )
    }
}

// ---------------------------------------------------------------- today

@Composable
private fun TodayCard(s: EnergySnapshot) {
    Card(
        Modifier.fillMaxWidth(), shape = ECard,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(3.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("היום", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            LineRow("שריפה טבעית במנוחה", n(s.bmr.kcal.toInt()))
            LineRow("פעילות יומיומית", n(s.neatToday))
            if (s.neatSourceHe.isNotBlank()) {
                Text(com.tamir.hamasa.domain.game.Addr.x(s.neatSourceHe), color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
            }
            LineRow("אימונים היום", n(s.workoutsToday))
            LineRow("עיכול המזון", n(s.tefToday))
            HorizontalDivider(color = HColors.Ink)
            LineRow("סך הכול שריפה", n(s.burnToday), bold = true)
            LineRow("אכלת", n(s.intakeToday), bold = true, color = HColors.Sky)
            Spacer(Modifier.height(4.dp))
            val bal = s.balanceToday
            Text(
                "מאזן היום: ${signed(bal)}",
                // colored by "הכיוון": a deficit is the plan when cutting, a surplus is the plan when gaining
                color = when (s.direction) {
                    com.tamir.hamasa.domain.energy.GoalDirection.CUT -> if (bal <= 0) HColors.Moss else HColors.Ember
                    com.tamir.hamasa.domain.energy.GoalDirection.GAIN ->
                        if (s.intakeToday == 0) HColors.Parchment else if (bal >= 0) HColors.Moss else HColors.Ember
                    com.tamir.hamasa.domain.energy.GoalDirection.MAINTAIN ->
                        if (s.intakeToday == 0 || kotlin.math.abs(bal) <= 250) HColors.Moss else HColors.Gold
                },
                fontSize = 26.sp, fontWeight = FontWeight.Black
            )
            Text(
                if (s.intakeToday == 0) "עוד לא נרשמה ארוחה היום. השריפה היא צפי ליום שלם."
                else "צפי ליום שלם. האכילה לפי יומן הארוחות. מינוס זה גירעון.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun LineRow(label: String, value: String, bold: Boolean = false, color: Color = HColors.Parchment) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(value, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold) FontWeight.Black else FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- 7 days

private val BurnColor = HColors.Gold
private val IntakeColor = HColors.Sky

private fun shortDay(day: Long, today: Long): String {
    if (day == today) return "היום"
    return when (Days.of(day).dayOfWeek) {
        DayOfWeek.SUNDAY -> "א׳"
        DayOfWeek.MONDAY -> "ב׳"
        DayOfWeek.TUESDAY -> "ג׳"
        DayOfWeek.WEDNESDAY -> "ד׳"
        DayOfWeek.THURSDAY -> "ה׳"
        DayOfWeek.FRIDAY -> "ו׳"
        DayOfWeek.SATURDAY -> "ש׳"
    }
}

@Composable
private fun WeekChartCard(week: List<EnergyDay>) {
    val today = Days.today()
    val top = max(1, week.maxOfOrNull { max(it.burn, it.intake) } ?: 1)
    val barArea = 130f
    Card(
        Modifier.fillMaxWidth(), shape = ECard,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("שבעה ימים: שריפה מול אכילה", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
            // Row follows RTL: the oldest day is on the right, today on the left.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                for (d in week) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.height(barArea.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Bar(d.burn, top, barArea, BurnColor)
                            Bar(d.intake, top, barArea, IntakeColor)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(shortDay(d.day, today), color = if (d.day == today) HColors.Gold else HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(BurnColor, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(6.dp))
                Text("שריפה", color = HColors.Parchment, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.size(10.dp).background(IntakeColor, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(6.dp))
                Text("אכילה", color = HColors.Parchment, style = MaterialTheme.typography.labelLarge)
            }
            val logged = week.filter { it.intake > 0 }
            if (logged.isNotEmpty()) {
                val avgIn = logged.sumOf { it.intake } / logged.size
                val avgBurn = logged.sumOf { it.burn } / logged.size
                Text(
                    "בימים הרשומים: שריפה ממוצעת ${n(avgBurn)}, אכילה ממוצעת ${n(avgIn)}. מאזן ממוצע ${signed(avgIn - avgBurn)}.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("אין השבוע ימים רשומים ביומן הארוחות. העמודות הכחולות מחכות.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Bar(value: Int, top: Int, area: Float, color: Color) {
    val frac = (value.toFloat() / top.toFloat()).coerceIn(0f, 1f)
    val h = if (value > 0) max(3f, area * frac) else 0f
    Box(Modifier.width(9.dp).height(h.dp).background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
}

// ---------------------------------------------------------------- target

@Composable
private fun TargetCard(s: EnergySnapshot, onAuto: () -> Unit, onAdaptive: (Boolean) -> Unit) {
    val p = s.profile
    val t = s.target
    Card(
        Modifier.fillMaxWidth(), shape = ECard,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(3.dp, HColors.Gold)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("היעד היומי המומלץ", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            Text("${n(t.kcal)} קלוריות", color = HColors.Parchment, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(
                "הוצאה יומית משוערת: ${n(s.tdeeUsed)} " + if (s.usingAdaptive) "(לפי הנתונים בפועל)." else "(לפי הנוסחה).",
                color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "הכיוון: ${s.direction.nameHe}" + if (s.directionAuto) " (אוטומטי)." else ".",
                color = HColors.Gold, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold
            )
            when (s.direction) {
                GoalDirection.CUT -> {
                    Text(
                        "גירעון של ${n(t.deficit)} ביום. צפי: ירידה של כ-${kg2(t.weeklyLossKg)} ק\"ג בשבוע (${rateLabel(p.goalRateX100)} ממשקל הגוף).",
                        color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                    )
                    if (t.limited) {
                        Text(
                            "היעד הוגבל: לא פחות מ-${n(max(if (p.sex == "F") EnergyModel.FLOOR_FEMALE else EnergyModel.FLOOR_MALE, s.bmr.kcal.toInt()))} ביום, ולא גירעון מעל ${n(EnergyModel.MAX_DEFICIT)}.",
                            color = HColors.Ember, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "בשבועיים הראשונים המשקל יורד מהר יותר (מים וגליקוגן). אחר כך הגוף מאט קצת. היעד מתעדכן עם כל שקילה.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
                    )
                }
                GoalDirection.MAINTAIN -> {
                    Text(
                        "אוכלים כמו ששורפים. המשקל אמור לעמוד במקום, עם תנודות של מים מיום ליום.",
                        color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                    )
                    if (t.limited) {
                        Text(
                            "היעד הוגבל: לא פחות מ-${n(max(if (p.sex == "F") EnergyModel.FLOOR_FEMALE else EnergyModel.FLOOR_MALE, s.bmr.kcal.toInt()))} ביום.",
                            color = HColors.Ember, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                GoalDirection.GAIN -> {
                    Text(
                        "עודף של ${n(t.surplus)} ביום. צפי: עלייה של כ-${kg2(t.weeklyGainKg)} ק\"ג בשבוע (${rateLabel(p.gainRateX100)} ממשקל הגוף).",
                        color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                    )
                    if (t.limited) {
                        Text(
                            "העודף נשמר בין ${n(EnergyModel.MIN_SURPLUS)} ל-${n(EnergyModel.MAX_SURPLUS)} ביום. יותר מזה הולך בעיקר לשומן.",
                            color = HColors.Ember, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "עלייה איטית שומרת על רוב התוספת כשריר. אם המשקל עולה מהר מהצפי, מורידים קצת.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (p.kcalAuto) {
                Text("היעד שלך: אוטומטי. אותו מספר מופיע במסך האוכל.", color = HColors.Moss, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            } else {
                Text("היעד שלך כרגע: ${n(p.kcalGoal)}, ידני.", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Button(
                    onClick = onAuto,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(com.tamir.hamasa.domain.game.Addr.t("עבור ליעד האוטומטי", "עברי ליעד האוטומטי"), fontWeight = FontWeight.Black) }
            }

            HorizontalDivider(color = HColors.SlateLight, modifier = Modifier.padding(vertical = 6.dp))
            val a = s.adaptive
            if (a.confidence != Confidence.NONE) {
                Text("לפי המשקל והאכילה שלך בפועל: ${n(a.tdee)} ביום", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (a.confidence == Confidence.GOOD) "ביטחון טוב. " else "ביטחון ראשוני. ") +
                        "${a.loggedDays} ימים רשומים מתוך ${a.spanDays}, ${a.weighIns} שקילות, מגמה ${signedKg(a.slopeKgPerWeek)} ק\"ג בשבוע.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
                val diff = a.tdee - s.model.total
                Text("הנוסחה אמרה ${n(s.model.total)}. הפער: ${signed(diff)}.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                if (p.useAdaptiveTdee) {
                    OutlinedButton(onClick = { onAdaptive(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text(com.tamir.hamasa.domain.game.Addr.t("חזור לנוסחה", "חזרי לנוסחה"), color = HColors.Parchment, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { onAdaptive(true) },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(com.tamir.hamasa.domain.game.Addr.t("בנה את היעד על הנתון הזה", "בני את היעד על הנתון הזה"), fontWeight = FontWeight.Black) }
                }
            } else {
                Text("חישוב לפי הנתונים בפועל", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "יופיע אחרי שבועיים של רישום ארוחות ושקילות (לפחות 10 ימים רשומים ו-4 שקילות). כרגע: ${a.loggedDays} ימים רשומים, ${a.weighIns} שקילות.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
                if (p.useAdaptiveTdee) {
                    TextButton(onClick = { onAdaptive(false) }) { Text(com.tamir.hamasa.domain.game.Addr.t("בטל שימוש בנתונים בפועל", "בטלי שימוש בנתונים בפועל"), color = HColors.ParchmentDim) }
                }
            }
        }
    }
}

private fun signedKg(v: Float): String =
    "‎" + (if (v < -0.005f) "−" else if (v > 0.005f) "+" else "") + kg2(abs(v))

// ---------------------------------------------------------------- goal settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalSettingsCard(s: EnergySnapshot, vm: EnergyViewModel) {
    val p = s.profile
    val lean = EnergyModel.leanMass(s.weightKg, p.bodyFatPct)
    // automatic macros for the kcal goal that is actually in force (manual kcal included)
    val autoNow = EnergyModel.macroTargets(s.effective.kcal, s.weightKg, p.heightCm.toFloat(), lean, direction = s.direction)
    Card(
        Modifier.fillMaxWidth(), shape = ECard,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("הגדרות יעד", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            Text("הכיוון", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = p.goalDirection !in 0..2, onClick = { vm.setDirection(-1) }, label = { Text("אוטומטי") })
                FilterChip(selected = p.goalDirection == 0, onClick = { vm.setDirection(0) }, label = { Text("ירידה") })
                FilterChip(selected = p.goalDirection == 1, onClick = { vm.setDirection(1) }, label = { Text("שמירה") })
                FilterChip(selected = p.goalDirection == 2, onClick = { vm.setDirection(2) }, label = { Text("עלייה") })
            }
            Text(
                if (s.directionAuto)
                    "אוטומטי: מעל משקל היעד (${kg1(p.goalWeightKg)} ק\"ג) יורדים. ביעד שומרים, או עולים לאט אם המטרה בג'ים ברו היא בניית שריר. עכשיו: ${s.direction.nameHe}."
                else
                    "נבחר ידנית: ${s.direction.nameHe}. היעד, החלבון והשומן נקבעים לפיו.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
            )
            when (s.direction) {
                GoalDirection.CUT -> {
                    Text("קצב ירידה בשבוע (אחוז ממשקל הגוף)", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (x in listOf(25, 50, 75, 100)) {
                            val kgWeek = x / 10000f * s.weightKg
                            FilterChip(
                                selected = p.goalRateX100 == x,
                                onClick = { vm.setRate(x) },
                                label = { Text("${rateLabel(x)} · ${kg2(kgWeek)} ק\"ג") }
                            )
                        }
                    }
                    Text("0.5% הוא ברירת המחדל: מספיק מהר, ושומר על הכוח במכון.", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                }
                GoalDirection.GAIN -> {
                    Text("קצב עלייה בשבוע (אחוז ממשקל הגוף)", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (x in listOf(25, 50)) {
                            val kgWeek = x / 10000f * s.weightKg
                            FilterChip(
                                selected = p.gainRateX100 == x,
                                onClick = { vm.setGainRate(x) },
                                label = { Text("${rateLabel(x)} · ${kg2(kgWeek)} ק\"ג") }
                            )
                        }
                    }
                    Text("0.25% הוא ברירת המחדל: עלייה נקייה. 0.5% למי שעוד חדש במכון.", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                }
                GoalDirection.MAINTAIN -> {
                    Text("בשמירה אין קצב לבחור. היעד הוא ההוצאה היומית.", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                }
            }

            HorizontalDivider(color = HColors.Ink)
            Text("כל ערך: אוטומטי מהמחשבון, או ידני ממך. ידני תמיד קובע.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            GoalField(
                label = "קלוריות", unit = "", current = s.effective.kcal, manual = !p.kcalAuto, autoValue = s.target.kcal,
                range = 800..6000, onSave = { vm.setKcalManual(it) }, onAuto = { vm.setKcalAuto() }
            )
            GoalField(
                label = "חלבון", unit = "גרם", current = s.effective.proteinG, manual = p.proteinGoal > 0, autoValue = autoNow.proteinG,
                range = 30..500, onSave = { vm.setProtein(it) }, onAuto = { vm.setProtein(0) }
            )
            GoalField(
                label = "פחמימות", unit = "גרם", current = s.effective.carbsG, manual = p.carbsGoal > 0, autoValue = autoNow.carbsG,
                range = 20..900, onSave = { vm.setCarbs(it) }, onAuto = { vm.setCarbs(0) }
            )
            GoalField(
                label = "שומן", unit = "גרם", current = s.effective.fatG, manual = p.fatGoal > 0, autoValue = autoNow.fatG,
                range = 20..400, onSave = { vm.setFat(it) }, onAuto = { vm.setFat(0) }
            )
        }
    }
}

@Composable
private fun GoalField(
    label: String, unit: String, current: Int, manual: Boolean, autoValue: Int,
    range: IntRange, onSave: (Int) -> Unit, onAuto: () -> Unit
) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val v = text.toIntOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (manual) "ידני" else "אוטומטי",
                color = if (manual) HColors.Ember else HColors.Moss, style = MaterialTheme.typography.labelLarge
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(if (unit.isEmpty()) label else "$label ($unit)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (v != null) onSave(v) }, enabled = v != null && v in range && (manual.not() || v != current)) {
                Text(com.tamir.hamasa.domain.game.Addr.t("שמור", "שמרי"), color = HColors.Parchment, fontWeight = FontWeight.Bold)
            }
        }
        if (manual) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("המחשבון היה אומר ${n(autoValue)}.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAuto) { Text(com.tamir.hamasa.domain.game.Addr.t("חזור לאוטומטי", "חזרי לאוטומטי"), color = HColors.Gold, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------------------------------------------------------- personal details

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonalCard(p: Profile, onSave: (Int, String, Int, Float, Int) -> Unit) {
    var ageText by remember(p.age) { mutableStateOf(if (p.age > 0) p.age.toString() else "") }
    var sex by remember(p.sex) { mutableStateOf(if (p.sex == "F") "F" else "M") }
    var heightText by remember(p.heightCm) { mutableStateOf(p.heightCm.toString()) }
    var bfText by remember(p.bodyFatPct) { mutableStateOf(if (p.bodyFatPct > 0f) trim1(p.bodyFatPct) else "") }
    var activity by remember(p.activityLevel) { mutableStateOf(p.activityLevel.coerceIn(0, ActivityLevel.values().size - 1)) }

    val age = ageText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val bf = if (bfText.isBlank()) 0f else bfText.replace(',', '.').toFloatOrNull()
    val valid = age != null && age in 14..100 && height != null && height in 120..230 && bf != null && (bf == 0f || bf in 3f..60f)

    Card(
        Modifier.fillMaxWidth(), shape = ECard,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("פרטים אישיים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ageText, onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("גיל") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = heightText, onValueChange = { heightText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("גובה (ס\"מ)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sex == "M", onClick = { sex = "M" }, label = { Text("זכר") })
                FilterChip(selected = sex == "F", onClick = { sex = "F" }, label = { Text("נקבה") })
            }
            OutlinedTextField(
                value = bfText, onValueChange = { bfText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(4) },
                label = { Text("אחוז שומן (לא חובה)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
            )
            Text("אם אחוז השומן ידוע (סריקה, מדידה), החישוב לפי מסת הגוף הרזה. אם לא, משאירים ריק.", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
            Text("פעילות ביום רגיל, בלי האימונים", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            for (lvl in ActivityLevel.values()) {
                val selected = activity == lvl.ordinal
                Card(
                    Modifier.fillMaxWidth().clickable { activity = lvl.ordinal },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = if (selected) HColors.Ink else HColors.Slate),
                    border = BorderStroke(2.dp, if (selected) HColors.Gold else HColors.Ink)
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(lvl.nameHe, color = if (selected) HColors.Gold else HColors.Parchment, fontWeight = FontWeight.Bold)
                        Text(com.tamir.hamasa.domain.game.Addr.x(lvl.descHe), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Button(
                onClick = { if (valid && age != null && height != null && bf != null) onSave(age, sex, height, bf, activity) },
                enabled = valid,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור פרטים", "שמרי פרטים"), fontWeight = FontWeight.Black) }
        }
    }
}

private fun trim1(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

@Composable
private fun AgeSexDialog(initialSex: String, onSave: (Int, String) -> Unit, onDismiss: () -> Unit) {
    var ageText by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(if (initialSex == "F") "F" else "M") }
    val age = ageText.toIntOrNull()
    val ok = age != null && age in 14..100
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שתי שאלות קטנות") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(com.tamir.hamasa.domain.game.CharacterNames.g("השריפה הטבעית תלויה בגיל ובמין. בלי זה, אני מנחש. ואני מנחש רע."))
                OutlinedTextField(
                    value = ageText, onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("גיל") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sex == "M", onClick = { sex = "M" }, label = { Text("זכר") })
                    FilterChip(selected = sex == "F", onClick = { sex = "F" }, label = { Text("נקבה") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (age != null && ok) onSave(age, sex) }, enabled = ok) { Text("רשמתי", color = HColors.Gold, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("אחר כך", color = HColors.ParchmentDim) } }
    )
}

// ---------------------------------------------------------------- how it works

@Composable
private fun HowCard(s: EnergySnapshot) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { open = !open }, shape = ECard,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("איך זה מחושב?", color = HColors.Gold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "סגירה" else "פתיחה", tint = HColors.Gold)
            }
            if (open) {
                val bmrLine = if (s.bmr.method == BmrMethod.KATCH)
                    "שריפה טבעית: נוסחת קאץ'-מקארדל, 370 ועוד 21.6 כפול מסת הגוף הרזה. משמשת כשאחוז השומן ידוע."
                else
                    "שריפה טבעית: נוסחת מיפלין-סנט ז'אור. 10 כפול משקל, ועוד 6.25 כפול גובה, פחות 5 כפול גיל, ועוד 5 לגברים (פחות 161 לנשים). הנוסחה המדויקת ביותר במחקרים, גם במשקל עודף, בטעות של כעשרה אחוזים."
                HowLine(bmrLine)
                HowLine("פעילות יומיומית: חלק מהשריפה הטבעית לפי רמת הפעילות שבחרת, מ-25% (יושבני) עד 70% (פעיל).")
                HowLine(
                    "כשהשעון מחובר: ביום שיש בו צעדים, הפעילות היומיומית מחושבת מהצעדים (אורך צעד לפי הגובה, כפול המשקל), ועוד 10% מהשריפה הטבעית לעמידה ותנועה. צעדים בתוך הליכה או ריצה שנרשמו לא נספרים פעמיים." +
                        if (s.modelFromSteps) " גם ההוצאה היומית ליעד נשענת עכשיו על ממוצע הצעדים של השבועיים האחרונים." else ""
                )
                HowLine("הליכה או ריצה עם דופק מהשעון: קלוריות לפי הדופק הממוצע, המשקל והגיל (משוואת קייטל), פחות המנוחה.")
                HowLine("אימונים: לפי טבלת המאמצים העולמית של פעילות גופנית (מהדורת 2024). קלוריות לדקה = (מאמץ פחות 1) כפול 3.5 כפול משקל, חלקי 200. מחסירים 1 כי המנוחה כבר נספרה. במכון, המאמץ נקבע לפי קצב הסטים: 3.5 באימון רגיל, עד 6 באימון מעגלי.")
                HowLine("עיכול: כעשרה אחוזים ממה שאכלת.")
                HowLine("יעד בירידה: הוצאה יומית פחות גירעון. גירעון = קצב הירידה כפול משקל הגוף כפול 7,700 (קלוריות בקילו), חלקי 7. לא יותר מ-1,000 ביום, ולא מתחת ל-1,500 לגבר או 1,200 לאישה, וגם לא מתחת לשריפה הטבעית.")
                HowLine("יעד בשמירה: ההוצאה היומית עצמה. יעד בעלייה: הוצאה יומית ועוד עודף לפי קצב של 0.25% או 0.5% ממשקל הגוף בשבוע, בין 150 ל-500 ביום.")
                HowLine("חלבון לפי הכיוון: בירידה 2.0 גרם לקילו, בשמירה 1.8, בעלייה 1.7 (במשקל עודף, לפי המשקל שבו מדד מסת הגוף הוא 27). כשאחוז השומן ידוע: 2.4, 2.2 או 2.0 גרם לקילו מסת גוף רזה. שומן: 25% מהקלוריות בירידה ובעלייה, 30% בשמירה. פחמימות: השאר.")
                HowLine("לפי הנתונים בפועל: ממוצע האכילה בימים הרשומים, פחות שינוי המשקל (קו מגמה על כל השקילות) כפול 7,700. ככה התיקון לנוסחה מגיע מהגוף שלך, לא מטבלה. צריך לפחות שבועיים.")
                HowLine("כל המספרים הם הערכה. טעות של 10% היא דבר רגיל. לכן המשקל בפועל הוא השופט האחרון.")
            }
        }
    }
}

@Composable
private fun HowLine(text: String) {
    Text(text, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
}
