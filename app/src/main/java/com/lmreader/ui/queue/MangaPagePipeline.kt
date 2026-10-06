package com.lmreader.ui.queue

import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.ui.reader.translation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

data class QueuePageWork(val task: ChapterTranslationEntity, val source: PageSource, val page: ReaderPage,
    val sourceLanguage: LocalTranslationLanguage, val targetLanguage: LocalTranslationLanguage,
    val render: BubbleRenderSettings, val threshold: Float, val retries: Int, val segTextScope: SegTextScope = SegTextScope.ALL,
    val textDetectionThreshold: Float = .45f, val freeTextMergeGapRatio: Float = DEFAULT_FREE_TEXT_MERGE_GAP_RATIO)

/** A manga owns both async branches. The MB budget provides backpressure without serializing stages. */
internal class MangaPagePipeline(parent: CoroutineScope, works: List<QueuePageWork>,
    private val translator: LocalPageTranslator, private val budget: TranslationCacheBudget,
    segConcurrency: Int, ocrConcurrency: Int) {
    private class State(val work: QueuePageWork) {
        val progress = MutableStateFlow(PageTranslationProgress(PageTranslationStage.READING))
        val segmentation = CompletableDeferred<Result<SegmentedPage?>>()
        val recognized = CompletableDeferred<Result<RecognizedPage?>>()
        val image = AtomicReference<SegmentedPage?>(null)
        var lease: TranslationCacheBudget.Lease? = null
    }
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val states = works.associate { it.page.pageId to State(it) }
    private val segQueue = Channel<State>(Channel.UNLIMITED)
    private val ocrQueue = Channel<State>(Channel.UNLIMITED)
    init {
        states.values.forEach { ocrQueue.trySend(it) }
        ocrQueue.close()
        // Admit in page order before dispatching Seg workers. Otherwise a later page
        // can win the last cache slot while every OCR worker waits for an earlier page.
        scope.launch {
            try {
                for (state in states.values) {
                    try {
                        val work = state.work
                        if (translator.cached(work.source, work.page, work.render) != null) {
                            state.segmentation.complete(Result.success(null)); continue
                        }
                        state.lease = budget.acquire(32L * 1_048_576)
                        segQueue.send(state)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { state.segmentation.complete(Result.failure(failure)) }
                }
            } finally { segQueue.close() }
        }
        repeat(segConcurrency) { scope.launch {
            for (state in segQueue) {
                try {
                    val work = state.work
                    val page = retry(work.retries) { translator.segment(work.source, work.page, work.threshold, work.textDetectionThreshold) { state.progress.value = it } }
                    state.image.set(page)
                    state.lease!!.shrink(page.image.allocationByteCount.toLong())
                    state.segmentation.complete(Result.success(page))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { state.segmentation.complete(Result.failure(failure)) }
            }
        } }
        repeat(ocrConcurrency) { scope.launch {
            for (state in ocrQueue) {
                var owned: SegmentedPage? = null
                try {
                    val segmented = state.segmentation.await().getOrThrow()
                    if (segmented == null) { state.recognized.complete(Result.success(null)); continue }
                    owned = segmented
                    state.image.set(null)
                    val bitmapBytes = segmented.image.allocationByteCount.toLong()
                    val recognized = translator.recognize(segmented, state.work.sourceLanguage, state.work.segTextScope, state.work.freeTextMergeGapRatio) { state.progress.value = it }
                    val metadataBytes = recognized.groups.sumOf { region ->
                        256L + region.sourceText.length * 2L + region.contour.size * 8L + region.textBounds.size * 16L
                    }
                    state.lease!!.shrink(minOf(metadataBytes, bitmapBytes))
                    state.recognized.complete(Result.success(recognized))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { state.recognized.complete(Result.failure(failure)) }
                finally { owned?.close() }
            }
        } }
    }
    suspend fun await(pageId: String): RecognizedPage? = states.getValue(pageId).recognized.await().getOrThrow()
    fun progress(pageId: String): StateFlow<PageTranslationProgress> = states.getValue(pageId).progress
    fun setProgress(pageId: String, progress: PageTranslationProgress) { states.getValue(pageId).progress.value = progress }
    suspend fun consume(pageId: String) { states.getValue(pageId).lease?.release() }
    suspend fun close() = withContext(NonCancellable) {
        job.cancelAndJoin()
        for (state in states.values) { state.image.getAndSet(null)?.close(); state.lease?.release() }
    }
}

internal suspend fun <T> retry(retries: Int, action: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try { return action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (attempt++ >= retries) throw failure }
    }
}
