package com.lmreader.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.vision.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

enum class PageTranslationStage { READING, SEGMENTING, OCR, TRANSLATING, SAVING }
data class PageTranslationProgress(val stage: PageTranslationStage, val completed: Int = 0, val total: Int = 0)

/** The owned analysis bitmap is released as soon as OCR finishes. */
class SegmentedPage internal constructor(val page: ReaderPage, val hash: String, val image: Bitmap,
    val seg: SegResult, val started: Long) : AutoCloseable {
    override fun close() { if (!image.isRecycled) image.recycle() }
}
data class RecognizedPage(val page: ReaderPage, val hash: String, val width: Int, val height: Int,
    val groups: List<PageTextRegion>, val started: Long)

class LocalPageTranslator(private val context: Context, private val vision: LocalVisionEngine,
    private val translator: LocalTextTranslator, val artifacts: ReaderPageArtifactStore,
    private val modelPacks: () -> List<TranslationModelPack>) {
    /** NMT and publication serialize; Seg/OCR and reader loads have independent locks. */
    val pageWriteMutex = Mutex()
    fun retainPreprocessing(pageId: String) {
        vision.preprocessingCache.markPendingPage(pageId); vision.preprocessingCache.retainPage(pageId)
    }
    fun releasePreprocessing(pageId: String) = vision.preprocessingCache.releasePage(pageId)
    suspend fun segment(pageSource: PageSource, page: ReaderPage, segThreshold: Float,
        textDetectionThreshold: Float = .35f,
        progress: (PageTranslationProgress) -> Unit = {}): SegmentedPage {
        var returned: SegmentedPage? = null
        try { return withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val input = File.createTempFile("page-source-", ".image", context.cacheDir)
        var image: Bitmap? = null
        try {
            progress(PageTranslationProgress(PageTranslationStage.READING))
            val hash = copySource(pageSource, page, input)
            val decoded = decodePageAnalysisImage(context, input).also { image = it }
            val seg = vision.segment(page.pageId, decoded, segThreshold, textDetectionThreshold, sourceSha256 = hash) {
                progress(PageTranslationProgress(PageTranslationStage.SEGMENTING, it.completed, it.total))
            }
            ensureActive()
            SegmentedPage(page, hash, decoded, seg, started).also { returned = it; image = null }
        } finally { image?.recycle(); input.delete() }
        } } catch (failure: Throwable) { returned?.close(); throw failure }
    }
    suspend fun recognize(segmented: SegmentedPage, source: LocalTranslationLanguage,
        scope: SegTextScope = SegTextScope.ALL,
        freeTextMergeGapRatio: Float = DEFAULT_FREE_TEXT_MERGE_GAP_RATIO,
        progress: (PageTranslationProgress) -> Unit = {}): RecognizedPage = withContext(Dispatchers.IO) {
        try {
            progress(PageTranslationProgress(PageTranslationStage.OCR))
            val regions = selectSegRegions(segmented.seg, scope, freeTextMergeGapRatio)
            val lines = ArrayList<OcrLine>()
            var elapsed = 0L
            for (region in regions) {
                val result = vision.cachedRecognizeRegion(segmented.page.pageId, segmented.hash, segmented.image, ocrLanguage(source),
                    region, segmented.seg.regions, segmented.seg.textLines) {
                    progress(PageTranslationProgress(PageTranslationStage.OCR, it.completed, it.total))
                }
                lines += result.lines; elapsed += result.elapsedMillis
            }
            val ocr = LocalOcrResult(segmented.page.pageId, segmented.image.width, segmented.image.height,
                ocrLanguage(source), lines.mapIndexed { i, line -> line.copy(id = "${segmented.page.pageId}:ocr:$i") }, elapsed)
            ensureActive()
            RecognizedPage(segmented.page, segmented.hash, segmented.image.width, segmented.image.height,
                groupPageText(segmented.seg, ocr, scope, freeTextMergeGapRatio), segmented.started)
        } finally { segmented.close() }
    }
    /** Caller holds pageWriteMutex, including queue state checks before publication. */
    suspend fun translateRecognized(page: RecognizedPage, source: LocalTranslationLanguage,
        target: LocalTranslationLanguage, render: BubbleRenderSettings,
        progress: (PageTranslationProgress) -> Unit = {}): ReaderPageTranslation = withContext(Dispatchers.IO) {
        val translated = ArrayList<LocalTranslatedText>(); val used = linkedSetOf<String>()
        val packSnapshot = modelPacks()
        for (batch in page.groups.chunked(256)) {
            ensureActive()
            val result = translator.translate(source, target, batch.map { LocalTranslationText(it.id, it.sourceText) }) { done, _ ->
                progress(PageTranslationProgress(PageTranslationStage.TRANSLATING, translated.size + done, page.groups.size))
            }
            translated += result.items; used += result.modelPackIds
        }
        ensureActive()
        progress(PageTranslationProgress(PageTranslationStage.SAVING))
        val job = currentCoroutineContext()
        artifacts.save(page.page.pageId, page.hash, source, target, page.width, page.height,
            bindPageTranslations(page.groups, translated), packSnapshot.filter { it.id in used }.map { it.identity },
            (System.nanoTime() - page.started) / 1_000_000, render, checkCancelled = { job.ensureActive() }).also {
                vision.preprocessingCache.commitPage(page.page.pageId)
            }
    }
    suspend fun translate(pageSource: PageSource, page: ReaderPage, source: LocalTranslationLanguage,
        target: LocalTranslationLanguage, render: BubbleRenderSettings,
        mode: TranslationPageMode = TranslationPageMode.BUBBLE, segThreshold: Float = .35f,
        segTextScope: SegTextScope = SegTextScope.ALL,
        textDetectionThreshold: Float = .35f,
        progress: (PageTranslationProgress) -> Unit = {}): ReaderPageTranslation {
        retainPreprocessing(page.pageId)
        try {
            val recognized = recognize(segment(pageSource, page, segThreshold, textDetectionThreshold, progress), source, segTextScope, progress = progress)
            return pageWriteMutex.withLock { translateRecognized(recognized, source, target, render, progress) }
        } finally { releasePreprocessing(page.pageId) }
    }
    suspend fun releaseModels() { vision.releaseModels(); translator.releaseModels() }
    suspend fun editablePage(pageSource: PageSource, page: ReaderPage, source: LocalTranslationLanguage,
        target: LocalTranslationLanguage, render: BubbleRenderSettings): ReaderPageTranslation = withContext(Dispatchers.IO) {
        val file = File.createTempFile("bubble-editor-", ".image", context.cacheDir)
        try {
            val hash = copySource(pageSource, page, file)
            artifacts.load(page.pageId, hash)?.let { return@withContext it }
            val image = decodePageAnalysisImage(context, file)
            try { ReaderPageTranslation(page.pageId, "", File(artifacts.root, "new-draft"), source, target,
                hash, image.width, image.height, emptyList(), emptyList(), 0, render) }
            finally { image.recycle() }
        } finally { file.delete() }
    }
    suspend fun cached(pageSource: PageSource, page: ReaderPage, render: BubbleRenderSettings): ReaderPageTranslation? = withContext(Dispatchers.IO) {
        artifacts.migrateLegacy()
        if (!artifacts.has(page.pageId)) return@withContext null
        val input = File.createTempFile("page-source-", ".image", context.cacheDir)
        try { artifacts.load(page.pageId, copySource(pageSource, page, input)) } finally { input.delete() }
    }
    private suspend fun copySource(source: PageSource, page: ReaderPage, destination: File): String {
        val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
        source.open(page).use { input -> destination.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                total += n; require(total <= 64_000_000) { "Source image exceeds 64 MB" }
                hash.update(buffer, 0, n); output.write(buffer, 0, n)
            }
        } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        val ocrSources = listOf(LocalTranslationLanguage.JAPANESE, LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED, LocalTranslationLanguage.CHINESE_TRADITIONAL, LocalTranslationLanguage.KOREAN)
        fun ocrLanguage(language: LocalTranslationLanguage) = when (language) {
            LocalTranslationLanguage.JAPANESE -> LocalOcrLanguage.JAPANESE
            LocalTranslationLanguage.KOREAN -> LocalOcrLanguage.KOREAN
            LocalTranslationLanguage.CHINESE_SIMPLIFIED -> LocalOcrLanguage.CHINESE_SIMPLIFIED
            LocalTranslationLanguage.CHINESE_TRADITIONAL -> LocalOcrLanguage.CHINESE_TRADITIONAL
            LocalTranslationLanguage.ENGLISH -> LocalOcrLanguage.ENGLISH
            else -> LocalOcrLanguage.ENGLISH
        }
    }
}

internal fun decodePageAnalysisImage(context: Context, file: File): Bitmap {
    return VisionImageDecoder.decode(context, Uri.fromFile(file), maxPixels = 4_000_000)
}
