package com.lmreader.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lmreader.R
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskNotice
import com.lmreader.tasks.TaskNoticeItem
import com.lmreader.tasks.TaskNoticePhase
import com.lmreader.tasks.TaskQueue
import com.lmreader.ui.common.LocalHomeOverlayPadding
import java.io.File
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

    @Test fun collectionCapsuleOverlaysThePageAndClearsProgressAndLastItem() {
        val notice = mutableStateOf<TaskNotice?>(null)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    HomeTaskLayout(Routes.LIBRARY, notice.value, {}, {}) { modifier ->
                        LazyColumn(modifier.testTag("floating-content").background(Color(0xFFDEEAF4)),
                            contentPadding = PaddingValues(bottom = LocalHomeOverlayPadding.current)) {
                            items(30) { index ->
                                androidx.compose.material3.Text("Item $index",
                                    Modifier.fillMaxWidth().height(56.dp).testTag("floating-item:$index"))
                            }
                        }
                    }
                }
            }
        }
        val capsule = compose.onNodeWithTag("home-collection-switcher")
        val content = compose.onNodeWithTag("floating-content")
        val page = content.fetchSemanticsNode().boundsInRoot
        val control = capsule.fetchSemanticsNode().boundsInRoot
        // A bottom layout row would place the control below the page instead of inside it.
        assertTrue(control.top > page.top && control.bottom < page.bottom)
        assertTrue(control.left > page.left && control.right < page.right)
        content.performScrollToIndex(29)
        assertTrue(compose.onNodeWithTag("floating-item:29").fetchSemanticsNode().boundsInRoot.bottom <
            capsule.fetchSemanticsNode().boundsInRoot.top)
        saveLayout("home-floating.png")

        compose.runOnIdle {
            notice.value = TaskNotice(TaskQueue.TRANSLATION, TaskNoticePhase.RUNNING,
                TaskNoticeItem("layout", "RUNNING", "Layout preview", "Chapter 1", 1, 10), 1)
            fontScale.value = 2f
        }
        compose.waitForIdle()
        val progress = compose.onNodeWithTag("home-translation-progress").assertIsDisplayed()
        assertTrue(capsule.fetchSemanticsNode().boundsInRoot.bottom < progress.fetchSemanticsNode().boundsInRoot.top)
        content.performScrollToIndex(29)
        assertTrue(compose.onNodeWithTag("floating-item:29").fetchSemanticsNode().boundsInRoot.bottom <
            capsule.fetchSemanticsNode().boundsInRoot.top)
        saveLayout("home-floating-progress.png")
    }

    private fun saveLayout(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
