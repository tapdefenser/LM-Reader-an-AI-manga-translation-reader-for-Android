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
class LocalVisionEngine(context: Context, private val settings: () -> VisionExecutionSettings = { VisionExecutionSettings() }) {
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
        textDetectionThreshold: Float = .45f,
        progress: (VisionProgress) -> Unit = {}): SegResult {
        val started = System.nanoTime()
        val seg = segPool.use(segConcurrency, settings()) { it.segment(imageId, image, threshold, progress) }
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
    suspend fun retainModels(needSeg: Boolean, languages: Set<LocalOcrLanguage>) {
        segPool.retain(if (needSeg) segConcurrency else 0, needSeg, false, emptySet())
        val needDetection = needSeg || languages.isNotEmpty()
        ocrPool.retain(if (needDetection) ocrConcurrency else 0, false, needDetection, languages)
    }
}

private class VisionSessionPool(private val context: Context, private val name: String, private val report: (String) -> Unit) {
    private class Slot {
        val mutex = Mutex()
        var settings: VisionExecutionSettings? = null
        var session: LocalVisionSession? = null
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
    private var retainedSettings: VisionExecutionSettings? = null
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
                    slot to (retainedSettings ?: settings.also { retainedSettings = it })
                }
            }
            if (admission != null) {
                val (slot, retained) = admission
                try {
                    slot.inUse = true; publishResources()
                    val session = slot.session ?: LocalVisionSession(context, retained, report) {
                        slot.resources = it; publishResources()
                    }.also { slot.session = it; slot.settings = retained }
                    return@withContext action(session)
                } finally { slot.inUse = false; publishResources(); slot.mutex.unlock(); revision.update { it + 1 } }
            }
            revision.first { it != before }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    suspend fun release() = configuration.withLock {
        for (slot in slots) {
            slot.mutex.lock()
            try { slot.session?.releaseModels(); slot.session = null; slot.settings = null }
            finally { slot.mutex.unlock(); revision.update { it + 1 } }
        }
        retainedSettings = null
    }
    suspend fun retain(limit: Int, needSeg: Boolean, needTextDetection: Boolean, languages: Set<LocalOcrLanguage>) = configuration.withLock {
        slots.forEachIndexed { index, slot ->
            slot.mutex.withLock {
                if (index >= limit) { slot.session?.releaseModels(); slot.session = null; slot.settings = null }
                else slot.session?.retainModels(needSeg, needTextDetection, languages)
            }
        }
        if (slots.all { it.session == null }) retainedSettings = null
    }
}
