package com.lmreader.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lmreader.R
import com.lmreader.di.AppContainer
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*

class HomeTaskChromeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun ballDragsAndShowsOnlyScreenSettingsPlaceholderOnHome() {
        val container=AppContainer.from(ApplicationProvider.getApplicationContext())
        val enabled=mutableStateOf(true)
        compose.setContent { MaterialTheme { HomeTaskChrome(container,enabled.value,{}) { Box(it) } } }
        val ball=compose.onNodeWithTag("screen-translation-ball")
        val before=ball.fetchSemanticsNode().boundsInRoot
        ball.performTouchInput { swipe(center,center+androidx.compose.ui.geometry.Offset(-90f,-90f)) }
        val after=ball.fetchSemanticsNode().boundsInRoot
        assertTrue(after.left<before.left);assertTrue(after.top<before.top)
        ball.performClick()
        val settings=compose.activity.getString(R.string.screen_translation_settings)
        compose.onNodeWithText(settings).assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.screen_translation_placeholder)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.local_mt_close)).performClick()
        compose.runOnIdle { enabled.value=false }
        ball.assertDoesNotExist()
    }
}
