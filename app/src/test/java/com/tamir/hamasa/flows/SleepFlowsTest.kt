package com.tamir.hamasa.flows

/*
 * Automatic sleep from the phone (data/health/PhoneSleep + ui/settings/PhoneSleepRow).
 *
 * Flows:
 *  1. phoneNightPick      pure: screen off 23:40-02:10, a 3-minute glance, off again 02:13-06:50 -> one night
 *                         23:40-06:50 of 7:07 (the glance is not sleep). An afternoon nap and a 2-hour night are
 *                         not taken; a later, longer night wins over an early short block.
 *  2. phoneSleepRowShown  the watch card in settings shows "שינה אוטומטית מהטלפון" and the button
 *                         "הפעלת שינה אוטומטית" (no usage access in tests); screenshot.
 *
 * Every failure message names the flow and the step in plain English.
 */

import android.os.Looper
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.health.PhoneSleep
import com.tamir.hamasa.ui.settings.WatchSettingsCard
import com.tamir.hamasa.ui.theme.HamasaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class SleepFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initialised = runCatching { AppGraph.workouts }.isSuccess
        if (!initialised) AppGraph.init(context)
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
            )
        }
    }

    private val min = 60_000L
    /** Minutes from 18:00 of the evening before (a fixed base, no time zone involved). */
    private fun t(hh: Int, mm: Int): Long {
        val h = if (hh >= 18) hh - 18 else hh + 6
        return 1_000_000_000_000L + (h * 60L + mm) * min
    }

    @Test
    fun phoneNightPick() {
        val flow = "phoneNightPick"
        val from = t(20, 0)
        val latest = t(6, 0)
        println("[flow-step] $flow: night with a glance")
        val night = PhoneSleep.pick(
            listOf(PhoneSleep.Off(t(23, 40), t(2, 10)), PhoneSleep.Off(t(2, 13), t(6, 50))),
            from, latest
        )
        assertNotNull("[$flow] no night found", night)
        assertEquals("[$flow] start", t(23, 40), night!!.startMillis)
        assertEquals("[$flow] end", t(6, 50), night.endMillis)
        assertEquals("[$flow] minutes (glance not counted)", 7 * 60 + 7, night.minutes)

        println("[flow-step] $flow: a nap before 20:00 and a 2-hour night are not taken")
        assertNull("[$flow] nap taken", PhoneSleep.pick(listOf(PhoneSleep.Off(t(18, 30), t(19, 59))), from, latest))
        assertNull("[$flow] 2-hour night taken", PhoneSleep.pick(listOf(PhoneSleep.Off(t(1, 0), t(3, 0))), from, latest))

        println("[flow-step] $flow: the longer block wins")
        val two = PhoneSleep.pick(
            listOf(PhoneSleep.Off(t(21, 0), t(0, 30)), PhoneSleep.Off(t(1, 0), t(8, 0))),
            from, latest
        )
        assertEquals("[$flow] longer block", t(1, 0), two?.startMillis)
    }

    @Test
    fun sleepTimesLine() {
        val flow = "sleepTimesLine"
        val today = com.tamir.hamasa.domain.Days.today()
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.ofEpochDay(today - 1).atTime(23, 40).atZone(zone).toInstant().toEpochMilli()
        val end = java.time.LocalDate.ofEpochDay(today).atTime(6, 50).atZone(zone).toInstant().toEpochMilli()
        com.tamir.hamasa.data.health.WatchStore.putSleep(mapOf(today to 411), mapOf(today to Pair(start, end)))
        assertEquals("[$flow] span stored", Pair(start, end), com.tamir.hamasa.data.health.WatchStore.sleepSpanFor(today))
        val p = kotlinx.coroutines.runBlocking { AppGraph.prefs.current() }.copy(sleepDay = today, sleepHoursSet = 7f, sleepManual = false)
        val line = com.tamir.hamasa.data.health.WatchSleep.sliderLine(p, 411, today)
        println("[flow-step] $flow: line = $line")
        if (!line.contains("מ-23:40 עד 06:50")) fail("[$flow] the line does not show the sleep hours: $line")
        if (!line.contains("6 שעות 51 דקות")) fail("[$flow] the line lost the duration: $line")
        // the cloud copy keeps the hours
        val o = org.json.JSONObject()
        com.tamir.hamasa.data.health.WatchStore.putCloud(o)
        if (!o.optString("watchSleepSpans", "").contains("$today:$start:$end")) fail("[$flow] cloud copy lacks the span")
    }

    @Test
    fun phoneSleepRowShown() {
        val flow = "phoneSleepRowShown"
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.verticalScroll(rememberScrollState())
                        ) { WatchSettingsCard() }
                    }
                }
            }
        }
        // the row title is always there; the body is the button (no usage access) or the "פועל" line (access on;
        // Robolectric may report the app op as allowed)
        val start = System.currentTimeMillis()
        fun count(text: String): Int = runCatching {
            composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes(atLeastOneRootRequired = false).size
        }.getOrDefault(0)
        while (true) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(100)
            if (count("שינה אוטומטית מהטלפון") > 0 && (count("הפעלת שינה אוטומטית") > 0 || count("פועל. בלילות") > 0)) break
            if (System.currentTimeMillis() - start > 20_000L) {
                val f = File("build/screenshots/flows/${flow}_00_failed.png")
                f.parentFile?.mkdirs()
                runCatching { composeRule.onRoot().captureRoboImage(f.path) }
                fail("[$flow] the phone sleep row never showed")
            }
            Thread.sleep(50)
        }
        val access = PhoneSleep.hasAccess(ApplicationProvider.getApplicationContext())
        println("[flow-step] $flow: usage access in test = $access")
        if (access) assertEquals("[$flow] on line", 1, count("פועל. בלילות"))
        else assertEquals("[$flow] button", 1, count("הפעלת שינה אוטומטית"))
        val file = File("build/screenshots/flows/${flow}_01_card.png")
        file.parentFile?.mkdirs()
        runCatching { composeRule.onRoot().captureRoboImage(file.path) }
    }
}
