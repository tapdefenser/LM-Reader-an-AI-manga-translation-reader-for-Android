package com.lmreader.ui.video

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.core.workflow.WorkflowProgramCodec
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskScheduler
import com.lmreader.ui.workflow.withApiOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real Room/workflow/options code with a non-starting scheduler host. No API is called.
 * Assertions decode the persisted task payload; they never accept a mere substring as proof.
 */
@RunWith(AndroidJUnit4::class)
class VideoApiBindingTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer

    @Before fun setup() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app) { TaskScheduler(it, canStart = { false }, acquire = { null }, release = {}) }
        container.startupReady.await()
        container.taskScheduler.ready.await()
    }

    @After fun cleanup() = runBlocking {
        if (::container.isInitialized) {
            runCatching { withTimeout(5_000) { container.taskScheduler.interrupt("TEST_CLEANUP") } }
            runCatching { closeContainer(container) }
            runCatching { container.workDatabase.close() }
        }
        if (::app.isInitialized) app.root.deleteRecursively()
    }

    @Test fun branchesKeepDifferentApisNestedNodesAreOverriddenAndLocalStillSubmits() = runBlocking {
        val file = File(app.cacheDir, "api-binding-${UUID.randomUUID()}.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file) }
        val inspected = VideoMediaSource.inspect(app, Uri.fromFile(file), persist = false)
        container.videoCatalog.importLegacy(inspected.id, inspected.uri, inspected.name, inspected.sha256,
            inspected.bytes, inspected.durationMs, inspected.width, inspected.height, inspected.rotation, 0)
        val project = container.videoTasks.ensureProject(inspected.id)

        val a = ApiProfile("api-a-${UUID.randomUUID()}", ApiProfileKind.LLM, "A",
            "https://api.example.invalid/v1", apiKey = "secret-a", model = "model-a", retryCount = 0)
        val b = a.copy(id = "api-b-${UUID.randomUUID()}", name = "B", apiKey = "secret-b", model = "model-b")
        container.apiProfiles.save(a)
        container.apiProfiles.save(b)

        val frameWorkflow = container.translationWorkflows.update(container.translationWorkflows.create().copy(
            name = "frame-api-binding-${UUID.randomUUID()}", program = WorkflowTemplates.visionApi(a.id)))
        val subtitleWorkflow = container.translationWorkflows.update(container.translationWorkflows.create().copy(
            name = "subtitle-api-binding-${UUID.randomUUID()}", program = SubtitleWorkflowTemplates.program(a.id)))

        val options = VideoWorkOptions(
            source = "en", target = "zh-Hans", mode = "BOTH",
            frameWorkflowId = frameWorkflow.id, subtitleWorkflowId = subtitleWorkflow.id,
            subtitleSource = "EXTERNAL", frameApiProfileId = b.id, subtitleApiProfileId = a.id,
        )
        val parentId = container.videoTasks.submit(project.id, options)
        val payload = JSONObject(container.workDatabase.tasks().get(parentId)!!.payload)
        val frameProgram = WorkflowProgramCodec.decode(
            payload.getJSONObject("frameWorkflow").getJSONObject("program").toString())
        val subtitleProgram = WorkflowProgramCodec.decode(
            payload.getJSONObject("subtitleWorkflow").getJSONObject("program").toString())

        fun bound(program: WorkflowProgram): Set<String> = program.allNodes()
            .filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
            .map { (it.inputs["profile"] as WorkflowExpression.Text).value }
            .toSet()

        assertEquals("frame branch must use its own API", setOf(b.id), bound(frameProgram))
        assertEquals("subtitle branch must not inherit the frame API", setOf(a.id), bound(subtitleProgram))
        assertTrue("the vision API node is nested under EACH",
            frameProgram.allNodes().any { it.id == "api-vision" })
        assertEquals("stored frame workflow must keep its default API", setOf(a.id),
            bound(container.translationWorkflows.find(frameWorkflow.id)!!.program))
        assertEquals("stored subtitle workflow must keep its default API", setOf(a.id),
            bound(container.translationWorkflows.find(subtitleWorkflow.id)!!.program))
        assertFalse("payload must not contain a profile secret", payload.toString().contains("secret-a"))
        assertFalse("payload must not contain a profile secret", payload.toString().contains("secret-b"))

        // Both children and otherwise branches are covered without mutating the source program.
        val child = WorkflowNode("nested-child", WorkflowKind.API,
            inputs = mapOf("profile" to WorkflowExpression.Text(a.id)))
        val fallback = child.copy(id = "nested-otherwise")
        val nested = WorkflowProgram(rows = listOf(WorkflowNode("branch", WorkflowKind.IF,
            children = listOf(child), otherwise = listOf(fallback))))
        assertEquals(setOf(b.id), bound(nested.withApiOverride(b.id)))
        assertEquals(setOf(a.id), bound(nested))

        // New choices survive JSON and the real settings row; old JSON and old positional calls stay valid.
        val restored = VideoWorkOptions.read(options.json())
        assertEquals(b.id, restored.frameApiProfileId)
        assertEquals(a.id, restored.subtitleApiProfileId)
        val persisted = container.videoTasks.options(project.id)
        assertEquals(b.id, persisted.frameApiProfileId)
        assertEquals(a.id, persisted.subtitleApiProfileId)
        val legacy = VideoWorkOptions.read(JSONObject(
            """{"source":"ja","target":"zh-Hans","mode":"BOTH","frameWorkflowId":"legacy-frame","subtitleWorkflowId":"legacy-subtitle"}"""))
        assertNull(legacy.frameApiProfileId)
        assertNull(legacy.subtitleApiProfileId)
        val positional = VideoWorkOptions("ja", "zh-Hans", "BOTH", "legacy-frame", "legacy-subtitle",
            1, 1, 0L, 0L, "EXTERNAL", -1, -1, null, null, "", 1280)
        assertNull(positional.frameApiProfileId)
        assertNull(positional.subtitleApiProfileId)

        // A pure local branch still submits; its API choice is persisted but the program keeps no API node.
        val localTask = container.videoTasks.submit(project.id, options.copy(
            mode = "FRAME", frameWorkflowId = TranslationWorkflow.LOCAL_MACHINE_ID,
            frameApiProfileId = b.id, subtitleApiProfileId = null))
        val localPayload = JSONObject(container.workDatabase.tasks().get(localTask)!!.payload)
        val localProgram = WorkflowProgramCodec.decode(
            localPayload.getJSONObject("frameWorkflow").getJSONObject("program").toString())
        assertFalse("a local workflow must stay local", localProgram.usesApi())
        assertEquals(b.id, localPayload.getJSONObject("options").getString("frameApiProfileId"))
        val sameLocalGoal = container.videoTasks.submit(project.id, options.copy(
            mode = "FRAME", frameWorkflowId = TranslationWorkflow.LOCAL_MACHINE_ID,
            frameApiProfileId = a.id, subtitleApiProfileId = null))
        assertEquals("unused API choices must not create a second local task", localTask, sameLocalGoal)
    }
}
