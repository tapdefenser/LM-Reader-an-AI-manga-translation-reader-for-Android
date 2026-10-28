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
    @Test fun ballDragsAndOpensAnUpwardMenuOnlyOnHome() {
        val container=AppContainer.from(ApplicationProvider.getApplicationContext())
        val enabled=mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                HomeTaskChrome(
                    container = container,
                    homeDestination = if(enabled.value) Routes.BOOKSHELF else null,
                    onOpenCollection = {},
                    onOpenQueue = {},
                ) { Box(it) }
            }
        }
        val ball=compose.onNodeWithTag("screen-translation-ball")
        val before=ball.fetchSemanticsNode().boundsInRoot
        ball.performTouchInput { swipe(center,center+androidx.compose.ui.geometry.Offset(-90f,-90f)) }
        val after=ball.fetchSemanticsNode().boundsInRoot
        assertTrue(after.left<before.left);assertTrue(after.top<before.top)
        ball.performClick()
        val settings=compose.activity.getString(R.string.screen_translation_settings)
        val item = compose.onNodeWithText(settings).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.screen_translation_start)).assertIsDisplayed()
        assertTrue(item.fetchSemanticsNode().positionInWindow.y < ball.fetchSemanticsNode().positionInWindow.y)
        compose.runOnIdle { enabled.value=false }
        ball.assertDoesNotExist()
        compose.onNodeWithTag("home-collection:LIBRARY").assertDoesNotExist()
        compose.onNodeWithTag("home-collection:BOOKSHELF").assertDoesNotExist()
    }

    @Test fun libraryAndShelfSegmentsReplaceImageAndVideoSwitch() {
        val container=AppContainer.from(ApplicationProvider.getApplicationContext())
        val destination = mutableStateOf(Routes.LIBRARY)
        compose.setContent {
            MaterialTheme {
                HomeTaskChrome(
                    container = container,
                    homeDestination = destination.value,
                    onOpenCollection = { destination.value = it },
                    onOpenQueue = {},
                ) { Box(it) }
            }
        }
        compose.onNodeWithTag("home-collection:LIBRARY").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("库").assertIsDisplayed()
        compose.onNodeWithTag("home-collection:BOOKSHELF").assertIsDisplayed().performClick()
        assertEquals(Routes.BOOKSHELF, destination.value)
        compose.onNodeWithTag("home-collection:BOOKSHELF").assertIsSelected()
        compose.onNodeWithTag("home-collection:LIBRARY").performClick().assertIsSelected()
        assertEquals(Routes.LIBRARY, destination.value)
        compose.onNodeWithText("图片").assertDoesNotExist()
        compose.onNodeWithText("视频").assertDoesNotExist()
    }
}
