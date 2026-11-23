package com.tamir.hamasa.flows

/*
 * System flows (music, rest alerts, updates) on the JVM (Robolectric + Compose ui-test). Spotify and PackageInstaller
 * cannot run here, so their pure decision logic is tested instead; the screens around them are tapped for real.
 * Images: build/screenshots/flows/<flow>_<step>.png (record mode only).
 *
 *  1. musicMomentsWorkout   WorkoutMusic.decideFrom over a whole workout: playlist at the start, the entrance song
 *                           when the heavy set is next, back to the playlist after the heavy exercise, the last-set
 *                           song with one set left (the cardio finisher ignored), each moment once.
 *  2. musicHeavyFirst       heavy set is the first set: playlist + entrance song together; heavy set = the only set:
 *                           no second "last set" moment.
 *  3. musicSettingsModes    MusicSettingsCard (connected): order / loop options and the smart shuffle note;
 *                           tapping "לפי הסדר" / "שיר אחד" stores shuffle = false / repeat = track.
 *  4. restAlertsLockScreen  RestAlerts.show: a countdown notification (chronometer, public on the lock screen,
 *                           default-importance silent channel) + an alarm at the end; the alarm receiver removes the
 *                           notification and pings once (a late second ping for the same rest is refused);
 *                           cancel() removes the alarm.
 *  5. updatesNeverDuringWorkout  backgroundUpdate does nothing (not even a check) during a workout and within 10 min
 *                           after one; the periodic update work runs every 2 h on any network.
 *  6. updatingOverlayClears the "מתעדכן" screen goes away when the install fails or is cancelled.
 *  7. backgroundUpdateButton "אפשר עדכון ברקע" opens the system "stop optimizing battery" request.
 */

import androidx.compose.ui.test.performSemanticsAction
import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.music.MusicChoice
import com.tamir.hamasa.data.music.MusicPrefs
import com.tamir.hamasa.data.music.MusicSettings
import com.tamir.hamasa.data.music.MusicSlot
import com.tamir.hamasa.data.music.SpotifyAuth
import com.tamir.hamasa.data.music.WorkoutMusic
import com.tamir.hamasa.data.update.UpdateCenter
import com.tamir.hamasa.data.update.UpdateStore
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.ui.common.UpdatingOverlay
import com.tamir.hamasa.ui.settings.BackgroundUpdateRow
import com.tamir.hamasa.ui.settings.MusicSettingsCard
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.workout.RestAlarmReceiver
import com.tamir.hamasa.ui.workout.RestAlerts
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class SystemFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var flow: String = "flow"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val initialised = runCatching { AppGraph.workouts }.isSuccess
        if (!initialised) AppGraph.init(context)
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
            )
        }
        Addr.set("M")
        CharacterNames.set("M")
    }

    @After
    fun tearDown() {
        disconnectSpotify()
        runCatching { RestAlerts.resetForTest() }
        com.tamir.hamasa.data.NotifPolicy.resetForTest()
        Addr.set("M")
        CharacterNames.set("M")
    }

    // ================================================================== helpers (as in UserFlowsTest)

    /** One flow never runs longer than 4 minutes: a screen that never settles fails the flow instead of hanging CI. */
    private var flowStartedAt: Long = 0L
    private var lastBeat: Long = 0L

    private fun checkDeadline() {
        if (flowStartedAt == 0L) flowStartedAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (now - lastBeat > 15_000L) { lastBeat = now; println("[flow-beat] ${javaClass.simpleName} ${(now - flowStartedAt) / 1000}s") }
        if (System.currentTimeMillis() - flowStartedAt > 240_000L) {
            org.junit.Assert.fail("flow timed out after 4 minutes (screen never settled)")
        }
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) { content() }
                }
            }
        }
    }

    private fun settle(totalMs: Long) {
        val step = 100L
        var waited = 0L
        while (waited < totalMs) {
            Thread.sleep(step)
            waited += step
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(step)
            checkDeadline()
        }
    }

    private fun waitFor(step: String, timeoutMs: Long = 10_000L, cond: () -> Boolean) {
        println("[flow-step] wait: $step")
        val start = System.currentTimeMillis()
        while (true) {
            val ok = try { cond() } catch (t: Throwable) { false }
            if (ok) return
            if (System.currentTimeMillis() - start > timeoutMs) fail("[$flow] step '$step': not reached within ${timeoutMs} ms")
            Thread.sleep(50)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(50)
            checkDeadline()
        }
    }

    private fun count(matcher: SemanticsMatcher): Int =
        try { composeRule.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size } catch (t: Throwable) { lastCountError = t.toString().take(300); 0 }

    /** The last exception swallowed by [count] (shown when a wait fails: a hidden error looks like "nothing on screen"). */
    private var lastCountError: String = ""


    private fun exists(matcher: SemanticsMatcher): Boolean = count(matcher) > 0

    private fun node(step: String, matcher: SemanticsMatcher, index: Int = 0, timeoutMs: Long = 10_000L): SemanticsNodeInteraction {
        val start = System.currentTimeMillis()
        while (count(matcher) <= index) {
            if (System.currentTimeMillis() - start > timeoutMs) fail("[$flow] step '$step': node not found within ${timeoutMs} ms")
            settle(100)
        }
        val n = composeRule.onAllNodes(matcher)[index]
        // no performScrollTo: its scroll animation needs the test clock, which these flows advance by hand
        return n
    }

    private fun tap(step: String, matcher: SemanticsMatcher, index: Int = 0, settleMs: Long = 400L) {
        println("[flow-step] $step")
        val n = node(step, matcher, index)
        // the click action itself (works off screen and needs no gesture / animation)
        try { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) } catch (t: Throwable) {
            try { n.performClick() } catch (t2: Throwable) { fail("[$flow] step '$step': click failed: ${t2.message}") }
        }
        settle(settleMs)
    }

    private fun clickable(text: String): SemanticsMatcher = hasText(text) and hasClickAction()

    private fun shot(step: String) {
        settle(300)
        val file = File("build/screenshots/flows/${flow}_$step.png")
        file.parentFile?.mkdirs()
        runCatching {
            val roots = composeRule.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).size
            if (roots <= 1) composeRule.onRoot().captureRoboImage(file.path)
            else composeRule.onAllNodes(isRoot()).onLast().captureRoboImage(file.path)
        }
    }

    /** A plain list of result lines on screen, for the image of a pure-logic flow. */
    @Composable
    private fun Lines(lines: List<String>) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            lines.forEach { Text(it, color = com.tamir.hamasa.ui.theme.HColors.Parchment) }
        }
    }

    // ================================================================== music helpers

    private fun set(id: Long, ex: String, idx: Int, kg: Float, done: Boolean = false) =
        SetLogEntity(id = id, sessionId = SESSION, exerciseId = ex, setIndex = idx, targetReps = 8, targetWeight = kg, completed = done)

    private fun prefsAll() = MusicPrefs(
        enabled = true,
        playlist = MusicChoice("spotify:playlist:train", "אימון", 30),
        boss = MusicChoice("spotify:track:boss", "כניסה"),
        lastSet = MusicChoice("spotify:track:last", "סט אחרון")
    )

    /** One decision with in-memory marks (the real ones live in SharedPreferences). */
    private fun decide(sets: List<SetLogEntity>, prefs: MusicPrefs, marks: HashMap<String, Long>, compound: Set<String>, restActive: Boolean = false) =
        WorkoutMusic.decideFrom(
            SESSION, sets, prefs,
            isCompound = { it in compound },
            mark = { marks[it] ?: -1L },
            setMark = { k, v -> marks[k] = v },
            restActive = restActive
        )

    /** Writes a token file and reloads SpotifyAuth, so the settings card shows the connected state (no network). */
    private fun connectSpotifyOffline() {
        val f = File(context.noBackupFilesDir, "spotify_tokens.json")
        f.writeText(JSONObject().put("access", "a").put("refresh", "r").put("expiresAt", System.currentTimeMillis() + 3_600_000L).toString())
        setAuthLoaded(false)
        SpotifyAuth.ensureLoaded()
    }

    private fun disconnectSpotify() {
        runCatching { File(context.noBackupFilesDir, "spotify_tokens.json").delete() }
        runCatching {
            setAuthLoaded(false)
            setAuthField("refreshToken", "")
            setAuthField("accessToken", "")
            SpotifyAuth.ensureLoaded()
        }
    }

    private fun setAuthLoaded(v: Boolean) {
        val f = SpotifyAuth::class.java.getDeclaredField("loaded")
        f.isAccessible = true
        f.setBoolean(null, v)
    }

    private fun setAuthField(name: String, v: String) {
        val f = SpotifyAuth::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(null, v)
    }

    // ================================================================== 1. music moments over a workout

    @Test
    fun musicMomentsWorkout() {
        flow = "musicMomentsWorkout"
        val prefs = prefsAll()
        val marks = HashMap<String, Long>()
        val compound = setOf("bench", "squat")
        // bench 60 kg x2, squat 100 kg x2 (the heavy set: squat set 1), curl x2, the cardio finisher
        var sets = listOf(
            set(1, "bench", 0, 60f), set(2, "bench", 1, 60f),
            set(3, "squat", 0, 100f), set(4, "squat", 1, 100f),
            set(5, "curl", 0, 15f), set(6, "curl", 1, 15f),
            set(7, "walk", 0, 0f)
        )
        val log = ArrayList<String>()
        fun step(name: String, expected: WorkoutMusic.Action?) {
            val got = decide(sets, prefs, marks, compound)
            log.add("$name: ${got ?: "-"}")
            assertEquals("[$flow] $name", expected, got)
            // the same list again (a recomposition, reopening the screen): nothing new
            assertNull("[$flow] $name repeated", decide(sets, prefs, marks, compound))
        }
        fun done(id: Long) { sets = sets.map { if (it.id == id) it.copy(completed = true) else it } }

        step("start", WorkoutMusic.Action.PLAYLIST)
        done(1); step("bench 1 done", null)
        done(2); step("bench 2 done, heavy next", WorkoutMusic.Action.BOSS)
        done(3); step("squat 1 done", null)
        done(4); step("heavy exercise over", WorkoutMusic.Action.PLAYLIST)
        done(5); step("one main set left", WorkoutMusic.Action.LAST)
        done(6); step("only the finisher left", null)
        done(7); step("all done", null)

        show { Lines(log) }
        shot("decisions")
    }

    // ================================================================== 1a. streak: shield days count, the chain crosses weeks

    @Test
    fun streakShieldDaysAndWeeks() {
        flow = "streakShieldDaysAndWeeks"
        val sun = 20758L // Sunday 2026-11-01
        // Sunday -> Wednesday: Monday is the week's free rest day, Tuesday uses the shield; both count
        val a = com.tamir.hamasa.domain.game.StreakRules.onActivity(sun, sun + 3, 2, 1, 0L)
        assertEquals("[$flow] streak with a shield day", 2 + 1 + 1, a.streak)
        assertEquals("[$flow] one shield used", 1, a.shieldsUsed)
        // Friday -> Sunday across the week border: Saturday is free, nothing used, the chain goes on
        val b = com.tamir.hamasa.domain.game.StreakRules.onActivity(sun - 2, sun, 2, 1, 0L)
        assertEquals("[$flow] chain across the week", 3, b.streak)
        assertEquals("[$flow] no shield used", 0, b.shieldsUsed)
        // no shield left for the second missed day: broken
        val c = com.tamir.hamasa.domain.game.StreakRules.onActivity(sun, sun + 3, 2, 0, 0L)
        assertEquals("[$flow] broken without a shield", true, c.broken)
        show { Lines(listOf("shield day counts: ${a.streak}", "across weeks: ${b.streak}", "broken: ${c.broken}")) }
        shot("streak")
    }

    // ================================================================== 1b. last-set song waits for the rest

    @Test
    fun musicLastSetAfterRest() {
        flow = "musicLastSetAfterRest"
        val prefs = prefsAll()
        val marks = HashMap<String, Long>()
        val compound = setOf("bench")
        var sets = listOf(set(1, "bench", 0, 60f), set(2, "bench", 1, 60f), set(3, "curl", 0, 15f), set(4, "curl", 1, 15f))
        fun done(id: Long) { sets = sets.map { if (it.id == id) it.copy(completed = true) else it } }
        assertEquals("[$flow] start", WorkoutMusic.Action.PLAYLIST_THEN_BOSS, decide(sets, prefs, marks, compound))
        done(1); done(2); done(3)
        // the set before the last is done and the rest runs: no last-set song yet, and asking again changes nothing
        assertNull("[$flow] during the rest", decide(sets, prefs, marks, compound, restActive = true))
        assertNull("[$flow] during the rest, again", decide(sets, prefs, marks, compound, restActive = true))
        // the rest is over: the song starts, once
        assertEquals("[$flow] after the rest", WorkoutMusic.Action.LAST, decide(sets, prefs, marks, compound, restActive = false))
        assertNull("[$flow] only once", decide(sets, prefs, marks, compound, restActive = false))
        show { Lines(listOf("during the rest: none", "after the rest: last-set song")) }
        shot("decisions")
    }

    // ================================================================== 2. heavy set first / heavy set is the last

    @Test
    fun musicHeavyFirst() {
        flow = "musicHeavyFirst"
        val prefs = prefsAll()
        val compound = setOf("squat", "bench")
        val log = ArrayList<String>()

        // heavy set first: playlist and entrance song together, then no second entrance
        var marks = HashMap<String, Long>()
        var sets = listOf(set(1, "squat", 0, 100f), set(2, "squat", 1, 100f), set(3, "bench", 0, 60f), set(4, "bench", 1, 60f))
        val first = decide(sets, prefs, marks, compound)
        log.add("heavy first: $first")
        assertEquals("[$flow] heavy first", WorkoutMusic.Action.PLAYLIST_THEN_BOSS, first)
        sets = sets.map { if (it.id == 1L) it.copy(completed = true) else it }
        assertNull("[$flow] no second entrance", decide(sets, prefs, marks, compound))

        // without a workout playlist the entrance song alone
        marks = HashMap()
        val noPl = decide(sets.map { it.copy(completed = false) }, prefs.copy(playlist = null), marks, compound)
        log.add("no playlist: $noPl")
        assertEquals("[$flow] no playlist", WorkoutMusic.Action.BOSS, noPl)

        // the heavy set is the only main set: the entrance song stays, no "last set" moment afterwards
        marks = HashMap()
        var one = listOf(set(10, "bench", 0, 40f, done = true), set(11, "squat", 0, 100f), set(12, "walk", 0, 0f))
        val a = decide(one, prefs, marks, compound)
        log.add("heavy is last: $a")
        // start mark is set but a set is already done: the boss moment, never LAST
        assertEquals("[$flow] heavy is last", WorkoutMusic.Action.BOSS, a)
        assertNull("[$flow] no LAST after", decide(one, prefs, marks, compound))
        one = one.map { if (it.id == 11L) it.copy(completed = true) else it }
        assertNull("[$flow] finisher only", decide(one, prefs, marks, compound))

        // music off in the settings: the pure decision is never asked (decide() returns early); a cleared slot
        // means its moment simply does not happen
        marks = HashMap()
        val noBoss = decide(listOf(set(20, "squat", 0, 100f), set(21, "curl", 0, 10f)), prefs.copy(boss = null), marks, compound)
        log.add("no boss song: $noBoss")
        assertEquals("[$flow] no boss song", WorkoutMusic.Action.PLAYLIST, noBoss)

        show { Lines(log) }
        shot("decisions")
    }

    // ================================================================== 3. order / loop settings

    @Test
    fun musicSettingsModes() {
        flow = "musicSettingsModes"
        connectSpotifyOffline()
        assertTrue("[$flow] connected (token file)", SpotifyAuth.isConnected)
        MusicSettings.setEnabled(true)
        MusicSettings.setChoice(MusicSlot.PLAYLIST, MusicChoice("spotify:playlist:train", "אימון", 30))
        MusicSettings.setShuffle(true)
        MusicSettings.setRepeat("off")

        show { Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) { MusicSettingsCard() } }
        settle(500)
        node("order options", clickable("ערבוב"))
        node("in order option", clickable("לפי הסדר"))
        node("loop playlist option", clickable("כל הפלייליסט"))
        node("loop one song option", clickable("שיר אחד"))
        assertTrue("[$flow] smart shuffle note", exists(hasText("ערבוב חכם", substring = true)))
        shot("shuffle_on")

        tap("in order", clickable("לפי הסדר"))
        assertFalse("[$flow] shuffle off in state", MusicSettings.state.value.shuffle)
        assertFalse(
            "[$flow] shuffle off stored",
            context.getSharedPreferences("music", Context.MODE_PRIVATE).getBoolean("shuffle", true)
        )
        tap("loop one song", clickable("שיר אחד"))
        assertEquals("[$flow] repeat track in state", "track", MusicSettings.state.value.repeat)
        assertEquals(
            "[$flow] repeat track stored", "track",
            context.getSharedPreferences("music", Context.MODE_PRIVATE).getString("repeat", "off")
        )
        shot("in_order_loop_track")

        // put the defaults back for other tests in this JVM
        MusicSettings.setShuffle(true)
        MusicSettings.setRepeat("off")
        MusicSettings.setChoice(MusicSlot.PLAYLIST, null)
    }

    // ================================================================== 4. rest timer on the lock screen + one ping

    @Test
    fun restAlertsLockScreen() {
        flow = "restAlertsLockScreen"
        val app = context as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RestAlerts.resetForTest()
        // the full countdown behaviour; under his "essential only" rule (the default) the countdown is skipped
        com.tamir.hamasa.data.NotifPolicy.setEssentialOnly(app, false)
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AlarmManager::class.java)

        val endAt = System.currentTimeMillis() + 90_000L
        RestAlerts.show(context, endAt, "הבא: סקוואט, סט 3")
        settle(100)

        val notes = Shadows.shadowOf(nm).allNotifications
        assertEquals("[$flow] one countdown notification", 1, notes.size)
        val n = notes[0]
        assertTrue("[$flow] chronometer", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue("[$flow] counts down", n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals("[$flow] ends at the rest end", endAt, n.`when`)
        assertEquals("[$flow] public on the lock screen", Notification.VISIBILITY_PUBLIC, n.visibility)
        val ch = nm.getNotificationChannel(n.channelId)
        assertNotNull("[$flow] channel", ch)
        assertEquals("[$flow] channel importance (shown on the lock screen)", NotificationManager.IMPORTANCE_DEFAULT, ch.importance)
        assertNull("[$flow] channel silent", ch.sound)

        val alarm = Shadows.shadowOf(am).peekNextScheduledAlarm()
        assertNotNull("[$flow] end-of-rest alarm", alarm)
        assertEquals("[$flow] alarm at the rest end", endAt, alarm!!.triggerAtMs)

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        show { Lines(listOf("התראה: $title", text, "שעון יורד: כן", "גלוי במסך הנעול: כן", "צלצול בסוף: מתוזמן")) }
        shot("countdown")

        // the alarm fires (app frozen / in the background): notification gone, one ping
        RestAlarmReceiver().onReceive(context, Intent(RestAlarmReceiver.ACTION))
        settle(1_500)
        assertEquals("[$flow] notification removed at the end", 0, Shadows.shadowOf(nm).allNotifications.size)
        // the ViewModel catching up much later for the same rest: no second ping
        assertFalse("[$flow] no second ping for the same rest", RestAlerts.shouldPing(System.currentTimeMillis() + 60_000L))

        // the next rest pings again
        val endAt2 = System.currentTimeMillis() + 60_000L
        RestAlerts.show(context, endAt2, "הבא: לחיצת חזה, סט 1")
        assertTrue("[$flow] next rest pings", RestAlerts.shouldPing(System.currentTimeMillis() + 120_000L))

        // skipping / finishing: alarm and countdown removed
        RestAlerts.show(context, System.currentTimeMillis() + 30_000L, "הבא")
        RestAlerts.cancel(context)
        settle(100)
        assertNull("[$flow] alarm cancelled", Shadows.shadowOf(am).peekNextScheduledAlarm())
        assertEquals("[$flow] countdown cancelled", 0, Shadows.shadowOf(nm).allNotifications.size)

        // essential only: no countdown notification, but the alarm (the end-of-rest alert) is still set
        com.tamir.hamasa.data.NotifPolicy.setEssentialOnly(app, true)
        val endAt3 = System.currentTimeMillis() + 45_000L
        RestAlerts.show(context, endAt3, "הבא: סקוואט, סט 2")
        settle(100)
        assertEquals("[$flow] essential only: no countdown", 0, Shadows.shadowOf(nm).allNotifications.size)
        assertEquals("[$flow] essential only: alarm still set", endAt3, Shadows.shadowOf(am).peekNextScheduledAlarm()?.triggerAtMs)
        RestAlerts.cancel(context)
    }

    // ================================================================== 5. updates never during / right after a workout

    @Test
    fun updatesNeverDuringWorkout() {
        flow = "updatesNeverDuringWorkout"
        val log = ArrayList<String>()
        runBlocking { AppGraph.workouts.seedIfNeeded() }
        UpdateStore.setBackgroundCheckAt(context, 0L)

        // a running workout
        val activeId = runBlocking {
            AppGraph.db.sessions().insert(
                SessionEntity(dateEpochDay = Days.today(), type = SessionType.GYM, status = SessionStatus.ACTIVE, modeId = null,
                    startedAtMillis = System.currentTimeMillis())
            )
        }
        runBlocking { UpdateCenter.backgroundUpdate(context) }
        assertEquals("[$flow] no check during a workout", 0L, UpdateStore.backgroundCheckAt(context))
        log.add("באמצע אימון: לא נבדק, לא הותקן")

        // finished 3 minutes ago
        runBlocking {
            val s = AppGraph.db.sessions().byId(activeId)!!
            val now = System.currentTimeMillis()
            AppGraph.db.sessions().update(s.copy(status = SessionStatus.DONE, endedAtMillis = now - 3L * 60_000L))
        }
        runBlocking { UpdateCenter.backgroundUpdate(context, allowDownload = true, force = true) }
        assertEquals("[$flow] no check right after a workout", 0L, UpdateStore.backgroundCheckAt(context))
        assertEquals("[$flow] nothing installing", 0, UpdateCenter.updatingBuild.value)
        log.add("3 דקות אחרי אימון: לא נבדק, לא הותקן")

        // the periodic background work: every 2 hours, any network (mobile data too)
        UpdateCenter.scheduleBackground(context)
        val infos: List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork("selfUpdate").get()
        assertEquals("[$flow] one periodic update work", 1, infos.size)
        val info = infos[0]
        assertEquals("[$flow] any network", NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals("[$flow] every 2 hours", TimeUnit.HOURS.toMillis(2), info.periodicityInfo?.repeatIntervalMillis)
        log.add("בדיקה ברקע: כל שעתיים, גם בגלישה")

        show { Lines(log) }
        shot("rules")
    }

    // ================================================================== 6. the "מתעדכן" screen never gets stuck

    @Test
    fun updatingOverlayClears() {
        flow = "updatingOverlayClears"
        @Suppress("UNCHECKED_CAST")
        val fg = UpdateCenter::class.java.getDeclaredField("_foregroundInstall").let { f ->
            f.isAccessible = true
            f.get(null) as MutableStateFlow<Boolean>
        }
        fg.value = true
        show { UpdatingOverlay() }
        waitFor("overlay shown") { exists(hasText("מתעדכן")) }
        shot("overlay")

        // the install was cancelled / failed: the screen (no close button, back disabled) must go away
        UpdateCenter.onInstallFailed(context, 0, "ההתקנה בוטלה.", fallBack = false)
        settle(300)
        waitFor("overlay gone") { !exists(hasText("מתעדכן")) }
        assertFalse("[$flow] flag cleared", UpdateCenter.foregroundInstall.value)
        shot("cleared")
    }

    // ================================================================== 7. "אפשר עדכון ברקע"

    @Test
    fun backgroundUpdateButton() {
        flow = "backgroundUpdateButton"
        show { Column(Modifier.padding(16.dp)) { BackgroundUpdateRow() } }
        settle(300)
        node("battery button", clickable("אפשר עדכון ברקע"))
        shot("button")
        tap("battery button", clickable("אפשר עדכון ברקע"))
        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull("[$flow] a system screen was opened", started)
        assertEquals("[$flow] battery optimization request", Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started!!.action)
        assertEquals("[$flow] for this app", "package:" + context.packageName, started.dataString)
    }

    private companion object {
        const val SESSION = 4242L
    }
}
