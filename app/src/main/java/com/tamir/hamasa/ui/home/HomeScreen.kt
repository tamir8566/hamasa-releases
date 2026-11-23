package com.tamir.hamasa.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.repo.WorkoutRepository
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.BodyBlend
import com.tamir.hamasa.domain.game.BodyStage
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.Professions
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.ui.weekly.WeeklySummaryBanner
import com.tamir.hamasa.ui.common.CharacterFigure
import com.tamir.hamasa.ui.common.AutoUpdateStrip
import com.tamir.hamasa.ui.common.rememberNamedPainter
import com.tamir.hamasa.ui.common.sceneFor
import com.tamir.hamasa.ui.common.sessionTypeLabelHe
import com.tamir.hamasa.ui.templates.WorkoutChooserDialog
import com.tamir.hamasa.ui.common.HomeWeekStrip
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.ui.progress.ProgressHomeCards
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.sin

private val PanelShape = RoundedCornerShape(26.dp)

@Composable
fun HomeScreen(
    onRoll: () -> Unit,
    onStartWorkout: (Long) -> Unit,
    onOpenWeight: () -> Unit,
    onOpenBadges: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onNeedOnboarding: () -> Unit
) {
    val vm: HomeViewModel = viewModel()
    val tapVm: ShambiulukTapViewModel = viewModel()   // tap on Shambiuluk -> a context line in the bubble
    val profile by vm.profile.collectAsStateWithLifecycle()
    val latestWeight by vm.latestWeight.collectAsStateWithLifecycle()
    val active by vm.activeSession.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val region by vm.currentRegion.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val aiMorning by vm.aiMorning.collectAsStateWithLifecycle()
    var chooserOpen by remember { mutableStateOf(false) }
    var walkOpen by remember { mutableStateOf(false) }
    var emergencyOpen by remember { mutableStateOf(false) }
    var homeOpen by remember { mutableStateOf(false) }

    val onboarded = profile?.onboarded
    LaunchedEffect(onboarded) {
        if (onboarded == false) onNeedOnboarding()
    }

    val today = remember { Days.today() }
    var closeDayOpen by remember { mutableStateOf(false) }
    val hourNow = remember { LocalTime.now().hour }

    val p = profile
    if (p == null) {
        Box(Modifier.fillMaxSize().background(HColors.Slate))
        return
    }
    // the morning line is picked once the profile is known, again if the player's or the hero's gender changes
    val morningLine = remember(p.userGender, p.characterVariant) { Voice.morning(null) }
    var speech by remember(morningLine) { mutableStateOf(morningLine) }

    val level = LevelTable.levelFor(p.xp)
    val progress = LevelTable.progress(p.xp)
    val currentKg = latestWeight?.kg ?: p.startWeightKg
    val blend = BodyStage.forProfile(currentKg, p)

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AutoUpdateStrip()
        WeeklySummaryBanner(onOpen = onOpenHistory)   // Friday morning .. Saturday night
        TopStatusRow(
            level = level, progress = progress, gold = p.gold, streak = p.streak,
            professionHe = Professions.active(p.professionId, p.xp)?.nameHe
        )
        Spacer(Modifier.height(12.dp))
        // "שמביולוק ישן": 3+ days without activity -> dimmed scene + floating "ז ז ז"; one session wakes him.
        val asleep = isAsleep(p, today)
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    tapVm.onTap { line -> speech = line }
                }
        ) {
            ScenePanel(blend = blend, variant = p.characterVariant, region = region, modifier = Modifier.fillMaxWidth().sleepDim(asleep))
            if (asleep) SleepOverlay(Modifier.matchParentSize())
        }
        Spacer(Modifier.height(12.dp))
        val moodLine = remember(todayMood(p)) { Voice.forMood(todayMood(p)) }
        val streakBroken = !asleep && isStreakBroken(p, today)
        val statusLine = remember(asleep, streakBroken) {
            when {
                asleep -> sleepyLine()
                streakBroken -> Voice.missed(p.plannedTomorrowHour) + " " + NEXT_SMALL_STEP
                else -> null
            }
        }
        SpeechBubble(
            text = when {
                speech != morningLine -> speech
                statusLine != null -> statusLine
                moodLine != null -> moodLine
                else -> aiMorning ?: speech // stage 2 G: today's Gemini morning line when there is one
            }
        )
        Spacer(Modifier.height(12.dp))

        val activeSession = active
        // Yesterday's "סגירת היום" decides today's default start (e.g. planned YOGA -> the button starts yoga).
        val planned = plannedForToday(p, today)
        if (activeSession == null && planned != null) {
            Text(plannedLabel(planned, p.plannedTomorrowHour), color = HColors.ParchmentDim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
        }
        when {
            activeSession != null -> PrimaryButton(text = "המשך אימון", enabled = !busy) { onStartWorkout(activeSession.id) }
            p.lastRollDay != today -> PrimaryButton(text = "הגרלת הבוקר", enabled = !busy) { onRoll() }
            planned != null && planned != SessionType.GYM -> PrimaryButton(text = "התחלה: ${sessionTypeLabelHe(planned)}", enabled = !busy) {
                if (planned == SessionType.WALK) walkOpen = true else vm.startType(planned, onStartWorkout)
            }
            else -> PrimaryButton(text = "התחלת אימון", enabled = !busy) {
                if (templates.isEmpty()) vm.startGym(onStartWorkout) else chooserOpen = true
            }
        }

        if (activeSession == null) {
            Spacer(Modifier.height(8.dp))
            ShortSessionRow(
                enabled = !busy,
                onMini = { vm.startMini(onStartWorkout) },
                onWalk = { walkOpen = true },
                onMinimal = { vm.startMinimal(onStartWorkout) }
            )
            // "אימון בבית": the coach builds a session from the equipment at home
            TextButton(onClick = { homeOpen = true }, enabled = !busy) {
                Text("אימון בבית", color = HColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            // the running coach: today's plan run, when one is due (never the day after a run)
            val runStatus = com.tamir.hamasa.data.repo.RunCoach.status(p, today)
            if (runStatus.plannedToday) {
                TextButton(onClick = { vm.startType(SessionType.RUN, onStartWorkout) }, enabled = !busy) {
                    Text("ריצה: ${runStatus.labelHe}", color = HColors.Ember, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            val emergencyLeft = WorkoutRepository.emergencyLeftFor(p, today)
            TextButton(onClick = { emergencyOpen = true }, enabled = !busy && emergencyLeft > 0) {
                Text(
                    if (emergencyLeft > 0) "כפתור חירום · נשארו $emergencyLeft החודש" else "כפתור חירום · נגמר לחודש הזה",
                    color = if (emergencyLeft > 0) HColors.Ember else HColors.ParchmentDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (chooserOpen) {
            WorkoutChooserDialog(
                templates = templates,
                enabled = !busy,
                onCoach = { chooserOpen = false; vm.startGym(onStartWorkout) },
                onTemplate = { id -> chooserOpen = false; vm.startTemplate(id, onStartWorkout) },
                onDismiss = { chooserOpen = false },
                onMini = { chooserOpen = false; vm.startMini(onStartWorkout) },
                onWalk = { chooserOpen = false; walkOpen = true },
                onMinimal = { chooserOpen = false; vm.startMinimal(onStartWorkout) },
                onHome = { chooserOpen = false; homeOpen = true }
            )
        }

        if (homeOpen) {
            com.tamir.hamasa.ui.coach.HomeWorkoutDialog(
                sleepHours = null,
                onDismiss = { homeOpen = false },
                onStarted = onStartWorkout
            )
        }

        if (walkOpen) {
            WalkDialog(
                enabled = !busy,
                onDismiss = { walkOpen = false },
                onStart = { minutes -> walkOpen = false; vm.startWalk(minutes, onStartWorkout) }
            )
        }

        if (emergencyOpen) {
            val left = WorkoutRepository.emergencyLeftFor(p, today)
            AlertDialog(
                onDismissRequest = { emergencyOpen = false },
                title = { Text("כפתור חירום", fontWeight = FontWeight.Black) },
                text = {
                    Text("שתים עשרה דקות מהתרגילים שדירגת חמישה כוכבים. נחשב לרצף במלואו. נשארו $left החודש, זה ישתמש באחד.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        emergencyOpen = false
                        vm.startEmergency(onStartWorkout, onNoneLeft = { speech = "החירום של החודש נגמר. אימון זעיר עדיין פתוח." })
                    }, enabled = !busy && left > 0) { Text("יוצאים") }
                },
                dismissButton = { TextButton(onClick = { emergencyOpen = false }) { Text("לא עכשיו") } }
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SmallLink("משקל", onOpenWeight)
            SmallLink("הישגים", onOpenBadges)
            SmallLink("היסטוריה", onOpenHistory)
            SmallLink("הגדרות", onOpenSettings)
        }
        // stage 3: steps from the watch (nothing at all when it is not connected)
        WatchStepsChip(Modifier.padding(top = 6.dp))
        // "הלב": resting HR vs the usual, 14 days, last workout HR (nothing without the watch / heart data)
        HomeHeartCard(Modifier.padding(top = 6.dp))
        // goal forecast line + monthly photo / monthly summary cards (nothing when none applies)
        ProgressHomeCards(onOpenWeight = onOpenWeight, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        HomeWeekStrip(onDayClick = { onOpenHistory() })
        // stage 2 H: "אויב חוזר" (only when this week's boss was beaten before)
        ReturningEnemyCard(Modifier.padding(top = 8.dp))
        // coach season: holiday event, resting HR warning, deload proposal, weekly challenge
        SeasonHomeCards(Modifier.padding(top = 8.dp))

        if (hourNow >= 19) {
            Spacer(Modifier.height(6.dp))
            AssistChip(
                onClick = { closeDayOpen = true },
                label = { Text("סגירת היום") },
                leadingIcon = { Icon(Icons.Filled.NightsStay, contentDescription = null, tint = HColors.Gold) }
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (closeDayOpen) {
        CloseDayDialog(
            initialType = runCatching { SessionType.valueOf(p.plannedTomorrowType) }.getOrNull() ?: SessionType.GYM,
            initialHour = p.plannedTomorrowHour.coerceIn(CLOSE_DAY_HOURS.first, CLOSE_DAY_HOURS.last),
            onDismiss = { closeDayOpen = false },
            onConfirm = { type, hour ->
                vm.closeDay(type, hour)
                closeDayOpen = false
                speech = "מחר ${sessionTypeLabelHe(type)} ב-${String.format("%02d", hour)}:00. נרשם. עכשיו לישון."
            }
        )
    }
}

@Composable
private fun TopStatusRow(level: Int, progress: Float, gold: Int, streak: Int, professionHe: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(HColors.Ink)
                .border(3.dp, HColors.Gold, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(level.toString(), color = HColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            // "מקצוע לשמביולוק": the chosen profession next to the level
            Text(
                if (professionHe != null) "רמה $level · ${com.tamir.hamasa.domain.game.CharacterNames.g(professionHe)}" else "רמה $level",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
            )
            // level 10: Shambiuluk's first title
            LevelGates.titleFor(level)?.let { title ->
                Text(com.tamir.hamasa.domain.game.CharacterNames.g(title), color = HColors.Gold, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp)),
                color = HColors.Gold,
                trackColor = HColors.Ink
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = HColors.Gold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
                Text(gold.toString(), color = HColors.Parchment, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = HColors.Ember, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
                Text(streak.toString(), color = HColors.Parchment, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

/** Scene layer geometry: all three layers share one 1080x1920 canvas drawn with ContentScale.Crop. */
private const val SCENE_IMAGE_ASPECT = 1080f / 1920f
/** Over-scale of every layer so the parallax offsets never reveal an edge. */
private const val SCENE_LAYER_SCALE = 1.08f
/** Where the feet sit inside the character canvas (900x1400, feet at ~y=1351). */
private const val FEET_CANVAS_FRAC = 0.965f

/**
 * Per-region placement + color grade, measured on the real layer PNGs:
 * [standFrac] = where the feet touch the flat top of the ledge (fraction of the 1920px image height, a bit
 * INTO the surface so the figure stands on it instead of hovering at its back edge);
 * Re-measured 2026-11-13 on the 0.72 panel: the old values put the feet on the BACK edge of the ledge (forest:
 * above it, river: on the water), so the figure looked like it was floating.
 * [tint] = average scene color around the figure, used to grade the full-color cutout into the sepia ink scene.
 */
private data class SceneFit(val standFrac: Float, val tint: Color)

private fun sceneFit(region: Int): SceneFit = when (region.coerceIn(0, 10)) {
    0, 1 -> SceneFit(0.665f, Color(0xFFBC9E72))   // הכפר / הגבעות – misty forest ledge
    2 -> SceneFit(0.725f, Color(0xFF6C533C))      // היער – dark, ruins
    3 -> SceneFit(0.800f, Color(0xFFCAAC81))      // הנהר – bridge
    4 -> SceneFit(0.720f, Color(0xFF9C9569))      // הביצה – greenish
    5 -> SceneFit(0.780f, Color(0xFFC59458))      // המדבר
    6 -> SceneFit(0.780f, Color(0xFFE2B083))      // הקניון – bright red rock
    7 -> SceneFit(0.650f, Color(0xFF7B5A46))      // הלבה – dark
    8 -> SceneFit(0.645f, Color(0xFFAD9A8B))      // היער הקפוא – cool, desaturated
    9 -> SceneFit(0.670f, Color(0xFF98775B))      // ההרים
    else -> SceneFit(0.740f, Color(0xFFC5A275))   // איי השמיים
}

/** Saturation drop + multiply by the scene tint, so skin/cloth pick up the scene's light instead of popping out. */
private fun figureColorMatrix(tint: Color): ColorMatrix {
    val m = ColorMatrix()
    m.setToSaturation(0.62f)
    // Normalise the tint so overall brightness stays, only the hue/temperature is pushed toward the scene.
    val mean = (tint.red + tint.green + tint.blue) / 3f
    val r = tint.red / mean; val g = tint.green / mean; val b = tint.blue / mean
    val tintM = ColorMatrix(
        floatArrayOf(
            r * 0.97f, 0f, 0f, 0f, 0f,
            0f, g * 0.97f, 0f, 0f, 0f,
            0f, 0f, b * 0.97f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    m *= tintM
    return m
}

/** Parallax scene: far (sky+mountains), mid (forest), near (ledge) + Shambiuluk glued to the ledge. [region] picks the layer set. */
@Composable
internal fun ScenePanel(blend: BodyBlend, variant: String, region: Int, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scene")
    // One slow sine drives all three layers and the character (period 6s).
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val auraAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "aura"
    )
    val drift = sin(phase)
    val breath = 1f + 0.006f * (1f + sin(phase))   // 1.000 .. 1.012

    BoxWithConstraints(
        modifier
            .aspectRatio(0.72f)
            .clip(PanelShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFE9B96A), Color(0xFF6B5A48))))
            .border(4.dp, HColors.GoldDeep, PanelShape)
    ) {
        val density = LocalDensity.current
        val farPx = with(density) { (3.dp * drift).roundToPx() }
        val midPx = with(density) { (7.dp * drift).roundToPx() }
        val nearPx = with(density) { (12.dp * drift).roundToPx() }
        val panelW = maxWidth
        val panelH = maxHeight

        val fit = sceneFit(region)
        // Stand line in panel coordinates: the image is Crop-fitted (width-bound, centered) then over-scaled.
        val imageH = panelW / SCENE_IMAGE_ASPECT
        val cropTop = (imageH - panelH) / 2f
        val standFrac = ((fit.standFrac * imageH.value - cropTop.value) / panelH.value - 0.5f) * SCENE_LAYER_SCALE + 0.5f
        val feetY = panelH * standFrac
        val figH = panelH * 0.56f
        val figW = figH * (900f / 1400f)
        val figTop = feetY - figH * FEET_CANVAS_FRAC
        val gradePaint = remember(region) { Paint().apply { colorFilter = ColorFilter.colorMatrix(figureColorMatrix(fit.tint)) } }

        // The three background layers crossfade (600ms) when the player enters a new map region.
        Crossfade(
            targetState = region,
            animationSpec = tween(600),
            modifier = Modifier.fillMaxSize(),
            label = "region"
        ) { r ->
            val scene = sceneFor(r)
            val far = rememberNamedPainter(scene.first)
            val mid = rememberNamedPainter(scene.second)
            val near = rememberNamedPainter(scene.third)
            Box(Modifier.fillMaxSize()) {
                if (far != null) {
                    Image(
                        painter = far, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = SCENE_LAYER_SCALE; scaleY = SCENE_LAYER_SCALE }
                            .offset { IntOffset(farPx, 0) }
                    )
                }
                if (mid != null) {
                    Image(
                        painter = mid, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = SCENE_LAYER_SCALE; scaleY = SCENE_LAYER_SCALE }
                            .offset { IntOffset(midPx, 0) }
                    )
                }
                if (near != null) {
                    Image(
                        painter = near, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = SCENE_LAYER_SCALE; scaleY = SCENE_LAYER_SCALE }
                            .offset { IntOffset(nearPx, 0) }
                    )
                }
            }
        }

        // Contact shadow on the ledge surface: a flattened dark ellipse centred slightly behind the feet,
        // warm-black so it reads as ink shading rather than a grey sticker shadow. Moves with the near layer.
        val shadowW = figW * 0.95f
        val shadowH = panelH * 0.075f
        Canvas(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = feetY - shadowH * 0.55f)
                .offset { IntOffset(nearPx, 0) }
                .width(shadowW)
                .height(shadowH)
        ) {
            val r = size.width / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            scale(scaleX = 1f, scaleY = size.height / (2f * r), pivot = c) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1A0E04).copy(alpha = 0.55f),
                            Color(0xFF1A0E04).copy(alpha = 0.30f),
                            Color(0xFF1A0E04).copy(alpha = 0f)
                        ),
                        center = c, radius = r
                    ),
                    radius = r, center = c
                )
            }
        }

        // The figure: graded into the scene's light (saturation + tint), darkened toward the feet (ambient
        // occlusion against the rock), breathing slightly from the feet up. Moves with the near layer.
        CharacterFigure(
            blend = blend,
            variant = variant,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = figTop)
                .offset { IntOffset(nearPx, 0) }
                .width(figW)
                .height(figH)
                .graphicsLayer {
                    scaleY = breath
                    transformOrigin = TransformOrigin(0.5f, FEET_CANVAS_FRAC)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    // Draw the figure through an offscreen layer whose paint carries the scene color grade.
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(Offset.Zero, size), gradePaint)
                        this@drawWithContent.drawContent()
                        canvas.restore()
                    }
                    // Only the figure's own pixels receive the gradient (SrcAtop inside the offscreen layer).
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1.0f to Color(0xFF2A1A0C).copy(alpha = 0.38f)
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                    // Faint warm rim from the sky light on the upper body, keeps the silhouette from going flat.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color(0xFFFFE2A8).copy(alpha = 0.10f),
                            0.45f to Color.Transparent
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                }
        )
        // Keep the aura value referenced (used by the pulse of the ground glow below) so the transition stays alive.
        Canvas(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = feetY - shadowH * 0.55f)
                .offset { IntOffset(nearPx, 0) }
                .width(shadowW * 1.3f)
                .height(shadowH)
        ) {
            // Very soft warm glow on the rock around the feet (hero light), pulsing gently.
            val r = size.width / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            scale(scaleX = 1f, scaleY = size.height / (2f * r), pivot = c) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFD27A).copy(alpha = auraAlpha * 0.35f), Color(0xFFFFD27A).copy(alpha = 0f)),
                        center = c, radius = r
                    ),
                    radius = r, center = c
                )
            }
        }
    }
}

@Composable
private fun SpeechBubble(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(HColors.Parchment)
            .border(BorderStroke(3.dp, HColors.Ink), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // every hero line passes through g() here too (already-resolved lines come back unchanged)
        Text(com.tamir.hamasa.domain.game.CharacterNames.g(text), color = HColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(60.dp)
            .border(3.dp, HColors.GoldDeep, RoundedCornerShape(30.dp))
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SmallLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, color = HColors.Parchment, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CloseDayDialog(initialType: SessionType, initialHour: Int, onDismiss: () -> Unit, onConfirm: (SessionType, Int) -> Unit) {
    var type by remember { mutableStateOf(initialType) }
    var hour by remember { mutableIntStateOf(initialHour) }
    // "הימור על עצמך": optional stake on showing up tomorrow (see data/repo/Bets.kt).
    val betProfile by com.tamir.hamasa.data.AppGraph.prefs.profile.collectAsState(initial = null)
    var stake by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { com.tamir.hamasa.data.AppGraph.scope.launch { com.tamir.hamasa.data.repo.Bets.settleMissed() } }
    val types = listOf(SessionType.GYM, SessionType.YOGA, SessionType.WALK, SessionType.MINI)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("סגירת היום", fontWeight = FontWeight.Black) },
        text = {
            Column {
                Text("מה מחר?", color = HColors.ParchmentDim)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(sessionTypeLabelHe(t)) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("באיזו שעה?", color = HColors.ParchmentDim)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (h in CLOSE_DAY_HOURS) {
                        FilterChip(selected = hour == h, onClick = { hour = h }, label = { Text(String.format("%02d:00", h)) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("הימור על עצמך", color = HColors.Gold, fontWeight = FontWeight.Bold)
                val bp = betProfile
                if (bp != null && bp.betStake > 0) {
                    Text("כבר יש הימור פתוח: ${bp.betStake} זהב. קודם מגיעים, אחר כך מהמרים שוב.", color = HColors.ParchmentDim, fontSize = 13.sp)
                } else {
                    Text(com.tamir.hamasa.domain.game.Addr.t("מגיע מחר: הזהב חוזר כפול. לא מגיע: הזהב הולך לקופת המפלצת.", "מגיעה מחר: הזהב חוזר כפול. לא מגיעה: הזהב הולך לקופת המפלצת."), color = HColors.ParchmentDim, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (g in com.tamir.hamasa.data.repo.Bets.STAKES) {
                            val affordable = g == 0 || (bp != null && bp.gold >= g)
                            FilterChip(
                                selected = stake == g,
                                enabled = affordable,
                                onClick = { stake = g },
                                label = { Text(if (g == 0) "בלי" else "$g") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val g = stake
                if (g > 0) com.tamir.hamasa.data.AppGraph.scope.launch { com.tamir.hamasa.data.repo.Bets.place(g) }
                onConfirm(type, hour)
            }) { Text("רשמתי") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
