package com.lmreader.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainMenuSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun drawerChoosesImageAndVideoInsteadOfLibraryAndShelf() {
        var opened: MainDestination? = null
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                MainMenuSheet(
                    onNavigate = { opened = it },
                    onDismiss = { dismissals++ },
                )
            }
        }
        compose.onNodeWithTag("main-menu:IMAGES").assertIsDisplayed()
        compose.onNodeWithTag("main-menu:VIDEOS").assertIsDisplayed()
        compose.onNodeWithText("库").assertDoesNotExist()
        compose.onNodeWithText("图库").assertDoesNotExist()
        compose.onNodeWithText("书架").assertDoesNotExist()
        compose.onNodeWithTag("main-menu:LIBRARY").assertDoesNotExist()
        compose.onNodeWithTag("main-menu:BOOKSHELF").assertDoesNotExist()
        compose.onNodeWithTag("main-menu:VIDEOS").performClick()
        assertEquals(MainDestination.VIDEOS, opened)
        compose.onNodeWithTag("main-menu:IMAGES").performClick()
        assertEquals(MainDestination.IMAGES, opened)
        assertEquals(2, dismissals)
    }
}
