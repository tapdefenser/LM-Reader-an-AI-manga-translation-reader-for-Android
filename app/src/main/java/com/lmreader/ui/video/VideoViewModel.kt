package com.lmreader.ui.video

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lmreader.core.model.*
import com.lmreader.core.vision.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class VideoUiState(val project: VideoProject? = null, val ready: Boolean = false, val status: String = "正在核对原视频…",
    val error: String? = null, val continuous: Boolean = false, val busy: Boolean = false, val subtitleBusy: Boolean = false,
    val positionMs: Long = 0, val playing: Boolean = false)

class VideoViewModel(private val container: AppContainer, private val id: String,
    private val frameEngine: (VideoProject, TranslationWorkflow) -> VideoFrameEngine = { p, w -> VideoTranslationEngine(container, p, w) }) : ViewModel() {
    private val store = container.videoLibrary
    private val mutable = MutableStateFlow(VideoUiState())
    val state = mutable.asStateFlow()
    @Volatile private var epoch = 0L
    private var worker: Job? = null
    private val retired = mutableListOf<Job>()
    private var subtitleJob: Job? = null
    private var sourceCheck: Job? = null
    private var engine: VideoFrameEngine? = null
    private var manualRequested = false
    private var lastAttempt = 0L
    private var lastProgressSave = 0L
    private var lastIntervalSave = 0L
    private var lastSamplePosition = -1L
    private data class Shown(val result: VideoFrameResult, val saved: ReaderPageTranslation, val proof: VideoFrameProof, val overlay: BubbleOverlay)
    private var shown: Shown? = null
    var pausePlayback: (() -> Unit)? = null
    private val sessionKey = container.videoSessions.register({ pausePlayback?.invoke(); stopAndAwait() }, { verifySource() })
    init {
        viewModelScope.launch { store.projects.collect { list -> mutable.update { it.copy(project = list.firstOrNull { p -> p.id == id }) } } }
        verifySource()
    }
    fun verifySource() { sourceCheck?.cancel(); sourceCheck = viewModelScope.launch {
        mutable.update { it.copy(ready = false, error = null) }
        try {
            val p = store.current(id)
            val actual = VideoMediaSource.inspect(container.application, Uri.parse(p.uri), persist = false)
            ensureActive(); check(store.current(id).uri == p.uri) { "原视频引用已变化，请重试" }
            check(actual.sha256 == p.sha256) { "原文件内容已变化，已有成果已保留；请重新定位原视频或另行导入新文件" }
            mutable.update { it.copy(ready = true, status = "原视频已就绪", error = null, positionMs = p.positionMs) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(ready = false, error = e.message ?: "无法读取原视频，请重新定位") } }
    } }
    fun reportError(message: String) { mutable.update { it.copy(error = message) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun invalidate() {
        epoch++; worker?.let { it.cancel(); retired += it }; worker = null; shown = null; lastSamplePosition = -1
        mutable.update { it.copy(busy = false) }
    }
    fun stop() {
        invalidate(); manualRequested = false
        val old = engine; engine = null
        mutable.update { it.copy(continuous = false, status = "画面翻译已停止") }
        val pending = retired.toList()
        viewModelScope.launch { pending.joinAll(); old?.close() }
    }
    fun start(continuous: Boolean) {
        stop(); val generation = epoch
        val p = store.current(id)
        manualRequested = !continuous
        mutable.update { it.copy(continuous = continuous, busy = true, status = "正在准备画面翻译…", error = null) }
        worker = viewModelScope.launch {
            retired.toList().joinAll(); retired.removeAll { it.isCompleted }
            var run: VideoFrameEngine? = null
            var retained = false
            try {
                val created = frameEngine(p, container.translationWorkflows.find(p.workflowId) ?: error("工作流已删除"))
                run = created
                withContext(Dispatchers.IO) { created.prepare() }
                ensureActive(); check(generation == epoch)
                engine = created; retained = true
                mutable.update { it.copy(status = "画面翻译已就绪", busy = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch) mutable.update { it.copy(error = e.message, continuous = false, busy = false) } }
            finally { if (!retained) run?.close() }
        }
    }
    suspend fun tick(player: VideoPlaybackView, masksEnabled: Boolean) {
        if (!mutable.value.ready) { player.showOverlay(null); return }
        val position = player.positionMs.coerceIn(0, store.current(id).durationMs)
        mutable.update { it.copy(positionMs = position, playing = player.playing) }
        if (SystemClock.uptimeMillis() - lastProgressSave > 5000) { lastProgressSave = SystemClock.uptimeMillis(); savePosition(position) }
        if (!masksEnabled || !player.ready) { player.showOverlay(null); return }
        if (position == lastSamplePosition && worker?.isActive == true) return
        lastSamplePosition = position
        val bitmap = player.capture() ?: return
        val generation = epoch
        try {
            val p = store.current(id)
            val identity = engine?.identity ?: p.frameIdentity
            var display = shown?.takeIf { it.result.identity == identity }
            if (display == null && identity != null) {
                val candidate = p.frames.lastOrNull { it.identity == identity && position >= it.atMs && position < it.untilMs }
                if (candidate != null) display = withContext(Dispatchers.Default) {
                    val saved = store.artifacts(id).current(candidate.pageId) ?: return@withContext null
                    val proof = VideoFrameProof.load(store.proofs(id), candidate.pageId) ?: return@withContext null
                    if (!proof.matches(bitmap, saved)) return@withContext null
                    Shown(candidate, saved, proof, prepareOverlay(bitmap, saved))
                }
            }
            val match = display?.let { withContext(Dispatchers.Default) { it.proof.matches(bitmap, it.saved) } } == true
            if (generation != epoch || player.seeking) return
            if (display != null && match) {
                shown = display
                player.showOverlay(display.overlay, display.saved.width, display.saved.height)
                if (SystemClock.uptimeMillis() - lastIntervalSave > 400 && position != display.result.atMs) {
                    lastIntervalSave = SystemClock.uptimeMillis()
                    val row = withContext(Dispatchers.IO) { store.verifyInterval(id, display.result, position.coerceAtMost(p.durationMs - 1)) }
                    shown = display.copy(result = row)
                }
                return
            }
            player.showOverlay(null); shown = null
            if (worker?.isActive == true || retired.any { !it.isCompleted } || engine == null || (!mutable.value.continuous && !manualRequested) || SystemClock.uptimeMillis() - lastAttempt < 750) return
            val run = engine ?: return
            val capturedAt = position.coerceAtMost(p.durationMs - 1)
            val owned = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: error("无法分配视频分析帧")
            lastAttempt = SystemClock.uptimeMillis()
            mutable.update { it.copy(busy = true, status = "正在翻译当前画面…") }
            worker = viewModelScope.launch {
                try {
                    val saved = withContext(Dispatchers.Default) { run.frame(owned, capturedAt) }
                    ensureActive(); if (generation != epoch) return@launch
                    val proof = VideoFrameProof.capture(owned, saved)
                    val overlay = withContext(Dispatchers.Default) { prepareOverlay(owned, saved) }
                    val context = currentCoroutineContext()
                    val row = withContext(Dispatchers.IO) {
                        proof.save(store.proofs(id), saved.pageId)
                        store.publishFrame(id, p.sha256, capturedAt, capturedAt + 350, run.identity, saved) {
                            context.ensureActive(); check(generation == epoch) { "视频任务已变化" }
                        }
                    }
                    ensureActive(); if (generation != epoch) return@launch
                    // No immediate painting: the next tick validates against fresh source pixels first.
                    shown = Shown(row, saved, proof, overlay); manualRequested = false
                    mutable.update { it.copy(status = "画面译文已保存") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (generation == epoch) { mutable.update { it.copy(error = e.message, continuous = false) }; manualRequested = false } }
                finally { owned.recycle(); if (generation == epoch) mutable.update { it.copy(busy = false) } }
            }
        } finally { bitmap.recycle() }
    }
    fun savePosition(position: Long) { container.backgroundScope.launch(Dispatchers.IO) {
        runCatching { store.change(id) { it.copy(positionMs = position.coerceIn(0, it.durationMs)) } }.onFailure { reportError(it.message ?: "无法保存观看位置") }
    } }
    fun settings(source: String, target: String, workflow: String, subtitleWorkflow: String) {
        stop(); stopSubtitles()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val from = LocalTranslationLanguage.fromTag(source).tag; val to = LocalTranslationLanguage.fromTag(target).tag
                store.change(id) { p -> p.copy(source = from, target = to, workflowId = workflow, subtitleWorkflowId = subtitleWorkflow,
                    frameIdentity = if (p.source == from && p.target == to && p.workflowId == workflow) p.frameIdentity else null,
                    subtitleIdentity = if (p.source == from && p.target == to && p.subtitleWorkflowId == subtitleWorkflow) p.subtitleIdentity
                        else if (p.cues.any { it.translation.isNotEmpty() }) "stale" else null) }
            } catch (e: Exception) { reportError(e.message ?: "设置未保存") }
        }
    }
    fun translateSubtitles() {
        if (subtitleJob?.isActive == true) return
        subtitleJob = viewModelScope.launch {
            val p = store.current(id); val initial = p.cues.associateBy { it.id }
            var run: VideoTranslationEngine? = null
            mutable.update { it.copy(subtitleBusy = true, error = null) }
            try {
                require(p.cues.isNotEmpty()) { "请先导入 SRT 字幕" }
                val workflow = container.translationWorkflows.find(p.subtitleWorkflowId) ?: error("字幕工作流已删除")
                run = VideoTranslationEngine(container, p, workflow)
                withContext(Dispatchers.IO) { run.prepare() }
                require(p.subtitleIdentity == null || p.subtitleIdentity == run.identity) { "字幕已有其他配置的成果，已保留。请先导出，再明确清空译文后重译" }
                val identity = run.identity
                withContext(Dispatchers.IO) { store.change(id) { it.copy(subtitleIdentity = identity) } }
                run.subtitles(p.cues) { cue, done, total ->
                    ensureActive()
                    withContext(Dispatchers.IO) { store.change(id) { current ->
                        check(current.subtitleIdentity == identity) { "字幕任务配置已变化" }
                        current.copy(cues = current.cues.map { existing ->
                            if (existing.id == cue.id && existing == initial[cue.id]) cue else existing
                        })
                    } }
                    mutable.update { it.copy(status = "字幕翻译 $done/$total") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e.message ?: "字幕翻译失败") }
            finally { run?.close(); mutable.update { it.copy(subtitleBusy = false) } }
        }
    }
    fun stopSubtitles() { subtitleJob?.cancel() }
    suspend fun stopAndAwait() {
        val old = engine
        stop(); stopSubtitles()
        retired.toList().joinAll(); subtitleJob?.join(); old?.close()
    }
    fun clearCache() { viewModelScope.launch {
        try {
            container.videoSessions.stopAndAwait()
            withContext(Dispatchers.IO) {
                val parent = java.io.File(container.application.filesDir, "video-analysis-cache").canonicalFile
                val folder = java.io.File(parent, id).canonicalFile
                check(folder.parentFile == parent); check(!folder.exists() || folder.deleteRecursively())
            }
            mutable.update { it.copy(status = "分析缓存已清理，正式成果保留") }
        } catch (e: Exception) { reportError(e.message ?: "缓存清理失败") }
    } }
    fun importSubtitles(uri: Uri) {
        stopSubtitles()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = container.application.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        require(out.size() + n <= VideoSubtitles.MAX_INPUT_BYTES) { "字幕文件超过 4 MiB" }; out.write(buffer, 0, n) }
                    out.toByteArray()
                }
                    ?: error("无法读取字幕")
                val p = store.current(id); val cues = VideoSubtitles.parse(bytes, p.durationMs)
                store.change(id) { it.copy(cues = cues, subtitleIdentity = null) }
            } catch (e: Exception) { reportError(e.message ?: "字幕导入失败") }
        }
    }
    fun edit(cue: VideoSubtitleCue, text: String) { viewModelScope.launch(Dispatchers.IO) {
        try { require(!Regex("\\n\\s*\\n").containsMatchIn(text)) { "SRT 字幕内不能包含空白行" }; store.editCue(id, cue.id, text, cue) }
        catch (e: Exception) { reportError(e.message ?: "校对未保存") }
    } }
    fun clearSubtitleTranslations() {
        stopSubtitles()
        viewModelScope.launch(Dispatchers.IO) { store.change(id) { it.copy(cues = it.cues.map { c -> c.copy(translation = "", manuallyEdited = false) }, subtitleIdentity = null) } }
    }
    fun offset(value: Long) { viewModelScope.launch(Dispatchers.IO) { store.change(id) { it.copy(offsetMs = value.coerceIn(-3600000, 3600000)) } } }
    override fun onCleared() {
        container.videoSessions.remove(sessionKey)
        epoch++; worker?.let { it.cancel(); retired += it }; subtitleJob?.cancel()
        val run = engine; engine = null
        val pending = retired.toList()
        CoroutineScope(Dispatchers.IO).launch { pending.joinAll(); run?.close() }
    }
    companion object {
        private fun prepareOverlay(bitmap: Bitmap, saved: ReaderPageTranslation): BubbleOverlay {
            val scaled = if (bitmap.width == saved.width && bitmap.height == saved.height) bitmap
                else Bitmap.createScaledBitmap(bitmap, saved.width, saved.height, true)
            return try { BubbleMaskRenderer().prepare(scaled, saved.regions, saved.renderSettings) }
            finally { if (scaled !== bitmap) scaled.recycle() }
        }
    }
}
