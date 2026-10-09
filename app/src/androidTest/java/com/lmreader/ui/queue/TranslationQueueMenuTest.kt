package com.lmreader.ui.queue

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lmreader.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.*

class TranslationQueueMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer.from(ApplicationProvider.getApplicationContext())
    private var previous = true
    private var previousPriority = TranslationSchedulingPriority.RESOURCES
    @Before fun setup() = runBlocking {
        previous = container.translationQueue.paused.value
        previousPriority = container.translationQueue.schedulingPriority.value
        container.translationQueue.pause(); container.translationQueue.awaitCurrentPage(); container.translationQueue.awaitResourceRelease()
        Assume.assumeTrue(container.database.translationDao().queueSnapshot().none { it.state in listOf("PENDING", "RUNNING", "PAUSED", "FAILED", "INTERRUPTED") })
    }
    @After fun restore() {
        container.translationQueue.setSchedulingPriority(previousPriority)
        if(!previous) container.translationQueue.resume()
    }
    @Test fun priorityChoicesPersistAndOnlyTheChosenModeIsChecked() {
        container.translationQueue.setSchedulingPriority(TranslationSchedulingPriority.RESOURCES)
        compose.setContent { MaterialTheme { TranslationQueueScreen(container, onBack = {}) } }
        compose.onNodeWithContentDescription("队列菜单").performClick()
        compose.onNodeWithTag("translation-priority:RESOURCES").assertIsSelected()
        compose.onNodeWithTag("translation-priority:ORDER").assertIsNotSelected()
        compose.onNodeWithText("队列顺序优先").performClick()
        compose.waitUntil(5000) { container.translationQueue.schedulingPriority.value == TranslationSchedulingPriority.ORDER }
        compose.onNodeWithContentDescription("队列菜单").performClick()
        compose.onNodeWithTag("translation-priority:ORDER").assertIsSelected()
        compose.onNodeWithTag("translation-priority:RESOURCES").assertIsNotSelected()
        compose.onAllNodesWithText("✓").assertCountEquals(1)
        compose.onNodeWithText("资源利用优先").performClick()
        compose.waitUntil(5000) { container.translationQueue.schedulingPriority.value == TranslationSchedulingPriority.RESOURCES }
    }
    @Test fun menuAlwaysOffersSeparateEnabledStartAndPauseActions() {
        compose.setContent { MaterialTheme { TranslationQueueScreen(container, onBack = {}) } }
        compose.onNodeWithContentDescription("队列菜单").performClick()
        compose.onNodeWithText("全部开始").assertIsEnabled()
        compose.onNodeWithText("全部暂停").assertIsEnabled()
        compose.onNodeWithText("全部开始").performClick()
        compose.waitUntil(5000) { !container.translationQueue.paused.value }
        compose.onNodeWithContentDescription("队列菜单").performClick()
        compose.onNodeWithText("全部开始").assertIsEnabled()
        compose.onNodeWithText("全部暂停").assertIsEnabled().performClick()
        compose.waitUntil(5000) { container.translationQueue.paused.value }
    }
}
