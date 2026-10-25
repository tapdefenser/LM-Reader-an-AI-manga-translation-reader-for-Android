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
import androidx.compose.ui.test.assertIsSelected
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
        val tasksBefore = container.workDatabase.tasks().all().size
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

        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("video-option:source").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("pt-BR", container.videoTasks.options(videoId).target)
        assertEquals(tasksBefore, container.workDatabase.tasks().all().size)
    }

    @Test fun defaultLocalWorkflowsHideApiAndLegacyControlsAndCloseCreatesNothing() = runBlocking<Unit> {
        val tasksBefore = container.workDatabase.tasks().all().size
        val shelfBefore = container.workDatabase.videoCatalogDao().byId(videoId)?.onShelf
        openDialog()

        assertEquals(0, compose.onAllNodesWithTag("video-option:frame-api").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("video-option:subtitle-api").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("字幕显示设置").fetchSemanticsNodes().size)
        compose.onNodeWithText("保存").assertExists()
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

    @Test fun savingOnlyChangesFutureOptionsAndPreservesExistingTaskSnapshotsAndResultMetadata() = runBlocking<Unit> {
        val old = container.videoTasks.options(videoId).copy(mode = "FRAME", source = "en")
        container.videoTasks.submit(videoId, old)
        val dao = container.workDatabase.results()
        val row = dao.settings(videoId)!!
        dao.putSettings(row.copy(json = org.json.JSONObject(row.json).put("frameIdentity", "keep-track")
            .put("resultEpoch", "keep-epoch").toString(), style = "{\"enabled\":false}"))
        val before = container.workDatabase.tasks().all().associateBy { it.taskId }
        val changed = old.copy(target = "pt-BR", frameSettings = old.frameSettings!!.copy(segThreshold = .71f,
            textDetectionThreshold = .63f, freeTextMergeGapRatio = .8f, similarityPercent = 77))
        container.videoTasks.saveOptions(videoId, changed)
        val after = container.workDatabase.tasks().all().associateBy { it.taskId }
        assertEquals(before.keys, after.keys)
        before.forEach { (id, task) ->
            assertEquals(task.payload, after.getValue(id).payload)
            assertEquals(task.generation, after.getValue(id).generation)
            assertEquals(task.state, after.getValue(id).state)
        }
        val stored = dao.settings(videoId)!!
        assertEquals("keep-track", org.json.JSONObject(stored.json).getString("frameIdentity"))
        assertEquals("keep-epoch", org.json.JSONObject(stored.json).getString("resultEpoch"))
        assertEquals("{\"enabled\":false}", stored.style)
        assertEquals(changed.frameSettings, container.videoTasks.options(videoId).frameSettings)
        assertEquals("pt-BR", container.videoTasks.options(videoId).target)
    }

    @Test fun maskTextColorSavesFromDialogWithoutChangingExistingWorkOrSubtitleStyle() = runBlocking<Unit> {
        val options = container.videoTasks.options(videoId).copy(mode = "FRAME", source = "en",
            endMs = container.videoCatalog.find(videoId)!!.durationMs)
        container.videoTasks.submit(videoId, options)
        val dao = container.workDatabase.results()
        val original = dao.settings(videoId)!!
        dao.putSettings(original.copy(style = "{\"enabled\":false,\"textColor\":123,\"offsetMs\":400,\"maskTextColor\":\"BLACK\"}"))
        val before = container.workDatabase.tasks().all().associateBy { it.taskId }
        openDialog()
        compose.onNodeWithTag("video-mask-text:BLACK").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("video-mask-text:WHITE").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("video-option:source").fetchSemanticsNodes().isEmpty() }
        val stored = dao.settings(videoId)!!
        assertEquals(com.lmreader.core.model.BubbleTextColorMode.WHITE, videoMaskTextColor(stored.style))
        val style = org.json.JSONObject(stored.style)
        assertEquals(false, style.getBoolean("enabled"))
        assertEquals(123, style.getInt("textColor"))
        assertEquals(400, style.getInt("offsetMs"))
        assertEquals(original.json, stored.json)
        val after = container.workDatabase.tasks().all().associateBy { it.taskId }
        assertEquals(before.keys, after.keys)
        // Parent progress text and normalized sort indexes reconcile asynchronously;
        // the saved work, its execution generation and its safe checkpoint must not change.
        before.forEach { (id, task) ->
            val current = after.getValue(id)
            assertEquals(task.payload, current.payload)
            assertEquals(task.generation, current.generation)
            assertEquals(task.state, current.state)
            assertEquals(task.checkpoint, current.checkpoint)
            assertEquals(task.completed, current.completed)
        }
    }

    @Test fun missingWorkflowCapabilitiesRouteByTheActualFailureRatherThanSubtitleSource() = runBlocking<Unit> {
        assertEquals("ASR_LOCAL", container.videoTasks.options(videoId).subtitleSource)
        val local = container.translationWorkflows.find(com.lmreader.core.model.TranslationWorkflow.LOCAL_MACHINE_ID)!!
        val from = com.lmreader.core.model.LocalTranslationLanguage.fromTag("en")
        val to = com.lmreader.core.model.LocalTranslationLanguage.fromTag("zh-Hans")
        for ((program, reason, route) in listOf(
            Triple(local.program, com.lmreader.tasks.TaskCapability.TRANSLATION.reason, "settings/api/local"),
            Triple(WorkflowTemplates.visionApi("missing-profile"), com.lmreader.tasks.TaskCapability.API.reason, "settings/api"),
        )) {
            val failure = runCatching { com.lmreader.tasks.requireWorkflowCapabilities(container, program, from, to, emptyList()) }.exceptionOrNull()
            assertTrue(failure is com.lmreader.tasks.TaskWaiting)
            assertEquals(reason, (failure as com.lmreader.tasks.TaskWaiting).reason)
            assertEquals(route, com.lmreader.tasks.capabilitySettingsRoute(failure.reason))
        }
    }

    @Test fun subtitleSourceListContainsTheDisplaySettingsWithoutCreatingTasks() = runBlocking<Unit> {
        val before = container.workDatabase.tasks().all().size
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(true) }
                if(open) VideoSubtitleSourceDialog(container, videoId) { open = false }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("字幕显示设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("翻译产生的字幕").performScrollTo().assertExists()
        compose.onNodeWithText("导入字幕…").performScrollTo().assertExists()
        compose.onNodeWithText("字幕显示设置").performScrollTo().assertExists()
        assertEquals(before, container.workDatabase.tasks().all().size)
        compose.onNodeWithText("关闭").performClick()
    }

    @Test fun apiWorkflowRevealsFrameApiPickerAndSavingKeepsLocalAsrWithoutQueuing() = runBlocking<Unit> {
        val profile = ApiProfile("profile-${UUID.randomUUID()}", ApiProfileKind.LLM, "测试配置",
            "https://api.example.invalid/v1", apiKey = "not-used", model = "test-model", retryCount = 0)
        container.apiProfiles.save(profile)
        val workflow = container.translationWorkflows.create().let { created ->
            container.translationWorkflows.update(created.copy(
                name = "画面API-${UUID.randomUUID()}",
                program = WorkflowTemplates.visionApi(profile.id),
            ))
        }
        val tasksBefore = container.workDatabase.tasks().all().size
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
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("video-option:source").fetchSemanticsNodes().isEmpty()
        }

        val saved = container.videoTasks.options(videoId)
        assertEquals("SUBTITLE", saved.mode)
        assertEquals("ASR_LOCAL", saved.subtitleSource)
        assertEquals(profile.id, saved.frameApiProfileId)
        assertTrue(saved.asrModelId == null)
        assertEquals(tasksBefore, container.workDatabase.tasks().all().size)
    }
}
