package com.tamir.hamasa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.ui.theme.HColors
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.tamir.hamasa.ui.adventure.AdventureScreen
import com.tamir.hamasa.ui.adventure.BossBoardScreen
import com.tamir.hamasa.ui.analysis.AnalysisScreen
import com.tamir.hamasa.ui.badges.BadgesScreen
import com.tamir.hamasa.ui.coach.CoachScreen
import com.tamir.hamasa.ui.exercises.CustomExercisesScreen
import com.tamir.hamasa.ui.history.HistoryHubScreen
import com.tamir.hamasa.ui.run.RunsScreen
import com.tamir.hamasa.ui.weekly.WeeklySummaryScreen
import com.tamir.hamasa.ui.history.SessionDetailScreen
import com.tamir.hamasa.ui.history.rememberSessionTitle
import com.tamir.hamasa.ui.home.HomeScreen
import com.tamir.hamasa.ui.home.MorningRollScreen
import com.tamir.hamasa.ui.home.OnboardingScreen
import com.tamir.hamasa.ui.map.MapScreen
import com.tamir.hamasa.ui.settings.SettingsScreen
import com.tamir.hamasa.ui.tree.TreeScreen
import com.tamir.hamasa.ui.weight.WeightScreen
import com.tamir.hamasa.ui.records.RecordsScreen
import com.tamir.hamasa.ui.progress.ProgressScreen
import com.tamir.hamasa.ui.progress.BeforeAfterScreen
import com.tamir.hamasa.ui.progress.MonthlySummaryScreen
import com.tamir.hamasa.ui.progress.ProgressNav
import com.tamir.hamasa.ui.workout.WorkoutScreen
import com.tamir.hamasa.ui.workout.WorkoutDoneScreen
import com.tamir.hamasa.ui.food.FoodScreen
import com.tamir.hamasa.ui.shop.ShopScreen
import com.tamir.hamasa.ui.templates.TemplateEditorScreen
import com.tamir.hamasa.ui.templates.TemplatesScreen
import com.tamir.hamasa.widget.Widgets

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ROLL = "roll"
    const val COACH = "coach"
    const val WORKOUT = "workout/{sessionId}"
    const val WORKOUT_DONE = "workout_done/{sessionId}"
    const val HISTORY = "history"
    const val WEEKLY = "weekly"
    const val WEIGHT = "weight"
    const val TREE = "tree"
    const val MAP = "map"
    const val BADGES = "badges"
    const val SETTINGS = "settings"
    const val FOOD = "food"
    const val SHOP = "shop"
    const val CUSTOM_EXERCISES = "custom_exercises"
    const val ANALYSIS = "analysis"
    const val TEMPLATES = "templates"
    const val TEMPLATE_EDIT = "template_edit/{templateId}"
    const val SESSION_DETAIL = "session/{sessionId}"
    const val RECORDS = "records"
    const val PROGRESS = "progress"
    const val ENERGY = "energy"
    /** "ההרפתקה": hub tab with the map, the tree, the shop, the badges and the boss board. */
    const val ADVENTURE = "adventure"
    const val BOSSES = "bosses"
    /** Running coach (ui/run, agent P), opened from the history tab. */
    const val RUNS = "runs"
    const val BEFORE_AFTER = ProgressNav.BEFORE_AFTER
    /** ym = year*12 + (month-1); -1 = the previous month. Opened through ProgressNav.monthly(). */
    const val MONTHLY_SUMMARY = ProgressNav.MONTHLY
    fun workout(id: Long) = "workout/$id"
    fun workoutDone(id: Long) = "workout_done/$id"
    fun sessionDetail(id: Long) = "session/$id"
    /** -1 = a new template. */
    fun templateEdit(id: Long) = "template_edit/$id"
}

/** Thin header with a back arrow (auto-mirrored for RTL) over screens reached from the home links. */
@Composable
private fun WithBack(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה", tint = HColors.Gold)
            }
            Text(title, color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Screens (besides the tab roots) that keep the bottom bar. */
private val BAR_ROUTES = setOf(
    Routes.BADGES, Routes.WEIGHT, Routes.SETTINGS, Routes.ANALYSIS, Routes.MAP, Routes.TREE, Routes.SHOP,
    Routes.BOSSES, Routes.RUNS, Routes.RECORDS, Routes.WEEKLY, Routes.MONTHLY_SUMMARY
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    // RTL: the first tab is on the right.
    val tabs = listOf(
        Tab(Routes.HOME, "בית", Icons.Filled.Home),
        Tab(Routes.COACH, "ג'ים ברו", Icons.Filled.FitnessCenter),
        Tab(Routes.FOOD, "אוכל", Icons.Filled.Restaurant),
        Tab(Routes.HISTORY, "היסטוריה", Icons.Filled.History),
        Tab(Routes.ADVENTURE, "ההרפתקה", Icons.Filled.Explore)
    )
    val tabRoutes = tabs.map { it.route }
    val showBar = current in tabRoutes || current in BAR_ROUTES
    // The tab that owns the screen on top (a screen opened from a tab keeps that tab selected).
    var selectedTab by rememberSaveable { mutableStateOf(Routes.HOME) }
    LaunchedEffect(current) { if (current != null && current in tabRoutes) selectedTab = current }
    // Tapping the selected tab again: its root is rebuilt (key changes) -> scrolled to the top, dialogs closed.
    val reselect = remember { mutableStateMapOf<String, Int>() }

    fun openTab(route: String) {
        selectedTab = route
        runCatching {
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true; restoreState = true
            }
        }
    }

    fun onTabClick(route: String) {
        if (route != selectedTab) {
            openTab(route)
            return
        }
        // already on this tab: back to its root, at the top
        reselect[route] = (reselect[route] ?: 0) + 1
        if (current != route) {
            val popped = runCatching { nav.popBackStack(route, false) }.getOrDefault(false)
            if (!popped) openTab(route)
        }
    }

    // an active workout shows a strip at the bottom of every screen except the workout itself
    val active by remember { com.tamir.hamasa.data.AppGraph.workouts.activeSession }.collectAsState(initial = null)
    val inWorkoutFlow = current == null || current.startsWith("workout") || current == Routes.ONBOARDING || current == Routes.ROLL
    val activeId = active?.id
    val showStrip = activeId != null && !inWorkoutFlow

    Scaffold(
        bottomBar = {
            Column {
                if (showStrip && activeId != null) {
                    com.tamir.hamasa.ui.common.ActiveWorkoutBar(
                        startedAtMillis = active?.startedAtMillis,
                        onOpen = { runCatching { nav.navigate(Routes.workout(activeId)) { launchSingleTop = true } } },
                        modifier = if (showBar) Modifier else Modifier.navigationBarsPadding()
                    )
                }
                if (showBar) NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = selectedTab == t.route,
                            onClick = { onTabClick(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
            composable(Routes.ONBOARDING) { OnboardingScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true }; launchSingleTop = true } }) }
            composable(Routes.HOME) {
                key(reselect[Routes.HOME] ?: 0) {
                    HomeScreen(
                        onRoll = { nav.navigate(Routes.ROLL) },
                        onStartWorkout = { id -> nav.navigate(Routes.workout(id)) },
                        onOpenWeight = { nav.navigate(Routes.WEIGHT) },
                        onOpenBadges = { nav.navigate(Routes.BADGES) },
                        onOpenHistory = { openTab(Routes.HISTORY) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onNeedOnboarding = { nav.navigate(Routes.ONBOARDING) }
                    )
                }
            }
            composable(Routes.ROLL) { MorningRollScreen(onStart = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } }, onBack = { nav.popBackStack() }) }
            composable(Routes.COACH) {
                key(reselect[Routes.COACH] ?: 0) {
                    CoachScreen(
                        onStartWorkout = { id -> nav.navigate(Routes.workout(id)) },
                        onOpenCustomExercises = { nav.navigate(Routes.CUSTOM_EXERCISES) },
                        onOpenTemplates = { nav.navigate(Routes.TEMPLATES) }
                    )
                }
            }
            composable(Routes.WORKOUT, arguments = listOf(navArgument("sessionId") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("sessionId")
                WorkoutScreen(sessionId = id, onFinished = { nav.navigate(Routes.workoutDone(id)) { popUpTo(Routes.HOME) } }, onAbandon = { nav.popBackStack(Routes.HOME, false) })
            }
            composable(Routes.WORKOUT_DONE, arguments = listOf(navArgument("sessionId") { type = NavType.LongType })) { e ->
                val doneId = e.arguments!!.getLong("sessionId")
                WorkoutDoneScreen(
                    sessionId = doneId,
                    onClose = { nav.popBackStack(Routes.HOME, false) },
                    onOpenDetail = { nav.navigate(Routes.sessionDetail(doneId)) }
                )
            }
            composable(Routes.WEEKLY) { WithBack("הסיכום השבועי", onBack = { nav.popBackStack() }) { WeeklySummaryScreen() } }
            composable(Routes.HISTORY) {
                key(reselect[Routes.HISTORY] ?: 0) {
                    HistoryHubScreen(
                        onOpenSession = { id -> nav.navigate(Routes.sessionDetail(id)) },
                        onOpenRuns = { nav.navigate(Routes.RUNS) },
                        onOpenWeekly = { nav.navigate(Routes.WEEKLY) },
                        onOpenMonthly = { runCatching { nav.navigate(ProgressNav.monthly()) } },
                        onOpenAnalysis = { nav.navigate(Routes.ANALYSIS) },
                        onOpenRecords = { nav.navigate(Routes.RECORDS) }
                    )
                }
            }
            composable(Routes.RUNS) { WithBack("ריצות", onBack = { nav.popBackStack() }) { com.tamir.hamasa.ui.run.RunsScreenContent(onStartRun = { id -> nav.navigate(Routes.workout(id)) }) } }
            composable(Routes.ADVENTURE) {
                key(reselect[Routes.ADVENTURE] ?: 0) {
                    AdventureScreen(
                        onOpenMap = { nav.navigate(Routes.MAP) },
                        onOpenTree = { nav.navigate(Routes.TREE) },
                        onOpenShop = { nav.navigate(Routes.SHOP) },
                        onOpenBadges = { nav.navigate(Routes.BADGES) },
                        onOpenBosses = { nav.navigate(Routes.BOSSES) }
                    )
                }
            }
            composable(Routes.BOSSES) { WithBack("לוח הבוסים", onBack = { nav.popBackStack() }) { BossBoardScreen() } }
            composable(Routes.SESSION_DETAIL, arguments = listOf(navArgument("sessionId") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("sessionId")
                WithBack(rememberSessionTitle(id), onBack = { nav.popBackStack() }) {
                    SessionDetailScreen(sessionId = id, onStartWorkout = { newId -> nav.navigate(Routes.workout(newId)) })
                }
            }
            composable(Routes.ANALYSIS) { WithBack("ניתוח", onBack = { nav.popBackStack() }) { AnalysisScreen() } }
            composable(Routes.WEIGHT) { WithBack("משקל", onBack = { nav.popBackStack() }) { WeightScreen(onOpenProgress = { nav.navigate(Routes.PROGRESS) }) } }
            composable(Routes.RECORDS) { WithBack("ספר השיאים", onBack = { nav.popBackStack() }) { RecordsScreen() } }
            composable(Routes.PROGRESS) { WithBack("תמונות ומידות", onBack = { nav.popBackStack() }) { ProgressScreen() } }
            composable(Routes.BEFORE_AFTER) { WithBack("לפני ואחרי", onBack = { nav.popBackStack() }) { BeforeAfterScreen() } }
            composable(Routes.MONTHLY_SUMMARY, arguments = listOf(navArgument("ym") { type = NavType.IntType })) { e ->
                val ym = e.arguments?.getInt("ym") ?: -1
                WithBack("סיכום חודש", onBack = { nav.popBackStack() }) { MonthlySummaryScreen(initialYm = ym) }
            }
            composable(Routes.TREE) { WithBack("עץ הכוח", onBack = { nav.popBackStack() }) { TreeScreen() } }
            composable(Routes.MAP) { WithBack("המסע", onBack = { nav.popBackStack() }) { MapScreen() } }
            composable(Routes.BADGES) { WithBack("הישגים", onBack = { nav.popBackStack() }) { BadgesScreen() } }
            composable(Routes.SETTINGS) { WithBack("הגדרות", onBack = { nav.popBackStack() }) { SettingsScreen(onOpenEnergy = { nav.navigate(Routes.ENERGY) }) } }
            composable(Routes.FOOD) { key(reselect[Routes.FOOD] ?: 0) { FoodScreen(onOpenEnergy = { nav.navigate(Routes.ENERGY) }) } }
            composable(Routes.ENERGY) { WithBack("מחשבון אנרגיה", onBack = { nav.popBackStack() }) { com.tamir.hamasa.ui.energy.EnergyScreen() } }
            composable(Routes.SHOP) { WithBack("החנות", onBack = { nav.popBackStack() }) { ShopScreen() } }
            composable(Routes.CUSTOM_EXERCISES) { CustomExercisesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.TEMPLATES) {
                WithBack("האימונים שלי", onBack = { nav.popBackStack() }) {
                    TemplatesScreen(
                        onStart = { id -> nav.navigate(Routes.workout(id)) },
                        onEdit = { id -> nav.navigate(Routes.templateEdit(id)) },
                        onNew = { nav.navigate(Routes.templateEdit(-1L)) }
                    )
                }
            }
            composable(Routes.TEMPLATE_EDIT, arguments = listOf(navArgument("templateId") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("templateId")
                WithBack(if (id <= 0L) "אימון חדש" else "עריכת אימון", onBack = { nav.popBackStack() }) {
                    TemplateEditorScreen(templateId = id, onDone = { nav.popBackStack() })
                }
            }
        }
        // design: the bottom edge of every screen is slightly faded, and stays pinned there while content scrolls
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, HColors.Slate.copy(alpha = 0.75f))))
        )
        }

        // widget "צלם ארוחה": go to the food screen; FoodScreen consumes the request and opens the camera once.
        // Placed after NavHost so the graph is already set when the effect runs.
        val snapMeal by Widgets.snapMeal.collectAsState()
        LaunchedEffect(snapMeal) {
            val route = nav.currentDestination?.route
            if (snapMeal && route == Routes.WORKOUT) {
                // a workout is open: push the food screen on top (no popping), so system back returns to the
                // running workout exactly as it was; popping would tear the workout screen off the stack
                runCatching { nav.navigate(Routes.FOOD) { launchSingleTop = true } }
            } else if (snapMeal && route != Routes.FOOD) {
                runCatching {
                    nav.navigate(Routes.FOOD) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }

        // Progress features opened from places with a fixed signature (Home cards, weight screen, weekly summary).
        val progressRequest by ProgressNav.request.collectAsState()
        LaunchedEffect(progressRequest) {
            val route = progressRequest ?: return@LaunchedEffect
            ProgressNav.consumed()
            runCatching { nav.navigate(route) { launchSingleTop = true } }
        }

        // an update installing right after the app started: one calm full screen instead of a sudden close
        com.tamir.hamasa.ui.common.UpdatingOverlay()
    }
}
