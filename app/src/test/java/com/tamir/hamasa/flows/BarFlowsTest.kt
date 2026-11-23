package com.tamir.hamasa.flows

/*
 * The active-workout strip at the bottom of every screen (ui/common/ActiveWorkoutBar).
 *  1. activeBarOpens   the strip shows "אימון פעיל" with the start time; tapping "חזרה לאימון" calls onOpen once.
 * Rendered alone (no navigation host). Image: build/screenshots/flows/activeBar_01.png
 */

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.ui.common.ActiveWorkoutBar
import com.tamir.hamasa.ui.theme.HamasaTheme
import org.junit.Assert.assertEquals
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
class BarFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeBarOpens() {
        val flow = "activeBarOpens"
        var opened = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme {
                    Column {
                        ActiveWorkoutBar(startedAtMillis = 1_700_000_000_000L, onOpen = { opened++ })
                    }
                }
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("אימון פעיל", substring = true)).assertExists()
        runCatching {
            val file = File("build/screenshots/flows/activeBar_01.png")
            file.parentFile?.mkdirs()
            composeRule.onRoot().captureRoboImage(file.path)
        }
        composeRule.onNode(hasText("חזרה לאימון")).performClick()
        composeRule.waitForIdle()
        assertEquals("[$flow] onOpen called once", 1, opened)
        // tapping the strip itself (the text, not the button) opens the workout too
        composeRule.onNode(hasText("אימון פעיל", substring = true)).performClick()
        composeRule.waitForIdle()
        assertEquals("[$flow] tapping the strip opens too", 2, opened)
    }
}
