package com.lmreader.ui.video

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.ApiProfile
import com.lmreader.core.model.ApiProfileKind
import com.lmreader.core.model.WorkflowTemplates
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real dialog, real Room options/catalog and a non-starting scheduler. No API or model work. */
@RunWith(AndroidJUnit4::class)
class VideoOptionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var videoId: String

    @Before fun setup() = runBlocking<Unit> {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app) {
            TaskScheduler(it, canStart = { false }, acquire = { null }, release = {})
        }
        container.startupReady.await()
        container.taskScheduler.ready.await()
        val file = File(app.cacheDir, "options-${UUID.randomUUID()}.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file) }
        val inspected = VideoMediaSource.inspect(app, Uri.fromFile(file), persist = false)
        container.videoCatalog.importLegacy(inspected.id, inspected.uri, inspected.name, inspected.sha256,
            inspected.bytes, inspected.durationMs, inspected.width, inspected.height, inspected.rotation, 0)
        videoId = inspected.id
        container.videoTasks.ensureProject(videoId)
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (::container.isInitialized) {
            runCatching { withTimeout(5_000) { container.taskScheduler.interrupt("TEST_CLEANUP") } }
            runCatching { closeContainer(container) }
        }
        if (::app.isInitialized) app.root.deleteRecursively()
        Unit
    }

    private fun openDialog() {
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(true) }
                if (open) {
                    VideoTranslationOptionsDialog(container, videoId, onDismiss = { open = false })
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("video-option:source").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun languageRowsOpenPickersAndManualTargetIsSaved() = runBlocking<Unit> {
        openDialog()

        compose.onNodeWithTag("video-option:source").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("video-option-picker").fetchSemanticsNodes().isNotEmpty()
        }
        val closeNodes = compose.onAllNodesWithText("关闭")
        closeNodes[closeNodes.fetchSemanticsNodes().size - 1].performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("video-option-picker").fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithTag("video-option:target").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("video-option-picker").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("video-language-manual").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("pt-BR")
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("pt-BR", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("加入队列").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("video-option:source").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("pt-BR", container.videoTasks.options(videoId).target)
    }

    @Test fun defaultLocalWorkflowsHideApiAndLegacyControlsAndCloseCreatesNothing() = runBlocking<Unit> {
        val tasksBefore = container.workDatabase.tasks().all().size
        val shelfBefore = container.workDatabase.videoCatalogDao().byId(videoId)?.onShelf
        openDialog()

        assertEquals(0, compose.onAllNodesWithTag("video-option:frame-api").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("video-option:subtitle-api").fetchSemanticsNodes().size)
        for (hidden in listOf("准备状态", "字幕高级选项", "字幕源", "ASR 模型")) {
            assertEquals(0, compose.onAllNodesWithText(hidden).fetchSemanticsNodes().size)
        }

        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("video-option:source").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(tasksBefore, container.workDatabase.tasks().all().size)
        assertEquals(shelfBefore, container.workDatabase.videoCatalogDao().byId(videoId)?.onShelf)
    }

    @Test fun apiWorkflowRevealsFrameApiPickerAndSubtitleOnlySubmissionUsesLocalAsr() = runBlocking<Unit> {
        val profile = ApiProfile("profile-${UUID.randomUUID()}", ApiProfileKind.LLM, "测试配置",
            "https://api.example.invalid/v1", apiKey = "not-used", model = "test-model", retryCount = 0)
        container.apiProfiles.save(profile)
        val workflow = container.translationWorkflows.create().let { created ->
            container.translationWorkflows.update(created.copy(
                name = "画面API-${UUID.randomUUID()}",
                program = WorkflowTemplates.visionApi(profile.id),
            ))
        }
        openDialog()

        compose.onNodeWithTag("video-option:frame-workflow").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(workflow.name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(workflow.name).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("video-option-picker").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("video-option:frame-api").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("video-option:frame-api").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(profile.displayName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(profile.displayName, substring = true).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("video-option-picker").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(profile.displayName, substring = true).assertExists()

        compose.onNodeWithText("仅字幕").performScrollTo().performClick()
        compose.onNodeWithText("加入队列").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("video-option:source").fetchSemanticsNodes().isEmpty()
        }

        val tasks = container.workDatabase.tasks().all()
        val subtitle = tasks.firstOrNull { it.type == "VIDEO_SUBTITLES" }
            ?: error("subtitle task was not queued")
        val options = JSONObject(subtitle.payload).getJSONObject("options")
        assertEquals("ASR_LOCAL", options.getString("subtitleSource"))
        assertTrue(options.isNull("audioApiProfileId"))
        assertEquals("ASR_LOCAL", container.videoTasks.options(videoId).subtitleSource)
    }
}
