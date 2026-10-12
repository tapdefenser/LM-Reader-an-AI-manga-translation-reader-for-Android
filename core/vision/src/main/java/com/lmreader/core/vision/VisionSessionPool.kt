package com.lmreader.core.vision

import android.app.ActivityManager
import android.content.Context
import com.lmreader.core.model.*
import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Seg and OCR own separate pools; no stage holds another stage's lock. */
class LocalVisionEngine(context: Context, cacheNamespace: String = "vision-preprocessing",
    private val settings: () -> VisionExecutionSettings = { VisionExecutionSettings() }) {
    private val context = context.applicationContext
    private val messages = MutableStateFlow<List<String>>(emptyList())
    val accelerationMessages = messages.asStateFlow()
    private fun report(message: String) { messages.update { (it.filterNot { old -> old == message } + message).takeLast(8) } }
    private val segPool = VisionSessionPool(this.context, "seg", ::report)
    private val ocrPool = VisionSessionPool(this.context, "ocr", ::report)
    val loadedResources = combine(segPool.resources, ocrPool.resources) { seg, ocr -> seg + ocr }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, emptyList())
    val activeSeg = segPool.activeTasks
    val activeOcr = ocrPool.activeTasks
    @Volatile private var preprocessingCacheLimit = 128L * 1_048_576
    val preprocessingCache = VisionPreprocessingCache(java.io.File(this.context.filesDir, cacheNamespace)) { preprocessingCacheLimit }
    suspend fun setPreprocessingCacheLimit(bytes: Long) { preprocessingCacheLimit = bytes; preprocessingCache.trim() }
    val segConcurrency get() = concurrency(settings().segConcurrency, true)
    val ocrConcurrency get() = concurrency(settings().ocrConcurrency, false)
    private fun concurrency(value: Int, seg: Boolean): Int {
        if (value != 0) return value
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val cores = Runtime.getRuntime().availableProcessors()
        // Auto reserves half of currently available RAM for the reader, NMT and the OS.
        val memorySlots = (memory.availMem / 2 / if (seg) 384_000_000L else 192_000_000L).toInt()
        val heapSlots = ((Runtime.getRuntime().maxMemory() - 32_000_000L) / if (seg) 128_000_000L else 96_000_000L).toInt()
        return minOf((cores / 2).coerceAtLeast(1), memorySlots.coerceAtLeast(1), heapSlots.coerceAtLeast(1), if (seg) 2 else 4)
    }
    suspend fun segment(imageId: String, image: Bitmap, threshold: Float = .35f,
        textDetectionThreshold: Float = .35f, sourceSha256: String? = null, scope: SegTextScope = SegTextScope.ALL,
        progress: (VisionProgress) -> Unit = {}): SegResult {
        if (sourceSha256 != null) return withContext(Dispatchers.IO) {
            preprocessingCache.segment(imageId, sourceSha256, image.width, image.height, threshold, textDetectionThreshold, scope) {
                segment(imageId, image, threshold, textDetectionThreshold, scope = scope, progress = progress)
            }
        }
        val started = System.nanoTime()
        val seg = if (scope == SegTextScope.FREE_TEXT) SegResult(imageId, image.width, image.height, emptyList(), 0, "DET")
            else segPool.use(segConcurrency, settings()) { it.segment(imageId, image, threshold, progress) }
        // Detection is a SEG function but consumes the shared OCR concurrency and backend.
        // Release the segmentation slot before taking an OCR slot so stages never hold both.
        val lines = ocrPool.use(ocrConcurrency, settings()) { it.detectSegTextLines(seg, image, textDetectionThreshold, progress) }
        val result = supplementFreeTextRegions(seg.copy(textLines = lines))
        progress(VisionProgress("SEG 完成",1,1))
        return result.copy(elapsedMillis = (System.nanoTime() - started) / 1_000_000)
    }
    suspend fun recognize(imageId: String, image: Bitmap, language: LocalOcrLanguage,
        regions: List<PixelRect>? = null, textLines: List<DetectedTextLine>? = null, progress: (VisionProgress) -> Unit = {}): LocalOcrResult {
        return ocrPool.use(ocrConcurrency, settings()) { session ->
            val lines = textLines ?: session.detectTextLines(imageId, image, regions,
                language in setOf(LocalOcrLanguage.JAPANESE, LocalOcrLanguage.CHINESE_SIMPLIFIED, LocalOcrLanguage.CHINESE_TRADITIONAL), progress)
            session.recognize(imageId, image, language, regions, lines, progress)
        }
    }
    suspend fun releaseModels() { segPool.release(); ocrPool.release() }
    suspend fun prepare(needSeg: Boolean, languages: Set<LocalOcrLanguage>, needTextDetection: Boolean = needSeg || languages.isNotEmpty()) {
        val sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        suspend fun warm(pool: VisionSessionPool, limit: Int, action: suspend (LocalVisionSession) -> Unit) {
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val ready = CompletableDeferred<Unit>()
            coroutineScope {
                List(limit) { async { pool.use(limit, settings()) { session ->
                    action(session)
                    if (completed.incrementAndGet() == limit) ready.complete(Unit)
                    ready.await()
                } } }.awaitAll()
            }
        }
        try {
            if (needSeg) warm(segPool, segConcurrency) {
                if ("seg" !in it.residentModels()) it.segment("warmup", sample)
            }
            if (needTextDetection || languages.isNotEmpty()) warm(ocrPool, ocrConcurrency) { session ->
                val resident = session.residentModels()
                if ("det" !in resident) session.detectTextLines("warmup", sample)
                for (language in languages.distinctBy { it == LocalOcrLanguage.KOREAN }) {
                    if ((if (language == LocalOcrLanguage.KOREAN) "ko" else "rec") !in resident)
                        session.recognize("warmup", sample, language, null,
                            listOf(DetectedTextLine(PixelRect(0f, 0f, 64f, 64f), 1f)))
                }
            }
        } finally { sample.recycle() }
    }
    /** Rebuild only the pool whose backend changed; trim unused models/clones before warming additions. */
    suspend fun reconfigure(needSeg: Boolean, languages: Set<LocalOcrLanguage>,
        needTextDetection: Boolean = needSeg || languages.isNotEmpty(), warm: Boolean = true) {
        val next = settings()
        segPool.configure(next)
        ocrPool.configure(next)
        retainModels(needSeg, languages, needTextDetection)
        if (warm) prepare(needSeg, languages, needTextDetection)
    }
    suspend fun retainModels(needSeg: Boolean, languages: Set<LocalOcrLanguage>, needTextDetection: Boolean = needSeg || languages.isNotEmpty()) {
        segPool.retain(if (needSeg) segConcurrency else 0, needSeg, false, emptySet())
        val needDetection = needTextDetection || languages.isNotEmpty()
        ocrPool.retain(if (needDetection) ocrConcurrency else 0, false, needDetection, languages)
    }
}

internal class VisionSessionPool(private val context: Context, private val name: String, private val report: (String) -> Unit) {
    private class Slot {
        val mutex = Mutex()
        var settings: VisionExecutionSettings? = null
        var session: LocalVisionSession? = null
        var retireWhenIdle = false
        @Volatile var resources: List<LoadedEngineResource> = emptyList()
        @Volatile var inUse = false
    }
    private val slots = List(8) { Slot() }
    private val revision = MutableStateFlow(0L)
    private val mutableResources = MutableStateFlow<List<LoadedEngineResource>>(emptyList())
    val resources = mutableResources.asStateFlow()
    private val mutableActiveTasks = MutableStateFlow(0)
    val activeTasks = mutableActiveTasks.asStateFlow()
    private val configuration = Mutex()
    // Configuration never waits for an occupied slot: warm-up admission also needs this lock.
    private data class Retention(val limit: Int, val seg: Boolean, val detection: Boolean, val languages: Set<LocalOcrLanguage>)
    private var retention: Retention? = null
    private fun compatible(previous: VisionExecutionSettings?, next: VisionExecutionSettings) = previous != null &&
        if (name == "seg") previous.segGpu == next.segGpu else previous.ocrBackend == next.ocrBackend
    private suspend fun clear(slot: Slot) {
        slot.session?.releaseModels(); slot.session = null; slot.settings = null; slot.resources = emptyList(); slot.retireWhenIdle = false
    }
    private suspend fun trim(index: Int, slot: Slot, required: Retention) {
        if (index >= required.limit) clear(slot)
        else slot.session?.retainModels(required.seg, required.detection, required.languages)
    }
    private fun publishResources() {
        mutableActiveTasks.update { slots.count { it.inUse } }
        mutableResources.update { slots.flatMapIndexed { index, slot ->
            slot.resources.map { it.copy(id = "$name:$index:${it.id}", inUse = slot.inUse) }
        } }
    }
    suspend fun <T> use(limit: Int, settings: VisionExecutionSettings, action: suspend (LocalVisionSession) -> T): T = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val before = revision.value
            val admission = configuration.withLock {
                slots.take(limit).firstOrNull { it.mutex.tryLock() }?.let { slot ->
                    slot to settings
                }
            }
            if (admission != null) {
                val (slot, retained) = admission
                try {
                    slot.inUse = true; publishResources()
                    if (slot.session != null && !compatible(slot.settings, retained)) clear(slot)
                    val session = slot.session ?: LocalVisionSession(context, retained, report) {
                        slot.resources = it; publishResources()
                    }.also { slot.session = it; slot.settings = retained }
                    return@withContext action(session)
                } finally { withContext(NonCancellable) {
                    try { configuration.withLock {
                        slot.inUse = false
                        if (slot.retireWhenIdle) clear(slot)
                        else retention?.let { trim(slots.indexOf(slot), slot, it) }
                    } } finally {
                        slot.inUse = false; publishResources(); slot.mutex.unlock(); revision.update { it + 1 }
                    }
                } }
            }
            revision.first { it != before }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    suspend fun release() = configuration.withLock {
        retention = null
        for (slot in slots) {
            if (!slot.mutex.tryLock()) { slot.retireWhenIdle = true; continue }
            try { clear(slot) } finally { slot.mutex.unlock() }
        }
        publishResources(); revision.update { it + 1 }
    }
    suspend fun configure(next: VisionExecutionSettings) = configuration.withLock {
        for (slot in slots) {
            if (!slot.mutex.tryLock()) continue
            try { if (slot.session != null && !compatible(slot.settings, next)) clear(slot) }
            finally { slot.mutex.unlock() }
        }
        publishResources(); revision.update { it + 1 }
    }
    suspend fun retain(limit: Int, needSeg: Boolean, needTextDetection: Boolean, languages: Set<LocalOcrLanguage>) = configuration.withLock {
        val required = Retention(limit, needSeg, needTextDetection, languages.toSet())
        retention = required
        slots.forEachIndexed { index, slot ->
            slot.retireWhenIdle = false
            if (!slot.mutex.tryLock()) return@forEachIndexed
            try { trim(index, slot, required) } finally { slot.mutex.unlock() }
        }
        publishResources(); revision.update { it + 1 }
    }
}
