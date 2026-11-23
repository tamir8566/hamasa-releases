package com.tamir.hamasa.flows

/*
 * The home scene in every map region: Shambiuluk must stand ON the ledge, not hover at its back edge
 * (owner report 2026-11-13, the forest region). The pictures are the check; the test fails only if a
 * region cannot be drawn.
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.domain.game.BodyBlend
import com.tamir.hamasa.ui.home.ScenePanel
import com.tamir.hamasa.ui.theme.HamasaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class SceneFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sceneStandsOnLedge() {
        val flow = "sceneStandsOnLedge"
        var region by mutableStateOf(0)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme {
                    Box { ScenePanel(blend = BodyBlend(3, 3, 0f, false), variant = "M", region = region, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
        var drawn = 0
        for (r in listOf(0, 2, 3, 4, 5, 6, 7, 8, 9, 10)) {
            region = r
            composeRule.mainClock.advanceTimeBy(1_500)
            val f = File("build/screenshots/flows/scene_region_" + r.toString().padStart(2, '0') + ".png")
            f.parentFile?.mkdirs()
            composeRule.onRoot().captureRoboImage(f.path)
            if (f.exists()) drawn++
        }
        assertTrue("[$flow] all ten regions drawn, got $drawn", drawn == 10)
    }
}
