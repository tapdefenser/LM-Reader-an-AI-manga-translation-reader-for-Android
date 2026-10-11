package com.lmreader.screen

import android.content.ComponentName
import android.content.Intent
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
        workflowId: String = TranslationWorkflow.LOCAL_MACHINE_ID, appStream: Boolean = false, manualProjection: Boolean = false): ScreenTranslationSettings {
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
            vision = VisionExecutionSettings(1, 1, false, OcrBackend.CPU), maximumCaptureEdge = 1600, sampleIntervalMillis = 200)
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
    private suspend fun fixture(video: Boolean = false, fixedControls: Boolean = false) {
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ScreenFixtureActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("video", video).putExtra("fixedControls", fixedControls))
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

    @Test fun singleAppStreamExcludesOurOverlayAndFollowsPanAndZoom() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            fixture() // Leave this target task in Recents before opening the share picker.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            waitState { it.inside }
            val original = start(ScreenTranslationMode.BROWSE, appStream = true)
            try {
                fixture(fixedControls = true)
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
                assertEquals("Fixed search must not follow content pan", search.value.top, afterPan!!.top, 10f)
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
                    val retainedBefore = controller.pendingFrames.value
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
                swipe(distance = 120f)
                awaitMovement("browse forward pan", before) { old, now -> kotlin.math.abs(now.top - old.top + 120f) < 16f }
                delay(1200)
                waitState { !it.busy && it.bubbles > 0 }
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
                        withTimeout(5000) { while (withContext(Dispatchers.Main) { controller.bubblePositions().keys.any { it in previousIds } }) delay(20) }
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
                listOf("before" to a, "after" to b).forEach { (name, frame) ->
                    java.io.File(context.cacheDir, "motion-$name.pgm").outputStream().use { out ->
                        out.write("P5\n${frame.width} ${frame.height}\n255\n".toByteArray()); out.write(frame.pixels)
                    }
                    frame.valid?.let { java.io.File(context.cacheDir, "motion-$name.mask").writeBytes(it) }
                }
            } }
            throw AssertionError("$stage: before=$before after=${withContext(Dispatchers.Main) { controller.bubblePositions() }} state=${controller.state.value} trace=${controller.motionTrace} diagnostics=${withContext(Dispatchers.Main) { controller.motionDiagnostics() }}", timeout)
        }
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
