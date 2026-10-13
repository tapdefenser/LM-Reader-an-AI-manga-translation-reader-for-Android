package com.lmreader.screen

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.app.UiAutomation
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.MainActivity
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*

/** Requires assistance, the public en -> zh-Hans pack, and ordinary overlay access for the projection fixture. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 34)
class ScreenTranslationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context get() = instrumentation.targetContext
    private val container get() = AppContainer.from(context)
    private val controller get() = container.screenTranslation
    private suspend fun waitState(predicate: (ScreenTranslationState) -> Boolean) = withTimeout(90_000) { controller.state.first(predicate) }
    private suspend fun start(mode: ScreenTranslationMode,
        workflowId: String = TranslationWorkflow.LOCAL_MACHINE_ID, appStream: Boolean = false, manualProjection: Boolean = false,
        regions: ScreenRegionSelection = ScreenRegionSelection()): ScreenTranslationSettings {
        // Instrumentation force-stops the old target process, including its
        // bound assistant. Rebind after startup while automation leaves normal
        // accessibility services enabled. This changes only the MuMu test app.
        if (ScreenAccessibilityService.instance == null) {
            automation.executeShellCommand("settings put secure enabled_accessibility_services null").close()
            delay(100)
            automation.executeShellCommand("settings put secure enabled_accessibility_services ${context.packageName}/com.lmreader.screen.ScreenAccessibilityService").close()
            automation.executeShellCommand("settings put secure accessibility_enabled 1").close()
        }
        withTimeout(15_000) { while (ScreenAccessibilityService.instance == null) delay(50) }
        val original = controller.preferences.current()
        val settings = ScreenTranslationSettings(mode = mode, preferAccessibility = !manualProjection,
            translation = MangaTranslationSettings(sourceLanguage = "en", targetLanguage = "zh-Hans", workflowId = workflowId, segTextScope = SegTextScope.FREE_TEXT),
            vision = VisionExecutionSettings(1, 1, false, OcrBackend.CPU), maximumCaptureEdge = 1600, sampleIntervalMillis = 200,
            browseRegions = regions, videoRegions = regions)
        controller.preferences.update { settings }
        controller.preferences.settings.first { it.mode == mode && it.preferAccessibility == !manualProjection && it.translation.sourceLanguage == "en" && it.translation.workflowId == workflowId }
        withContext(Dispatchers.Main) { controller.start(context) }
        if (manualProjection) consentToRecording(singleApp = false)
        else if (settings.requiresAccessibility) { if (appStream) consentToRecording(singleApp = true) else declineRecordingForWindowFallback() }
        waitState { it.started }
        val ready = waitState { it.engine == ScreenEngineState.READY || it.engine == ScreenEngineState.FAILED }
        assertEquals(ready.status, ScreenEngineState.READY, ready.engine)
        return original
    }
    private suspend fun declineRecordingForWindowFallback() = withTimeout(15_000) {
        fun findStart(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.viewIdResourceName in setOf("com.android.systemui:id/cancel", "android:id/button2") || node.text?.toString() in setOf("取消", "Cancel")) return node
            for (i in 0 until node.childCount) findStart(node.getChild(i))?.let { return it }
            return null
        }
        while (!controller.state.value.started) {
            findStart(automation.rootInActiveWindow)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
        }
    }
    private suspend fun fixture(video: Boolean = false, fixedControls: Boolean = false, orientation: Int? = null,
        videoText: String? = null, subtitleShift: Float = 0f) {
        val intent = Intent().setComponent(ComponentName(instrumentation.context.packageName, ScreenFixtureActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("video", video).putExtra("fixedControls", fixedControls)
            .putExtra("videoText", videoText).putExtra("subtitleShift", subtitleShift)
        orientation?.let { intent.putExtra("orientation", it) }
        context.startActivity(intent)
        waitState { !it.inside }; delay(700)
    }
    private suspend fun consentToRecording(singleApp: Boolean) = withTimeout(25_000) {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
            return null
        }
        fun click(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            var current: android.view.accessibility.AccessibilityNodeInfo? = node
            repeat(5) { if (current?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return true; current = current?.parent }
            return false
        }
        var modeChosen = false; var openedMode = false
        while (!controller.state.value.started) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() != "com.android.systemui") { delay(100); continue }
            val fixture = find(root) { it.text?.toString()?.contains("Screen translation fixture") == true ||
                it.contentDescription?.toString()?.contains("Screen translation fixture") == true ||
                it.contentDescription?.toString() == instrumentation.context.packageName }
            val choice = find(root) { it.text?.toString() in if (singleApp)
                setOf("单个应用", "共享一个应用", "一个应用", "Single app", "A single app", "An app") else
                setOf("整个屏幕", "共享整个屏幕", "Entire screen", "Your entire screen") }
            val spinner = find(root) { it.className?.toString() == "android.widget.Spinner" }
            when {
                singleApp && fixture != null -> click(fixture)
                !modeChosen && choice != null && openedMode -> { click(choice); modeChosen = true }
                !modeChosen && spinner != null && !openedMode -> { click(spinner); openedMode = true }
                else -> find(root) { it.viewIdResourceName == "com.android.systemui:id/start" ||
                    it.text?.toString() in setOf("立即开始", "开始录制", "共享屏幕", "Start now", "Start recording", "Share screen", "Next", "下一步") }?.let(::click)
            }
            delay(100)
        }
    }

    private suspend fun awaitApplied(settings: (ScreenTranslationSettings) -> Boolean = { true },
        workflowId: String? = null, revision: Int? = null) = withTimeout(90_000) {
        while (!withContext(Dispatchers.Main) {
            controller.state.value.engine == ScreenEngineState.READY &&
                controller.appliedEngineSettings()?.let(settings) == true &&
                (workflowId == null || controller.appliedEngineWorkflow()?.id == workflowId) &&
                (revision == null || controller.appliedEngineWorkflow()?.revision == revision)
        }) delay(20)
    }

    @Test fun liveSettingsRetainOcrAndAddOrRemoveOnlyNeededSegClones() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val original = start(ScreenTranslationMode.MANUAL)
            val observed = java.util.concurrent.CopyOnWriteArrayList<List<LoadedEngineResource>>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { controller.vision.loadedResources.collect { observed += it } }
            try {
                val initial = controller.vision.loadedResources.first { it.size == 2 }
                controller.preferences.update { it.copy(sampleIntervalMillis = 777) }
                awaitApplied({ it.sampleIntervalMillis == 777 })
                controller.preferences.update { it.copy(translation = it.translation.copy(segTextScope = SegTextScope.ALL)) }
                awaitApplied({ it.translation.segTextScope == SegTextScope.ALL })
                controller.vision.loadedResources.first { it.size == 3 && it.any { r -> r.kind == InferenceEngineKind.SEG } }
                controller.preferences.update { it.copy(vision = it.vision.copy(segConcurrency = 2)) }
                awaitApplied({ it.vision.segConcurrency == 2 })
                controller.vision.loadedResources.first { it.count { r -> r.kind == InferenceEngineKind.SEG } == 2 }
                controller.preferences.update { it.copy(translation = it.translation.copy(segTextScope = SegTextScope.FREE_TEXT)) }
                awaitApplied({ it.translation.segTextScope == SegTextScope.FREE_TEXT })
                val final = controller.vision.loadedResources.first { it.size == 2 }
                assertEquals(initial.map { it.id }.toSet(), final.map { it.id }.toSet())
                assertTrue("Common OCR resources must remain resident throughout hot reload: $observed",
                    observed.all { resources -> initial.all { old -> resources.any { it.id == old.id } } })
                assertTrue(container.localTranslator.loadedResources.value.isNotEmpty())
            } finally {
                observer.cancelAndJoin(); controller.stopAndAwait(); controller.preferences.update { original }
            }
        }
    }

    @Test fun selectedWorkflowSaveAndSwitchReconcileResourcesAndResumeBrowseWithoutTapping() = runBlocking<Unit> {
        val changed = container.translationWorkflows.create()
        val unrelated = container.translationWorkflows.create()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val original = start(ScreenTranslationMode.BROWSE)
                try {
                    fixture(); withContext(Dispatchers.Main) { controller.tap() }
                    waitState { !it.busy && it.bubbles > 0 }
                    controller.preferences.update { it.copy(translation = it.translation.copy(workflowId = changed.id)) }
                    awaitApplied(workflowId = changed.id, revision = changed.revision)
                    assertTrue(controller.state.value.continuous)
                    controller.vision.loadedResources.first { it.size == 1 && it.single().id.endsWith(":det") }
                    withTimeout(10_000) { container.localTranslator.loadedResources.first { it.isEmpty() } }
                    val edited = container.translationWorkflows.update(changed.copy(program = WorkflowTemplates.localMachine(), retries = 0))
                    awaitApplied(workflowId = changed.id, revision = edited.revision)
                    assertTrue(controller.state.value.continuous)
                    waitState { !it.busy && it.bubbles > 0 }
                    assertTrue(container.localTranslator.loadedResources.value.isNotEmpty())
                    val before = withContext(Dispatchers.Main) { controller.bubblePositions().keys }
                    container.translationWorkflows.update(unrelated.copy(name = "Unrelated saved edit"))
                    delay(600)
                    assertEquals("Unselected workflow edits must not interrupt this screen session", before,
                        withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                    // Manual stop remains authoritative even when the selected workflow changes.
                    withContext(Dispatchers.Main) { controller.stopEngine() }
                    waitState { it.engine == ScreenEngineState.STOPPED }
                    container.translationWorkflows.update(edited.copy(name = "Saved while stopped"))
                    controller.preferences.update { it.copy(sampleIntervalMillis = 888) }
                    delay(600)
                    assertEquals(ScreenEngineState.STOPPED, controller.state.value.engine)
                    assertTrue(controller.vision.loadedResources.value.isEmpty())
                } finally { controller.stopAndAwait(); controller.preferences.update { original } }
            }
        } finally {
            container.translationWorkflows.delete(changed.id); container.translationWorkflows.delete(unrelated.id)
        }
    }

    @Test fun liveApiProfileAndWorkflowPromptEditsApplyToNextScreenRequests() = runBlocking<Unit> {
        val profileId = java.util.UUID.randomUUID().toString()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    requests += request.body.readUtf8()
                    return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
                }
            }
            start()
        }
        val created = container.translationWorkflows.create()
        val profile = ApiProfile(profileId, ApiProfileKind.LLM, "Live API fixture", server.url("/v1").toString(), model = "initial-model", retryCount = 0)
        fun program(prompt: String) = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
            node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text(prompt)))
        }
        try {
            container.apiProfiles.save(profile)
            val selected = container.translationWorkflows.update(created.copy(program = program("initial-prompt"), retries = 0))
            ActivityScenario.launch(MainActivity::class.java).use {
                val original = start(ScreenTranslationMode.BROWSE, workflowId = created.id)
                try {
                    fixture(); withContext(Dispatchers.Main) { controller.tap() }
                    waitState { !it.busy && it.bubbles > 0 }
                    container.apiProfiles.save(profile.copy(model = "updated-model"))
                    withTimeout(60_000) { while (requests.none { it.contains("updated-model") }) delay(20) }
                    val edited = container.translationWorkflows.update(selected.copy(program = program("updated-prompt")))
                    awaitApplied(workflowId = created.id, revision = edited.revision)
                    withTimeout(60_000) { while (requests.none { it.contains("updated-prompt") && it.contains("updated-model") }) delay(20) }
                    waitState { it.continuous && !it.busy && it.bubbles > 0 }
                } finally { controller.stopAndAwait(); controller.preferences.update { original } }
            }
        } finally {
            container.translationWorkflows.delete(created.id); container.apiProfiles.delete(profileId); server.shutdown()
        }
    }

    @Test fun invalidAndRapidConfigurationChangesRecoverToLatestRequestedEngine() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val original = start(ScreenTranslationMode.MANUAL)
            try {
                controller.preferences.update { it.copy(translation = it.translation.copy(workflowId = "deleted-fixture-workflow")) }
                waitState { it.engine == ScreenEngineState.FAILED }
                // Validation failures must not discard the last usable models.
                assertEquals(setOf("det", "rec"), controller.vision.loadedResources.value.map { it.id.substringAfterLast(":") }.toSet())
                controller.preferences.update { it.copy(translation = it.translation.copy(workflowId = TranslationWorkflow.LOCAL_MACHINE_ID, segTextScope = SegTextScope.ALL)) }
                controller.preferences.settings.first { it.translation.segTextScope == SegTextScope.ALL }
                controller.preferences.update { it.copy(vision = it.vision.copy(segGpu = true), sampleIntervalMillis = 654) }
                controller.preferences.update { it.copy(vision = it.vision.copy(segGpu = false),
                    translation = it.translation.copy(segTextScope = SegTextScope.FREE_TEXT), sampleIntervalMillis = 987) }
                awaitApplied({ it.sampleIntervalMillis == 987 && !it.vision.segGpu && it.translation.segTextScope == SegTextScope.FREE_TEXT })
                withTimeout(10_000) { controller.vision.loadedResources.first { it.size == 2 && it.none { r -> r.kind == InferenceEngineKind.SEG } } }
                fixture(); withContext(Dispatchers.Main) { controller.tap() }
                waitState { !it.busy && it.bubbles > 0 }
            } finally { controller.stopAndAwait(); controller.preferences.update { original } }
        }
    }

    @Test fun engineWarmupKeepsItsAdmissionLimitWhenAvailableMemoryChanges() = runBlocking<Unit> {
        controller.stopAndAwait()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val translator = com.lmreader.core.translation.BergamotTextTranslator(
            { container.translationModels.installedCatalog() }, container.translationInstaller,
            { MachineTranslationResources(2, 1024) },
            { if (reads.incrementAndGet() <= 2) 4L * 1024 * 1_048_576 else 64L * 1_048_576 })
        val owner = "warmup-memory-fixture"
        try {
            withTimeout(20_000) { translator.prepare(owner, setOf(LocalTranslationLanguage.ENGLISH to LocalTranslationLanguage.CHINESE_SIMPLIFIED)) }
            assertEquals(2, translator.loadedResources.value.size)
            assertEquals("Warm-up must snapshot capacity once rather than change it between workers", 2, reads.get())
        } finally { translator.unpin(owner); translator.retainModels(emptySet()) }
    }

    @Test fun dialogsAndInlineMenusKeepPublishedBubblesAndTrackingResumesAfterDismissal() = runBlocking<Unit> {
        for (appStream in listOf(false, true)) {
            ActivityScenario.launch(MainActivity::class.java).use {
                fixture(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                waitState { it.inside }
                val original = start(ScreenTranslationMode.BROWSE, appStream = appStream)
                fun popup(window: Boolean = false, inline: Boolean = false) {
                    context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ScreenFixtureActivity::class.java.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("popup", window).putExtra("inlinePopup", inline).putExtra("fixedControls", true))
                }
                try {
                    fixture(fixedControls = true, orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                    withContext(Dispatchers.Main) { controller.traceMotion = true; controller.motionTrace.clear(); controller.tap() }
                    waitState { it.bubbles > 0 && !it.busy }
                    val before = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    popup(window = true)
                    try { withTimeout(3000) { while (ScreenAccessibilityService.instance?.contentOcclusions.isNullOrEmpty()) delay(20) } }
                    catch (timeout: TimeoutCancellationException) { throw AssertionError("Dialog detection appStream=$appStream: ${ScreenAccessibilityService.instance?.windowDiagnostics()} trace=${controller.motionTrace} root=${automation.rootInActiveWindow}", timeout) }
                    delay(1500)
                    assertEquals("A real dialog must not clear the underlying page: ${controller.motionTrace}", before.keys,
                        withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                    popup(); delay(650)
                    assertEquals(before.keys, withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                    val panelReference = withContext(Dispatchers.Main) { controller.trackingSample() }
                    popup(inline = true); delay(1500)
                    if (before.keys != withContext(Dispatchers.Main) { controller.bubblePositions().keys }) {
                        panelReference?.let { saveMotionSample("panel-before", it) }
                        controller.rejectedMotion?.second?.let { saveMotionSample("panel-after", it) }
                    }
                    assertEquals("An in-window menu is occlusion rather than a scene cut: ${controller.motionDiagnostics()} ${controller.motionTrace}", before.keys,
                        withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                    popup(); delay(650)
                    val restored = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    assertEquals(before.keys, restored.keys)
                    swipe(distance = 120f)
                    awaitMovement("tracking after both kinds of popup close", restored) { old, now -> kotlin.math.abs(now.top - old.top + 120f) < 16f }
                } finally { popup(); withContext(Dispatchers.Main) { controller.traceMotion = false; controller.stop() }; controller.preferences.update { original } }
            }
        }
    }

    @Test fun everyModeSkipsAnExhaustedPageAndCanTranslateTheNextFrame() = runBlocking<Unit> {
        for (mode in ScreenTranslationMode.entries) {
            val profileId = "screen-failure-${java.util.UUID.randomUUID()}"
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val server = okhttp3.mockwebserver.MockWebServer().apply {
                dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                        if (calls.incrementAndGet() == 1) okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("Fixture page failed")
                        else okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
                }; start()
            }
            var workflowId: String? = null
            try {
                container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Screen failure fixture", server.url("/v1").toString(),
                    model = "fixture", parallelLimit = 1, retryCount = 0))
                val created = container.translationWorkflows.create().also { workflowId = it.id }
                val api = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
                    node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                        "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Translate this fixture.")))
                }
                val program = com.lmreader.core.workflow.WorkflowEditing.update(api, "bubbles") { it.copy(mode = WorkflowMode.SYNC) }
                container.translationWorkflows.update(created.copy(program = program, retries = 0))
                ActivityScenario.launch(MainActivity::class.java).use {
                    val original = start(mode, workflowId = created.id)
                    val pendingRoot = java.io.File(context.noBackupFilesDir, "screen-pending")
                    var failedId: String? = null
                    try {
                        val retained = ScreenPendingFrames(pendingRoot).count.value
                        fixture(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                        withContext(Dispatchers.Main) { controller.tap() }
                        waitState { !it.busy && it.status.startsWith("当前画面失败") }
                        assertEquals(ScreenEngineState.READY, controller.state.value.engine)
                        assertEquals("$mode must release the failed frame slot", 0, controller.state.value.activeChapters)
                        assertEquals(mode != ScreenTranslationMode.MANUAL, controller.state.value.continuous)
                        assertTrue("Failed detection masks must be withdrawn", withContext(Dispatchers.Main) { controller.bubblePositions(true).isEmpty() })
                        val saved = ScreenPendingFrames(pendingRoot).latest()!!; failedId = saved.pageId
                        assertEquals(retained + 1, controller.pendingFrames.value)
                        assertNotNull(saved.error); assertTrue(saved.image.isFile)
                        delay(750)
                        assertEquals("A static failed page must not immediately resubmit", 1, calls.get())
                        when (mode) {
                            ScreenTranslationMode.MANUAL -> withContext(Dispatchers.Main) { controller.tap() }
                            ScreenTranslationMode.BROWSE -> swipe(distance = 400f)
                            ScreenTranslationMode.VIDEO -> press(longPress = false)
                        }
                        waitState { !it.busy && it.bubbles > 0 }
                        assertTrue("A later frame must reach the API", calls.get() > 1)
                        assertEquals(retained + 1, controller.pendingFrames.value)
                        assertTrue("Later success must retain the failed retry input", saved.image.isFile)
                    } finally {
                        withContext(Dispatchers.Main) { controller.stopAndAwait() }
                        failedId?.let { ScreenPendingFrames(pendingRoot).complete(it) }
                        controller.preferences.update { original }
                    }
                }
            } finally { server.shutdown(); workflowId?.let { container.translationWorkflows.delete(it) }; container.apiProfiles.delete(profileId) }
        }
    }

    @Test fun browseFailureBeforeSegSkipsStaticPixelsAndRecognizesNewExposure() = runBlocking<Unit> {
        val profileId = "screen-preseg-failure-${java.util.UUID.randomUUID()}"
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (calls.incrementAndGet() == 1) okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("Fixture before SEG failed")
                    else okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
            }; start()
        }
        var workflowId: String? = null
        try {
            container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Screen before SEG fixture", server.url("/v1").toString(),
                model = "fixture", parallelLimit = 1, retryCount = 0))
            val created = container.translationWorkflows.create().also { workflowId = it.id }
            val api = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
                node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                    "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Translate this fixture.")))
            }
            val checked = WorkflowVariable("screen-check", "Frame check", WorkflowType.TEXT)
            val declare = WorkflowNode("declare-screen-check", WorkflowKind.DECLARE, variable = checked)
            val gate = WorkflowNode("before-seg", WorkflowKind.API, target = WorkflowRef(checked.id), inputs = mapOf(
                "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Check this fixture page.")))
            val program = com.lmreader.core.workflow.WorkflowEditing.update(api, "pages") { it.copy(children = listOf(declare, gate) + it.children) }
            container.translationWorkflows.update(created.copy(program = program, retries = 0))
            ActivityScenario.launch(MainActivity::class.java).use {
                val original = start(ScreenTranslationMode.BROWSE, workflowId = created.id)
                val pendingRoot = java.io.File(context.noBackupFilesDir, "screen-pending")
                var failedId: String? = null
                try {
                    fixture(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                    withContext(Dispatchers.Main) { controller.tap() }
                    waitState { !it.busy && it.status.startsWith("当前画面失败") }
                    failedId = ScreenPendingFrames(pendingRoot).latest()!!.pageId
                    assertTrue(controller.browseRecognitionHistory.isEmpty())
                    delay(750); assertEquals(1, calls.get()); assertTrue(controller.state.value.continuous)
                    swipe(distance = 400f)
                    waitState { !it.busy && it.bubbles > 0 }
                    assertTrue(controller.browseRecognitionHistory.isNotEmpty())
                } finally {
                    withContext(Dispatchers.Main) { controller.stopAndAwait() }
                    failedId?.let { ScreenPendingFrames(pendingRoot).complete(it) }
                    controller.preferences.update { original }
                }
            }
        } finally { server.shutdown(); workflowId?.let { container.translationWorkflows.delete(it) }; container.apiProfiles.delete(profileId) }
    }

    @Test fun videoFailureInOneSelectedCropDoesNotSkipOtherCropsOfTheSameFrame() = runBlocking<Unit> {
        val profileId = "screen-crop-failure-${java.util.UUID.randomUUID()}"
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (calls.incrementAndGet() == 1) okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("Fixture first crop failed")
                    else okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
            }; start()
        }
        var workflowId: String? = null
        try {
            container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Screen crop fixture", server.url("/v1").toString(),
                model = "fixture", parallelLimit = 1, retryCount = 0))
            val created = container.translationWorkflows.create().also { workflowId = it.id }
            val api = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
                node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                    "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Translate this fixture.")))
            }
            val program = com.lmreader.core.workflow.WorkflowEditing.update(api, "bubbles") { it.copy(mode = WorkflowMode.SYNC) }
            container.translationWorkflows.update(created.copy(program = program, retries = 0))
            ActivityScenario.launch(MainActivity::class.java).use {
                val regions = ScreenRegionSelection(portrait = listOf(ScreenNormalizedRegion(0f, .05f, 1f, .45f), ScreenNormalizedRegion(0f, .5f, 1f, .95f)))
                val original = start(ScreenTranslationMode.VIDEO, workflowId = created.id, regions = regions)
                val pendingRoot = java.io.File(context.noBackupFilesDir, "screen-pending")
                var failedId: String? = null
                val existingInputs = pendingRoot.listFiles().orEmpty().filter { it.extension == "json" }.map { it.name }.toSet()
                try {
                    val retained = ScreenPendingFrames(pendingRoot).count.value
                    fixture(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                    withContext(Dispatchers.Main) { controller.tap() }
                    withTimeout(90_000) {
                        while (failedId == null || controller.state.value.bubbles == 0) {
                            failedId = pendingRoot.listFiles().orEmpty().filter { it.extension == "json" && it.name !in existingInputs }
                                .firstNotNullOfOrNull { file -> runCatching {
                                    org.json.JSONObject(file.readText()).takeIf { it.has("error") }?.getString("id")
                                }.getOrNull() }
                            delay(20)
                        }
                    }
                    assertTrue("The second crop must publish despite the first crop's error", withContext(Dispatchers.Main) { controller.bubblePositions().isNotEmpty() })
                    assertTrue(calls.get() > 1); assertTrue(controller.state.value.continuous)
                    assertTrue(controller.state.value.activeChapters in 0..2)
                    assertTrue(controller.pendingFrames.value >= retained + 1)
                } finally {
                    withContext(Dispatchers.Main) { controller.stopAndAwait() }
                    pendingRoot.listFiles().orEmpty().filter { it.extension == "json" && it.name !in existingInputs }.forEach { file ->
                        runCatching { org.json.JSONObject(file.readText()).getString("id") }.getOrNull()
                            ?.let { ScreenPendingFrames(pendingRoot).complete(it) }
                    }
                    controller.preferences.update { original }
                }
            }
        } finally { server.shutdown(); workflowId?.let { container.translationWorkflows.delete(it) }; container.apiProfiles.delete(profileId) }
    }

    @Test fun onlyBrowseShowsDetectionMasksAndEveryModePublishesBeforeTheNextBubbleCompletes() = runBlocking<Unit> {
        for (mode in ScreenTranslationMode.entries) {
            val profileId = "screen-progress-${java.util.UUID.randomUUID()}"
            val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
            val firstReply = java.util.concurrent.CountDownLatch(1); val secondReply = java.util.concurrent.CountDownLatch(1)
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val server = okhttp3.mockwebserver.MockWebServer().apply {
                dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                        when (calls.incrementAndGet()) {
                            1 -> { first.complete(Unit); firstReply.await(60, java.util.concurrent.TimeUnit.SECONDS) }
                            2 -> { second.complete(Unit); secondReply.await(60, java.util.concurrent.TimeUnit.SECONDS) }
                        }
                        return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
                    }
                }; start()
            }
            var workflowId: String? = null
            try {
                container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Screen progress fixture", server.url("/v1").toString(),
                    model = "fixture", parallelLimit = 1, retryCount = 0))
                val workflow = container.translationWorkflows.create().also { workflowId = it.id }
                val program = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
                    node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                        "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Translate this fixture.")))
                }
                container.translationWorkflows.update(workflow.copy(program = program, retries = 0))
                ActivityScenario.launch(MainActivity::class.java).use {
                    val original = start(mode, workflowId = workflow.id)
                    try {
                        fixture(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                        withContext(Dispatchers.Main) { controller.traceMotion = true; controller.motionTrace.clear(); controller.tap() }
                        withTimeout(90_000) { first.await() }
                        val masks = withContext(Dispatchers.Main) { controller.bubblePositions(includePlaceholders = true) }
                        assertTrue("No translation exists while the first request is blocked", withContext(Dispatchers.Main) { controller.bubblePositions().isEmpty() })
                        if (mode == ScreenTranslationMode.BROWSE) {
                            assertTrue("Detection must draw masks before translation", masks.isNotEmpty())
                            swipe(distance = 100f)
                            withTimeout(3000) { while (withContext(Dispatchers.Main) { masks.none { (id, old) ->
                                controller.bubblePositions(true)[id]?.let { kotlin.math.abs(it.top - old.top + 100f) < 16f } == true } }) delay(20) }
                        } else assertTrue("$mode must not show empty detection masks", masks.isEmpty())
                        firstReply.countDown(); withTimeout(20_000) { second.await() }
                        withTimeout(3000) { while (withContext(Dispatchers.Main) { controller.bubblePositions().isEmpty() }) delay(20) }
                        assertTrue("A ready bubble appears while a sibling is still waiting", controller.state.value.busy)
                        if (mode == ScreenTranslationMode.BROWSE) {
                            val filled = withContext(Dispatchers.Main) { controller.bubblePositions() }
                            assertTrue("Fill the existing tracking identity", filled.keys.any { it in masks })
                            val beforeMove = withContext(Dispatchers.Main) { controller.bubblePositions() }
                            swipe(distance = 40f)
                            awaitMovement("a newly filled bubble immediately participates in flow", beforeMove) { old, now -> kotlin.math.abs(now.top - old.top + 40f) < 16f }
                        }
                        secondReply.countDown(); waitState { !it.busy }
                        assertTrue(controller.state.value.status, withContext(Dispatchers.Main) { controller.bubblePositions().isNotEmpty() })
                    } finally { firstReply.countDown(); secondReply.countDown(); withContext(Dispatchers.Main) { controller.traceMotion = false; controller.stop() }; controller.preferences.update { original } }
                }
            } finally {
                firstReply.countDown(); secondReply.countDown(); server.shutdown()
                workflowId?.let { container.translationWorkflows.delete(it) }; container.apiProfiles.delete(profileId)
            }
        }
    }

    @Test fun stoppedProjectionKeepsBubblesAndContinuousTrackingResumesOnWindowScreenshots() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            fixture(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            waitState { it.inside }
            val original = start(ScreenTranslationMode.BROWSE, appStream = true)
            try {
                fixture(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                withContext(Dispatchers.Main) { controller.tap() }
                waitState { it.bubbles > 0 && !it.busy }
                val before = withContext(Dispatchers.Main) { controller.bubblePositions() }
                assertTrue(withContext(Dispatchers.Main) { controller.motionDiagnostics().contains("source=单应用录屏流") })
                // Stop the actual projection through Android's public API, delivering
                // its normal onStop callback rather than invoking a controller stub.
                val captureField = ScreenTranslationController::class.java.getDeclaredField("projection").apply { isAccessible = true }
                val capture = captureField.get(controller) as ProjectionScreenCapture
                val projectionField = ProjectionScreenCapture::class.java.getDeclaredField("projection").apply { isAccessible = true }
                val projection = projectionField.get(capture) as android.media.projection.MediaProjection
                withContext(Dispatchers.Main) { projection.stop() }
                withTimeout(10_000) { while (!withContext(Dispatchers.Main) {
                    controller.motionDiagnostics().contains("source=无障碍窗口截图") && controller.state.value.continuous
                }) delay(100) }
                assertEquals(before.keys, withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                swipe(distance = 90f)
                awaitMovement("projection stopped, window capture follows again", before) { old, now -> kotlin.math.abs(now.top - old.top + 90f) < 16f }
                assertTrue(controller.state.value.continuous)
                assertEquals(ScreenEngineState.READY, controller.state.value.engine)
            } finally { withContext(Dispatchers.Main) { controller.stop() }; controller.preferences.update { original } }
        }
    }
    @Test fun singleAppStreamExcludesOurOverlayAndFollowsPanAndZoom() = runBlocking<Unit> {
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            ActivityScenario.launch(MainActivity::class.java).use {
                fixture(orientation = orientation) // Leave this target task in Recents before opening the share picker.
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                waitState { it.inside }
                val original = start(ScreenTranslationMode.BROWSE, appStream = true)
                try {
                    fixture(fixedControls = true, orientation = orientation)
                    withContext(Dispatchers.Main) { controller.traceMotion = true; controller.motionTrace.clear(); controller.tap() }
                    waitState { it.bubbles > 0 && !it.busy }
                    val diagnostic = withContext(Dispatchers.Main) { controller.motionDiagnostics() }
                    assertTrue(diagnostic, diagnostic.contains("source=单应用录屏流"))
                    val before = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    dragWhileHeld(120f) {
                        awaitMovement("single-app stream pan while finger is down", before) { old, now -> kotlin.math.abs(now.top - old.top + 120f) < 16f }
                    }
                    val search = before.maxBy { it.value.top }
                    val afterPan = withContext(Dispatchers.Main) { controller.bubblePositions()[search.key] }
                    assertNotNull("Fixed search object must survive the drag", afterPan)
                    val searchDiagnostic = withContext(Dispatchers.Main) { controller.objectSimilarityDiagnostics(search.key, search.value) }
                    assertEquals("Fixed search must not follow content pan: $searchDiagnostic", search.value.top, afterPan!!.top, 10f)
                    val beforeZoom = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ScreenFixtureActivity::class.java.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("scale", 1.25f).putExtra("shift", -180f).putExtra("fixedControls", true))
                    awaitMovement("single-app stream simultaneous pan/zoom", beforeZoom) { old, now ->
                        kotlin.math.abs(now.width / old.width - 1.25f) < .08f &&
                            kotlin.math.abs(now.top - ((old.top + 120f) * 1.25f - 180f)) < 25f }
                    val afterZoom = withContext(Dispatchers.Main) { controller.bubblePositions()[search.key] }
                    assertNotNull(afterZoom)
                    assertEquals("Fixed search must retain position during mixed pan/zoom", search.value.top, afterZoom!!.top, 10f)
                    assertEquals("Fixed search must retain size during zoom", search.value.width, afterZoom.width, 10f)
                } finally { withContext(Dispatchers.Main) { controller.traceMotion = false; controller.stop() }; controller.preferences.update { original } }
            }
        }
    }
    @Test fun nextScreenChapterSegmentsWhileTheFirstApiIsBlocked() = runBlocking<Unit> {
        val profileId = "screen-buffer-${java.util.UUID.randomUUID()}"
        val requested = CompletableDeferred<Unit>()
        val reply = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (calls.incrementAndGet() == 1) { requested.complete(Unit); reply.await(60, java.util.concurrent.TimeUnit.SECONDS) }
                    return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
                }
            }
            start()
        }
        var workflowId: String? = null
        val originalPriority = container.translationQueue.schedulingPriority.value
        try {
            container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Screen buffer fixture", server.url("/v1").toString(),
                model = "fixture", parallelLimit = 1, retryCount = 0))
            val created = container.translationWorkflows.create().also { workflowId = it.id }
            val program = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
                node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                    "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Translate this fixture text.")))
            }
            container.translationWorkflows.update(created.copy(program = program, retries = 0))
            container.translationQueue.setSchedulingPriority(com.lmreader.ui.queue.TranslationSchedulingPriority.RESOURCES)
            ActivityScenario.launch(MainActivity::class.java).use {
                val original = start(ScreenTranslationMode.BROWSE, workflowId = created.id)
                try {
                    val retainedBefore = ScreenPendingFrames(java.io.File(context.noBackupFilesDir, "screen-pending")).count.value
                    fixture(); withContext(Dispatchers.Main) { controller.tap() }
                    withTimeout(90_000) { requested.await() }
                    val nextSeg = async {
                        kotlinx.coroutines.flow.combine(controller.state, controller.vision.loadedResources) { state, resources ->
                            state.activeChapters == 2 && resources.any { it.kind == InferenceEngineKind.OCR && it.inUse }
                        }.first { it }
                    }
                    try {
                        swipe(distance = 180f)
                        withTimeout(15_000) { nextSeg.await() }
                        assertEquals("The first API still owns the single API permit", 1, calls.get())
                        assertEquals(2, controller.state.value.activeChapters)
                        assertTrue("Both admitted screenshots must remain journaled", controller.pendingFrames.value >= retainedBefore + 2)
                    } finally { reply.countDown(); nextSeg.cancelAndJoin() }
                    waitState { !it.busy }
                    assertTrue(controller.state.value.status, controller.state.value.continuous)
                    assertEquals("Only the two completed frames are removed; prior failed inputs survive", retainedBefore, controller.pendingFrames.value)
                    withContext(Dispatchers.Main) { controller.tap() }
                    assertEquals(ScreenEngineState.READY, controller.state.value.engine)
                } finally { withContext(Dispatchers.Main) { controller.stop() }; controller.preferences.update { original } }
            }
        } finally {
            reply.countDown(); server.shutdown()
            workflowId?.let { container.translationWorkflows.delete(it) }
            container.apiProfiles.delete(profileId)
            container.translationQueue.setSchedulingPriority(originalPriority)
        }
    }
    @Test fun singleAppStreamTracksThroughWholeScreenDarkAndColorMasks() = runBlocking<Unit> {
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            ActivityScenario.launch(MainActivity::class.java).use {
                fixture(orientation = orientation)
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                waitState { it.inside }
                val original = start(ScreenTranslationMode.BROWSE, appStream = true)
                fun change(scale: Float, shift: Float, opacity: Float, color: Int = android.graphics.Color.BLACK) {
                    context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ScreenFixtureActivity::class.java.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("scale", scale).putExtra("shift", shift).putExtra("fixedControls", true)
                        .putExtra("tintOpacity", opacity).putExtra("tintColor", color))
                }
                try {
                    fixture(fixedControls = true, orientation = orientation)
                    withContext(Dispatchers.Main) { controller.traceMotion = true; controller.motionTrace.clear(); controller.tap() }
                    waitState { it.bubbles > 0 && !it.busy }
                    val before = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    val search = before.maxBy { it.value.top }
                    change(1f, 0f, .65f)
                    delay(1000)
                    assertEquals("Dimming alone must keep the published identities: ${controller.motionTrace}", before.keys,
                        withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                    val blue = android.graphics.Color.rgb(35, 65, 210)
                    change(1.12f, -140f, .7f, blue)
                    awaitMovement("pan/zoom while black mask changes to blue", before) { old, now ->
                        kotlin.math.abs(now.width / old.width - 1.12f) < .06f && kotlin.math.abs(now.top - (old.top * 1.12f - 140f)) < 25f }
                    val zoomed = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    assertNotNull("Fixed search survives a tinted zoom", zoomed[search.key])
                    assertEquals(search.value.top, zoomed.getValue(search.key).top, 10f)
                    assertEquals(search.value.width, zoomed.getValue(search.key).width, 10f)
                    change(.92f, 60f, 0f)
                    awaitMovement("shrink/pan as the blue mask disappears", zoomed) { old, now ->
                        kotlin.math.abs(now.width / old.width - .92f / 1.12f) < .06f &&
                            kotlin.math.abs(now.top - ((old.top + 140f) * .92f / 1.12f + 60f)) < 25f }
                    val restored = withContext(Dispatchers.Main) { controller.bubblePositions()[search.key] }
                    assertNotNull(restored)
                    assertEquals(search.value.top, restored!!.top, 10f)
                    assertEquals(search.value.width, restored.width, 10f)
                } finally { withContext(Dispatchers.Main) { controller.traceMotion = false; controller.stop() }; controller.preferences.update { original } }
            }
        }
    }
    @Test fun manualScreenCapturePublishesRealTextAndClearsAfterScroll() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val original = start(ScreenTranslationMode.MANUAL)
            try {
                fixture()
                withContext(Dispatchers.Main) { controller.traceMotion = true; controller.motionTrace.clear(); controller.tap() }
                assertTrue(controller.state.value.busy)
                val completed = waitState { !it.busy && it.bubbles > 0 }
                assertEquals("屏幕翻译完成", completed.status)
                val publishedIds = withContext(Dispatchers.Main) { controller.bubblePositions().keys }
                delay(1200)
                assertEquals("Drawing the translation must not clear its own result: ${controller.motionTrace}", publishedIds, withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                assertFalse("Text-only mode must not load a bubble SEG model", controller.vision.loadedResources.value.any { it.kind == InferenceEngineKind.SEG })
                swipe()
                waitState { it.bubbles == 0 }
                assertFalse(controller.state.value.busy)
            } finally { withContext(Dispatchers.Main) { controller.traceMotion = false; controller.stop() }; controller.preferences.update { original } }
        }
    }
    @Test fun videoPublishesWhileTheBackgroundKeepsMovingAndStopClearsIt() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val original = start(ScreenTranslationMode.VIDEO)
            try {
                fixture(true)
                withContext(Dispatchers.Main) { controller.tap() }
                val completed = waitState { it.continuous && it.bubbles > 0 }
                assertTrue(completed.continuous)
                withContext(Dispatchers.Main) { controller.tap() }
                assertFalse(controller.state.value.continuous); assertEquals(0, controller.state.value.bubbles)
                delay(1500); assertEquals(0, controller.state.value.bubbles)
            } finally { withContext(Dispatchers.Main) { controller.stop() }; controller.preferences.update { original } }
        }
    }
    @Test fun manualProjectionBaselineIncludesThePublishedOverlay() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val original = start(ScreenTranslationMode.MANUAL, manualProjection = true)
            try {
                fixture()
                withContext(Dispatchers.Main) { controller.traceMotion = true; controller.motionTrace.clear(); controller.tap() }
                waitState { !it.busy && it.bubbles > 0 }
                val published = withContext(Dispatchers.Main) { controller.bubblePositions().keys }
                delay(1500)
                assertEquals("The projection baseline must include the visible translation: ${controller.motionTrace}",
                    published, withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                swipe(); waitState { it.bubbles == 0 }
            } finally { withContext(Dispatchers.Main) { controller.traceMotion = false; controller.stop() }; controller.preferences.update { original } }
        }
    }
    @Test fun videoKeepsPublishedResultWhileTheNextApiIsBlocked() = runBlocking<Unit> {
        val profileId = "screen-retention-${java.util.UUID.randomUUID()}"
        val block = java.util.concurrent.atomic.AtomicBoolean()
        val requested = CompletableDeferred<Unit>()
        val reply = java.util.concurrent.CountDownLatch(1)
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (block.get()) { requested.complete(Unit); reply.await(60, java.util.concurrent.TimeUnit.SECONDS) }
                    return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
                }
            }
            start()
        }
        var workflowId: String? = null
        try {
            container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Screen retention fixture", server.url("/v1").toString(),
                model = "fixture", parallelLimit = 1, retryCount = 0))
            val created = container.translationWorkflows.create().also { workflowId = it.id }
            val program = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
                node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                    "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Translate this fixture text.")))
            }
            container.translationWorkflows.update(created.copy(program = program, retries = 0))
            ActivityScenario.launch(MainActivity::class.java).use {
                val original = start(ScreenTranslationMode.VIDEO, workflowId = created.id)
                try {
                    fixture(true); withContext(Dispatchers.Main) { controller.tap() }
                    waitState { it.bubbles > 0 }
                    block.set(true)
                    withTimeout(20_000) { requested.await() }
                    // A frame already running when the block was set may finish;
                    // record the stable published version after that transition.
                    delay(700)
                    val published = withContext(Dispatchers.Main) { controller.bubblePositions().keys }
                    assertTrue(published.isNotEmpty())
                    delay(1200)
                    assertEquals("Keep the preceding translation throughout the API wait", published,
                        withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                    assertTrue("The frame window must stay bounded", controller.state.value.activeChapters in 1..2)
                    block.set(false); reply.countDown()
                    withTimeout(20_000) { while (withContext(Dispatchers.Main) { controller.bubblePositions().keys == published }) delay(20) }
                    assertTrue(controller.state.value.bubbles > 0)
                    withContext(Dispatchers.Main) { controller.tap() }
                    delay(1000); assertEquals(0, controller.state.value.bubbles)
                } finally { reply.countDown(); withContext(Dispatchers.Main) { controller.stop() }; controller.preferences.update { original } }
            }
        } finally {
            block.set(false); reply.countDown(); server.shutdown()
            workflowId?.let { container.translationWorkflows.delete(it) }
            container.apiProfiles.delete(profileId)
        }
    }
    @Test fun videoTwoFrameBufferWaitsForFirstFillAndReusesPendingSubtitleWork() = runBlocking<Unit> {
        val requested = CompletableDeferred<Unit>(); val reply = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        withVideoApi({
            if (calls.incrementAndGet() == 1) { requested.complete(Unit); reply.await(60, java.util.concurrent.TimeUnit.SECONDS) }
        }) { workflowId ->
            ActivityScenario.launch(MainActivity::class.java).use {
                val subtitles = ScreenRegionSelection(portrait = listOf(ScreenNormalizedRegion(.05f, .80f, .95f, .91f)))
                val original = start(ScreenTranslationMode.VIDEO, workflowId, regions = subtitles)
                try {
                    fixture(true, orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, videoText = "The rabbit is blue.")
                    withContext(Dispatchers.Main) { controller.tap() }
                    withTimeout(90_000) { requested.await() }
                    withTimeout(30_000) {
                        while (withContext(Dispatchers.Main) { controller.videoRecognitionHistory.size < 2 }) delay(20)
                    }
                    val initial = withContext(Dispatchers.Main) { controller.videoRecognitionHistory.toList() }
                    assertEquals(2, controller.state.value.activeChapters)
                    assertTrue("Same detected subtitles reuse even unfinished text", initial[1].reused.isNotEmpty())
                    assertEquals(initial[0].regions, initial[1].regions)
                    delay(1200)
                    assertEquals("A finished reusable second frame must not free a third slot", 2,
                        withContext(Dispatchers.Main) { controller.videoRecognitionHistory.size })
                    assertEquals(1, calls.get())
                    reply.countDown()
                    withTimeout(30_000) {
                        while (withContext(Dispatchers.Main) { controller.videoRecognitionHistory.size < 4 || controller.bubblePositions().isEmpty() }) delay(20)
                    }
                    val ready = withContext(Dispatchers.Main) { controller.bubblePositions().keys }
                    assertTrue("Late first-frame text is forwarded to reused newer-frame identities", ready.all { it in initial[0].regions })
                    val completeCalls = calls.get()
                    delay(1200)
                    assertEquals("A moving background must not cause the same subtitles to be translated again", completeCalls, calls.get())
                    assertTrue(controller.state.value.continuous)
                } finally { reply.countDown(); withContext(Dispatchers.Main) { controller.stopAndAwait() }; controller.preferences.update { original } }
            }
        }
    }
    @Test fun videoChangedSubtitleKeepsOldTextUntilFillAndRetainsTheUnchangedLine() = runBlocking<Unit> {
        val block = java.util.concurrent.atomic.AtomicBoolean()
        val requested = CompletableDeferred<Unit>(); val reply = java.util.concurrent.CountDownLatch(1)
        withVideoApi({ if (block.get()) { requested.complete(Unit); reply.await(60, java.util.concurrent.TimeUnit.SECONDS) } }) { workflowId ->
            ActivityScenario.launch(MainActivity::class.java).use {
                // Keep the moving background and the fixture's vertical panel
                // edges outside this subtitle-only selection.
                val subtitles = ScreenRegionSelection(portrait = listOf(ScreenNormalizedRegion(.10f, .72f, .90f, .91f)))
                val original = start(ScreenTranslationMode.VIDEO, workflowId, regions = subtitles)
                try {
                    fixture(true, orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, videoText = "The rabbit is blue.")
                    withContext(Dispatchers.Main) { controller.tap() }
                    withTimeout(90_000) {
                        while (!withContext(Dispatchers.Main) {
                            val detected = controller.videoRecognitionHistory.lastOrNull()?.regions.orEmpty()
                            val filled = controller.bubblePositions().keys
                            // A preview may finish before its workflow. Two fully
                            // reused successors prove FIFO has drained all initial
                            // translation work before blocking genuinely new text.
                            val recent = controller.videoRecognitionHistory.takeLast(2)
                            controller.videoRecognitionHistory.size >= 4 && detected.size >= 2 && detected.all { it in filled } &&
                                recent.all { it.regions == it.reused }
                        }) delay(20)
                    }
                    val before = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    block.set(true)
                    fixture(true, orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, videoText = "A new story begins.")
                    withTimeout(30_000) { requested.await() }
                    delay(500)
                    assertEquals("Keep the preceding translation until a new subtitle is filled", before.keys,
                        withContext(Dispatchers.Main) { controller.bubblePositions().keys })
                    val next = withContext(Dispatchers.Main) { controller.videoRecognitionHistory.last() }
                    assertTrue("The unchanged second line must keep its original identity", next.reused.isNotEmpty())
                    assertTrue("Changed first-line SEG must be sent through OCR/translation: frame=$next previous=${before.keys}", next.regions.any { it !in before.keys })
                    block.set(false); reply.countDown()
                    withTimeout(30_000) {
                        while (withContext(Dispatchers.Main) { controller.bubblePositions().keys == before.keys }) delay(20)
                    }
                    val after = withContext(Dispatchers.Main) { controller.bubblePositions().keys }
                    assertTrue(after.intersect(before.keys).isNotEmpty())
                    assertTrue(after.any { it !in before.keys })
                } finally { reply.countDown(); withContext(Dispatchers.Main) { controller.stopAndAwait() }; controller.preferences.update { original } }
            }
        }
    }
    @Test fun videoPendingRetryPublishesOutsideTheContinuousFrameWindow() = runBlocking<Unit> {
        val requested = CompletableDeferred<Unit>(); val reply = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        withVideoApi({ if (calls.incrementAndGet() == 1) { requested.complete(Unit); reply.await(60, java.util.concurrent.TimeUnit.SECONDS) } }) { workflowId ->
            ActivityScenario.launch(MainActivity::class.java).use {
                val subtitles = ScreenRegionSelection(portrait = listOf(ScreenNormalizedRegion(.10f, .80f, .90f, .91f)))
                val original = start(ScreenTranslationMode.VIDEO, workflowId, regions = subtitles)
                try {
                    fixture(true, orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, videoText = "The rabbit is blue.")
                    withContext(Dispatchers.Main) { controller.tap() }
                    withTimeout(90_000) { requested.await() }
                    withContext(Dispatchers.Main) { controller.tap() }
                    reply.countDown()
                    delay(500) // allow the cancelled workflow to finish its retry-journal write
                    assertFalse(controller.state.value.continuous)
                    withContext(Dispatchers.Main) { controller.retryPendingFrame() }
                    withTimeout(60_000) {
                        while (!controller.state.value.let { !it.busy && it.bubbles > 0 }) delay(20)
                    }
                    delay(500)
                    assertTrue("A completed retry keeps its own filled bubbles", withContext(Dispatchers.Main) { controller.bubblePositions().isNotEmpty() })
                    assertFalse(controller.state.value.continuous)
                } finally { reply.countDown(); withContext(Dispatchers.Main) { controller.stopAndAwait() }; controller.preferences.update { original } }
            }
        }
    }
    private suspend fun withVideoApi(beforeResponse: () -> Unit, test: suspend (String) -> Unit) {
        val profileId = "video-seg-${java.util.UUID.randomUUID()}"
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    beforeResponse()
                    return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Translated\"}}]}\n\ndata: [DONE]\n\n")
                }
            }; start()
        }
        var workflowId: String? = null
        val priority = container.translationQueue.schedulingPriority.value
        try {
            container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Video SEG fixture", server.url("/v1").toString(),
                model = "fixture", parallelLimit = 1, retryCount = 0))
            val created = container.translationWorkflows.create().also { workflowId = it.id }
            val program = com.lmreader.core.workflow.WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
                node.copy(kind = WorkflowKind.API, resultType = WorkflowType.TEXT, inputs = mapOf(
                    "profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Translate this fixture subtitle.")))
            }
            container.translationWorkflows.update(created.copy(program = program, retries = 0))
            container.translationQueue.setSchedulingPriority(com.lmreader.ui.queue.TranslationSchedulingPriority.RESOURCES)
            test(created.id)
        } finally {
            server.shutdown(); workflowId?.let { container.translationWorkflows.delete(it) }
            container.apiProfiles.delete(profileId); container.translationQueue.setSchedulingPriority(priority)
        }
    }
    @Test fun browseFramesFollowPanAndZoomAndReplaceChangedScenes() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            waitState { it.inside }
            val original = start(ScreenTranslationMode.BROWSE)
            try {
                fixture()
                withContext(Dispatchers.Main) { controller.traceMotion = true; controller.motionTrace.clear() }
                withContext(Dispatchers.Main) { controller.tap() }
                waitState { it.bubbles > 0 && !it.busy }
                val before = withContext(Dispatchers.Main) { controller.bubblePositions() }
                val firstRecognition = withContext(Dispatchers.Main) { controller.browseRecognitionHistory.last() }
                val firstSegArea = firstRecognition.initialInputs.sumOf { it.area.toDouble() }
                swipe(distance = 120f)
                awaitMovement("browse forward pan", before) { old, now -> kotlin.math.abs(now.top - old.top + 120f) < 16f }
                delay(1200)
                waitState { !it.busy && it.bubbles > 0 }
                val incremental = withTimeout(15_000) {
                    var next: ScreenTranslationController.BrowseRecognition? = null
                    while (next == null) {
                        next = withContext(Dispatchers.Main) {
                            controller.browseRecognitionHistory.firstOrNull { it.sequence > firstRecognition.sequence }
                        }
                        if (next == null) delay(20)
                    }
                    next
                }
                assertTrue("The real SEG calls must crop a new strip rather than re-segment the screen: $incremental",
                    incremental.initialInputs.isNotEmpty() && incremental.initialInputs.sumOf { it.area.toDouble() } < firstSegArea * .5)
                val beforeReverse = withContext(Dispatchers.Main) { controller.bubblePositions() }
                swipe(reverse = true, distance = 120f)
                awaitMovement("browse reverse pan", beforeReverse) { old, now -> kotlin.math.abs(now.top - old.top - 120f) < 16f }
                waitState { !it.busy && it.bubbles > 0 }
                run {
                    val beforeZoom = withContext(Dispatchers.Main) { controller.bubblePositions() }
                    context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ScreenFixtureActivity::class.java.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("scale", 1.25f))
                    awaitMovement("browse zoom", beforeZoom) { old, now -> kotlin.math.abs(now.width / old.width - 1.25f) < .08f }
                }
                run {
                    for (longPress in listOf(false, true)) {
                        waitState { it.bubbles > 0 && !it.busy }
                        val previousIds = withContext(Dispatchers.Main) { controller.bubblePositions().keys }
                        press(longPress)
                        try { withTimeout(5000) { while (withContext(Dispatchers.Main) { controller.bubblePositions().keys.any { it in previousIds } }) delay(20) } }
                        catch (timeout: TimeoutCancellationException) { throw AssertionError("Scene cut longPress=$longPress state=${controller.state.value} positions=${controller.bubblePositions()} tracking=${controller.motionDiagnostics()} trace=${controller.motionTrace}", timeout) }
                        waitState { it.continuous && it.bubbles > 0 && !it.busy }
                        assertEquals(ScreenEngineState.READY, controller.state.value.engine)
                    }
                }
                val loaded = container.localTranslator.loadedResources.value
                withContext(Dispatchers.Main) { controller.tap() }
                assertEquals(0, controller.state.value.bubbles)
                delay(1000); assertEquals(0, controller.state.value.bubbles)
                assertEquals(ScreenEngineState.READY, controller.state.value.engine)
                assertEquals("Stopping the ball must keep the warmed decoder sessions", loaded, container.localTranslator.loadedResources.value)
                withContext(Dispatchers.Main) { controller.tap() }
                waitState { it.bubbles > 0 && !it.busy }
                assertEquals("Restarting the ball must reuse the warmed decoder sessions", loaded, container.localTranslator.loadedResources.value)
                withContext(Dispatchers.Main) { controller.tap() }
            } finally { withContext(Dispatchers.Main) { controller.traceMotion = false; controller.stop() }; controller.preferences.update { original } }
        }
    }
    private suspend fun press(longPress: Boolean) {
        val metrics = context.resources.displayMetrics
        val time = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, elapsed: Long) {
            val event = MotionEvent.obtain(time, time + elapsed, action, metrics.widthPixels * .35f, metrics.heightPixels * .55f, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0)
        val duration = if (longPress) 800L else 60L
        delay(duration); send(MotionEvent.ACTION_UP, duration)
    }
    private suspend fun awaitMovement(stage: String, before: Map<String, PixelRect>, matches: (PixelRect, PixelRect) -> Boolean) {
        try {
            withTimeout(3000) { while (withContext(Dispatchers.Main) { before.none { (id, rect) -> controller.bubblePositions()[id]?.let { matches(rect, it) } == true } }) delay(10) }
        } catch (timeout: TimeoutCancellationException) {
            withContext(Dispatchers.Main) { controller.rejectedMotion?.let { (a, b) ->
                listOf("before" to a, "after" to b).forEach { (name, frame) -> saveMotionSample(name, frame) }
            } }
            throw AssertionError("$stage: before=$before after=${withContext(Dispatchers.Main) { controller.bubblePositions() }} state=${controller.state.value} trace=${controller.motionTrace} diagnostics=${withContext(Dispatchers.Main) { controller.motionDiagnostics() }}", timeout)
        }
    }
    private fun saveMotionSample(name: String, frame: ScreenGrayFrame) {
        java.io.File(context.cacheDir, "motion-$name.pgm").outputStream().use { out ->
            out.write("P5\n${frame.width} ${frame.height}\n255\n".toByteArray()); out.write(frame.pixels)
        }
        frame.valid?.let { java.io.File(context.cacheDir, "motion-$name.mask").writeBytes(it) }
    }
    private fun swipe(reverse: Boolean = false, distance: Float? = null) {
        val metrics = context.resources.displayMetrics
        val from = metrics.heightPixels * if (reverse) .25f else .8f
        val to = if (distance != null) from + distance * if (reverse) 1 else -1 else metrics.heightPixels * if (reverse) .8f else .25f
        val time = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, elapsed: Long) {
            val event = MotionEvent.obtain(time, time + elapsed, action, metrics.widthPixels * .35f, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, from, 0)
        for (step in 1..10) send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 10, step * 20L)
        send(MotionEvent.ACTION_UP, to, 220)
    }
    private suspend fun dragWhileHeld(distance: Float, check: suspend () -> Unit) {
        val metrics = context.resources.displayMetrics
        val start = android.os.SystemClock.uptimeMillis()
        val from = metrics.heightPixels * .6f; val to = from - distance
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, metrics.widthPixels * .35f, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, from)
        try {
            for (step in 1..12) { send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 12); delay(30) }
            check() // Assertions run before ACTION_UP, while the application still has touch ownership.
        } finally { send(MotionEvent.ACTION_UP, to) }
    }
}
