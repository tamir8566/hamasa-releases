package com.tamir.hamasa.flows

/*
 * The workout on the wrist (ui/workout/WristCard): the phone notification the watch mirrors, and its "סט הושלם" button.
 *
 * Flows:
 *  1. wristCardNextSet      an ACTIVE gym session (bench 2 x 8 x 60 kg, plank 2 x 45 s) -> refresh posts the card
 *                           "לחיצת חזה במוט" / "סט 1 מתוך 2 · 8 חזרות · 60 ק"ג" with one action "סט הושלם", silent.
 *  2. wristDoneNoScreen     the button with no workout screen alive: the set is recorded with its planned reps and
 *                           weight, the card moves to set 2, a rest countdown starts. A second press on the same
 *                           set changes nothing. The timed plank reads "45 שניות".
 *  3. wristRestOver         app not on screen: restOver re-posts the card on the alerting channel with
 *                           "המנוחה נגמרה." in the title and says it alerted; on screen it alerts too.
 *  4. wristSessionOver      the session is no longer ACTIVE -> the button only clears the card.
 *
 * Every failure message names the flow and the step in plain English.
 */

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.update.UpdateCenter
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.workout.WristCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class WristFlowsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val initialised = runCatching { AppGraph.workouts }.isSuccess
        if (!initialised) AppGraph.init(app)
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                app,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
            )
        }
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { AppGraph.workouts.seedIfNeeded() }
        // the flows below check the full card behaviour; the "essential only" rule has its own test
        com.tamir.hamasa.data.NotifPolicy.setEssentialOnly(app, false)
        WristCard.clear(app)
    }

    @After
    fun tearDown() {
        com.tamir.hamasa.data.NotifPolicy.resetForTest()
        UpdateCenter.setForeground(false)
        WristCard.clear(app)
    }

    private fun step(flow: String, s: String) = println("[flow-step] $flow: $s")

    /** ACTIVE gym session: bench 2 x 8 x 60, plank 2 x 45 s. Returns the session id. */
    private fun seedSession(status: SessionStatus = SessionStatus.ACTIVE): Long = runBlocking {
        val id = AppGraph.db.sessions().insert(
            SessionEntity(
                dateEpochDay = Days.today(), type = SessionType.GYM, status = status, modeId = "mini",
                startedAtMillis = System.currentTimeMillis() - 10L * 60_000L
            )
        )
        AppGraph.db.sessions().insertSets(
            listOf(
                SetLogEntity(sessionId = id, exerciseId = "bench_press", setIndex = 0, targetReps = 8, targetWeight = 60f),
                SetLogEntity(sessionId = id, exerciseId = "bench_press", setIndex = 1, targetReps = 8, targetWeight = 60f),
                SetLogEntity(sessionId = id, exerciseId = "plank", setIndex = 0, targetReps = 45, targetWeight = 0f),
                SetLogEntity(sessionId = id, exerciseId = "plank", setIndex = 1, targetReps = 45, targetWeight = 0f)
            )
        )
        id
    }

    private fun sets(id: Long): List<SetLogEntity> = runBlocking { AppGraph.db.sessions().setsForOnce(id) }

    private fun posted(): Notification? {
        val nm = app.getSystemService(NotificationManager::class.java)
        // the wrist card only (the rest countdown is another notification)
        return Shadows.shadowOf(nm).allNotifications.lastOrNull { it.channelId == "workout_wrist" || it.channelId == "rest_over_wrist2" }
    }

    private fun title(n: Notification?): String = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
    private fun text(n: Notification?): String = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

    @Test
    fun wristCardNextSet() {
        val flow = "wristCardNextSet"
        val id = seedSession()
        step(flow, "refresh the card")
        runBlocking { WristCard.refresh(app, id) }
        val card = WristCard.current
        assertNotNull("[$flow] no card after refresh", card)
        assertEquals("[$flow] card points at the wrong set", sets(id).first { it.exerciseId == "bench_press" && it.setIndex == 0 }.id, card!!.setId)
        val n = posted()
        assertNotNull("[$flow] no notification posted", n)
        assertEquals("[$flow] title", "לחיצת חזה במוט", title(n))
        assertEquals("[$flow] text", "סט 1 מתוך 2 · 8 חזרות · 60 ק\"ג", text(n))
        assertEquals("[$flow] one action", 1, n!!.actions?.size ?: 0)
        assertEquals("[$flow] action label", "סט הושלם", n.actions[0].title.toString())
        assertEquals("[$flow] silent channel", "workout_wrist", n.channelId)
        assertTrue("[$flow] must not be ongoing (watches skip ongoing)", (n.flags and Notification.FLAG_ONGOING_EVENT) == 0)
    }

    @Test
    fun wristDoneNoScreen() {
        val flow = "wristDoneNoScreen"
        val id = seedSession()
        runBlocking { WristCard.refresh(app, id) }
        val first = WristCard.current!!.setId
        step(flow, "press סט הושלם")
        runBlocking { WristCard.completeFromWrist(app, id, first) }
        val done = sets(id).first { it.id == first }
        assertTrue("[$flow] set not completed", done.completed)
        assertEquals("[$flow] reps", 8, done.reps)
        assertEquals("[$flow] weight", 60f, done.weight, 0.01f)
        val second = sets(id).first { it.exerciseId == "bench_press" && it.setIndex == 1 }.id
        assertEquals("[$flow] card did not move to set 2", second, WristCard.current?.setId)
        assertEquals("[$flow] text set 2", "סט 2 מתוך 2 · 8 חזרות · 60 ק\"ג", text(posted()))

        step(flow, "second press on the same set")
        runBlocking { WristCard.completeFromWrist(app, id, first) }
        assertEquals("[$flow] double press completed more sets", 1, sets(id).count { it.completed })
        assertEquals("[$flow] card moved on a double press", second, WristCard.current?.setId)

        step(flow, "finish bench, plank is timed")
        runBlocking { WristCard.completeFromWrist(app, id, second) }
        assertEquals("[$flow] plank title", "פלאנק", title(posted()))
        assertEquals("[$flow] plank text", "סט 1 מתוך 2 · 45 שניות", text(posted()))
    }

    @Test
    fun wristRestOver() {
        val flow = "wristRestOver"
        val id = seedSession()
        runBlocking { WristCard.refresh(app, id) }
        step(flow, "rest over, app away")
        UpdateCenter.setForeground(false)
        assertTrue("[$flow] restOver did not alert", WristCard.restOver(app))
        val n = posted()
        assertEquals("[$flow] alert channel", "rest_over_wrist2", n?.channelId)
        assertTrue("[$flow] title should start with המנוחה נגמרה", title(n).startsWith("המנוחה נגמרה."))
        step(flow, "rest over, app on screen")
        UpdateCenter.setForeground(true)
        assertTrue("[$flow] did not alert while on screen", WristCard.restOver(app))
        assertEquals("[$flow] on screen should alert too", "rest_over_wrist2", posted()?.channelId)
    }

    @Test
    fun wristSessionOver() {
        val flow = "wristSessionOver"
        val id = seedSession()
        runBlocking { WristCard.refresh(app, id) }
        val first = WristCard.current!!.setId
        runBlocking {
            val s = AppGraph.db.sessions().byId(id)!!
            AppGraph.db.sessions().update(s.copy(status = SessionStatus.DONE))
        }
        step(flow, "press after the session ended")
        runBlocking { WristCard.completeFromWrist(app, id, first) }
        assertNull("[$flow] card not cleared", WristCard.current)
        assertFalse("[$flow] set completed after the session ended", sets(id).first { it.id == first }.completed)
    }

    /** The settings test button posts an alerting notification on the rest-over channel. */
    @Test
    fun wristTestBuzz() {
        val flow = "wristTestBuzz"
        assertTrue("[$flow] test buzz posted", WristCard.testBuzz(app))
        val n = Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications
            .lastOrNull { it.channelId == "rest_over_wrist2" }
        assertTrue("[$flow] alerting channel", n != null)
        assertEquals("[$flow] title", "בדיקת רטט", title(n))
    }

    /** His rule "essential only" (the default): no silent next-set card, but the end of the rest still alerts. */
    @Test
    fun wristEssentialOnly() {
        val flow = "wristEssentialOnly"
        // setUp wrote "all notifications" for the other flows; a fresh install reads true (checked below from a clean file)
        app.getSharedPreferences("notif_policy", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        com.tamir.hamasa.data.NotifPolicy.resetForTest()
        assertTrue("[$flow] essential only is the default", com.tamir.hamasa.data.NotifPolicy.essentialOnly(app))
        val id = seedSession()
        runBlocking { WristCard.refresh(app, id) }
        assertNotNull("[$flow] the card state is kept", WristCard.current)
        assertNull("[$flow] no silent card posted", posted())
        UpdateCenter.setForeground(false)
        assertTrue("[$flow] rest over still alerts", WristCard.restOver(app))
        assertEquals("[$flow] alert channel", "rest_over_wrist2", posted()?.channelId)
        // the next set: the alert is taken down, nothing silent replaces it
        runBlocking { WristCard.refresh(app, id) }
        assertNull("[$flow] alert taken down on the next set", posted())
    }
}
