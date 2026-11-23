package com.tamir.hamasa.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.coach.Difficulty
import com.tamir.hamasa.domain.energy.ActivityLevel
import com.tamir.hamasa.domain.energy.EnergyModel
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.domain.energy.Sex
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.BodyStage
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.ui.common.CharacterStageImage
import com.tamir.hamasa.ui.common.focusLabelHe
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Steps of the first-run flow: 0 welcome, 1 address (אתה / את) + character, 2 personal, 3 weight, 4 "where are you now", 5 goals, 6 summary. */
private const val STEP_COUNT = 7
private const val STEP_WELCOME = 0
private const val STEP_CHARACTER = 1
private const val STEP_PERSONAL = 2
private const val STEP_WEIGHT = 3
private const val STEP_STAGE = 4
private const val STEP_GOALS = 5
private const val STEP_SUMMARY = 6

/** Direction values as in Prefs.goalDirection: 0 cut, 1 maintain, 2 gain. */
private const val DIR_CUT = 0
private const val DIR_MAINTAIN = 1
private const val DIR_GAIN = 2

/** Average net kcal of one workout, only for the onboarding preview (the real goal comes from EnergyData). */
private const val PREVIEW_KCAL_PER_WORKOUT = 300f

private val CardShape = RoundedCornerShape(22.dp)

/** First run (AppRoot route ONBOARDING). The signature is fixed; the flow lives in [OnboardingFlow]. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    OnboardingFlow(startStep = STEP_WELCOME, onDone = onDone)
}

/**
 * The multi-step onboarding. [startStep] exists for the screenshot test (steps 1 and 4); the app always starts at 0.
 * Everything is saved at the end in one Prefs update (+ the first weigh-in); CloudSync carries the new fields.
 * Body stage calibration (THE IRON RULE stays: the body is a function of the weight only): the stage picked in
 * step 4 is pinned at today's weight, stage 10 at the goal weight (BodyStage.calibrated).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingFlow(startStep: Int = STEP_WELCOME, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(startStep.coerceIn(0, STEP_COUNT - 1)) }
    // welcome / restore
    var restoreOpen by rememberSaveable { mutableStateOf(false) }
    var codeText by rememberSaveable { mutableStateOf("") }
    var restoring by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    // address (Prefs.userGender, "" = not chosen yet) + character
    var userGender by rememberSaveable { mutableStateOf("") }
    var variant by rememberSaveable { mutableStateOf(CharacterNames.MALE) }
    // personal
    var nameText by rememberSaveable { mutableStateOf("") }
    var ageText by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf("") }
    var heightText by rememberSaveable { mutableStateOf("") }
    // weight
    var weightText by rememberSaveable { mutableStateOf("") }
    var goalText by rememberSaveable { mutableStateOf("") }
    var directionManual by rememberSaveable { mutableIntStateOf(-1) }
    // stage
    var stageIndex by rememberSaveable { mutableIntStateOf(-1) }
    // goals
    var daysPerWeek by rememberSaveable { mutableIntStateOf(3) }
    var focusName by rememberSaveable { mutableStateOf("") }
    var kcalAuto by rememberSaveable { mutableStateOf(true) }
    var kcalText by rememberSaveable { mutableStateOf("") }
    var proteinAuto by rememberSaveable { mutableStateOf(true) }
    var proteinText by rememberSaveable { mutableStateOf("") }
    var difficultyName by rememberSaveable { mutableStateOf(Difficulty.NORMAL.name) }
    var hasWatch by rememberSaveable { mutableStateOf(false) }

    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> onDone() }

    fun finish() {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
    }

    // ---- parsed values
    val age = ageText.toIntOrNull()
    val heightCm = heightText.toIntOrNull()
    val weightKg = parseKg(weightText)
    val goalKg = parseKg(goalText)
    val autoDirection = if (weightKg != null && goalKg != null) suggestDirection(weightKg, goalKg) else DIR_CUT
    val direction = if (directionManual in DIR_CUT..DIR_GAIN) directionManual else autoDirection
    val addressSex = if (userGender.isNotEmpty()) userGender else Addr.current
    val focus = runCatching { Focus.valueOf(focusName) }.getOrNull() ?: defaultFocus(direction)
    val kcalEstimate = estimateKcal(weightKg ?: 80f, heightCm ?: 175, age ?: 30, sex, daysPerWeek, direction)
    val kcalManual = kcalText.toIntOrNull()
    val kcalUsed = if (kcalAuto) kcalEstimate else (kcalManual ?: kcalEstimate)
    val proteinEstimate = estimateProtein(kcalUsed, weightKg ?: 80f, heightCm ?: 175, direction)
    val proteinManual = proteinText.toIntOrNull()

    val personalValid = nameText.isNotBlank() && age != null && age in 14..100 && heightCm != null && heightCm in 120..230 && sex.isNotEmpty()
    val weightValid = weightKg != null && weightKg in 30f..300f && goalKg != null && goalKg in 30f..300f
    val goalsValid = daysPerWeek in 1..7 &&
        (kcalAuto || (kcalManual != null && kcalManual in 800..6000)) &&
        (proteinAuto || (proteinManual != null && proteinManual in 30..400))
    val canGoNext = when (step) {
        STEP_CHARACTER -> userGender.isNotEmpty()
        STEP_PERSONAL -> personalValid
        STEP_WEIGHT -> weightValid
        STEP_STAGE -> stageIndex in 0 until BodyStage.ANCHORS
        STEP_GOALS -> goalsValid
        else -> true
    }

    fun save() {
        if (saving) return
        val w = weightKg ?: return
        val g = goalKg ?: return
        saving = true
        val dirToStore = if (directionManual in DIR_CUT..DIR_GAIN) directionManual else -1
        val kcalGoal = kcalUsed.coerceIn(800, 6000)
        val protein = if (proteinAuto) 0 else (proteinManual ?: 0)
        val stage = (stageIndex.coerceIn(0, BodyStage.ANCHORS - 1) + 1).toFloat()
        scope.launch {
            AppGraph.prefs.update {
                it.copy(
                    onboarded = true,
                    playerName = nameText.trim().take(24),
                    characterVariant = CharacterNames.normalize(variant),
                    userGender = if (userGender == Addr.FEMALE) Addr.FEMALE else Addr.MALE,
                    age = age ?: it.age,
                    sex = sex,
                    heightCm = heightCm ?: it.heightCm,
                    startWeightKg = w,
                    goalWeightKg = g,
                    goalDirection = dirToStore,
                    stageAnchorStage = stage,
                    stageAnchorWeight = w,
                    trainingDaysPerWeek = daysPerWeek,
                    focus = focus,
                    kcalAuto = kcalAuto,
                    kcalGoal = kcalGoal,
                    proteinGoal = protein,
                    coachDifficulty = difficultyName,
                    hasWatch = hasWatch
                )
            }
            runCatching { AppGraph.workouts.logWeight(w) }
            if (kcalAuto) runCatching { com.tamir.hamasa.data.repo.EnergyData.syncAutoGoal() }
            runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
            finish()
        }
    }

    fun restore() {
        val code = codeText.trim()
        if (code.isEmpty() || restoring) return
        restoring = true
        restoreError = null
        scope.launch {
            val result = try {
                com.tamir.hamasa.data.cloud.Account.restoreFromCode(code)
            } catch (e: Exception) {
                com.tamir.hamasa.data.cloud.Account.RestoreResult.NETWORK
            }
            if (result == com.tamir.hamasa.data.cloud.Account.RestoreResult.OK) {
                AppGraph.prefs.update { it.copy(onboarded = true) }
                restoring = false
                finish()
            } else {
                restoring = false
                restoreError = restoreErrorHe(result)
            }
        }
    }

    /** The address and the hero apply at once (every following text uses them) and are stored right away, so the
     *  AppGraph Prefs sync does not switch them back before the final save. */
    fun applyChoice() {
        val g = if (userGender == Addr.FEMALE) Addr.FEMALE else Addr.MALE
        val v = CharacterNames.normalize(variant)
        val chosen = userGender.isNotEmpty()
        if (chosen) Addr.set(g)
        CharacterNames.set(v)
        scope.launch {
            AppGraph.prefs.update {
                if (chosen) it.copy(userGender = g, characterVariant = v) else it.copy(characterVariant = v)
            }
        }
    }

    BackHandler(enabled = step > STEP_WELCOME && !saving) { step -= 1 }
    // Step 0: back closes the restore panel, otherwise the app. Popping back to Home would only push
    // onboarding again (Home sends a not-onboarded player here), an endless loop. Ignored while restoring.
    val backContext = LocalContext.current
    BackHandler(enabled = step == STEP_WELCOME) {
        if (!restoring) {
            if (restoreOpen) {
                restoreOpen = false
            } else {
                findActivity(backContext)?.finish()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(HColors.Slate)) {
        // ---- header: title + progress dots
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("המסע הגדול", color = HColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            ProgressDots(count = STEP_COUNT, current = step)
        }

        // ---- step body
        Crossfade(targetState = step, animationSpec = tween(250), label = "onboarding_step", modifier = Modifier.weight(1f)) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (s) {
                    STEP_WELCOME -> WelcomeStep(
                        restoreOpen = restoreOpen,
                        code = codeText,
                        restoring = restoring,
                        error = restoreError,
                        onToggleRestore = { restoreOpen = !restoreOpen; restoreError = null },
                        onCodeChange = { codeText = it.take(40) },
                        onRestore = { restore() }
                    )
                    STEP_CHARACTER -> CharacterStep(
                        userGender = userGender,
                        onGender = { g ->
                            userGender = g
                            if (sex.isEmpty()) sex = g
                            applyChoice()
                        },
                        variant = variant,
                        onPick = { v ->
                            variant = v
                            if (sex.isEmpty()) sex = v
                            applyChoice()
                        }
                    )
                    STEP_PERSONAL -> PersonalStep(
                        name = nameText, onName = { nameText = it.take(24) },
                        age = ageText, onAge = { ageText = it.filter { c -> c.isDigit() }.take(3) },
                        height = heightText, onHeight = { heightText = it.filter { c -> c.isDigit() }.take(3) },
                        sex = sex, onSex = { sex = it }
                    )
                    STEP_WEIGHT -> WeightStep(
                        weight = weightText, onWeight = { weightText = kgFilter(it) },
                        goal = goalText, onGoal = { goalText = kgFilter(it) },
                        weightKg = weightKg, goalKg = goalKg,
                        direction = direction, autoDirection = autoDirection, manual = directionManual in DIR_CUT..DIR_GAIN,
                        onDirection = { d ->
                            directionManual = d
                            if (d == DIR_MAINTAIN && weightText.isNotBlank()) goalText = weightText
                        }
                    )
                    STEP_STAGE -> StageStep(
                        variant = variant, selected = stageIndex, addressSex = addressSex, goalKg = goalKg,
                        onPick = { stageIndex = it }
                    )
                    STEP_GOALS -> GoalsStep(
                        days = daysPerWeek, onDays = { daysPerWeek = it },
                        focus = focus, onFocus = { focusName = it.name },
                        kcalAuto = kcalAuto, onKcalAuto = { kcalAuto = it }, kcalText = kcalText, onKcalText = { kcalText = it.filter { c -> c.isDigit() }.take(4) },
                        kcalEstimate = kcalEstimate,
                        proteinAuto = proteinAuto, onProteinAuto = { proteinAuto = it }, proteinText = proteinText,
                        onProteinText = { proteinText = it.filter { c -> c.isDigit() }.take(3) },
                        proteinEstimate = proteinEstimate,
                        difficultyName = difficultyName, onDifficulty = { difficultyName = it },
                        hasWatch = hasWatch, onWatch = { hasWatch = it }
                    )
                    else -> SummaryStep(
                        variant = variant,
                        stageIndex = stageIndex,
                        name = nameText.trim(),
                        age = age, heightCm = heightCm, sex = sex,
                        weightKg = weightKg, goalKg = goalKg, direction = direction,
                        days = daysPerWeek, focus = focus,
                        kcalAuto = kcalAuto, kcal = kcalUsed,
                        proteinAuto = proteinAuto, protein = if (proteinAuto) proteinEstimate else (proteinManual ?: proteinEstimate),
                        difficultyName = difficultyName, hasWatch = hasWatch
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // ---- bottom bar: back / next
        Row(
            Modifier.fillMaxWidth().background(HColors.Ink).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > STEP_WELCOME) {
                TextButton(onClick = { if (!saving) step -= 1 }) { Text("חזרה", color = HColors.ParchmentDim, fontSize = 18.sp) }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            val label = when (step) {
                STEP_WELCOME -> "מתחילים"
                STEP_SUMMARY -> "יוצאים לדרך"
                else -> "הלאה"
            }
            Button(
                onClick = {
                    if (step == STEP_WEIGHT && focusName.isEmpty()) focusName = defaultFocus(direction).name
                    if (step < STEP_SUMMARY) step += 1 else save()
                },
                enabled = canGoNext && !saving && !restoring,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
            ) {
                Text(label, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
        }
    }
}

// ============================================================== steps

@Composable
private fun WelcomeStep(
    restoreOpen: Boolean,
    code: String,
    restoring: Boolean,
    error: String?,
    onToggleRestore: () -> Unit,
    onCodeChange: (String) -> Unit,
    onRestore: () -> Unit
) {
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.width(200.dp).height(290.dp).clip(CardShape).background(HColors.SlateLight).border(3.dp, HColors.GoldDeep, CardShape),
        contentAlignment = Alignment.BottomCenter
    ) {
        CharacterStageImage(variant = CharacterNames.MALE, index = 4, modifier = Modifier.fillMaxSize().padding(10.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text("ברוכים הבאים למסע", color = HColors.Parchment, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    SpeechCard("מסע אחד. גוף אחד. מפלצת אחת שמנסה לעצור. כמה שאלות קצרות ויוצאים לדרך. שתי דקות. אולי שלוש.")
    Spacer(Modifier.height(20.dp))
    TextButton(onClick = onToggleRestore) {
        Text("יש לי קוד שחזור", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
    if (restoreOpen) {
        InkCard {
            Text("קוד השחזור מהמכשיר הקודם", color = HColors.Parchment, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                label = { Text("קוד שחזור") },
                singleLine = true,
                textStyle = TextStyle(fontSize = 20.sp, color = HColors.Parchment),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRestore,
                enabled = code.isNotBlank() && !restoring,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (restoring) "משחזר..." else "שחזור", fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = HColors.Ember, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun CharacterStep(userGender: String, onGender: (String) -> Unit, variant: String, onPick: (String) -> Unit) {
    StepTitle("איך לפנות?")
    Text("כל הכתוב באפליקציה יתאים לבחירה.", color = HColors.ParchmentDim, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AddressCard(
            g = Addr.MALE, word = "אתה", line = "לשון זכר", selected = userGender == Addr.MALE,
            modifier = Modifier.weight(1f), onPick = onGender
        )
        AddressCard(
            g = Addr.FEMALE, word = "את", line = "לשון נקבה", selected = userGender == Addr.FEMALE,
            modifier = Modifier.weight(1f), onPick = onGender
        )
    }
    Spacer(Modifier.height(24.dp))
    StepTitle("מי יוצא איתך לדרך?")
    SpeechCard("הגוף של הדמות משתנה רק לפי המשקל. לא לפי רמה. לא לפי נקודות. רק המשקל. זה החוק.")
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CharacterCard(
            v = CharacterNames.MALE, selected = variant == CharacterNames.MALE,
            line = "המקורי. דרמטי. עייף מהמפלצת עוד לפני שהתחיל.",
            modifier = Modifier.weight(1f), onPick = onPick
        )
        CharacterCard(
            v = CharacterNames.FEMALE, selected = variant == CharacterNames.FEMALE,
            line = "אחותו. אותה מפלצת. פחות תלונות. כנראה.",
            modifier = Modifier.weight(1f), onPick = onPick
        )
    }
}

/** One of the two address options (Prefs.userGender), same card look as [CharacterCard] without the art. */
@Composable
private fun AddressCard(g: String, word: String, line: String, selected: Boolean, modifier: Modifier, onPick: (String) -> Unit) {
    Column(
        modifier
            .clip(CardShape)
            .background(if (selected) HColors.SlateLight else HColors.Ink)
            .border(if (selected) 4.dp else 2.dp, if (selected) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.5f), CardShape)
            .clickable { onPick(g) }
            .padding(vertical = 18.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(word, color = if (selected) HColors.Gold else HColors.Parchment, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(line, color = HColors.ParchmentDim, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CharacterCard(v: String, selected: Boolean, line: String, modifier: Modifier, onPick: (String) -> Unit) {
    Column(
        modifier
            .clip(CardShape)
            .background(if (selected) HColors.SlateLight else HColors.Ink)
            .border(if (selected) 4.dp else 2.dp, if (selected) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.5f), CardShape)
            .clickable { onPick(v) }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CharacterStageImage(variant = v, index = 4, modifier = Modifier.fillMaxWidth().height(230.dp))
        Spacer(Modifier.height(8.dp))
        Text(CharacterNames.he(v), color = if (selected) HColors.Gold else HColors.Parchment, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(line, color = HColors.ParchmentDim, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonalStep(
    name: String, onName: (String) -> Unit,
    age: String, onAge: (String) -> Unit,
    height: String, onHeight: (String) -> Unit,
    sex: String, onSex: (String) -> Unit
) {
    StepTitle("כמה פרטים קטנים")
    SpeechCard("גיל, מין וגובה נכנסים לחישוב האנרגיה. אף אחד אחר לא רואה אותם. גם לא אני. טוב, קצת.")
    Spacer(Modifier.height(16.dp))
    BigField(label = "שם", value = name, onChange = onName, keyboardType = KeyboardType.Text)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigField(label = "גיל", value = age, onChange = onAge, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
        BigField(label = "גובה (ס\"מ)", value = height, onChange = onHeight, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(14.dp))
    SectionLabel("מין (לחישוב האנרגיה)")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("זכר", sex == "M") { onSex("M") }
        Pill("נקבה", sex == "F") { onSex("F") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeightStep(
    weight: String, onWeight: (String) -> Unit,
    goal: String, onGoal: (String) -> Unit,
    weightKg: Float?, goalKg: Float?,
    direction: Int, autoDirection: Int, manual: Boolean,
    onDirection: (Int) -> Unit
) {
    StepTitle("המשקל")
    SpeechCard(com.tamir.hamasa.domain.game.CharacterNames.g("מספר אחד היום. מספר אחד ליעד. את השאר אני סוחב."))
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigField(label = "משקל היום (ק\"ג)", value = weight, onChange = onWeight, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        BigField(label = "משקל יעד (ק\"ג)", value = goal, onChange = onGoal, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(16.dp))
    SectionLabel("הכיוון")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("ירידה", direction == DIR_CUT) { onDirection(DIR_CUT) }
        Pill("שמירה", direction == DIR_MAINTAIN) { onDirection(DIR_MAINTAIN) }
        Pill("עלייה", direction == DIR_GAIN) { onDirection(DIR_GAIN) }
    }
    Spacer(Modifier.height(8.dp))
    if (weightKg != null && goalKg != null) {
        val diff = goalKg - weightKg
        val diffLine = when {
            abs(diff) <= BodyStage.MAINTAIN_BAND_KG -> "היעד הוא המשקל של היום. שומרים על מה שיש."
            diff < 0f -> "עוד ${fmtKg(-diff)} ק\"ג לרדת."
            else -> "עוד ${fmtKg(diff)} ק\"ג לעלות. בשריר, אם אפשר."
        }
        Text(diffLine, color = HColors.Parchment, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (!manual) Text("הכיוון הוצע לפי שני המשקלים. אפשר לשנות.", color = HColors.ParchmentDim, fontSize = 14.sp)
        else if (direction != autoDirection && direction != DIR_MAINTAIN) {
            Text("הכיוון לא תואם את שני המשקלים. גם זה מותר. הג'ים ברו יתאים את הקלוריות לכיוון.", color = HColors.Ember, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StageStep(variant: String, selected: Int, addressSex: String, goalKg: Float?, onPick: (Int) -> Unit) {
    StepTitle(if (addressSex == Addr.FEMALE) "איפה את עכשיו?" else "איפה אתה עכשיו?")
    SpeechCard(CharacterNames.g("לוחצים על הדמות שהכי דומה לך היום. בכנות. אף אחד לא מסתכל. חוץ ממני."))
    Spacer(Modifier.height(14.dp))
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(BodyStage.ANCHORS) { i ->
            StageCard(variant = variant, index = i, selected = selected == i, onPick = onPick)
        }
    }
    Spacer(Modifier.height(14.dp))
    if (selected in 0 until BodyStage.ANCHORS) {
        InkCard {
            Text("שלב ${selected + 1} מתוך ${BodyStage.ANCHORS}", color = HColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Black)
            val goalLine = if (goalKg != null) "שלב ${BodyStage.ANCHORS} מחכה ביעד: ${fmtKg(goalKg)} ק\"ג." else ""
            Text("מכאן והלאה רק המשקל מזיז את הדמות. $goalLine", color = HColors.Parchment, fontSize = 16.sp)
        }
    } else {
        Text("גוללים הצידה. עשרה שלבים. אחד מהם הוא היום.", color = HColors.ParchmentDim, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StageCard(variant: String, index: Int, selected: Boolean, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(124.dp)
            .clip(shape)
            .background(if (selected) HColors.SlateLight else HColors.Ink)
            .border(if (selected) 4.dp else 2.dp, if (selected) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.45f), shape)
            .clickable { onPick(index) }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CharacterStageImage(variant = variant, index = index, modifier = Modifier.fillMaxWidth().height(190.dp))
        Spacer(Modifier.height(4.dp))
        Text("${index + 1}", color = if (selected) HColors.Gold else HColors.Parchment, fontSize = 20.sp, fontWeight = FontWeight.Black)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalsStep(
    days: Int, onDays: (Int) -> Unit,
    focus: Focus, onFocus: (Focus) -> Unit,
    kcalAuto: Boolean, onKcalAuto: (Boolean) -> Unit, kcalText: String, onKcalText: (String) -> Unit, kcalEstimate: Int,
    proteinAuto: Boolean, onProteinAuto: (Boolean) -> Unit, proteinText: String, onProteinText: (String) -> Unit, proteinEstimate: Int,
    difficultyName: String, onDifficulty: (String) -> Unit,
    hasWatch: Boolean, onWatch: (Boolean) -> Unit
) {
    StepTitle("המטרות")
    SpeechCard("ברירות מחדל טובות כבר מסומנות. אפשר פשוט להמשיך. אני לא אספר.")
    Spacer(Modifier.height(14.dp))

    SectionLabel("ימי אימון בשבוע")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (d in 1..7) Pill("$d", days == d) { onDays(d) }
    }
    Spacer(Modifier.height(14.dp))

    SectionLabel("הדגש של הג'ים ברו")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Focus.values().forEach { f -> Pill(focusLabelHe(f), focus == f) { onFocus(f) } }
    }
    Spacer(Modifier.height(14.dp))

    SectionLabel("קלוריות ביום")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("אוטומטי", kcalAuto) { onKcalAuto(true) }
        Pill("ידני", !kcalAuto) { onKcalAuto(false) }
    }
    Spacer(Modifier.height(6.dp))
    if (kcalAuto) {
        Text("בערך $kcalEstimate ביום. מתעדכן לבד לפי השקילות והאימונים.", color = HColors.ParchmentDim, fontSize = 15.sp)
    } else {
        BigField(label = "קלוריות ביום", value = kcalText, onChange = onKcalText, keyboardType = KeyboardType.Number)
    }
    Spacer(Modifier.height(14.dp))

    SectionLabel("חלבון ביום")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("אוטומטי", proteinAuto) { onProteinAuto(true) }
        Pill("ידני", !proteinAuto) { onProteinAuto(false) }
    }
    Spacer(Modifier.height(6.dp))
    if (proteinAuto) {
        Text("בערך $proteinEstimate גרם ביום.", color = HColors.ParchmentDim, fontSize = 15.sp)
    } else {
        BigField(label = "חלבון ביום (גרם)", value = proteinText, onChange = onProteinText, keyboardType = KeyboardType.Number)
    }
    Spacer(Modifier.height(14.dp))

    SectionLabel("רמת קושי")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Difficulty.values().forEach { d -> Pill(d.nameHe, difficultyName == d.name) { onDifficulty(d.name) } }
    }
    Spacer(Modifier.height(14.dp))

    SectionLabel("שעון חכם")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("יש לי", hasWatch) { onWatch(true) }
        Pill("אין לי", !hasWatch) { onWatch(false) }
    }
    if (hasWatch) {
        Spacer(Modifier.height(6.dp))
        Text("מחברים אותו אחר כך בהגדרות. צעדים, דופק ושינה נכנסים לבד.", color = HColors.ParchmentDim, fontSize = 15.sp)
    }
}

@Composable
private fun SummaryStep(
    variant: String,
    stageIndex: Int,
    name: String,
    age: Int?, heightCm: Int?, sex: String,
    weightKg: Float?, goalKg: Float?, direction: Int,
    days: Int, focus: Focus,
    kcalAuto: Boolean, kcal: Int,
    proteinAuto: Boolean, protein: Int,
    difficultyName: String, hasWatch: Boolean
) {
    StepTitle("הכל מוכן")
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HColors.Parchment)
            .border(4.dp, HColors.Ink, CardShape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(110.dp).height(170.dp).clip(RoundedCornerShape(16.dp)).background(HColors.Slate),
                contentAlignment = Alignment.BottomCenter
            ) {
                CharacterStageImage(variant = variant, index = stageIndex.coerceIn(0, BodyStage.ANCHORS - 1), modifier = Modifier.fillMaxSize().padding(6.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifEmpty { "בלי שם" }, color = HColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(Addr.t("יוצא עם ", "יוצאת עם ") + CharacterNames.he(variant), color = HColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("שלב ${stageIndex.coerceIn(0, BodyStage.ANCHORS - 1) + 1} מתוך ${BodyStage.ANCHORS}", color = HColors.GoldDeep, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        val sexHe = if (sex == "F") "נקבה" else "זכר"
        SummaryRow("פרטים", "גיל ${age ?: 0} · גובה ${heightCm ?: 0} ס\"מ · $sexHe")
        if (weightKg != null && goalKg != null) {
            SummaryRow("משקל", "${fmtKg(weightKg)} ← ${fmtKg(goalKg)} ק\"ג · ${directionHe(direction)}")
        }
        SummaryRow("אימונים", "$days בשבוע · ${focusLabelHe(focus)}")
        SummaryRow("קלוריות", if (kcalAuto) "אוטומטי, בערך $kcal" else "$kcal ביום")
        SummaryRow("חלבון", if (proteinAuto) "אוטומטי, בערך $protein גרם" else "$protein גרם ביום")
        val diffHe = Difficulty.values().firstOrNull { it.name == difficultyName }?.nameHe ?: Difficulty.NORMAL.nameHe
        SummaryRow("קושי", diffHe)
        SummaryRow("שעון", if (hasWatch) "יש. מחברים בהגדרות." else "אין. גם בלי זה הולכים.")
    }
    Spacer(Modifier.height(14.dp))
    SpeechCard("הכל רשום. המפלצת כבר יודעת. היא לא שמחה.")
}

// ============================================================== small pieces

@Composable
private fun ProgressDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val color = when {
                i == current -> HColors.Gold
                i < current -> HColors.GoldDeep
                else -> HColors.SlateLight
            }
            Box(
                Modifier
                    .height(10.dp)
                    .width(if (i == current) 28.dp else 10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, color = HColors.Parchment, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
}

/** A short Shambiuluk line on parchment with an ink border (same look as the home speech bubble). */
@Composable
private fun SpeechCard(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(HColors.Parchment)
            .border(3.dp, HColors.Ink, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text(text, color = HColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InkCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HColors.Ink)
            .border(2.dp, HColors.GoldDeep, CardShape)
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    )
}

/** Big selectable pill: gold when selected, slate otherwise. */
@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) HColors.Gold else HColors.SlateLight)
            .border(2.dp, if (selected) HColors.GoldDeep else Color(0xFF4A525C), shape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) HColors.Ink else HColors.Parchment, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigField(label: String, value: String, onChange: (String) -> Unit, keyboardType: KeyboardType, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp, color = HColors.Parchment),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = HColors.GoldDeep, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(86.dp))
        Text(value, color = HColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

// ============================================================== pure helpers

/** The Activity behind a Compose context (it may be wrapped, e.g. by a theme wrapper). */
private fun findActivity(context: Context): Activity? {
    var c: Context? = context
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

private fun restoreErrorHe(r: com.tamir.hamasa.data.cloud.Account.RestoreResult): String = when (r) {
    com.tamir.hamasa.data.cloud.Account.RestoreResult.BAD_CODE -> "קוד של שתים עשרה אותיות. זה לא הוא. עוד ניסיון."
    com.tamir.hamasa.data.cloud.Account.RestoreResult.NOT_FOUND -> "הקוד תקין, אבל בענן אין עליו כלום. שווה לבדוק אותו שוב."
    com.tamir.hamasa.data.cloud.Account.RestoreResult.SERVER_NOT_READY -> "הענן עוד לא מוכן לכמה משתמשים. כדאי לנסות שוב אחר כך."
    com.tamir.hamasa.data.cloud.Account.RestoreResult.NETWORK -> "אין חיבור. הקוד מחכה. גם אני."
    com.tamir.hamasa.data.cloud.Account.RestoreResult.FAILED -> "ההורדה נעצרה באמצע. הקוד נשמר. עוד לחיצה תשלים."
    com.tamir.hamasa.data.cloud.Account.RestoreResult.BACKUP_FAILED -> "הגיבוי של מה שכבר במכשיר נכשל. שום דבר לא הוחלף. עם רשת זה יעבוד."
    com.tamir.hamasa.data.cloud.Account.RestoreResult.OK -> ""
}

private fun parseKg(text: String): Float? = text.replace(',', '.').toFloatOrNull()

private fun kgFilter(text: String): String = text.filter { it.isDigit() || it == '.' || it == ',' }.take(5)

private fun fmtKg(kg: Float): String {
    val r = (kg * 10f).roundToInt()
    return if (r % 10 == 0) (r / 10).toString() else "${r / 10}.${abs(r % 10)}"
}

/** Same 0.5 kg band as BodyStage: lighter goal = cut, heavier goal = gain, otherwise maintain. */
private fun suggestDirection(weightKg: Float, goalKg: Float): Int = when {
    goalKg < weightKg - BodyStage.MAINTAIN_BAND_KG -> DIR_CUT
    goalKg > weightKg + BodyStage.MAINTAIN_BAND_KG -> DIR_GAIN
    else -> DIR_MAINTAIN
}

private fun defaultFocus(direction: Int): Focus = when (direction) {
    DIR_GAIN -> Focus.BUILD_MUSCLE
    DIR_MAINTAIN -> Focus.STRENGTH
    else -> Focus.LOSE_WEIGHT
}

private fun goalDirectionOf(direction: Int): GoalDirection = when (direction) {
    DIR_MAINTAIN -> GoalDirection.MAINTAIN
    DIR_GAIN -> GoalDirection.GAIN
    else -> GoalDirection.CUT
}

private fun directionHe(direction: Int): String = goalDirectionOf(direction).nameHe

/** Preview of the automatic calorie goal (sedentary life + the chosen training days); the app recomputes it later. */
private fun estimateKcal(weightKg: Float, heightCm: Int, age: Int, sex: String, days: Int, direction: Int): Int {
    return try {
        val s = if (sex == "F") Sex.FEMALE else Sex.MALE
        val bmr = EnergyModel.bmr(weightKg, heightCm.toFloat(), age.coerceIn(14, 100), s, 0f).kcal
        val workoutsPerDay = days.coerceIn(0, 7) * PREVIEW_KCAL_PER_WORKOUT / 7f
        val tdee = EnergyModel.tdeeEstimate(bmr, ActivityLevel.SEDENTARY, workoutsPerDay).total.toFloat()
        EnergyModel.intakeFor(goalDirectionOf(direction), tdee, weightKg, 0.5f, 0.25f, bmr, s).kcal.coerceIn(800, 6000)
    } catch (e: Exception) {
        2200
    }
}

private fun estimateProtein(kcal: Int, weightKg: Float, heightCm: Int, direction: Int): Int {
    return try {
        Nutrition.targets(kcal, weightKg, 0, 0, 0, heightCm, 0f, goalDirectionOf(direction)).proteinG
    } catch (e: Exception) {
        (weightKg * 1.6f).roundToInt()
    }
}
