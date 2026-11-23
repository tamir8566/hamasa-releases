package com.tamir.hamasa.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore("hamasa_prefs")

/** Coach focus the user picks for a week/period. */
enum class Focus { LOSE_WEIGHT, BUILD_MUSCLE, STRENGTH, FLEXIBILITY, ENDURANCE, BRANCH_CHEST, BRANCH_BACK, BRANCH_LEGS, BRANCH_SHOULDERS_ARMS, BRANCH_CORE }

data class Profile(
    val onboarded: Boolean = false,
    /** Finished-session count at the moment the map opened (level 6); -1 = journey not started yet. */
    val mapStartDone: Int = -1,
    /** One-time correction of the first-workout "every exercise is a record" bug has run. */
    val economyFix1Done: Boolean = false,
    val heightCm: Int = 185,
    val startWeightKg: Float = 105f,
    val goalWeightKg: Float = 90f,
    val focus: Focus = Focus.LOSE_WEIGHT,
    val xp: Int = 0,
    val gold: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val shields: Int = 1,
    val lastActiveDay: Long = 0L,         // epochDay of last counted activity
    val lastRollDay: Long = 0L,           // epochDay of last morning roll
    val rollModeId: String = "",          // today's rolled mode
    val rollGoal: String = "",            // today's rolled daily goal
    val rollBonus: String = "",           // "double_xp" | "chest" | "none"
    val plannedTomorrowType: String = "", // SessionType name chosen at day close
    val plannedTomorrowHour: Int = 7,
    val quietFrom: Int = 23,
    val quietTo: Int = 7,
    val luckChain: Int = 0,               // consecutive workouts (affects legendary chest odds)
    val lifetimeKg: Double = 0.0,
    val kcalGoal: Int = 2200,
    /** User exercise swaps for the current coach block: "baseId>chosenId,baseId>chosenId". */
    val exerciseOverridesCsv: String = "",
    /** Coach block id the overrides belong to; -1 = none. */
    val exerciseOverridesBlock: Int = -1,
    // --- stage 1 C: boss rounds, stats, hard battle, badges, weekly box, battle log ---
    /** Session id in which a rest was cut short (a set started / rest skipped with >10 s left); -1 = none. */
    val restBrokenSessionId: Long = -1L,
    /** weekStart (Sunday epochDay) whose "הקרב הקשה" double XP was already given; -1 = never. */
    val hardBattleWeek: Long = -1L,
    /** Sealed chest drops of the week, "week;RARITY;xp;gold;modeId;itemId;shield;joker" joined by '|'. */
    val sealedChests: String = "",
    /** Chests whose contents were applied (opened). */
    val chestsOpened: Int = 0,
    /** "ספר הקרבות": sessionId + U+001F + line, entries joined by U+001E. */
    val battleLog: String = "",
    /** Extra map stations earned through endurance (on top of one station per finished session). */
    val mapBonusStations: Int = 0,
    /** yyyy*12+month of the last monthly stability shield grant; 0 = never. */
    val stabilityShieldMonth: Int = 0,
    /** Bosses ever defeated. Only grows (boss_progress rows are keyed by boss and get replaced on rotation). */
    val bossesDefeatedTotal: Int = 0,
    // --- stage 1 A: emergency button, close-the-day, reminders, weekly rest day ---
    /** yyyy*12+month the [emergencyUsed] counter belongs to; 0 = never. */
    val emergencyMonth: Int = 0,
    /** Emergency workouts started in [emergencyMonth] (limit 2 per calendar month). */
    val emergencyUsed: Int = 0,
    /** epochDay of the last "סגירת היום" (the +5 XP is granted once per day; the plan is for lastCloseDay + 1). */
    val lastCloseDay: Long = 0L,
    /** epochDay the workout reminder was last sent. */
    val reminderSentDay: Long = 0L,
    /** epochDay the evening close-the-day nudge was last sent. */
    val closeNudgeDay: Long = 0L,
    /** weekStart (Sunday epochDay) of the week whose free rest day was already used; 0 = none. */
    val restWeekUsed: Long = 0L,
    // --- stage 1 D: bet on yourself, mood, sounds, shop goal, photo reminder ---
    /** Gold staked at day close on showing up on [betDay]. 0 = no open bet. Already taken from [gold]. */
    val betStake: Int = 0,
    /** epochDay the open bet is about (tomorrow at the time it was placed). */
    val betDay: Long = 0L,
    /** "קופת המפלצת": gold of lost bets. Released back to the player by a full week. */
    val monsterPot: Int = 0,
    /** Today's mood 1..5 (0 = not chosen) and the epochDay it was chosen on. */
    val mood: Int = 0,
    val moodDay: Long = 0L,
    val soundOn: Boolean = true,
    /** Reward id marked as "יעד" in the shop; 0 = none. */
    val rewardGoalId: Long = 0L,
    /** yyyy*12+month of the last monthly progress-photo reminder; 0 = never. */
    val photoNudgeMonth: Int = 0,
    // --- stage 1 B: modes, morning roll, surprise events, level gates ---
    /** Finished gym sessions since the last surprise event fired. */
    val sessionsSinceEvent: Int = 0,
    /** Session the surprise-event decision belongs to; 0 = none yet. */
    val eventPlanSessionId: Long = 0L,
    /** Accessory set number (1-based) after which the event fires; -1 = no event in that session; 0 = already fired. */
    val eventPlanSetNo: Int = -1,
    /** Surprise events accepted / completed, ever (for badges). */
    val eventsAccepted: Int = 0,
    val eventsCompleted: Int = 0,
    /** The one-time extra shield of level 5 was already given. */
    val level5ShieldGiven: Boolean = false,
    /** epochDay on which a rolled "joker" was turned into a mode of the player's choice; 0 = never. */
    val jokerPickDay: Long = 0L,
    /** Session in which the "surprise" mode already swapped its exercise; 0 = none. */
    val surpriseSwapSessionId: Long = 0L,
    // --- stage 1 E: jokers, focus kcal suggestion, tree max levels, monthly badges ---
    /** Jokers found in chests, waiting to be used on the morning-roll screen (max 3). */
    val jokers: Int = 0,
    /** Focus name the calorie-goal suggestion was already offered for; "" = never (first value is recorded silently). */
    val kcalAskedFocus: String = "",
    /** Highest level ever reached per tree branch, "CHEST:2,BACK:1". Branch levels never go down. */
    val branchMaxCsv: String = "",
    /** "העץ מתחיל ברמה 4": branch progress counts only sessions with id > this (-1 = not reached yet). */
    val treeStartSessionId: Long = -1L,
    /** epochDay the tree marker was set (-1 = not set). Informational, for backups and docs. */
    val treeStartDay: Long = -1L,
    /** Monthly badges and the month they belong to, "badgeId:yyyy*12+month,...". */
    val seasonalMonthsCsv: String = "",
    // --- stage 2 H: v2 game ideas ---
    /** "אימון מסופר": sessionId + U+001F + title + U+001F + lines joined by '\n', entries joined by U+001E. */
    val storyLog: String = "",
    /** "כישורים מיוחדים": chosen SkillId name ("" = none), the day it was chosen, the day the active skill was last used. */
    val skillId: String = "",
    val skillChosenDay: Long = 0L,
    val skillUsedDay: Long = 0L,
    /** Badge id whose hint "סקרנות" revealed last; "" = none. */
    val skillHintBadgeId: String = "",
    /** "אויב חוזר": boss defeats, "bossId:count:lastWeek,..." (only grows). */
    val bossDefeatsCsv: String = "",
    /** "מקצוע לשמביולוק": chosen ProfessionId name ("" = none) and the day it was chosen (one change per season). */
    val professionId: String = "",
    val professionChosenDay: Long = 0L,
    // --- stage 2 G: note to the coach, Gemini morning line ---
    /** Active limits from "הודעה לג'ים ברו" as JSON (data/ai/CoachNotes); "" = none. */
    val coachConstraintsJson: String = "",
    /** Last epochDay (inclusive) the limits hold; 0 = none. */
    val coachConstraintsUntil: Long = 0L,
    // --- coach season: deload week, resting HR, weekly challenge, seasonal event boss ---
    /** Sunday (epochDay) of the accepted deload week; 0 = none. Active while today is in [start, start + 6]. */
    val deloadWeekStart: Long = 0L,
    /** Saturday (epochDay) that ended the last accepted deload week; 0 = never. */
    val deloadLastEnd: Long = 0L,
    /** The deload proposal stays hidden until this epochDay ("לא עכשיו" = 7 days); 0 = not snoozed. */
    val deloadSnoozeUntil: Long = 0L,
    /** Daily resting heart rate from the watch, "epochDay:bpm;..." (last ~40 days, data/health/RestingHr). */
    val restingHrCsv: String = "",
    /** Sunday (epochDay) of the week whose challenge is [challengeId]; 0 = none assigned yet. */
    val challengeWeek: Long = 0L,
    /** Weekly challenge id (domain/game/WeeklyChallenges); "" = none. */
    val challengeId: String = "",
    /** Sunday (epochDay) of the week whose challenge reward was paid; 0 = never. */
    val challengeDoneWeek: Long = 0L,
    /** Weekly challenges completed, ever (badges at 4 and 10). Only grows. */
    val challengesCompleted: Int = 0,
    /** Seasonal event boss, "eventId:year|hp|maxHp|defeated(0/1)"; "" = none (data/repo/EventBoss). */
    val eventBossState: String = "",
    /** epochDay of the cached Gemini morning line; 0 = never. Local only. */
    val aiMorningDay: Long = 0L,
    /** Today's Gemini morning line ("" = use the regular Voice line). Local only. */
    val aiMorningLine: String = "",
    // --- stage 2 F: nutrition goals and meal-photo game hooks ---
    /** Daily macro goals in grams; 0 = automatic (see domain/food/MacroGoals). */
    val proteinGoal: Int = 0,
    val carbsGoal: Int = 0,
    val fatGoal: Int = 0,
    /** epochDay whose "+15 צילום כל הארוחות" bonus was already given; 0 = never. */
    val lastPhotoDayBonus: Long = 0L,
    /** epochDay whose "ציד קלוריות" reward was already given; 0 = never. */
    val lastCalorieHuntDay: Long = 0L,
    /** Photo meals saved with total kcal within 10% of the AI estimate (badge right_guess at 5). */
    val goodGuessMeals: Int = 0,
    /** "ארוחות קבועות": saved meals as a JSON array (data/repo/SavedMeals); "" = none. */
    val savedMealsJson: String = "",
    /** Normalized meal descriptions the "לשמור כארוחה קבועה?" offer was already shown for, joined by '\n'. */
    val savedMealAskedCsv: String = "",
    // --- energy calculator (domain/energy/EnergyModel, docs/energy_model.md) ---
    /** Age in years; 0 = unknown (the energy screen asks on first open). */
    val age: Int = 0,
    /** "M" | "F"; "" = unknown (treated as "M" until answered). */
    val sex: String = "",
    /** Body fat %; 0 = unknown (then Mifflin-St Jeor instead of Katch-McArdle). */
    val bodyFatPct: Float = 0f,
    /** ActivityLevel ordinal of non-exercise life: 0 sedentary, 1 light, 2 moderate, 3 active. */
    val activityLevel: Int = 0,
    /** Weight-loss rate in hundredths of a percent of body weight per week (25, 50, 75, 100). */
    val goalRateX100: Int = 50,
    /** true = [kcalGoal] is computed by the energy model (kept in sync by EnergyData.syncAutoGoal). */
    val kcalAuto: Boolean = false,
    /** true = the automatic goal uses the real-data (adaptive) TDEE when it is reliable. */
    val useAdaptiveTdee: Boolean = false,
    /** "הכיוון": -1 automatic (from goal weight + focus), 0 cut, 1 maintain, 2 lean gain (EnergyModel.GoalDirection). */
    val goalDirection: Int = -1,
    /** Lean-gain rate in hundredths of a percent of body weight per week (25 or 50). */
    val gainRateX100: Int = 25,
    // --- coach difficulty and the running coach (domain/coach/Difficulty, domain/coach/RunPlan, data/repo/RunCoach) ---
    /** "EASY" | "NORMAL" | "HARD": the coach-screen setting (a note to the coach may override it for a few days). */
    val coachDifficulty: String = "NORMAL",
    /** Next session of the 9-week run plan: week 1..9 (10 = plan finished) and session 1..3. */
    val runWeek: Int = 1,
    val runDay: Int = 1,
    /** epochDay of the last run (plan or watch); the plan never asks for two run days in a row. 0 = never. */
    val runLastDay: Long = 0L,
    /** Session id of the last run that moved the plan (or repeated it); 0 = none. */
    val runLastSessionId: Long = 0L,
    /** What the last plan run did: "advanced" | "repeat_hard" | "repeat_hr" | "short" | "watch" | "" (none). */
    val runLastResult: String = "",
    /** Plan run whose watch heart rate is still awaited for the "too high -> repeat the week" check; 0 = none. */
    val runHrCheckSessionId: Long = 0L,
    /** Highest watch-imported RUN session id already considered for the plan; 0 = not initialised. */
    val runWatchCheckedId: Long = 0L,
    // --- last night's sleep (coach / morning roll slider; data/health/WatchSleep) ---
    /** epochDay the sleep below belongs to; any other day = not set yet (the slider shows its default). */
    val sleepDay: Long = 0L,
    /** Hours on the slider (half-hour steps, 3..10), from the watch or set by hand. */
    val sleepHoursSet: Float = 0f,
    /** true = he moved the slider himself that day: the watch never overwrites it. */
    val sleepManual: Boolean = false,
    // --- AI provider (data/ai/AiClient) ---
    /** "auto" (Claude when its key exists, else Gemini) | "claude" | "gemini". The other one is the fallback. */
    val aiProvider: String = "auto",
    // --- "אימון בבית" (domain/coach/HomeGear): what he has at home, remembered between sessions ---
    /** Comma-separated HomeGear keys he has at home ("band,dumbbell,mat"). Body weight is always implied. */
    val homeGearCsv: String = "",
    /** Heaviest dumbbell he has (kg per hand, adjustable or a fixed pair); 0 = not told. */
    val homeDumbbellMaxKg: Float = 0f,
    /** true = one fixed pair of [homeDumbbellMaxKg] (not adjustable dumbbells). */
    val homeDumbbellFixed: Boolean = false,
    /** Kettlebell weight in kg; 0 = not told. */
    val homeKettlebellKg: Float = 0f,
    /** "כמה זמן יש לך?": 20 / 30 / 45 / 60 minutes; 0 = no limit. */
    val homeMinutes: Int = 0,
    /** HomeGear focus key: "FULL" | "UPPER" | "LOWER" | "CORE". */
    val homeFocus: String = "FULL",
    // --- multi-user onboarding: character, name, body-stage calibration (domain/game/BodyStage.calibrated) ---
    /** Player's name from onboarding; "" = not given. */
    val playerName: String = "",
    /** "M" = שמביולוק, "F" = שמביולוקה (domain/game/CharacterNames). */
    val characterVariant: String = "M",
    /** How the app addresses the player: "M" = לשון זכר, "F" = לשון נקבה (domain/game/Addr). */
    val userGender: String = "M",
    /** Body stage (1..10, may be fractional) pinned at [stageAnchorWeight]; 0 = no calibration yet (legacy mapping). */
    val stageAnchorStage: Float = 0f,
    /** Weight (kg) the anchor stage is pinned at; 0 = none. */
    val stageAnchorWeight: Float = 0f,
    /** Training days per week chosen in onboarding (informational for the coach); 0 = not chosen. */
    val trainingDaysPerWeek: Int = 0,
    /** The user said in onboarding that he has a smartwatch (the watch is connected later in settings). */
    val hasWatch: Boolean = false
)

class Prefs(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val mapStartDone = intPreferencesKey("mapStartDone")
        val economyFix1Done = booleanPreferencesKey("economyFix1Done")
        val height = intPreferencesKey("height")
        val startW = floatPreferencesKey("startW")
        val goalW = floatPreferencesKey("goalW")
        val focus = stringPreferencesKey("focus")
        val xp = intPreferencesKey("xp")
        val gold = intPreferencesKey("gold")
        val streak = intPreferencesKey("streak")
        val bestStreak = intPreferencesKey("bestStreak")
        val shields = intPreferencesKey("shields")
        val lastActiveDay = longPreferencesKey("lastActiveDay")
        val lastRollDay = longPreferencesKey("lastRollDay")
        val rollMode = stringPreferencesKey("rollMode")
        val rollGoal = stringPreferencesKey("rollGoal")
        val rollBonus = stringPreferencesKey("rollBonus")
        val plannedType = stringPreferencesKey("plannedType")
        val plannedHour = intPreferencesKey("plannedHour")
        val quietFrom = intPreferencesKey("quietFrom")
        val quietTo = intPreferencesKey("quietTo")
        val luckChain = intPreferencesKey("luckChain")
        val lifetimeKg = floatPreferencesKey("lifetimeKg")
        val kcalGoal = intPreferencesKey("kcalGoal")
        val exOverrides = stringPreferencesKey("exOverrides")
        val exOverridesBlock = intPreferencesKey("exOverridesBlock")
        val restBrokenSessionId = longPreferencesKey("restBrokenSessionId")
        val hardBattleWeek = longPreferencesKey("hardBattleWeek")
        val sealedChests = stringPreferencesKey("sealedChests")
        val chestsOpened = intPreferencesKey("chestsOpened")
        val battleLog = stringPreferencesKey("battleLog")
        val mapBonusStations = intPreferencesKey("mapBonusStations")
        val stabilityShieldMonth = intPreferencesKey("stabilityShieldMonth")
        val bossesDefeatedTotal = intPreferencesKey("bossesDefeatedTotal")
        val emergencyMonth = intPreferencesKey("emergencyMonth")
        val emergencyUsed = intPreferencesKey("emergencyUsed")
        val lastCloseDay = longPreferencesKey("lastCloseDay")
        val reminderSentDay = longPreferencesKey("reminderSentDay")
        val closeNudgeDay = longPreferencesKey("closeNudgeDay")
        val restWeekUsed = longPreferencesKey("restWeekUsed")
        val betStake = intPreferencesKey("betStake")
        val betDay = longPreferencesKey("betDay")
        val monsterPot = intPreferencesKey("monsterPot")
        val mood = intPreferencesKey("mood")
        val moodDay = longPreferencesKey("moodDay")
        val soundOn = booleanPreferencesKey("soundOn")
        val rewardGoalId = longPreferencesKey("rewardGoalId")
        val photoNudgeMonth = intPreferencesKey("photoNudgeMonth")
        val sessionsSinceEvent = intPreferencesKey("sessionsSinceEvent")
        val eventPlanSessionId = longPreferencesKey("eventPlanSessionId")
        val eventPlanSetNo = intPreferencesKey("eventPlanSetNo")
        val eventsAccepted = intPreferencesKey("eventsAccepted")
        val eventsCompleted = intPreferencesKey("eventsCompleted")
        val level5ShieldGiven = booleanPreferencesKey("level5ShieldGiven")
        val jokerPickDay = longPreferencesKey("jokerPickDay")
        val surpriseSwapSessionId = longPreferencesKey("surpriseSwapSessionId")
        val jokers = intPreferencesKey("jokers")
        val kcalAskedFocus = stringPreferencesKey("kcalAskedFocus")
        val branchMaxCsv = stringPreferencesKey("branchMaxCsv")
        val treeStartSessionId = longPreferencesKey("treeStartSessionId")
        val treeStartDay = longPreferencesKey("treeStartDay")
        val seasonalMonthsCsv = stringPreferencesKey("seasonalMonthsCsv")
        val storyLog = stringPreferencesKey("storyLog")
        val skillId = stringPreferencesKey("skillId")
        val skillChosenDay = longPreferencesKey("skillChosenDay")
        val skillUsedDay = longPreferencesKey("skillUsedDay")
        val skillHintBadgeId = stringPreferencesKey("skillHintBadgeId")
        val bossDefeatsCsv = stringPreferencesKey("bossDefeatsCsv")
        val professionId = stringPreferencesKey("professionId")
        val professionChosenDay = longPreferencesKey("professionChosenDay")
        val coachConstraintsJson = stringPreferencesKey("coachConstraintsJson")
        val coachConstraintsUntil = longPreferencesKey("coachConstraintsUntil")
        val deloadWeekStart = longPreferencesKey("deloadWeekStart")
        val deloadLastEnd = longPreferencesKey("deloadLastEnd")
        val deloadSnoozeUntil = longPreferencesKey("deloadSnoozeUntil")
        val restingHrCsv = stringPreferencesKey("restingHrCsv")
        val challengeWeek = longPreferencesKey("challengeWeek")
        val challengeId = stringPreferencesKey("challengeId")
        val challengeDoneWeek = longPreferencesKey("challengeDoneWeek")
        val challengesCompleted = intPreferencesKey("challengesCompleted")
        val eventBossState = stringPreferencesKey("eventBossState")
        val aiMorningDay = longPreferencesKey("aiMorningDay")
        val aiMorningLine = stringPreferencesKey("aiMorningLine")
        val proteinGoal = intPreferencesKey("proteinGoal")
        val carbsGoal = intPreferencesKey("carbsGoal")
        val fatGoal = intPreferencesKey("fatGoal")
        val lastPhotoDayBonus = longPreferencesKey("lastPhotoDayBonus")
        val lastCalorieHuntDay = longPreferencesKey("lastCalorieHuntDay")
        val goodGuessMeals = intPreferencesKey("goodGuessMeals")
        val savedMealsJson = stringPreferencesKey("savedMealsJson")
        val savedMealAskedCsv = stringPreferencesKey("savedMealAskedCsv")
        val age = intPreferencesKey("age")
        val sex = stringPreferencesKey("sex")
        val bodyFatPct = floatPreferencesKey("bodyFatPct")
        val activityLevel = intPreferencesKey("activityLevel")
        val goalRateX100 = intPreferencesKey("goalRateX100")
        val kcalAuto = booleanPreferencesKey("kcalAuto")
        val useAdaptiveTdee = booleanPreferencesKey("useAdaptiveTdee")
        val goalDirection = intPreferencesKey("goalDirection")
        val gainRateX100 = intPreferencesKey("gainRateX100")
        val coachDifficulty = stringPreferencesKey("coachDifficulty")
        val runWeek = intPreferencesKey("runWeek")
        val runDay = intPreferencesKey("runDay")
        val runLastDay = longPreferencesKey("runLastDay")
        val runLastSessionId = longPreferencesKey("runLastSessionId")
        val runLastResult = stringPreferencesKey("runLastResult")
        val runHrCheckSessionId = longPreferencesKey("runHrCheckSessionId")
        val runWatchCheckedId = longPreferencesKey("runWatchCheckedId")
        val sleepDay = longPreferencesKey("sleepDay")
        val sleepHoursSet = floatPreferencesKey("sleepHoursSet")
        val sleepManual = booleanPreferencesKey("sleepManual")
        val aiProvider = stringPreferencesKey("aiProvider")
        val homeGearCsv = stringPreferencesKey("homeGearCsv")
        val homeDumbbellMaxKg = floatPreferencesKey("homeDumbbellMaxKg")
        val homeDumbbellFixed = booleanPreferencesKey("homeDumbbellFixed")
        val homeKettlebellKg = floatPreferencesKey("homeKettlebellKg")
        val homeMinutes = intPreferencesKey("homeMinutes")
        val homeFocus = stringPreferencesKey("homeFocus")
        val playerName = stringPreferencesKey("playerName")
        val characterVariant = stringPreferencesKey("characterVariant")
        val userGender = stringPreferencesKey("userGender")
        val stageAnchorStage = floatPreferencesKey("stageAnchorStage")
        val stageAnchorWeight = floatPreferencesKey("stageAnchorWeight")
        val trainingDaysPerWeek = intPreferencesKey("trainingDaysPerWeek")
        val hasWatch = booleanPreferencesKey("hasWatch")
    }

    val profile: Flow<Profile> = context.store.data.map { p -> toProfile(p) }

    private fun toProfile(p: Preferences): Profile =
        Profile(
            onboarded = p[K.onboarded] ?: false,
            mapStartDone = p[K.mapStartDone] ?: -1,
            economyFix1Done = p[K.economyFix1Done] ?: false,
            heightCm = p[K.height] ?: 185,
            startWeightKg = p[K.startW] ?: 105f,
            goalWeightKg = p[K.goalW] ?: 90f,
            focus = p[K.focus]?.let { runCatching { Focus.valueOf(it) }.getOrNull() } ?: Focus.LOSE_WEIGHT,
            xp = p[K.xp] ?: 0,
            gold = p[K.gold] ?: 0,
            streak = p[K.streak] ?: 0,
            bestStreak = p[K.bestStreak] ?: 0,
            shields = p[K.shields] ?: 1,
            lastActiveDay = p[K.lastActiveDay] ?: 0L,
            lastRollDay = p[K.lastRollDay] ?: 0L,
            rollModeId = p[K.rollMode] ?: "",
            rollGoal = p[K.rollGoal] ?: "",
            rollBonus = p[K.rollBonus] ?: "",
            plannedTomorrowType = p[K.plannedType] ?: "",
            plannedTomorrowHour = p[K.plannedHour] ?: 7,
            quietFrom = p[K.quietFrom] ?: 23,
            quietTo = p[K.quietTo] ?: 7,
            luckChain = p[K.luckChain] ?: 0,
            lifetimeKg = (p[K.lifetimeKg] ?: 0f).toDouble(),
            kcalGoal = p[K.kcalGoal] ?: 2200,
            exerciseOverridesCsv = p[K.exOverrides] ?: "",
            exerciseOverridesBlock = p[K.exOverridesBlock] ?: -1,
            restBrokenSessionId = p[K.restBrokenSessionId] ?: -1L,
            hardBattleWeek = p[K.hardBattleWeek] ?: -1L,
            sealedChests = p[K.sealedChests] ?: "",
            chestsOpened = p[K.chestsOpened] ?: 0,
            battleLog = p[K.battleLog] ?: "",
            mapBonusStations = p[K.mapBonusStations] ?: 0,
            stabilityShieldMonth = p[K.stabilityShieldMonth] ?: 0,
            bossesDefeatedTotal = p[K.bossesDefeatedTotal] ?: 0,
            emergencyMonth = p[K.emergencyMonth] ?: 0,
            emergencyUsed = p[K.emergencyUsed] ?: 0,
            lastCloseDay = p[K.lastCloseDay] ?: 0L,
            reminderSentDay = p[K.reminderSentDay] ?: 0L,
            closeNudgeDay = p[K.closeNudgeDay] ?: 0L,
            restWeekUsed = p[K.restWeekUsed] ?: 0L,
            betStake = p[K.betStake] ?: 0,
            betDay = p[K.betDay] ?: 0L,
            monsterPot = p[K.monsterPot] ?: 0,
            mood = p[K.mood] ?: 0,
            moodDay = p[K.moodDay] ?: 0L,
            soundOn = p[K.soundOn] ?: true,
            rewardGoalId = p[K.rewardGoalId] ?: 0L,
            photoNudgeMonth = p[K.photoNudgeMonth] ?: 0,
            sessionsSinceEvent = p[K.sessionsSinceEvent] ?: 0,
            eventPlanSessionId = p[K.eventPlanSessionId] ?: 0L,
            eventPlanSetNo = p[K.eventPlanSetNo] ?: -1,
            eventsAccepted = p[K.eventsAccepted] ?: 0,
            eventsCompleted = p[K.eventsCompleted] ?: 0,
            level5ShieldGiven = p[K.level5ShieldGiven] ?: false,
            jokerPickDay = p[K.jokerPickDay] ?: 0L,
            surpriseSwapSessionId = p[K.surpriseSwapSessionId] ?: 0L,
            jokers = p[K.jokers] ?: 0,
            kcalAskedFocus = p[K.kcalAskedFocus] ?: "",
            branchMaxCsv = p[K.branchMaxCsv] ?: "",
            treeStartSessionId = p[K.treeStartSessionId] ?: -1L,
            treeStartDay = p[K.treeStartDay] ?: -1L,
            seasonalMonthsCsv = p[K.seasonalMonthsCsv] ?: "",
            storyLog = p[K.storyLog] ?: "",
            skillId = p[K.skillId] ?: "",
            skillChosenDay = p[K.skillChosenDay] ?: 0L,
            skillUsedDay = p[K.skillUsedDay] ?: 0L,
            skillHintBadgeId = p[K.skillHintBadgeId] ?: "",
            bossDefeatsCsv = p[K.bossDefeatsCsv] ?: "",
            professionId = p[K.professionId] ?: "",
            professionChosenDay = p[K.professionChosenDay] ?: 0L,
            coachConstraintsJson = p[K.coachConstraintsJson] ?: "",
            coachConstraintsUntil = p[K.coachConstraintsUntil] ?: 0L,
            deloadWeekStart = p[K.deloadWeekStart] ?: 0L,
            deloadLastEnd = p[K.deloadLastEnd] ?: 0L,
            deloadSnoozeUntil = p[K.deloadSnoozeUntil] ?: 0L,
            restingHrCsv = p[K.restingHrCsv] ?: "",
            challengeWeek = p[K.challengeWeek] ?: 0L,
            challengeId = p[K.challengeId] ?: "",
            challengeDoneWeek = p[K.challengeDoneWeek] ?: 0L,
            challengesCompleted = p[K.challengesCompleted] ?: 0,
            eventBossState = p[K.eventBossState] ?: "",
            aiMorningDay = p[K.aiMorningDay] ?: 0L,
            aiMorningLine = p[K.aiMorningLine] ?: "",
            proteinGoal = p[K.proteinGoal] ?: 0,
            carbsGoal = p[K.carbsGoal] ?: 0,
            fatGoal = p[K.fatGoal] ?: 0,
            lastPhotoDayBonus = p[K.lastPhotoDayBonus] ?: 0L,
            lastCalorieHuntDay = p[K.lastCalorieHuntDay] ?: 0L,
            goodGuessMeals = p[K.goodGuessMeals] ?: 0,
            savedMealsJson = p[K.savedMealsJson] ?: "",
            savedMealAskedCsv = p[K.savedMealAskedCsv] ?: "",
            age = p[K.age] ?: 0,
            sex = p[K.sex] ?: "",
            bodyFatPct = p[K.bodyFatPct] ?: 0f,
            activityLevel = p[K.activityLevel] ?: 0,
            goalRateX100 = p[K.goalRateX100] ?: 50,
            kcalAuto = p[K.kcalAuto] ?: false,
            useAdaptiveTdee = p[K.useAdaptiveTdee] ?: false,
            goalDirection = p[K.goalDirection] ?: -1,
            gainRateX100 = p[K.gainRateX100] ?: 25,
            coachDifficulty = p[K.coachDifficulty] ?: "NORMAL",
            runWeek = p[K.runWeek] ?: 1,
            runDay = p[K.runDay] ?: 1,
            runLastDay = p[K.runLastDay] ?: 0L,
            runLastSessionId = p[K.runLastSessionId] ?: 0L,
            runLastResult = p[K.runLastResult] ?: "",
            runHrCheckSessionId = p[K.runHrCheckSessionId] ?: 0L,
            runWatchCheckedId = p[K.runWatchCheckedId] ?: 0L,
            sleepDay = p[K.sleepDay] ?: 0L,
            sleepHoursSet = p[K.sleepHoursSet] ?: 0f,
            sleepManual = p[K.sleepManual] ?: false,
            aiProvider = p[K.aiProvider] ?: "auto",
            homeGearCsv = p[K.homeGearCsv] ?: "",
            homeDumbbellMaxKg = p[K.homeDumbbellMaxKg] ?: 0f,
            homeDumbbellFixed = p[K.homeDumbbellFixed] ?: false,
            homeKettlebellKg = p[K.homeKettlebellKg] ?: 0f,
            homeMinutes = p[K.homeMinutes] ?: 0,
            homeFocus = p[K.homeFocus] ?: "FULL",
            playerName = p[K.playerName] ?: "",
            characterVariant = if (p[K.characterVariant] == "F") "F" else "M",
            userGender = if (p[K.userGender] == "F") "F" else "M",
            stageAnchorStage = p[K.stageAnchorStage] ?: 0f,
            stageAnchorWeight = p[K.stageAnchorWeight] ?: 0f,
            trainingDaysPerWeek = p[K.trainingDaysPerWeek] ?: 0,
            hasWatch = p[K.hasWatch] ?: false
        )

    suspend fun current(): Profile = profile.first()

    suspend fun update(block: (Profile) -> Profile) {
        context.store.edit { p ->
            // Read the current profile from the transaction's own snapshot (never from the flow inside edit).
            val cur = toProfile(p)
            val n = block(cur)
            p[K.onboarded] = n.onboarded
            p[K.mapStartDone] = n.mapStartDone
            p[K.economyFix1Done] = n.economyFix1Done
            p[K.height] = n.heightCm
            p[K.startW] = n.startWeightKg
            p[K.goalW] = n.goalWeightKg
            p[K.focus] = n.focus.name
            p[K.xp] = n.xp
            p[K.gold] = n.gold
            p[K.streak] = n.streak
            p[K.bestStreak] = n.bestStreak
            p[K.shields] = n.shields
            p[K.lastActiveDay] = n.lastActiveDay
            p[K.lastRollDay] = n.lastRollDay
            p[K.rollMode] = n.rollModeId
            p[K.rollGoal] = n.rollGoal
            p[K.rollBonus] = n.rollBonus
            p[K.plannedType] = n.plannedTomorrowType
            p[K.plannedHour] = n.plannedTomorrowHour
            p[K.quietFrom] = n.quietFrom
            p[K.quietTo] = n.quietTo
            p[K.luckChain] = n.luckChain
            p[K.lifetimeKg] = n.lifetimeKg.toFloat()
            p[K.kcalGoal] = n.kcalGoal
            p[K.exOverrides] = n.exerciseOverridesCsv
            p[K.exOverridesBlock] = n.exerciseOverridesBlock
            p[K.restBrokenSessionId] = n.restBrokenSessionId
            p[K.hardBattleWeek] = n.hardBattleWeek
            p[K.sealedChests] = n.sealedChests
            p[K.chestsOpened] = n.chestsOpened
            p[K.battleLog] = n.battleLog
            p[K.mapBonusStations] = n.mapBonusStations
            p[K.stabilityShieldMonth] = n.stabilityShieldMonth
            p[K.bossesDefeatedTotal] = n.bossesDefeatedTotal
            p[K.emergencyMonth] = n.emergencyMonth
            p[K.emergencyUsed] = n.emergencyUsed
            p[K.lastCloseDay] = n.lastCloseDay
            p[K.reminderSentDay] = n.reminderSentDay
            p[K.closeNudgeDay] = n.closeNudgeDay
            p[K.restWeekUsed] = n.restWeekUsed
            p[K.betStake] = n.betStake
            p[K.betDay] = n.betDay
            p[K.monsterPot] = n.monsterPot
            p[K.mood] = n.mood
            p[K.moodDay] = n.moodDay
            p[K.soundOn] = n.soundOn
            p[K.rewardGoalId] = n.rewardGoalId
            p[K.photoNudgeMonth] = n.photoNudgeMonth
            p[K.sessionsSinceEvent] = n.sessionsSinceEvent
            p[K.eventPlanSessionId] = n.eventPlanSessionId
            p[K.eventPlanSetNo] = n.eventPlanSetNo
            p[K.eventsAccepted] = n.eventsAccepted
            p[K.eventsCompleted] = n.eventsCompleted
            p[K.level5ShieldGiven] = n.level5ShieldGiven
            p[K.jokerPickDay] = n.jokerPickDay
            p[K.surpriseSwapSessionId] = n.surpriseSwapSessionId
            p[K.jokers] = n.jokers
            p[K.kcalAskedFocus] = n.kcalAskedFocus
            p[K.branchMaxCsv] = n.branchMaxCsv
            p[K.treeStartSessionId] = n.treeStartSessionId
            p[K.treeStartDay] = n.treeStartDay
            p[K.seasonalMonthsCsv] = n.seasonalMonthsCsv
            p[K.storyLog] = n.storyLog
            p[K.skillId] = n.skillId
            p[K.skillChosenDay] = n.skillChosenDay
            p[K.skillUsedDay] = n.skillUsedDay
            p[K.skillHintBadgeId] = n.skillHintBadgeId
            p[K.bossDefeatsCsv] = n.bossDefeatsCsv
            p[K.professionId] = n.professionId
            p[K.professionChosenDay] = n.professionChosenDay
            p[K.coachConstraintsJson] = n.coachConstraintsJson
            p[K.coachConstraintsUntil] = n.coachConstraintsUntil
            p[K.deloadWeekStart] = n.deloadWeekStart
            p[K.deloadLastEnd] = n.deloadLastEnd
            p[K.deloadSnoozeUntil] = n.deloadSnoozeUntil
            p[K.restingHrCsv] = n.restingHrCsv
            p[K.challengeWeek] = n.challengeWeek
            p[K.challengeId] = n.challengeId
            p[K.challengeDoneWeek] = n.challengeDoneWeek
            p[K.challengesCompleted] = n.challengesCompleted
            p[K.eventBossState] = n.eventBossState
            p[K.aiMorningDay] = n.aiMorningDay
            p[K.aiMorningLine] = n.aiMorningLine
            p[K.proteinGoal] = n.proteinGoal
            p[K.carbsGoal] = n.carbsGoal
            p[K.fatGoal] = n.fatGoal
            p[K.lastPhotoDayBonus] = n.lastPhotoDayBonus
            p[K.lastCalorieHuntDay] = n.lastCalorieHuntDay
            p[K.goodGuessMeals] = n.goodGuessMeals
            p[K.savedMealsJson] = n.savedMealsJson
            p[K.savedMealAskedCsv] = n.savedMealAskedCsv
            p[K.age] = n.age
            p[K.sex] = n.sex
            p[K.bodyFatPct] = n.bodyFatPct
            p[K.activityLevel] = n.activityLevel
            p[K.goalRateX100] = n.goalRateX100
            p[K.kcalAuto] = n.kcalAuto
            p[K.useAdaptiveTdee] = n.useAdaptiveTdee
            p[K.goalDirection] = n.goalDirection
            p[K.gainRateX100] = n.gainRateX100
            p[K.coachDifficulty] = n.coachDifficulty
            p[K.runWeek] = n.runWeek
            p[K.runDay] = n.runDay
            p[K.runLastDay] = n.runLastDay
            p[K.runLastSessionId] = n.runLastSessionId
            p[K.runLastResult] = n.runLastResult
            p[K.runHrCheckSessionId] = n.runHrCheckSessionId
            p[K.runWatchCheckedId] = n.runWatchCheckedId
            p[K.sleepDay] = n.sleepDay
            p[K.sleepHoursSet] = n.sleepHoursSet
            p[K.sleepManual] = n.sleepManual
            p[K.aiProvider] = n.aiProvider
            p[K.homeGearCsv] = n.homeGearCsv
            p[K.homeDumbbellMaxKg] = n.homeDumbbellMaxKg
            p[K.homeDumbbellFixed] = n.homeDumbbellFixed
            p[K.homeKettlebellKg] = n.homeKettlebellKg
            p[K.homeMinutes] = n.homeMinutes
            p[K.homeFocus] = n.homeFocus
            p[K.playerName] = n.playerName
            p[K.characterVariant] = if (n.characterVariant == "F") "F" else "M"
            p[K.userGender] = if (n.userGender == "F") "F" else "M"
            p[K.stageAnchorStage] = n.stageAnchorStage
            p[K.stageAnchorWeight] = n.stageAnchorWeight
            p[K.trainingDaysPerWeek] = n.trainingDaysPerWeek
            p[K.hasWatch] = n.hasWatch
        }
    }
}
