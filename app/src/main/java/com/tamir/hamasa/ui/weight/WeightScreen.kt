package com.tamir.hamasa.ui.weight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.WeightEntity
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.game.BodyStage
import com.tamir.hamasa.ui.common.CharacterFigure
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.ui.progress.ProgressNav
import com.tamir.hamasa.domain.progress.WeightForecast
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class WeightViewModel : ViewModel() {
    val profile: StateFlow<Profile?> = AppGraph.prefs.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val weights: StateFlow<List<WeightEntity>> = AppGraph.workouts.allWeights.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _line = MutableStateFlow<String?>(null)
    val line: StateFlow<String?> = _line

    fun log(kg: Float) {
        viewModelScope.launch {
            val previous = weights.value.lastOrNull()?.kg
            val p = profile.value ?: runCatching { AppGraph.prefs.current() }.getOrNull()
            val direction = if (p != null) com.tamir.hamasa.data.repo.EnergyData.directionOf(p, kg) else com.tamir.hamasa.domain.energy.GoalDirection.CUT
            AppGraph.workouts.logWeight(kg)
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            _line.value = weightLine(previous, kg, direction)
        }
    }

    private fun weightLine(previous: Float?, now: Float, direction: com.tamir.hamasa.domain.energy.GoalDirection): String {
        val gain = direction == com.tamir.hamasa.domain.energy.GoalDirection.GAIN
        val maintain = direction == com.tamir.hamasa.domain.energy.GoalDirection.MAINTAIN
        if (previous == null) return when {
            gain -> "נקודת ההתחלה נרשמה. מכאן רק למעלה. במשקל, וגם ברוח."
            maintain -> "נקודת ההתחלה נרשמה. מכאן שומרים. על המשקל, לא על הסוד."
            else -> "נקודת ההתחלה נרשמה. מכאן רק למטה. במשקל, לא ברוח."
        }
        val diff = now - previous
        if (gain) return when {
            abs(diff) < 0.05f -> "אותו משקל. יציבות היא גם תוצאה. נרשם."
            diff > 0f -> "עלה ${fmt(diff)} ק\"ג. הידד. בדיוק לשם הולכים."
            else -> "ירד ${fmt(abs(diff))} ק\"ג. מדוע. אין טענות, יש ארוחה הבאה."
        }
        if (maintain) return when {
            abs(diff) < 0.05f -> "אותו משקל. בשמירה זה בדיוק התפקיד. נרשם."
            abs(diff) < 1f -> "זז ${fmt(abs(diff))} ק\"ג. בשמירה זה רעש של יום. נרשם."
            diff > 0f -> "עלה ${fmt(diff)} ק\"ג. בשמירה זה כבר שווה מבט. רק מבט."
            else -> "ירד ${fmt(abs(diff))} ק\"ג. בשמירה זה כבר שווה מבט. רק מבט."
        }
        // half the time a line from the larger Voice weigh-in pools, so the reaction does not repeat
        if (kotlin.random.Random.nextBoolean()) return com.tamir.hamasa.domain.game.Voice.weighIn(now, diff)
        return when {
            abs(diff) < 0.05f -> "אותו משקל. יציבות היא גם תוצאה. נרשם."
            diff < 0f -> com.tamir.hamasa.domain.game.CharacterNames.pick("ירד ${fmt(abs(diff))} ק\"ג. הידד. לא מופתע. רק מרוצה.", "ירד ${fmt(abs(diff))} ק\"ג. הידד. לא מופתעת. רק מרוצה.")
            else -> "עלה ${fmt(diff)} ק\"ג. מדוע. אין טענות, יש מחר."
        }
    }
}

private fun fmt(kg: Float): String {
    val r = (kg * 10).roundToInt() / 10f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}

@Composable
fun WeightScreen(onOpenProgress: () -> Unit = {}) {
    val vm: WeightViewModel = viewModel()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val line by vm.line.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    val p = profile ?: return
    val current = weights.lastOrNull()?.kg ?: p.startWeightKg
    val blend = BodyStage.forProfile(current, p)
    val direction = com.tamir.hamasa.data.repo.EnergyData.directionOf(p, current)
    val gainGoal = direction == com.tamir.hamasa.domain.energy.GoalDirection.GAIN
    val toGo = abs(current - p.goalWeightKg)
    val toGoText = if (toGo <= 0.5f) "ביעד" else "נשארו ${fmt(toGo)} ק\"ג"

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("משקל", style = MaterialTheme.typography.headlineMedium, color = HColors.Gold)
        Spacer(Modifier.height(8.dp))
        Text("${fmt(current)} ק\"ג", color = HColors.Parchment, fontSize = 56.sp, fontWeight = FontWeight.Black)
        Text("יעד ${fmt(p.goalWeightKg)} ק\"ג · $toGoText", color = HColors.ParchmentDim, fontSize = 14.sp)

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { v -> input = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                label = { Text("משקל היום") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            val kg = input.replace(',', '.').toFloatOrNull()
            Button(
                onClick = { if (kg != null) { vm.log(kg); input = "" } },
                enabled = kg != null && kg > 30f && kg < 300f,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("רישום", fontWeight = FontWeight.Black) }
        }

        val shown = line
        if (shown != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(HColors.Parchment)
                    .border(3.dp, HColors.Ink, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) { Text(shown, color = HColors.Ink, fontWeight = FontWeight.SemiBold) }
        }

        Spacer(Modifier.height(20.dp))
        // the last two weigh-ins side by side, with the difference between them
        LastTwoWeighIns(weights, gainGoal)
        Spacer(Modifier.height(16.dp))
        Text("המסלול", color = HColors.ParchmentDim, fontSize = 14.sp, modifier = Modifier.align(Alignment.Start))
        Text("לחיצה על נקודה מראה את השקילה.", color = HColors.ParchmentDim, fontSize = 11.sp, modifier = Modifier.align(Alignment.Start))
        WeightChart(weights = weights, goal = p.goalWeightKg, modifier = Modifier.fillMaxWidth().height(220.dp))

        // goal forecast from the trend of the last 28 days (domain/progress/WeightForecast)
        Spacer(Modifier.height(12.dp))
        val forecast = remember(weights, p.goalWeightKg, gainGoal) {
            WeightForecast.compute(weights.map { it.dateEpochDay to it.kg }, p.goalWeightKg, com.tamir.hamasa.domain.Days.today(), gainGoal)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HColors.Ink)
                .border(2.dp, HColors.GoldDeep, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("תחזית", color = HColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(WeightForecast.headline(forecast, p.goalWeightKg), color = HColors.Parchment, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            WeightForecast.rateLine(forecast)?.let { Text(it, color = HColors.ParchmentDim, fontSize = 13.sp) }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("שלב הגוף", color = HColors.ParchmentDim, fontSize = 14.sp)
                // one name per anchor (BodyStage.ANCHORS = 10), heaviest..fit
                val stageNames = if (com.tamir.hamasa.domain.game.CharacterNames.isFemale(p.characterVariant)) listOf("הכבדה ביותר", "כבדה", "עודף משקל", "שמנמנה", "ממוצעת", "מתהדקת", "רזה למחצה", "רזה", "אתלטית", "חטובה") else listOf("הכבד ביותר", "כבד", "עודף משקל", "שמנמן", "ממוצע", "מתהדק", "רזה למחצה", "רזה", "אתלטי", "חטוב")
                val stageText = stageNames.getOrElse(blend.anchorA) { "" }
                Text(stageText, color = HColors.Parchment, fontSize = 22.sp, fontWeight = FontWeight.Black)
                val next = if (blend.anchorB != blend.anchorA) "${(blend.t * 100).roundToInt()}% לשלב הבא (${stageNames.getOrElse(blend.anchorB) { "" }})" else if (blend.beyondGoal) (if (com.tamir.hamasa.domain.game.CharacterNames.isFemale(p.characterVariant)) "מעבר ליעד. האישה והאגדה." else "מעבר ליעד. האיש והאגדה.") else "השלב האחרון"
                Text(next, color = HColors.ParchmentDim, fontSize = 13.sp)
                Text("הגוף של ${com.tamir.hamasa.domain.game.CharacterNames.he(p.characterVariant)} נקבע רק לפי המשקל.", color = HColors.ParchmentDim, fontSize = 11.sp)
            }
            Box(
                Modifier
                    .width(120.dp)
                    .height(190.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HColors.SlateLight)
                    .border(2.dp, HColors.GoldDeep, RoundedCornerShape(16.dp))
            ) {
                CharacterFigure(blend = blend, variant = p.characterVariant, modifier = Modifier.fillMaxSize().padding(6.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpenProgress,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
        ) { Text("תמונות ומידות", fontWeight = FontWeight.Black) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { ProgressNav.open(ProgressNav.BEFORE_AFTER) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, HColors.GoldDeep)
            ) { Text("לפני ואחרי", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { ProgressNav.open(ProgressNav.monthly(-1)) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, HColors.GoldDeep)
            ) { Text("סיכום חודש", color = HColors.Gold, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Plain Canvas line chart: all logged weights, with a dashed-looking goal line. */
@Composable
private fun WeightChart(weights: List<WeightEntity>, goal: Float, modifier: Modifier = Modifier) {
    val lineColor = HColors.Gold
    val goalColor = HColors.Moss
    val gridColor = HColors.SlateLight
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(HColors.Ink)
            .border(2.dp, HColors.SlateLight, RoundedCornerShape(16.dp))
    ) {
        if (weights.size < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (weights.isEmpty()) "עדיין אין רישומים" else "רישום אחד. מחר יהיה קו.", color = HColors.ParchmentDim)
            }
        } else {
            // tap: the nearest point by x; its weight and date are drawn above it (tap again elsewhere to move)
            var selected by remember(weights) { mutableStateOf(weights.size - 1) }
            val labelPaint = remember {
                android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(0x1B, 0x1E, 0x25)
                    textSize = 34f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            }
            val labelBg = HColors.Gold
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .testTag("weight_chart")
                    .pointerInput(weights) {
                        detectTapGestures { tap ->
                            val minDay = weights.first().dateEpochDay
                            val maxDay = weights.last().dateEpochDay
                            val daySpan = (maxDay - minDay).coerceAtLeast(1L).toFloat()
                            var best = 0
                            var bestD = Float.MAX_VALUE
                            weights.forEachIndexed { i, wgt ->
                                val px = (wgt.dateEpochDay - minDay).toFloat() / daySpan * size.width
                                val d = abs(px - tap.x)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            selected = best
                        }
                    }
            ) {
            val minDay = weights.first().dateEpochDay
            val maxDay = weights.last().dateEpochDay
            val daySpan = (maxDay - minDay).coerceAtLeast(1L).toFloat()
            var lo = minOf(weights.minOf { it.kg }, goal)
            var hi = maxOf(weights.maxOf { it.kg }, goal)
            if (hi - lo < 2f) { lo -= 1f; hi += 1f }
            val pad = (hi - lo) * 0.08f
            lo -= pad; hi += pad
            val w = size.width
            val h = size.height
            fun x(day: Long) = (day - minDay).toFloat() / daySpan * w
            fun y(kg: Float) = h - (kg - lo) / (hi - lo) * h

            for (i in 0..4) {
                val yy = h * i / 4f
                drawLine(gridColor, Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
            }
            val gy = y(goal)
            var gx = 0f
            while (gx < w) {
                drawLine(goalColor, Offset(gx, gy), Offset((gx + 10f).coerceAtMost(w), gy), strokeWidth = 3f)
                gx += 18f
            }
            val path = Path()
            weights.forEachIndexed { i, wgt ->
                val px = x(wgt.dateEpochDay)
                val py = y(wgt.kg)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
            weights.forEach { wgt ->
                drawCircle(lineColor, radius = 6f, center = Offset(x(wgt.dateEpochDay), y(wgt.kg)))
            }
            // the selected point: a bigger dot and a gold label "92.4 · 03/11" (kg, then the date)
            weights.getOrNull(selected)?.let { sel ->
                val cx = x(sel.dateEpochDay)
                val cy = y(sel.kg)
                drawCircle(HColors.Parchment, radius = 11f, center = Offset(cx, cy))
                drawCircle(lineColor, radius = 7f, center = Offset(cx, cy))
                val d = java.time.LocalDate.ofEpochDay(sel.dateEpochDay)
                // digits only (the native canvas does not reorder mixed Hebrew / digits): "103 · 19/10"
                val label = fmt(sel.kg) + "  ·  " + String.format(java.util.Locale.US, "%02d/%02d", d.dayOfMonth, d.monthValue)
                val tw = labelPaint.measureText(label) + 28f
                val th = 48f
                val lx = (cx - tw / 2f).coerceIn(0f, (w - tw).coerceAtLeast(0f))
                val above = cy - th - 16f
                val ly = if (above >= 0f) above else cy + 16f
                drawRoundRect(labelBg, topLeft = Offset(lx, ly), size = androidx.compose.ui.geometry.Size(tw, th), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f))
                drawContext.canvas.nativeCanvas.drawText(label, lx + tw / 2f, ly + th / 2f + 12f, labelPaint)
            }
            }
        }
    }
}

/** Two cards: the latest weigh-in and the one before it, and the difference between them, colored by his goal. */
@Composable
private fun LastTwoWeighIns(weights: List<WeightEntity>, gainGoal: Boolean) {
    if (weights.isEmpty()) return
    val last = weights.last()
    val prev = weights.getOrNull(weights.size - 2)
    fun dateOf(e: WeightEntity): String {
        val d = java.time.LocalDate.ofEpochDay(e.dateEpochDay)
        return String.format(java.util.Locale.US, "%02d/%02d", d.dayOfMonth, d.monthValue)
    }
    val diff = if (prev != null) last.kg - prev.kg else null
    val good = diff != null && abs(diff) >= 0.05f && ((diff < 0f) != gainGoal)
    val diffColor = when {
        diff == null || abs(diff) < 0.05f -> HColors.ParchmentDim
        good -> HColors.Moss
        else -> HColors.Danger
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WeighInCard("השקילה האחרונה", "${fmt(last.kg)} ק\"ג", dateOf(last), HColors.Gold, Modifier.weight(1f).testTag("weigh_last"))
            if (prev != null) WeighInCard("השקילה שלפניה", "${fmt(prev.kg)} ק\"ג", dateOf(prev), HColors.ParchmentDim, Modifier.weight(1f).testTag("weigh_prev"))
            else WeighInCard("השקילה שלפניה", "—", "עוד אין", HColors.ParchmentDim, Modifier.weight(1f).testTag("weigh_prev"))
        }
        if (diff != null) {
            Spacer(Modifier.height(6.dp))
            val sign = if (diff > 0f) "+" else if (diff < 0f) "-" else ""
            val word = when {
                abs(diff) < 0.05f -> "אותו משקל בין שתי השקילות."
                diff < 0f -> "ירידה של ${fmt(abs(diff))} ק\"ג בין שתי השקילות."
                else -> "עלייה של ${fmt(diff)} ק\"ג בין שתי השקילות."
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(word, color = HColors.Parchment, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("\u200E$sign${fmt(abs(diff))}", color = diffColor, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.testTag("weigh_diff"))
            }
        }
    }
}

@Composable
private fun WeighInCard(title: String, big: String, date: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(HColors.Ink)
            .border(2.dp, accent, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(title, color = HColors.ParchmentDim, fontSize = 12.sp)
        Text(big, color = HColors.Parchment, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(date, color = accent, fontSize = 12.sp)
    }
}
