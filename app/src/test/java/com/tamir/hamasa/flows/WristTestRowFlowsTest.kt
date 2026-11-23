package com.tamir.hamasa.flows

/*
 * The settings row "בדיקת רטט בשעון" (ui/settings/WristTestRow).
 *  1. wristTestRowTap   tapping "רטט בשעון עכשיו" posts the alerting notification and shows "נשלח".
 * Rendered alone. Image: build/screenshots/flows/wristTestRow_01.png
 */

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.ui.settings.WristTestRow
import com.tamir.hamasa.ui.theme.HamasaTheme
import org.junit.Assert.assertTrue
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
class WristTestRowFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wristTestRowTap() {
        val flow = "wristTestRowTap"
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(app)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme {
                    Column(Modifier.padding(16.dp)) { WristTestRow() }
                }
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("רטט בשעון עכשיו")).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("נשלח", substring = true)).assertExists()
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = Shadows.shadowOf(nm).allNotifications.any { it.channelId == "rest_over_wrist2" }
        assertTrue("[$flow] alerting notification posted", posted)
        runCatching {
            val file = File("build/screenshots/flows/wristTestRow_01.png")
            file.parentFile?.mkdirs()
            composeRule.onRoot().captureRoboImage(file.path)
        }
    }
}
