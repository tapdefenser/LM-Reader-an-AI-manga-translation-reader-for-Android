package com.lmreader.screen

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.LocaleList
import android.widget.Toast
import androidx.annotation.StringRes
import com.lmreader.R
import com.lmreader.ui.i18n.UiTextTranslations
import com.lmreader.core.model.*
import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.storage.reader.*
import com.lmreader.core.storage.settings.ScreenTranslationPreferences
import com.lmreader.core.vision.LocalVisionEngine
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationSchedulingPriority
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.reader.translation.*
import com.lmreader.ui.workflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import kotlin.math.*

enum class ScreenEngineState { STOPPED, LOADING, READY, STOPPING, FAILED }
data class ScreenTranslationState(val started: Boolean = false, val engine: ScreenEngineState = ScreenEngineState.STOPPED,
    val continuous: Boolean = false, val busy: Boolean = false, val status: String = "", val bubbles: Int = 0, val inside: Boolean = true,
    val activeChapters: Int = 0, val timing: String = "")

/** Two single-page temporary chapters share the ordinary workflow engines and cache budget. */
class ScreenTranslationController(private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val preferences = ScreenTranslationPreferences(container.application)
    @Volatile private var visionSettings = preferences.settings.value.vision
    val vision by lazy { LocalVisionEngine(container.application, "screen-vision-preprocessing") { visionSettings } }
    val state = MutableStateFlow(ScreenTranslationState())
    private var service: ScreenTranslationService? = null
    private var projection: ProjectionScreenCapture? = null
    private var frameBroker: ScreenFrameBroker? = null
    private var overlay: ScreenOverlay? = null
    private var captureLoop: Job? = null
    private var motionLoop: Job? = null
    private val browseTracker = ScreenBrowseTracker(::trace)
    private val observationLock = Mutex()
    private val stability = ScreenBrowseStability()
    private var lastBrowseReliable = false
    private data class DetectedBrowseRegion(val id: String, val bounds: PixelRect, val pose: ScreenTransform,
        val template: ScreenObjectTemplate?, val version: Long)
    private val detectedBrowseRegions = linkedMapOf<String, List<DetectedBrowseRegion>>()
    private val supersededBrowseRegions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val browseBubbleJobs = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Job>>()
    private val browseHosts = java.util.concurrent.ConcurrentHashMap<String, AndroidWorkflowHost>()
    private val browseSession by lazy { ScreenBrowseSessionStore(container.application) }
    internal data class BrowseRecognition(val sequence: Long, val fresh: List<PixelRect>,
        val initialInputs: List<PixelRect>, val mergedInputs: List<PixelRect>, val reused: Set<String>)
    internal val browseRecognitionHistory = ArrayDeque<BrowseRecognition>()
    private val captureWakeups = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var worker: Job? = null
    private val chapterWindow = ScreenChapterWindow()
    private data class VideoFrame(val chapter: ScreenChapterWindow.Chapter, val cropCount: Int, val capturedAt: Long,
        val previous: List<ScreenVideoRegion>, val crops: MutableMap<Int, List<ScreenVideoRegion>> = linkedMapOf(),
        val claimed: MutableMap<String, Int> = linkedMapOf(), var segmented: Boolean = false) {
        val regions get() = crops.values.flatten()
    }
    private data class VideoTarget(val number: Long, val bounds: PixelRect)
    internal data class VideoRecognition(val number: Long, val capturedAt: Long, val segmentedAt: Long,
        val regions: Set<String>, val reused: Set<String>)
    internal val videoRecognitionHistory = ArrayDeque<VideoRecognition>()
    private val videoFrames = linkedMapOf<Long, VideoFrame>()
    private var latestVideoFrame: VideoFrame? = null
    private val videoTargets = linkedMapOf<String, VideoTarget>()
    private var videoCadence: ScreenVideoCadence? = null
    private val chapterJobs = linkedMapOf<Long, Job>()
    private val chapterRevision = MutableStateFlow(0L)
    private val cacheBudget = TranslationCacheBudget(preferences.settings.value.cacheMegabytes.toLong() * 1_048_576)
    private val sessionGlossary = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val publicationOrder = ScreenPublicationOrder()
    private var frameFailure: String? = null
    private val waitReasons = mutableMapOf<String, String>()
    private val retiring = mutableListOf<Job>()
    private val pending = ScreenPendingFrames(File(container.application.noBackupFilesDir, "screen-pending"))
    val pendingFrames = pending.count.asStateFlow()
    val apiResources = MutableStateFlow<List<String>>(emptyList())
    internal fun bubblePositions(includePlaceholders: Boolean = false) = overlay?.positions(includePlaceholders).orEmpty()
    internal fun trackingSample() = browseTracker.latestFrame?.gray
    internal fun motionDiagnostics() = "sequence=${projection?.sequence} active=${motionLoop?.isActive} source=${frameBroker?.backend} preprocessing=${projection?.motionBackend} raw=${browseTracker.rawPose} shown=${browseTracker.displayPose} occlusions=${browseTracker.visualOcclusions}"
    internal fun objectSimilarityDiagnostics(id: String, expected: PixelRect): String {
        val obj = overlay?.motionObjects(browseTracker.rawPose)?.firstOrNull { it.id == id }
        val frame = browseTracker.latestFrame ?: return "no tracking frame"
        return "object=$obj expected=${obj?.template?.compare(frame.gray, frame.bounds, expected)} frame=${frame.bounds}"
    }
    internal var traceMotion = false
    internal val motionTrace = ArrayDeque<String>()
    internal var rejectedMotion: Pair<ScreenGrayFrame, ScreenGrayFrame>? = null
        private set
    private fun trace(message: String) { if (traceMotion) { if (motionTrace.size >= 24) motionTrace.removeFirst(); motionTrace.addLast(message) } }
    private var warming: Job? = null
    private var unloading: Job? = null
    private var engineMessage: Toast? = null
    private val resourceLifecycle = Mutex()
    private var preparedOwner: String? = null
    val accessibilityAvailable = MutableStateFlow(ScreenAccessibilityService.instance != null)
    private val visibleActivities = mutableSetOf<Activity>()
    @Volatile private var generation = 0L
    private var configuration: ScreenTranslationSettings? = null
    private var workflow: TranslationWorkflow? = null
    private var program: WorkflowProgram? = null
    private val coverage = ScreenCoverage()
    private val coverageTracker = ScreenBrowseCoverageTracker()
    private var coverageProbeAt = 0L
    private var viewport: PixelRect? = null
    private var cumulative = ScreenTransform()
    private var gray: ScreenGrayFrame? = null
    private var grayArea: PixelRect? = null
    private var activeWindow = -1
    private var activePackage = ""
    private val textCache = java.util.concurrent.ConcurrentHashMap<List<String>, WorkflowValue>()
    private val owner = "screen-translation"
    private data class EngineRequest(val wanted: Boolean = false, val revision: Long = 0)
    private data class EngineInput(val settings: ScreenTranslationSettings, val workflow: TranslationWorkflow?, val profiles: List<ApiProfile>,
        val installedModels: List<String>)
    private val engineRequest = MutableStateFlow(EngineRequest())
    private var resumeContinuous = false
    internal fun appliedEngineSettings() = configuration
    internal fun appliedEngineWorkflow() = workflow
    init {
        scope.launch {
            combine(preferences.settings, container.translationWorkflows.workflows, container.apiProfiles.profiles, engineRequest,
                container.translationModels.installedPacks) { config, workflows, profiles, request, installed ->
                val selected = workflows.firstOrNull { it.id == (config.translation.workflowId ?: TranslationWorkflow.LOCAL_MACHINE_ID) }
                val ids = selected?.program?.withApiOverride(config.translation.apiProfileId)?.allNodes()
                    ?.filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
                    ?.mapNotNull { (it.inputs["profile"] as? WorkflowExpression.Text)?.value }?.toSet().orEmpty()
                request to EngineInput(config, selected, profiles.filter { it.id in ids },
                    if (selected?.program?.uses(WorkflowKind.TRANSLATE) == true) installed.values.map { it.identity }.sorted() else emptyList())
            }.distinctUntilChanged().collect { (request, input) ->
                if (request.wanted && state.value.started) refreshEngine(input)
            }
        }
    }
    fun setVisible(activity: Activity, visible: Boolean) {
        val wasInside = state.value.inside
        if (visible) visibleActivities += activity else visibleActivities -= activity
        val inside = visibleActivities.isNotEmpty()
        if (inside != wasInside && state.value.started) resetScene()
        state.update { it.copy(inside = inside) }; updateButton()
    }
    fun openSettings(context: android.content.Context) { context.startActivity(Intent(context, ScreenTranslationSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    fun start(context: android.content.Context) { context.startActivity(Intent(context, ScreenTranslationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    internal fun attach(service: ScreenTranslationService, projection: ProjectionScreenCapture?) {
        this.service = service; this.projection = projection
        val assist = ScreenAccessibilityService.instance.takeIf { preferences.settings.value.usesAccessibility }
        val host = assist ?: service
        overlay?.close(); overlay = ScreenOverlay(host, assist != null, { openSettings(host) }, ::tap)
        frameBroker?.close(); frameBroker = ScreenFrameBroker(projection, { configuration ?: preferences.settings.value }, { overlay })
        projection?.onContentChanged = {
            frameBroker?.invalidate()
            if ((configuration ?: preferences.settings.value).mode == ScreenTranslationMode.BROWSE &&
                !ScreenAccessibilityService.instance?.contentOcclusions.isNullOrEmpty()) captureWakeups.trySend(Unit)
            else resetScene()
        }
        state.update { it.copy(started = true) }; prepareEngine()
    }
    fun accessibilityChanged() {
        accessibilityAvailable.value = ScreenAccessibilityService.instance != null
        if (state.value.started && preferences.settings.value.usesAccessibility) {
            val assist = ScreenAccessibilityService.instance ?: return
            resumeContinuous = resumeContinuous || state.value.continuous
            stopContinuous(preserveResume = true); overlay?.close(); overlay = ScreenOverlay(assist, true, { openSettings(assist) }, ::tap); updateButton()
            if (engineRequest.value.wanted) engineRequest.update { it.copy(revision = it.revision + 1) }
        }
    }
    fun accessibilityInterrupted() { if (state.value.started && preferences.settings.value.usesAccessibility) stop("无障碍辅助已关闭，请重新启动屏幕翻译") }
    fun projectionStopped(): Boolean {
        if (configuration?.requiresAccessibility != true || android.os.Build.VERSION.SDK_INT < 34 || ScreenAccessibilityService.instance == null) return false
        projection = null; frameBroker?.invalidate()
        android.util.Log.w("ScreenTranslation", "Projection stopped; continuing with accessibility window screenshots")
        state.update { it.copy(status = "录屏流已结束，已切换为无障碍窗口截图") }
        captureWakeups.trySend(Unit)
        return true
    }
    fun stop(reason: String = "") {
        android.util.Log.i("ScreenTranslation", "STOP reason=$reason")
        waitReasons.clear()
        engineRequest.update { it.copy(wanted = false) }; resumeContinuous = false
        val hadEngine = state.value.engine != ScreenEngineState.STOPPED
        generation++; val stoppedEpoch = generation
        retire(warming); warming = null; retire(unloading); unloading = null; retire(worker); worker = null; retireChapters(); captureLoop?.cancel(); captureLoop = null; motionLoop?.cancel(); motionLoop = null; browseTracker.reset(); clearing?.cancel(); clearing = null
        clearBrowseSession()
        frameBroker?.close(); frameBroker = null
        projection?.close(); projection = null; overlay?.close(); overlay = null
        preparedOwner?.let(container.localTranslator::unpin); preparedOwner = null
        configuration = null; workflow = null; program = null; textCache.clear(); apiResources.value = emptyList()
        state.update { ScreenTranslationState(inside = it.inside, status = reason) }
        val current = service; service = null; current?.finishSession()
        scope.launch {
            joinRetired()
            resourceLifecycle.withLock {
                if (generation == stoppedEpoch && !state.value.started) {
                    releaseEngineResources()
                    if (hadEngine) showEngineMessage(R.string.screen_translation_engine_unloaded)
                }
            }
        }
    }
    /** Backup restore must finish outstanding frame writes before replacing settings. */
    suspend fun stopAndAwait() = withContext(Dispatchers.Main.immediate) {
        stop()
        joinRetired()
        resourceLifecycle.withLock { }
    }
    private fun retire(job: Job?) { job?.let { it.cancel(); retiring += it } }
    private fun retireChapters() {
        chapterJobs.values.toList().forEach(::retire); chapterJobs.clear(); chapterWindow.clear()
        clearVideoFrames()
        detectedBrowseRegions.clear(); stability.reset(); lastBrowseReliable = false
        chapterRevision.update { it + 1 }
    }
    private suspend fun joinRetired() { retiring.toList().joinAll(); retiring.removeAll { it.isCompleted } }
    private fun finishWorker(job: Job, manual: Boolean = false) {
        if (worker !== job) return
        worker = null; state.update { it.copy(busy = chapterWindow.size > 0) }; updateButton()
        if (manual && state.value.started && state.value.engine == ScreenEngineState.READY) monitor()
        else if (state.value.continuous) captureWakeups.trySend(Unit)
    }
    private fun stopContinuous(preserveResume: Boolean = false) {
        if (!preserveResume) resumeContinuous = false
        generation++; retire(worker); worker = null; retireChapters(); retire(captureLoop); captureLoop = null; retire(motionLoop); motionLoop = null; browseTracker.reset(); clearing?.cancel(); clearing = null
        clearBrowseSession()
        coverage.clear(); coverageTracker.clear(); coverageProbeAt = 0L; gray = null; grayArea = null; frameFailure = null; cumulative = ScreenTransform(); overlay?.clear()
        state.update { it.copy(continuous = false, busy = false, bubbles = 0, activeChapters = 0) }; updateButton()
    }
    private fun clearBrowseSession() {
        supersededBrowseRegions.clear()
        browseRecognitionHistory.clear()
        if (configuration?.mode == ScreenTranslationMode.BROWSE) browseSession.submit(emptyList())
    }
    private fun localizedContext() = container.application.createConfigurationContext(
        android.content.res.Configuration(container.application.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(container.generalPreferences.effectiveLanguageTag))
        })
    private fun message(@StringRes id: Int) = localizedContext().getString(id)
    private fun showEngineMessage(@StringRes id: Int) = showEngineMessage(message(id))
    private fun showEngineMessage(text: String) {
        engineMessage?.cancel()
        engineMessage = Toast.makeText(localizedContext(), UiTextTranslations.translate(localizedContext(), text), Toast.LENGTH_SHORT).also { it.show() }
    }
    private suspend fun releaseEngineResources() {
        vision.releaseModels()
        if (container.translationQueue.activeMangas.value.isEmpty() && container.translationQueue.priorityPage.value == null)
            container.localTranslator.retainModels(emptySet())
    }
    fun toggleEngine() {
        when (state.value.engine) {
            ScreenEngineState.LOADING, ScreenEngineState.READY -> stopEngine()
            ScreenEngineState.STOPPING -> Unit
            ScreenEngineState.STOPPED, ScreenEngineState.FAILED -> prepareEngine()
        }
    }
    /** Keep the capture session and movable ball while releasing this session's engines. */
    fun stopEngine(reason: String = "") {
        engineRequest.update { it.copy(wanted = false) }; resumeContinuous = false
        if (!state.value.started || unloading?.isActive == true || state.value.engine == ScreenEngineState.STOPPED) return
        stopContinuous(); retire(warming); warming = null
        preparedOwner?.let(container.localTranslator::unpin); preparedOwner = null
        configuration = null; workflow = null; program = null; textCache.clear(); apiResources.value = emptyList()
        state.update { it.copy(engine = ScreenEngineState.STOPPING, status = message(R.string.screen_translation_engine_unloading)) }; updateButton()
        unloading = scope.launch(start = CoroutineStart.LAZY) {
            try {
                joinRetired()
                resourceLifecycle.withLock {
                    releaseEngineResources()
                    if (!state.value.started || unloading !== currentCoroutineContext().job) return@withLock
                    state.update { it.copy(engine = ScreenEngineState.STOPPED, status = reason.ifBlank { message(R.string.screen_translation_engine_unloaded) }) }
                    showEngineMessage(R.string.screen_translation_engine_unloaded); updateButton()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (state.value.started && unloading === currentCoroutineContext().job) {
                    state.update { it.copy(engine = ScreenEngineState.FAILED, status = failure.message ?: message(R.string.screen_translation_engine_unload_failed)) }
                    showEngineMessage(state.value.status); updateButton()
                }
            } finally { if (unloading === currentCoroutineContext().job) unloading = null }
        }
        unloading?.start()
    }
    fun prepareEngine() {
        if (!state.value.started || state.value.engine == ScreenEngineState.READY || warming?.isActive == true || unloading?.isActive == true) return
        engineRequest.update { EngineRequest(wanted = true, revision = it.revision + 1) }
    }
    private fun refreshEngine(input: EngineInput) {
        resumeContinuous = resumeContinuous || state.value.continuous
        stopContinuous(preserveResume = true)
        retire(warming); warming = null
        state.update { it.copy(engine = ScreenEngineState.LOADING, status = message(R.string.screen_translation_engine_loading)) }; updateButton()
        val pinOwner = "$owner:${UUID.randomUUID()}"
        warming = scope.launch(start = CoroutineStart.LAZY) {
            var ready = false
            try {
                joinRetired()
                resourceLifecycle.withLock {
                val config = input.settings
                require(!config.requiresAccessibility || ScreenAccessibilityService.instance != null) { "浏览和视频模式需要启用无障碍辅助" }
                require(!config.requiresAccessibility || Build.VERSION.SDK_INT >= 34) { "当前系统没有干净连续截图能力，请使用手动模式" }
                val selected = input.workflow ?: error("工作流已删除，请重新选择")
                val source = LocalTranslationLanguage.fromTag(config.translation.sourceLanguage ?: error("请选择原文语言"))
                val target = LocalTranslationLanguage.fromTag(config.translation.targetLanguage ?: error("请选择目标语言"))
                val profiles = input.profiles
                translationTaskSnapshot(selected, config.translation, source.tag, target.tag, config.translation.customStyle.orEmpty(), BubbleRenderSettings(), profiles)
                val execution = selected.program.withApiOverride(config.translation.apiProfileId)
                val nodes = execution.allNodes()
                val profileIds = nodes.filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
                    .mapNotNull { (it.inputs["profile"] as? WorkflowExpression.Text)?.value }.toSet()
                apiResources.value = profiles.filter { it.id in profileIds }.map { "${it.name} · ${it.model}" }
                fun language(expression: WorkflowExpression?): LocalTranslationLanguage? = when (expression) {
                    is WorkflowExpression.Text -> LocalTranslationLanguage.fromTag(expression.value)
                    WorkflowSystem.ref(WorkflowSystem.SOURCE) -> source
                    WorkflowSystem.ref(WorkflowSystem.TARGET) -> target
                    else -> null
                }
                val languages = nodes.filter { it.kind == WorkflowKind.OCR }.mapNotNull { node ->
                    language(node.inputs["language"])?.let(LocalPageTranslator::ocrLanguage)
                }.toSet()
                val routes = nodes.filter { it.kind == WorkflowKind.TRANSLATE }.mapNotNull { node ->
                    val from = language(node.inputs["source"]); val to = language(node.inputs["target"])
                    if (from != null && to != null) from to to else null
                }.toSet()
                vision.setPreprocessingCacheLimit(config.cacheMegabytes.toLong() * 1_048_576)
                cacheBudget.setLimit(config.cacheMegabytes.toLong() * 1_048_576)
                sessionGlossary.clear(); textCache.clear()
                // Swap pins before warming: retain shared routes, release removed screen routes,
                // and avoid charging both old and new model routes against the memory budget.
                container.localTranslator.pinModels(pinOwner, routes)
                preparedOwner?.let(container.localTranslator::unpin); preparedOwner = null
                container.translationQueue.retainScreenTranslationModels(routes)
                visionSettings = config.vision
                coroutineScope {
                    val segmentation = async(Dispatchers.IO) { vision.reconfigure(nodes.any { it.kind == WorkflowKind.SEG } && config.translation.segTextScope != SegTextScope.FREE_TEXT,
                        languages, needTextDetection = nodes.any { it.kind == WorkflowKind.SEG } || languages.isNotEmpty()) }
                    val translation = async(Dispatchers.IO) { container.localTranslator.prepare(pinOwner, routes) }
                    segmentation.await(); translation.await()
                }
                currentCoroutineContext().ensureActive()
                if (!state.value.started || !engineRequest.value.wanted || warming !== currentCoroutineContext().job) return@launch
                val captureChanged = configuration?.usesAccessibility != config.usesAccessibility
                configuration = config; workflow = selected; program = execution
                frameBroker?.invalidate()
                if (captureChanged) {
                    val assist = ScreenAccessibilityService.instance.takeIf { config.usesAccessibility }
                    val host = assist ?: checkNotNull(service)
                    overlay?.close(); overlay = ScreenOverlay(host, assist != null, { openSettings(host) }, ::tap)
                }
                preparedOwner = pinOwner; ready = true
                state.update { it.copy(engine = ScreenEngineState.READY, status = message(R.string.screen_translation_engine_loaded)) }; updateButton()
                showEngineMessage(R.string.screen_translation_engine_loaded)
                if (resumeContinuous && config.mode != ScreenTranslationMode.MANUAL) {
                    state.update { it.copy(continuous = true, status = "正在监测屏幕") }
                    monitor(); followMotion(); updateButton()
                }
                resumeContinuous = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (state.value.started && warming === currentCoroutineContext().job) {
                    state.update { it.copy(engine = ScreenEngineState.FAILED, status = failure.message ?: "翻译引擎加载失败") }
                    showEngineMessage(state.value.status)
                }
                updateButton()
            } finally { if (!ready) container.localTranslator.unpin(pinOwner) }
        }
        warming?.start()
    }
    fun tap() {
        val current = state.value
        if (!current.started) return
        if (current.engine != ScreenEngineState.READY) {
            if (current.engine == ScreenEngineState.LOADING) showEngineMessage(R.string.screen_translation_engine_loading)
            else if (current.engine == ScreenEngineState.STOPPING) showEngineMessage(R.string.screen_translation_engine_unloading)
            else prepareEngine()
            return
        }
        val config = configuration ?: return
        engineMessage?.cancel()
        if (config.mode == ScreenTranslationMode.MANUAL) {
            if (current.busy) return
            stopContinuous()
            state.update { it.copy(status = "正在截取屏幕") }
            val epoch = generation
            worker = scope.launch(start = CoroutineStart.LAZY) {
                try { joinRetired(); val startedAt = SystemClock.uptimeMillis(); val frame = capture().limited(config.maximumCaptureEdge); process(frame, listOf(frame.bounds), epoch,
                    startedAt = startedAt, captureMillis = SystemClock.uptimeMillis() - startedAt) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { if (worker === currentCoroutineContext().job) state.update { it.copy(status = failure.message ?: "屏幕翻译失败") } }
                finally { finishWorker(currentCoroutineContext().job, manual = true) }
            }
            state.update { it.copy(busy = true) }; updateButton(); worker?.start()
        } else if (current.continuous) { stopContinuous(); state.update { it.copy(status = "连续屏幕翻译已停止") } }
        else { state.update { it.copy(continuous = true, status = "正在监测屏幕") }; monitor(); followMotion(); updateButton() }
    }
    fun scroll(windowId: Int) {
        val config = configuration ?: return
        if (activeWindow >= 0 && windowId != activeWindow) return
        if (config.mode == ScreenTranslationMode.MANUAL) { trace("manual scroll window=$windowId"); clearManualLater(); return }
        interaction(windowId, action = false)
    }
    fun windowChanged(id: Int, pkg: String) {
        if (pkg == container.application.packageName || pkg == "com.android.systemui") return
        if (activePackage.isNotEmpty() && (activeWindow != id || activePackage != pkg)) { frameBroker?.invalidate(); resetScene() }
        activeWindow = id; activePackage = pkg
        val assistant = ScreenAccessibilityService.instance
        if (assistant != null && !assistant.contentWindowAvailable && assistant.contentOcclusions.isNotEmpty())
            browseTracker.pauseForOcclusion()
        overlay?.setOcclusions(assistant?.contentOcclusions.orEmpty())
        captureWakeups.trySend(Unit)
    }
    fun interaction(windowId: Int, action: Boolean) {
        if (!state.value.started || state.value.inside || activeWindow >= 0 && windowId != activeWindow) return
        if (action) captureWakeups.trySend(Unit)
    }
    private fun resetScene() { trace("reset epoch=$generation package=$activePackage window=$activeWindow"); generation++; clearing?.cancel(); clearing = null; coverage.clear(); coverageTracker.clear(); coverageProbeAt = 0L; detectedBrowseRegions.clear(); supersededBrowseRegions.clear(); clearVideoFrames(); stability.reset(); lastBrowseReliable = false; gray = null; grayArea = null; browseTracker.reset(); cumulative = ScreenTransform(); overlay?.clear(); if (configuration?.mode == ScreenTranslationMode.BROWSE) browseSession.submit(emptyList()); captureWakeups.trySend(Unit); state.update { it.copy(bubbles = 0) } }
    private var clearing: Job? = null
    private fun clearManualLater() {
        generation++
        clearing?.cancel(); clearing = scope.launch { delay(configuration?.manualClearMillis?.toLong() ?: 120); overlay?.clear(); state.update { it.copy(bubbles = 0) } }
    }
    private fun move(transform: ScreenTransform, bounds: PixelRect, area: PixelRect? = null) {
        cumulative = cumulative.then(transform)
        val margin = configuration?.offscreenMarginPercent ?: 25
        overlay?.move(transform, bounds, margin, area)
        state.update { it.copy(bubbles = overlay?.count ?: 0) }
    }
    private suspend fun capture(): CapturedScreen = checkNotNull(frameBroker).capture()
    private fun translationAreas(config: ScreenTranslationSettings, bounds: PixelRect): List<PixelRect> {
        if (config.mode == ScreenTranslationMode.MANUAL) return listOf(bounds)
        val window = overlay?.matchingArea(bounds) ?: bounds
        val selected = config.regionSelection().areas(overlay?.screenBounds() ?: bounds, window)
        if (config.mode != ScreenTranslationMode.VIDEO || config.videoTopPercent == 0 && config.videoBottomPercent == 100) return selected
        val legacy = PixelRect(bounds.left, bounds.top + bounds.height * config.videoTopPercent / 100,
            bounds.right, bounds.top + bounds.height * config.videoBottomPercent / 100)
        return selected.mapNotNull { it.intersection(legacy) }
    }

    private fun supersedeBrowseRegions(ids: Set<String>) {
        supersededBrowseRegions += ids
        ids.forEach { id -> browseBubbleJobs[id]?.forEach { it.cancel(WorkflowBubbleStopped()) } }
        browseHosts.values.forEach { it.supersedeScreenRegions(ids) }
    }
    private fun reportWait(stage: String, reason: String?) {
        val previous = waitReasons[stage]
        if (previous == reason) return
        if (reason == null) waitReasons.remove(stage) else waitReasons[stage] = reason
        val queue = container.translationQueue.items.value.count { it.state == "PENDING" || it.state == "RUNNING" }
        android.util.Log.i("ScreenTranslation", "${if (reason == null) "RESUME" else "WAIT"} stage=$stage reason=${reason ?: previous} mode=${configuration?.mode} source=${frameBroker?.backend} chapters=${chapterWindow.size} queue=$queue priority=${container.translationQueue.schedulingPriority.value} cache=${cacheBudget.bytes.value}/${cacheBudget.limitBytes}")
    }
    private suspend fun awaitQueuePermission(stage: String) {
        fun allowed() = container.translationQueue.paused.value ||
            container.translationQueue.schedulingPriority.value == TranslationSchedulingPriority.RESOURCES ||
            container.translationQueue.items.value.none { it.state == "PENDING" || it.state == "RUNNING" }
        if (!allowed()) {
            reportWait(stage, "manga-queue")
            state.update { it.copy(status = "等待翻译队列，暂停队列可加快屏幕翻译") }
        }
        combine(container.translationQueue.paused, container.translationQueue.schedulingPriority, container.translationQueue.items) { paused, priority, items ->
            paused || priority == TranslationSchedulingPriority.RESOURCES || items.none { it.state == "PENDING" || it.state == "RUNNING" }
        }.first { it }
        if (waitReasons[stage] == "manga-queue") reportWait(stage, null)
    }
    private suspend fun recoverCapture(stage: String, failure: Throwable): Boolean {
        if (!failure.isTemporaryScreenCaptureFailure()) return false
        reportWait(stage, "capture-${(failure as? ScreenCaptureException)?.code ?: "no-frame"}")
        state.update { it.copy(status = failure.message ?: "正在等待屏幕画面") }
        delay(334)
        return true
    }
    private suspend fun observeBrowse(frame: CapturedMotion, snapshotSources: Boolean = false): BrowseObservation = observationLock.withLock {
        val epoch = generation
        val previousPose = browseTracker.rawPose
        val previousFrame = if (traceMotion) browseTracker.latestFrame else null
        val renderer = overlay
        val objects = renderer?.motionObjects(previousPose).orEmpty()
        val observation = browseTracker.register(frame, epoch)
        if (traceMotion && !observation.reliable && previousFrame != null) rejectedMotion = previousFrame.gray to frame.gray
        if (epoch != generation) return@withLock observation.copy(reliable = false, newest = false)
        if (observation.sceneChanged) {
            resetScene()
            val first = browseTracker.register(frame, generation)
            stability.observe(frame.sequence, SystemClock.uptimeMillis(), false, true)
            return@withLock first
        }
        if (observation.newest) {
            val decisions = withContext(Dispatchers.Default) {
                ScreenObjectTracker.evaluate(objects, frame.excluding(browseTracker.visualOcclusions).gray, frame.bounds,
                    previousPose.inverse().then(observation.capturePose), frame.sequence,
                    allowFixedCorrection = configuration?.trackingAlgorithm == ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS)
            }
            if (epoch != generation) return@withLock observation.copy(reliable = false, newest = false)
            if (renderer != null && overlay === renderer) {
                renderer.setOcclusions(ScreenAccessibilityService.instance?.contentOcclusions.orEmpty() + browseTracker.visualOcclusions)
                val removed = linkedSetOf<String>()
                val invalidated = renderer.follow(observation.capturePose, observation.displayPose, frame.bounds,
                    configuration?.offscreenMarginPercent ?: 25, decisions) { removed += it }
                coverage.removeContent(invalidated)
                if (removed.isNotEmpty()) {
                    supersedeBrowseRegions(removed)
                    browseSession.submit(renderer.sessionRows(browseSession.sessionId, epoch))
                }
            }
            cumulative = observation.displayPose
            stability.observe(frame.sequence, SystemClock.uptimeMillis(), observation.moved, observation.reliable)
            val now = SystemClock.uptimeMillis()
            if (now >= coverageProbeAt) {
                coverageProbeAt = now + (configuration?.sampleIntervalMillis ?: 250)
                val reliable = observation.reliable && !observation.moved && browseTracker.visualOcclusions.isEmpty() &&
                    ScreenAccessibilityService.instance?.contentOcclusions.isNullOrEmpty() &&
                    stability.ready(now, configuration?.settleMillis ?: 180, 1200)
                val changed = withContext(Dispatchers.Default) {
                    coverageTracker.evaluate(frame.gray, frame.bounds, observation.capturePose, frame.sequence, reliable)
                }
                if (epoch != generation) return@withLock observation.copy(reliable = false, newest = false)
                if (changed.isNotEmpty()) {
                    coverage.removeContent(changed)
                    trace("COVERAGE invalidated=$changed sequence=${frame.sequence}")
                    android.util.Log.i("ScreenTranslation", "Invalidating changed browse coverage blocks=${changed.size} sequence=${frame.sequence}")
                    captureWakeups.trySend(Unit)
                }
                coverageTracker.retain(screenRetentionBounds(frame.bounds, configuration?.offscreenMarginPercent ?: 25), observation.capturePose)
            }
            state.update { it.copy(bubbles = overlay?.count ?: 0) }
            if (observation.moved || observation.reliable != lastBrowseReliable) captureWakeups.trySend(Unit)
            lastBrowseReliable = observation.reliable
        }
        if (!snapshotSources || !observation.reliable) observation else {
            // Freeze individual attachment corrections under the observation
            // lock, before another following frame can mutate their positions.
            val sourceVersions = renderer?.sourceRegions(previousPose).orEmpty().associate { it.id to it.version }
            val shown = if (observation.newest) renderer?.sourceRegions(observation.capturePose).orEmpty()
                else withContext(Dispatchers.Default) {
                    ScreenObjectTracker.evaluate(objects, frame.gray, frame.bounds,
                        previousPose.inverse().then(observation.capturePose), frame.sequence,
                        allowFixedCorrection = configuration?.trackingAlgorithm == ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS).filter { !it.remove }.mapNotNull { decision ->
                        objects.firstOrNull { it.id == decision.id }?.let { obj -> ScreenSourceRegion(obj.id,
                            decision.sourceToScreen.bounds(obj.sourceBounds), sourceVersions[obj.id] ?: 0, obj.template) }
                    }
                }
            val shownIds = shown.map { it.id }.toSet()
            val pending = detectedBrowseRegions.values.flatten().filter { it.id !in shownIds && it.id !in supersededBrowseRegions }
            val waiting = withContext(Dispatchers.Default) { pending.map { source ->
                val decision = ScreenObjectTracker.evaluate(listOf(ScreenMotionObject(source.id, 0, source.bounds,
                    ScreenTransform(), source.template)), frame.gray, frame.bounds,
                    source.pose.inverse().then(observation.capturePose), frame.sequence,
                    allowFixedCorrection = configuration?.trackingAlgorithm == ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS).single()
                ScreenSourceRegion(source.id, decision.sourceToScreen.bounds(source.bounds), source.version, source.template)
            } }
            observation.copy(sources = shown + waiting)
        }
    }

    private fun followMotion() {
        if (configuration?.mode != ScreenTranslationMode.BROWSE || motionLoop?.isActive == true) return
        motionLoop = scope.launch {
            joinRetired()
            while (isActive && state.value.started && state.value.continuous) {
                val config = configuration ?: break
                if (state.value.inside) { delay(100); continue }
                val assistant = ScreenAccessibilityService.instance
                if (assistant != null && !assistant.contentWindowAvailable && assistant.contentOcclusions.isNotEmpty()) {
                    overlay?.setOcclusions(assistant.contentOcclusions)
                    delay(100); continue
                }
                val started = SystemClock.uptimeMillis()
                try {
                    val beforeCapture = generation
                    val acquired = checkNotNull(frameBroker).motion()
                    reportWait("motion", null)
                    if (assistant != null && !assistant.contentWindowAvailable && assistant.contentOcclusions.isNotEmpty()) continue
                    val areas = translationAreas(config, acquired.bounds)
                    overlay?.setTranslationAreas(areas)
                    val next = acquired.restricted(areas)
                    if (beforeCapture != generation) continue
                    viewport = next.bounds
                    observeBrowse(next)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ScreenContentTemporarilyOccluded) { reportWait("motion", "occluded"); delay(100) }
                catch (failure: Throwable) { if (recoverCapture("motion", failure)) continue; haltContinuous(failure.message ?: "画面跟踪失败"); break }
                val minimum = if (frameBroker?.streaming == true) 1000L / config.followFramesPerSecond else 334L
                // Work finishes before the next frame is requested. Slow estimates
                // run to completion; elapsed frame targets never cancel them.
                delay((started + minimum - SystemClock.uptimeMillis()).coerceAtLeast(0))
            }
        }
    }

    private fun haltContinuous(reason: String) {
        android.util.Log.w("ScreenTranslation", "HALT reason=$reason chapters=${chapterWindow.size}")
        captureLoop?.cancel(); captureLoop = null; motionLoop?.cancel(); motionLoop = null
        frameFailure = reason
        state.update { it.copy(continuous = false, status = reason) }
        updateButton()
    }

    private fun monitor() {
        if (captureLoop?.isActive == true) return
        captureLoop = scope.launch {
            joinRetired()
            val config = configuration ?: return@launch
            val cadence = ScreenFrameCadence(config.sampleIntervalMillis.toLong())
            val videoClock = if (config.mode == ScreenTranslationMode.VIDEO) ScreenVideoCadence(config.sampleIntervalMillis.toLong()).also { videoCadence = it } else null
            var baseline: ScreenGrayFrame? = null
            var baselineArea: PixelRect? = null
            var baselineButton: PixelRect? = null
            if (config.mode == ScreenTranslationMode.MANUAL) {
                if (overlay?.count == 0) return@launch
                // The button has recovered and the translated layer has been submitted.
                // Full-display probes keep that layer visible, just like this baseline.
                overlay?.awaitDraw()
                val frame = try { checkNotNull(frameBroker).capture(monitoring = true, fresh = true, after = projection?.sequence ?: -1) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { state.update { it.copy(status = failure.message ?: "画面检测失败") }; return@launch }
                try {
                    baselineButton = overlay?.buttonBounds()
                    baselineArea = frame.bounds
                    baseline = withContext(Dispatchers.Default) { frame.gray(excluded = listOfNotNull(baselineButton) + overlay?.systemExclusions().orEmpty()) }
                    trace("manual baseline bounds=${frame.bounds} size=${baseline.width}x${baseline.height}")
                    cadence.probed(SystemClock.uptimeMillis())
                } finally { frame.close() }
            }
            while (isActive && state.value.started && state.value.engine == ScreenEngineState.READY) {
                if (config.mode == ScreenTranslationMode.MANUAL && overlay?.count == 0) break
                if (config.mode != ScreenTranslationMode.MANUAL && !state.value.continuous) break
                if (state.value.inside) { delay(100); continue }
                if (config.mode == ScreenTranslationMode.BROWSE &&
                    (!ScreenAccessibilityService.instance?.contentOcclusions.isNullOrEmpty() ||
                        browseTracker.visualOcclusions.isNotEmpty() ||
                        !stability.ready(SystemClock.uptimeMillis(), config.settleMillis, 1200))) {
                    reportWait("recognition", if (!ScreenAccessibilityService.instance?.contentOcclusions.isNullOrEmpty() ||
                        browseTracker.visualOcclusions.isNotEmpty()) "occluded" else "stabilizing")
                    withTimeoutOrNull(stability.waitMillis(SystemClock.uptimeMillis(), config.settleMillis).coerceIn(33, 334)) { captureWakeups.receive() }
                    continue
                }
                if (config.mode != ScreenTranslationMode.MANUAL) {
                    val diskAvailable = withContext(Dispatchers.IO) { pending.bytes() } + vision.preprocessingCache.protectedBytes.value < cacheBudget.limitBytes
                    val memoryAvailable = cacheBudget.bytes.value + 16_000_000 <= cacheBudget.limitBytes
                    if ((!diskAvailable || !memoryAvailable) && chapterWindow.size == 0) {
                        haltContinuous("屏幕缓存已满，结果仍完整保留。请重试失败画面或增加缓存预算"); break
                    }
                    if (!chapterWindow.canAdmit(diskAvailable && memoryAvailable)) {
                        reportWait("recognition", if (!diskAvailable || !memoryAvailable) "cache-budget" else "screen-frame-window")
                        withTimeoutOrNull(1000) { captureWakeups.receive() }; continue
                    }
                    awaitQueuePermission("recognition")
                }
                val wait = videoClock?.waitMillis(SystemClock.uptimeMillis()) ?: cadence.waitMillis(SystemClock.uptimeMillis())
                if (wait > 0) { withTimeoutOrNull(wait) { captureWakeups.receive() }; continue }
                if (config.mode == ScreenTranslationMode.BROWSE && viewport != null) {
                    val knownAreas = translationAreas(config, viewport!!)
                    val pose = browseTracker.rawPose
                    val fresh = knownAreas.flatMap { coverage.exposed(it, pose) }.flatMap { screenExposedRegions(it,
                        chapterWindow.reserved(generation, pose) + overlay?.fixedRegions().orEmpty()) }
                    val scale = minOf(1f, config.maximumCaptureEdge / maxOf(viewport!!.width, viewport!!.height))
                    if (screenBrowseRecognitionCrops(fresh, knownAreas, 0f).none {
                            it.width * scale >= config.minimumNewAreaPixels && it.height * scale >= config.minimumNewAreaPixels }) {
                        reportWait("recognition", "no-new-region")
                        withTimeoutOrNull(1000) { captureWakeups.receive() }; continue
                    }
                }
                var held: CapturedScreen? = null
                reportWait("recognition", null)
                try {
                    val captureStarted = SystemClock.uptimeMillis()
                    val beforeCapture = generation
                    val captured = checkNotNull(frameBroker).capture(monitoring = config.mode == ScreenTranslationMode.MANUAL,
                        fresh = config.mode == ScreenTranslationMode.VIDEO)
                        .limited(config.maximumCaptureEdge).also { held = it }
                    reportWait("capture", null)
                    cadence.probed(SystemClock.uptimeMillis())
                    if (beforeCapture != generation) continue
                    if (captured.windowId >= 0 && activeWindow >= 0 && captured.windowId != activeWindow) resetScene()
                    if (captured.windowId >= 0) activeWindow = captured.windowId
                    viewport = captured.bounds
                    val selectedAreas = translationAreas(config, captured.bounds)
                    if (config.mode != ScreenTranslationMode.MANUAL) overlay?.setTranslationAreas(selectedAreas)
                    if (selectedAreas.isEmpty()) continue
                    val area = PixelRect(selectedAreas.minOf { it.left }, selectedAreas.minOf { it.top },
                        selectedAreas.maxOf { it.right }, selectedAreas.maxOf { it.bottom })
                    var pose = ScreenTransform()
                    var browseFrame: CapturedMotion? = null
                    var browseSources = emptyList<ScreenSourceRegion>()
                    when (config.mode) {
                        ScreenTranslationMode.MANUAL -> {
                            val next = withContext(Dispatchers.Default) { captured.gray(excluded = listOfNotNull(baselineButton, overlay?.buttonBounds()) + overlay?.systemExclusions().orEmpty()) }
                            val difference = baseline?.difference(next) ?: 0f
                            trace("manual probe bounds=${captured.bounds} diff=$difference")
                            if (baselineArea != captured.bounds || difference >= config.changeThresholdPercent) {
                                if (traceMotion) baseline?.let { rejectedMotion = it to next }
                                clearManualLater(); break
                            }
                            continue
                        }
                        ScreenTranslationMode.VIDEO -> {
                            // Every admitted video frame reaches SEG. Reuse is decided for
                            // corresponding detected regions, never by a whole-screen probe.
                        }
                        ScreenTranslationMode.BROWSE -> {
                            val next = CapturedMotion(withContext(Dispatchers.Default) { captured.gray(excluded = overlay?.systemExclusions().orEmpty() +
                                ScreenAccessibilityService.instance?.contentOcclusions.orEmpty()) },
                                captured.bounds, captured.sequence, captured.capturedAt).restricted(selectedAreas)
                            val observation = observeBrowse(next, snapshotSources = true)
                            if (!observation.reliable || browseTracker.visualOcclusions.isNotEmpty() ||
                                !ScreenAccessibilityService.instance?.contentOcclusions.isNullOrEmpty() ||
                                !stability.ready(SystemClock.uptimeMillis(), config.settleMillis, 1200)) continue
                            pose = observation.capturePose
                            browseFrame = next
                            browseSources = observation.sources
                        }
                    }
                    if (!state.value.continuous) break
                    coverage.retain(screenRetentionBounds(captured.bounds, config.offscreenMarginPercent), pose)
                    val candidates = if (config.mode == ScreenTranslationMode.VIDEO) selectedAreas
                        else selectedAreas.flatMap { coverage.exposed(it, pose) }.flatMap { screenExposedRegions(it,
                            chapterWindow.reserved(generation, pose) + overlay?.fixedRegions().orEmpty()) }
                    // Test the packed exposure before dropping thin subtraction
                    // remnants; adjoining remnants may form one meaningful input.
                    val eligible = if (config.mode != ScreenTranslationMode.BROWSE) candidates else
                        screenBrowseRecognitionCrops(candidates, selectedAreas, 0f).filter { r ->
                            floor((r.right - captured.bounds.left) * captured.bitmap.width / captured.bounds.width) - ceil((r.left - captured.bounds.left) * captured.bitmap.width / captured.bounds.width) >= config.minimumNewAreaPixels &&
                            floor((r.bottom - captured.bounds.top) * captured.bitmap.height / captured.bounds.height) - ceil((r.top - captured.bounds.top) * captured.bitmap.height / captured.bounds.height) >= config.minimumNewAreaPixels }
                    val exposed = candidates.filter { r -> eligible.any { it.intersects(r) } }
                    if (exposed.isEmpty()) continue
                    val epoch = generation
                    // The workflow retains this frame's source pixels. The SEG
                    // planner receives only exposed tiles, then explicit merge crops.
                    if (epoch != generation) continue
                    val processingAreas = if (browseFrame != null) listOf(area) else exposed
                    val captureMillis = SystemClock.uptimeMillis() - captureStarted
                    val reserved = exposed.flatMap { r -> selectedAreas.mapNotNull { it.intersection(r) } }
                    if (reserved.isEmpty()) continue
                    val chapter = chapterWindow.admit(epoch, pose, reserved)
                    if (videoClock != null) {
                        videoFrames[chapter.number] = VideoFrame(chapter, processingAreas.size, captureStarted, latestVideoFrame?.regions.orEmpty())
                        videoClock.captured()
                        android.util.Log.d("ScreenVideo", "CAPTURE frame=${chapter.number} at=$captureStarted buffered=${chapterWindow.size}")
                    }
                    cadence.submitted(SystemClock.uptimeMillis()); held = null
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        try { process(captured, processingAreas, epoch, chapter = chapter, startedAt = captureStarted,
                            captureMillis = captureMillis, browseFrame = browseFrame, scopeAreas = selectedAreas,
                            browseSources = browseSources, freshAreas = exposed) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (chapterJobs[chapter.number] === currentCoroutineContext().job)
                                skipFrame(epoch, chapter.number, null, failure, reserved, browseFrame, pose)
                        }
                        catch (failure: Throwable) { if (chapterJobs[chapter.number] === currentCoroutineContext().job) haltContinuous(failure.message ?: "屏幕翻译失败") }
                        finally { finishChapter(chapter, currentCoroutineContext().job) }
                    }
                    chapterJobs[chapter.number] = job
                    state.update { it.copy(busy = true, activeChapters = chapterWindow.size) }; updateButton(); job.start()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ScreenContentTemporarilyOccluded) { reportWait("capture", "occluded"); delay(100) }
                catch (failure: Throwable) { if (recoverCapture("capture", failure)) continue; haltContinuous(failure.message ?: "屏幕截图失败"); break }
                finally { held?.close() }
            }
            updateButton()
        }
    }
    private fun finishChapter(chapter: ScreenChapterWindow.Chapter, job: Job) {
        if (chapterJobs[chapter.number] !== job) return
        chapterJobs.remove(chapter.number)
        chapterWindow.complete(chapter, inOrder = configuration?.mode == ScreenTranslationMode.VIDEO)
        videoFrames.remove(chapter.number); pruneVideoTargets(); chapterRevision.update { it + 1 }
        waitReasons.remove("workflow-${chapter.number}"); waitReasons.remove("consumer-${chapter.number}")
        state.update { it.copy(busy = worker?.isActive == true || chapterWindow.size > 0, activeChapters = chapterWindow.size) }
        updateButton(); if (state.value.continuous) captureWakeups.trySend(Unit)
    }
    private fun clearVideoFrames() {
        videoFrames.clear(); latestVideoFrame = null; videoTargets.clear()
        videoRecognitionHistory.clear(); videoCadence?.reset()
    }
    private fun pruneVideoTargets() {
        val keep = latestVideoFrame?.regions.orEmpty().mapTo(hashSetOf()) { it.id }
        videoFrames.values.forEach { frame -> frame.regions.forEach { keep += it.id } }
        keep += overlay?.positions(true)?.keys.orEmpty()
        videoTargets.keys.retainAll(keep)
    }
    /** Main-thread SEG commit: all selected crops must be ready before another image can enter. */
    private fun videoSegmented(chapter: ScreenChapterWindow.Chapter?, crop: Int, regions: List<ScreenVideoRegion>) {
        if (chapter == null || chapter.epoch != generation) return
        val frame = videoFrames[chapter.number] ?: return
        frame.crops[crop] = regions
        regions.forEach { region ->
            if ((videoTargets[region.id]?.number ?: Long.MIN_VALUE) <= chapter.number)
                videoTargets[region.id] = VideoTarget(chapter.number, region.bounds)
        }
        if (overlay?.promoteVideo(regions.associate { it.id to it.bounds }, chapter.number) == true)
            publicationOrder.accept(chapter.epoch, chapter.number, generation)
        if (!frame.segmented && frame.crops.size == frame.cropCount) {
            frame.segmented = true
            val now = SystemClock.uptimeMillis()
            chapter.preprocessingFinished(); videoCadence?.segmented(now)
            if ((latestVideoFrame?.chapter?.number ?: Long.MIN_VALUE) < chapter.number) latestVideoFrame = frame
            val ids = frame.regions.map { it.id }.toSet()
            val previous = frame.previous.map { it.id }.toSet()
            if (videoRecognitionHistory.size >= 64) videoRecognitionHistory.removeFirst()
            videoRecognitionHistory.addLast(VideoRecognition(chapter.number, frame.capturedAt, now, ids, ids.intersect(previous)))
            android.util.Log.d("ScreenVideo", "SEG frame=${chapter.number} at=$now regions=${ids.size} reused=${ids.intersect(previous).size} buffered=${chapterWindow.size}")
            pruneVideoTargets(); captureWakeups.trySend(Unit)
        }
    }
    private fun renderVideo(renderer: ScreenOverlay, prepared: List<ScreenOverlay.PreparedBubble>, pageToScreen: ScreenTransform,
        chapter: ScreenChapterWindow.Chapter?, epoch: Long, complete: Boolean, area: PixelRect) {
        val number = chapter?.number ?: 0
        var filled = false
        var cleanupVersion = number
        for (bubble in prepared.filterNot { it.placeholder }) {
            val target = videoTargets[bubble.id]
            val effective = maxOf(number, target?.number ?: number)
            if (!publicationOrder.accept(epoch, effective, generation)) continue
            val transform = if (target != null && target.number > number)
                screenVideoRegionTransform(bubble.bounds, target.bounds) else pageToScreen
            renderer.addVideo(listOf(bubble), transform, effective)
            filled = true
            cleanupVersion = maxOf(cleanupVersion, effective)
        }
        if ((filled || complete) && publicationOrder.accept(epoch, cleanupVersion, generation)) {
            // Pending origin work may already belong to a newer frame. Keep its
            // identity, drawing and future text instead of re-translating it.
            val retained = videoTargets.filterValues { it.number >= cleanupVersion && it.bounds.intersects(area) }.keys
            // A duplicate-only frame may finish before its origin's API. Its
            // empty workflow is not evidence that the replacement text is ready.
            if (retained.all { it in renderer.positions(false) }) renderer.finishVideoArea(area, cleanupVersion, retained)
        }
        pruneVideoTargets()
    }
    private suspend fun beginConsumer(chapter: ScreenChapterWindow.Chapter?) {
        if (chapter == null) return
        var registered = false
        try { withContext(Dispatchers.Main.immediate) {
            if (container.translationQueue.schedulingPriority.value == TranslationSchedulingPriority.ORDER &&
                chapterWindow.chapters.any { it.number < chapter.number }) reportWait("consumer-${chapter.number}", "earlier-screen-frame")
            combine(chapterRevision, container.translationQueue.schedulingPriority) { _, priority ->
                priority != TranslationSchedulingPriority.ORDER || chapterWindow.chapters.none { it.number < chapter.number }
            }.first { it }
            reportWait("consumer-${chapter.number}", null)
            chapter.consumerStarted(); registered = true; captureWakeups.trySend(Unit)
        } } catch (failure: Throwable) { if (registered) chapter.consumerFinished(); throw failure }
    }
    private fun endConsumer(chapter: ScreenChapterWindow.Chapter?) { chapter?.consumerFinished() }
    private fun skipFrame(epoch: Long, chapter: Long, pageId: String?, failure: Exception,
        areas: List<PixelRect>, sample: CapturedMotion?, atCapture: ScreenTransform): String {
        val reason = failure.message ?: "屏幕翻译失败"
        android.util.Log.w("ScreenTranslation", "SKIP chapter=$chapter page=$pageId mode=${configuration?.mode} error=${failure.javaClass.simpleName} reason=$reason")
        if (generation == epoch) {
            // Failed inputs remain retryable, but a static browse page must not
            // repeatedly spend SEG/OCR/API resources on the same failed crop.
            if (sample != null && configuration?.mode == ScreenTranslationMode.BROWSE) {
                areas.forEach { coverage.add(it, atCapture) }
                coverageTracker.add(areas, sample.gray, sample.bounds, atCapture, sample.sequence)
            }
            state.update { it.copy(status = "当前画面失败，已跳过：$reason", bubbles = overlay?.count ?: 0) }
        }
        return reason
    }
    private suspend fun process(frame: CapturedScreen, areas: List<PixelRect>, epoch: Long, retry: PendingScreenFrame? = null,
        chapter: ScreenChapterWindow.Chapter? = null, startedAt: Long = SystemClock.uptimeMillis(), captureMillis: Long = 0,
        browseFrame: CapturedMotion? = null, scopeAreas: List<PixelRect> = emptyList(),
        browseSources: List<ScreenSourceRegion> = emptyList(), freshAreas: List<PixelRect> = emptyList()): Boolean {
        val capturedFrame = frame
        val config = retry?.settings ?: configuration ?: run { frame.close(); return false }
        val originalTransform = chapter?.capturedAt ?: cumulative
        val performance = ScreenPerformanceTrace(chapter?.number ?: 0, startedAt).also { it.record("CAPTURE", captureMillis) }
        var failedReason: String? = null
        state.update { it.copy(busy = true, status = "正在翻译屏幕") }; updateButton()
        try {
            val cleanBrowseFrame = if (config.mode == ScreenTranslationMode.BROWSE) browseFrame ?: CapturedMotion(
                withContext(Dispatchers.Default) { frame.gray(excluded = overlay?.systemExclusions().orEmpty()) },
                frame.bounds, frame.sequence, frame.capturedAt).let { if (scopeAreas.isEmpty()) it else it.restricted(scopeAreas) } else null
            // Capacity gates new pages, never the remaining crops or OCR of
            // an already admitted chapter. Completed cache entries can trim.
            if (retry == null && chapter == null && pending.bytes() + vision.preprocessingCache.protectedBytes.value >= cacheBudget.limitBytes)
                error("屏幕缓存已满，结果仍完整保留。请重试失败画面或增加缓存预算")
            viewport = frame.bounds
            // Queue order means the already queued comics advance first. The
            // resource-utilization option allows independent screen SEG/OCR now.
            val queueStarted = SystemClock.uptimeMillis()
            awaitQueuePermission("workflow-${chapter?.number ?: "manual"}")
            performance.record("QUEUE", SystemClock.uptimeMillis() - queueStarted)
            suspend fun processArea(areaIndex: Int, area: PixelRect) {
                currentCoroutineContext().ensureActive()
                if (epoch >= 0 && generation != epoch) return
                val context = container.application
                // Browse keeps this clean capture available while its planner
                // detects new tiles, then restores only real text merge groups.
                val border = if (config.mode == ScreenTranslationMode.BROWSE) 0f else config.overlapPixels * frame.bounds.width / frame.bitmap.width
                val expanded = PixelRect(maxOf(frame.bounds.left, area.left - border), maxOf(frame.bounds.top, area.top - border),
                    minOf(frame.bounds.right, area.right + border), minOf(frame.bounds.bottom, area.bottom + border))
                fun lower(value: Float) = floor(value).toInt()
                fun upper(value: Float) = ceil(value).toInt()
                val x = lower((expanded.left - frame.bounds.left) * frame.bitmap.width / frame.bounds.width).coerceIn(0, frame.bitmap.width)
                val y = lower((expanded.top - frame.bounds.top) * frame.bitmap.height / frame.bounds.height).coerceIn(0, frame.bitmap.height)
                val right = upper((expanded.right - frame.bounds.left) * frame.bitmap.width / frame.bounds.width).coerceIn(x, frame.bitmap.width)
                val bottom = upper((expanded.bottom - frame.bounds.top) * frame.bitmap.height / frame.bounds.height).coerceIn(y, frame.bitmap.height)
                val minimum = if (config.mode == ScreenTranslationMode.BROWSE) config.minimumNewAreaPixels else 2
                if (right - x < minimum || bottom - y < minimum) {
                    if (config.mode == ScreenTranslationMode.VIDEO) videoSegmented(chapter, areaIndex, emptyList())
                    return
                }
                val croppedArea = PixelRect(frame.bounds.left + x * frame.bounds.width / frame.bitmap.width,
                    frame.bounds.top + y * frame.bounds.height / frame.bitmap.height,
                    frame.bounds.left + right * frame.bounds.width / frame.bitmap.width,
                    frame.bounds.top + bottom * frame.bounds.height / frame.bitmap.height)
                var mergedCrop = ScreenMergedCrop(croppedArea, emptySet())
                var ownedAreas = (if (scopeAreas.isEmpty()) listOf(croppedArea) else scopeAreas.mapNotNull { it.intersection(croppedArea) })
                    .flatMap { screenExposedRegions(it, mergedCrop.preserves.map { source -> source.bounds }) }
                val rawCrop = Bitmap.createBitmap(frame.bitmap, x, y, right - x, bottom - y)
                val image = withContext(Dispatchers.Default) {
                    val masked = maskedScreenCrop(rawCrop, croppedArea, ownedAreas)
                    if (config.mode != ScreenTranslationMode.MANUAL && masked === rawCrop) masked.copy(Bitmap.Config.ARGB_8888, true)!! else masked
                }
                if (image !== rawCrop && rawCrop !== frame.bitmap) rawCrop.recycle()
                val pageToCapture = ScreenTransform(frame.bounds.width / frame.bitmap.width,
                    croppedArea.left, croppedArea.top)
                val pageId = retry?.pageId ?: "screen:${UUID.randomUUID()}"
                val input = retry?.image ?: pending.file(pageId)
                val page = ReaderPage(pageId, 0, "屏幕", pageId)
                val source = object : PageSource {
                    override suspend fun pages() = listOf(page)
                    override suspend fun open(page: ReaderPage) = input.inputStream()
                    override suspend fun probe(page: ReaderPage) = PageGeometry(image.width, image.height)
                }
                var host: AndroidWorkflowHost? = null
                var admitted = false
                var journal: PendingScreenFrame? = null
                var successful = false
                var cropRegions = emptyList<ScreenSourceRegion>()
                val renderedRegions = linkedMapOf<String, PageTranslatedRegion>()
                var reusedVideoRegions = emptyList<ScreenVideoRegion>()
                if (config.mode == ScreenTranslationMode.BROWSE) overlay?.protectReplacements(pageId, mergedCrop.replaces)
                try {
                    val imageStarted = SystemClock.uptimeMillis()
                    val hash = withContext(Dispatchers.IO) {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        if (retry == null) pending.writeImage(pageId) { output ->
                            val hashed = java.security.DigestOutputStream(output, digest)
                            check(image.compress(Bitmap.CompressFormat.PNG, 100, hashed)); hashed.flush()
                        } else input.inputStream().use { stream ->
                            val buffer = ByteArray(65536)
                            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    performance.record("IMAGE", SystemClock.uptimeMillis() - imageStarted)
                    val from = LocalTranslationLanguage.fromTag(config.translation.sourceLanguage!!); val to = LocalTranslationLanguage.fromTag(config.translation.targetLanguage!!)
                    val profiles = retry?.snapshot?.let { org.json.JSONObject(it).optJSONObject("apiProfiles")?.let { p -> ApiProfileCodec.decode(p.toString()) } }
                        ?: container.apiProfiles.profiles.first()
                    val options = WorkflowRunSettings(from, to, config.translation.customStyle.orEmpty(), config.translation.effectiveBubbleRender(BubbleRenderSettings()),
                        config.translation.effectiveSegThreshold(), profiles, config.translation.segTextScope,
                        config.translation.effectiveTextDetectionThreshold(), config.translation.effectiveFreeTextMergeGapRatio())
                    val chapterId = "screen-chapter:$pageId"
                    val run = object : AndroidWorkflowHost(context, container, owner, "屏幕翻译", listOf(WorkflowChapterInput(chapterId, "屏幕", source, listOf(page))),
                        options, cacheBudget, reusePages = false, requestOrigin = "屏幕翻译", vision = vision, ephemeral = true, ephemeralGlossary = sessionGlossary,
                        pageImages = mapOf(pageId to WorkflowPageImage(image, hash)),
                        screenPreparation = screenVisionPreparation(retry?.snapshot?.let { WorkflowProgramCodec.decode(org.json.JSONObject(it).getJSONObject("program").toString()) } ?: program!!, from.tag, to.tag)) {
                        override suspend fun segmentPage(pageId: String, bitmap: Bitmap, sha256: String, frame: WorkflowFrame): WorkflowPageSegmentation {
                            if (config.mode == ScreenTranslationMode.VIDEO && chapter != null) {
                                val detection = super.segmentPage(pageId, bitmap, sha256, frame)
                                val previous = withContext(Dispatchers.Main.immediate) {
                                    videoFrames[chapter?.number]?.previous.orEmpty()
                                }
                                val fresh = withContext(Dispatchers.Default) { screenVideoRegions(bitmap, detection.regions, pageToCapture) }
                                val candidates = withContext(Dispatchers.Default) { screenVideoRegionMatches(previous, fresh, config.videoSimilarityPercent) }
                                val matches = withContext(Dispatchers.Main.immediate) {
                                    if (epoch >= 0 && generation != epoch) throw WorkflowPageStopped()
                                    val video = videoFrames[chapter?.number]
                                    candidates.filterValues { old -> video?.claimed?.get(old.id) == null || video.claimed[old.id] == areaIndex }
                                        .also { accepted -> accepted.values.forEach { old -> video?.claimed?.set(old.id, areaIndex) } }
                                }
                                reusedVideoRegions = fresh.mapNotNull { region -> matches[region.id]?.let { region.copy(id = it.id) } }
                                val remaining = detection.regions.filterNot { it.id in matches }
                                // Whole-page OCR/vision nodes must also see only new source
                                // text. Keep unmatched detections intact where boxes touch.
                                var finalHash: String? = detection.sha256
                                if (matches.isNotEmpty()) {
                                    val excluded = detection.regions.filter { it.id in matches }.flatMap { region ->
                                        screenExposedRegions(region.bounds, remaining.map { it.bounds })
                                    }
                                    val viewportPixels = PixelRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                                    withContext(Dispatchers.Default) { maskedScreenCrop(bitmap, viewportPixels,
                                        screenExposedRegions(viewportPixels, excluded), copy = false) }
                                    finalHash = withContext(Dispatchers.IO) {
                                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                                        pending.writeImage(pageId) { output ->
                                            val hashed = java.security.DigestOutputStream(output, digest)
                                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, hashed)); hashed.flush()
                                        }
                                        digest.digest().joinToString("") { "%02x".format(it) }
                                    }
                                }
                                return detection.copy(regions = remaining, sha256 = finalHash)
                            }
                            if (config.mode != ScreenTranslationMode.BROWSE) return super.segmentPage(pageId, bitmap, sha256, frame)
                            val inverse = pageToCapture.inverse()
                            val old = browseSources.map { it.copy(bounds = inverse.bounds(it.bounds)) }
                            val allowed = ownedAreas.map(inverse::bounds)
                            val fresh = (freshAreas.ifEmpty { listOf(croppedArea) }).map(inverse::bounds)
                            val plan = withContext(Dispatchers.Default) {
                                ScreenBrowseSegPlanner { tile, id ->
                                    val seg = vision.segment(id, tile, options.segThreshold, options.textDetectionThreshold,
                                        scope = options.segTextScope)
                                    seg to com.lmreader.core.vision.selectSegRegions(seg, options.segTextScope, options.freeTextMergeGapRatio)
                                }.plan(pageId, bitmap, fresh, old, allowed, config.browseMergeDistancePixels.toFloat(),
                                    config.overlapPixels.toFloat()) { source, region ->
                                    val similarity = if (cleanBrowseFrame == null) null else
                                        source.template?.compareOriginalFrame(cleanBrowseFrame.gray, cleanBrowseFrame.bounds, pageToCapture.bounds(region.bounds))
                                    screenSameSourceRegion(source.bounds, region.bounds, similarity)
                                }
                            }
                            val stillCurrent = withContext(Dispatchers.Main.immediate) {
                                if (generation != epoch) false else {
                                    mergedCrop = plan.replacement.copy(bounds = croppedArea,
                                        replacedSources = plan.replacement.replacedSources.map { it.copy(bounds = pageToCapture.bounds(it.bounds)) })
                                    ownedAreas = plan.owned.map(pageToCapture::bounds)
                                    overlay?.protectReplacements(pageId, mergedCrop.replaces)
                                    plan.searched.forEach { coverage.add(pageToCapture.bounds(it), originalTransform) }
                                    cleanBrowseFrame?.let { source -> coverageTracker.add(plan.firstPassInputs.map(pageToCapture::bounds),
                                        source.gray, source.bounds, originalTransform, source.sequence) }
                                    val diagnostic = BrowseRecognition(cleanBrowseFrame?.sequence ?: capturedFrame.sequence,
                                        freshAreas, plan.firstPassInputs.map(pageToCapture::bounds), plan.mergeInputs.map(pageToCapture::bounds), plan.reused)
                                    if (browseRecognitionHistory.size >= 32) browseRecognitionHistory.removeFirst()
                                    browseRecognitionHistory.addLast(diagnostic)
                                    trace("SEG frame=${diagnostic.sequence} fresh=${diagnostic.fresh} tiles=${diagnostic.initialInputs} merges=${diagnostic.mergedInputs} reused=${diagnostic.reused}")
                                    true
                                }
                            }
                            if (!stillCurrent) throw WorkflowPageStopped()
                            val viewportPixels = PixelRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                            val needsMask = screenExposedRegions(viewportPixels, plan.owned).isNotEmpty()
                            if (needsMask) withContext(Dispatchers.Default) { maskedScreenCrop(bitmap, viewportPixels, plan.owned, copy = false) }
                            val finalHash = if (!needsMask) sha256 else withContext(Dispatchers.IO) {
                                val digest = java.security.MessageDigest.getInstance("SHA-256")
                                pending.writeImage(pageId) { output ->
                                    val hashed = java.security.DigestOutputStream(output, digest)
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, hashed)); hashed.flush()
                                }
                                digest.digest().joinToString("") { "%02x".format(it) }
                            }
                            return WorkflowPageSegmentation(plan.segmentation, plan.regions, finalHash)
                        }
                        override suspend fun canProcessBubble(frame: WorkflowFrame): Boolean {
                            if (config.mode != ScreenTranslationMode.BROWSE) return true
                            val id = (frame.currentBubble()?.fields?.get("id") as? WorkflowValue.Text)?.value ?: return true
                            return generation == epoch && id !in supersededBrowseRegions
                        }
                        override suspend fun runBubble(frame: WorkflowFrame, action: suspend () -> Boolean): Boolean {
                            if (config.mode != ScreenTranslationMode.BROWSE) return action()
                            val id = (frame.currentBubble()?.fields?.get("id") as? WorkflowValue.Text)?.value ?: return action()
                            return supervisorScope {
                                val work = async(start = CoroutineStart.LAZY) { if (!canProcessBubble(frame)) throw WorkflowBubbleStopped(); action() }
                                val jobs = browseBubbleJobs.computeIfAbsent(id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                                jobs += work; work.start()
                                try { work.await() } finally { jobs.remove(work); if (jobs.isEmpty()) browseBubbleJobs.remove(id, jobs) }
                            }
                        }
                        override fun timing(pageId: String?, stage: String, elapsedMillis: Long) { performance.record(stage, elapsedMillis) }
                        override suspend fun regionsDetected(pageId: String, regions: List<SegRegion>) {
                            if (config.mode == ScreenTranslationMode.VIDEO) {
                                val fresh = withContext(Dispatchers.Default) { screenVideoRegions(image, regions, pageToCapture) }
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == epoch) {
                                        cropRegions = fresh.map { ScreenSourceRegion(it.id, it.bounds, chapter?.number ?: 0) }
                                        videoSegmented(chapter, areaIndex, fresh + reusedVideoRegions)
                                    }
                                }
                                return
                            }
                            if (cleanBrowseFrame == null || generation != epoch) return
                            val sources = withContext(Dispatchers.Default) { regions.map { region ->
                                val bounds = pageToCapture.bounds(region.bounds)
                                DetectedBrowseRegion(region.id, bounds, originalTransform,
                                    ScreenObjectTemplate.capture(cleanBrowseFrame.gray, cleanBrowseFrame.bounds, bounds), chapter?.number ?: 0)
                            } }
                            withContext(Dispatchers.Main.immediate) {
                                if (generation == epoch) {
                                    detectedBrowseRegions[pageId] = sources
                                    cropRegions = sources.map { ScreenSourceRegion(it.id, it.bounds, it.version) }
                                }
                            }
                        }
                        override suspend fun visionPrepared(pageId: String) {
                            if (config.mode != ScreenTranslationMode.VIDEO && areaIndex == areas.lastIndex) withContext(Dispatchers.Main.immediate) {
                                chapter?.preprocessingFinished(); captureWakeups.trySend(Unit)
                            }
                        }
                        override suspend fun waitingForApi(call: WorkflowApiCall, frame: WorkflowFrame, waiting: Boolean) {
                            if (waiting) beginConsumer(chapter) else endConsumer(chapter)
                        }
                        override fun progress(frame: WorkflowFrame, progress: PageTranslationProgress) {
                            if (generation == epoch) state.update { it.copy(status = when (progress.stage) {
                                PageTranslationStage.SEGMENTING -> if (config.translation.segTextScope == SegTextScope.FREE_TEXT) "正在检测文字" else "正在分割气泡"; PageTranslationStage.OCR -> "正在识别文字";
                                PageTranslationStage.TRANSLATING -> "正在翻译文字"; else -> "正在处理屏幕"
                            }) }
                        }
                        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
                            if (kind != WorkflowKind.TRANSLATE) return super.request(kind, inputs, frame)
                            val key = listOf("source", "target", "text").map { (inputs.getValue(it) as WorkflowValue.Text).value }
                            textCache[key]?.let { return it }
                            beginConsumer(chapter)
                            return try { super.request(kind, inputs, frame).also { textCache[key] = it; if (textCache.size > 512) textCache.keys.firstOrNull()?.let(textCache::remove) } }
                                finally { endConsumer(chapter) }
                        }
                        override suspend fun pagePreviewed(frame: WorkflowFrame, saved: ReaderPageTranslation) = renderResult(saved, complete = false)
                        override suspend fun pagePublished(frame: WorkflowFrame, saved: ReaderPageTranslation) = renderResult(saved, complete = true)
                        private suspend fun renderResult(saved: ReaderPageTranslation, complete: Boolean) {
                            if (generation != epoch) return
                            val changed = saved.regions.filter { region ->
                                if (region.region.id in supersededBrowseRegions) return@filter false
                                val previous = renderedRegions[region.region.id]
                                region != previous && (region.translatedText.isNotBlank() || previous == null && !complete)
                            }
                            val original = if (image.width == saved.width && image.height == saved.height) image else Bitmap.createScaledBitmap(image, saved.width, saved.height, true)
                            try {
                                val pageToScreen = ScreenTransform((right - x) * capturedFrame.bounds.width / capturedFrame.bitmap.width / saved.width,
                                    capturedFrame.bounds.left + x * capturedFrame.bounds.width / capturedFrame.bitmap.width,
                                    capturedFrame.bounds.top + y * capturedFrame.bounds.height / capturedFrame.bitmap.height)
                                val renderer = overlay ?: return
                                val renderStarted = SystemClock.uptimeMillis()
                                val prepared = withContext(Dispatchers.Default) {
                                    renderer.prepare(original, changed, options.render,
                                        showEmpty = config.mode == ScreenTranslationMode.BROWSE && !complete).map { bubble ->
                                        if (cleanBrowseFrame == null) bubble else bubble.copy(template = ScreenObjectTemplate.capture(
                                            cleanBrowseFrame.gray, cleanBrowseFrame.bounds, pageToScreen.bounds(bubble.bounds)))
                                    }
                                }
                                performance.record("RENDER", SystemClock.uptimeMillis() - renderStarted)
                                var initialDecisions = emptyList<ScreenObjectDecision>()
                                var decisionsPose = originalTransform
                                if (cleanBrowseFrame != null) {
                                    val latest = withContext(Dispatchers.Main.immediate) { browseTracker.latestFrame to browseTracker.rawPose }
                                    decisionsPose = latest.second
                                    latest.first?.let { current -> initialDecisions = withContext(Dispatchers.Default) {
                                        ScreenObjectTracker.evaluate(prepared.map { bubble -> ScreenMotionObject(bubble.id, 0,
                                            bubble.bounds, pageToScreen, bubble.template) }, current.gray, current.bounds,
                                            originalTransform.inverse().then(latest.second), current.sequence,
                                            allowFixedCorrection = config.trackingAlgorithm == ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS)
                                    } }
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == epoch) {
                                        if (config.mode == ScreenTranslationMode.VIDEO && chapter != null) {
                                            if (overlay === renderer) renderVideo(renderer, prepared, pageToScreen, chapter, epoch, complete, croppedArea)
                                            changed.forEach { renderedRegions[it.region.id] = it }
                                            return@withContext
                                        }
                                        if (config.mode == ScreenTranslationMode.BROWSE) {
                                            val currentRaw = browseTracker.rawPose
                                            val corrected = initialDecisions.map { decision ->
                                                if (decision.attachment == ScreenObjectAttachment.FIXED) decision else decision.copy(
                                                    sourceToScreen = decision.sourceToScreen.then(decisionsPose.inverse().then(currentRaw)))
                                            }
                                            if (overlay === renderer) {
                                                val superseded = renderer.replaceBrowse(prepared, pageToScreen, originalTransform,
                                                    currentRaw, cumulative, chapter?.number ?: 0, mergedCrop, corrected, ownedAreas, cropRegions)
                                                supersedeBrowseRegions(superseded)
                                                browseSession.submit(renderer.sessionRows(browseSession.sessionId, epoch))
                                            }
                                        } else {
                                            val transform = pageToScreen.then(originalTransform.inverse().then(cumulative))
                                            if (overlay === renderer) {
                                                renderer.add(prepared, transform, chapter?.number ?: 0)
                                                // Explicit retries run outside the continuous
                                                // frame window and retain their own filled IDs.
                                                if (config.mode == ScreenTranslationMode.VIDEO && complete)
                                                    renderer.finishVideoArea(croppedArea, 0,
                                                        saved.regions.filter { it.translatedText.isNotBlank() }.map { it.region.id }.toSet())
                                            }
                                        }
                                        if (overlay === renderer) {
                                            changed.forEach { renderedRegions[it.region.id] = it }
                                            if (complete) renderer.discardEmpty(saved.regions.map { it.region.id }.toSet())
                                        }
                                    }
                                }
                            } finally { if (original !== image) original.recycle() }
                            state.update { it.copy(bubbles = overlay?.count ?: 0) }
                        }
                    }.also { host = it; if (config.mode == ScreenTranslationMode.BROWSE) browseHosts[pageId] = it }
                    val snapshot = retry?.snapshot ?: org.json.JSONObject(translationTaskSnapshot(workflow!!, config.translation,
                        from.tag, to.tag, config.translation.customStyle.orEmpty(), BubbleRenderSettings(), options.apiProfiles)).put("screenPixelAreas",
                        org.json.JSONArray().also { values -> ownedAreas.forEach { r ->
                            values.put(org.json.JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
                        } }).toString()
                    withContext(Dispatchers.IO) {
                        val submitted = PendingScreenFrame(pageId, input, croppedArea, config, snapshot)
                        pending.admit(submitted); admitted = true; journal = submitted
                        val captured = org.json.JSONObject(snapshot)
                        WorkflowRuntime(captured.getInt("parallelLimit"), captured.getInt("retries")).execute(WorkflowProgramCodec.decode(captured.getJSONObject("program").toString()), run)
                        require(run.published.containsKey(pageId)) { "工作流未生成本页结果，请检查页面循环与回填步骤" }
                        successful = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    val reason = skipFrame(epoch, chapter?.number ?: 0, pageId, failure,
                        freshAreas.ifEmpty { ownedAreas }.mapNotNull { it.intersection(croppedArea) }, cleanBrowseFrame, originalTransform)
                    failedReason = reason
                    journal?.let { submitted -> withContext(NonCancellable + Dispatchers.IO) {
                        try { pending.admit(submitted.copy(error = reason)) }
                        catch (journalFailure: Exception) { android.util.Log.w("ScreenTranslation", "Failed to record page error; original retry journal retained", journalFailure) }
                    } }
                } finally {
                    host?.close()
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (config.mode == ScreenTranslationMode.VIDEO && generation == epoch) {
                            // Invalid/failed workflows still release their SEG gate, while
                            // the frame remains buffered until all its crop work has ended.
                            if (videoFrames[chapter?.number]?.crops?.containsKey(areaIndex) != true)
                                videoSegmented(chapter, areaIndex, emptyList())
                        }
                        detectedBrowseRegions.remove(pageId); browseHosts.remove(pageId); overlay?.releaseReplacements(pageId)
                        if (!successful) {
                            if (config.mode == ScreenTranslationMode.BROWSE)
                                supersededBrowseRegions.removeAll(overlay?.discardFailedEmpty(renderedRegions.keys).orEmpty())
                            else overlay?.discardEmpty(renderedRegions.keys)
                            state.update { it.copy(bubbles = overlay?.count ?: 0) }
                        }
                        if (config.mode == ScreenTranslationMode.BROWSE && generation == epoch) browseSession.submit(overlay?.sessionRows(browseSession.sessionId, epoch).orEmpty())
                    }
                    if (successful) pending.complete(pageId) else if (!admitted) input.delete()
                    if (image !== frame.bitmap) image.recycle()
                }
                if (successful && generation == epoch && config.mode == ScreenTranslationMode.BROWSE) {
                    if (browseSources.isEmpty()) ownedAreas.forEach { coverage.add(it, originalTransform) }
                    coverage.removeContent(overlay?.fixedContentAreas().orEmpty())
                }
            }
            if (config.mode == ScreenTranslationMode.VIDEO) supervisorScope {
                areas.mapIndexed { index, area -> async { processArea(index, area) } }.awaitAll()
            } else areas.forEachIndexed { index, area -> processArea(index, area) }
            if (generation == epoch) {
                state.update { it.copy(status = frameFailure ?: failedReason?.let { reason -> "当前画面失败，已跳过：$reason" } ?: "屏幕翻译完成", timing = performance.summary()) }
            }
            return failedReason == null
        } finally { frame.close() }
    }
    fun retryPendingFrame() {
        if (!state.value.started || state.value.busy || state.value.engine != ScreenEngineState.READY) return
        val saved = pending.latest() ?: return
        stopContinuous()
        val epoch = generation
        worker = scope.launch(start = CoroutineStart.LAZY) {
            var original: CapturedScreen? = null
            try {
                val bitmap = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(saved.image.path) ?: error("失败画面无法读取") }
                val stored = CapturedScreen(bitmap, saved.bounds).also { original = it }
                val current = capture()
                val savedAreas = org.json.JSONObject(saved.snapshot).optJSONArray("screenPixelAreas")?.let { values ->
                    (0 until values.length()).map { index -> val r = values.getJSONArray(index)
                        PixelRect(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat()) }
                } ?: listOf(saved.bounds)
                val same = try { current.bounds.contains(saved.bounds) && withContext(Dispatchers.Default) {
                    val old = CapturedMotion(stored.gray(), saved.bounds).restricted(savedAreas).gray
                    val now = CapturedMotion(current.gray(saved.bounds), saved.bounds).restricted(savedAreas).gray
                    old.difference(now) < saved.settings.changeThresholdPercent
                } }
                    finally { current.close() }
                val complete = process(stored, listOf(saved.bounds), if (same) epoch else -1, saved, scopeAreas = savedAreas)
                if (generation == epoch && complete) state.update { it.copy(status = if (same) "失败画面重试完成" else "失败画面已处理；当前屏幕已改变，请重新截图") }
                else if (generation == epoch && !same) state.update { it.copy(status = "失败画面重试失败，已保留供再次重试") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { if (generation == epoch) state.update { it.copy(status = failure.message ?: "屏幕翻译失败") } }
            finally { original?.close(); finishWorker(currentCoroutineContext().job, manual = true) }
        }
        state.update { it.copy(busy = true) }; updateButton(); worker?.start()
    }
    private fun updateButton() {
        val current = state.value
        overlay?.buttonState(current.inside, current.engine == ScreenEngineState.READY, current.busy, current.continuous,
            (configuration ?: preferences.settings.value).mode == ScreenTranslationMode.MANUAL)
        service?.refreshNotice()
    }
}

private fun PixelRect.intersection(other: PixelRect): PixelRect? = if (!intersects(other)) null else
    PixelRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))

private fun PixelRect.contains(other: PixelRect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
